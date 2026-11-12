package dev.codex.hfutcampus

import dev.codex.hfutcampus.NativePlugin.Host
import android.os.Looper
import dev.codex.hfutcampus.RearQrEncoder
import dev.codex.hfutcampus.CampusRearProvider
import dev.codex.hfutcampus.RearCallerPolicy
import dev.codex.hfutcampus.CardSnapshot
import android.content.Context
import android.database.Cursor
import dev.codex.hfutcampus.CampusRuntime
import dev.codex.hfutcampus.RearSnapshotAdapter
import dev.codex.hfutcampus.CampusRearProvider.QueryOptions
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.SystemClock
import dev.codex.hfutcampus.RearBarcodeEncoder
import java.util.HashSet
import java.util.function.BooleanSupplier
import kotlin.math.max
import kotlin.math.min

/** Atomic read-only display snapshots. Queries never refresh, login, persist or proxy network data.  */
class CampusRearProvider internal constructor(
    private val host: Host,
    private val sharing: BooleanSupplier,
    private val showBalance: () -> Boolean
) {
    /** MAML passes absent DOM attributes as empty strings; these are not SQL filters.  */
    internal object QueryOptions {
        fun isUnfiltered(selection: String?, args: Array<out String>?, order: String?): Boolean {
            return (selection == null || selection.isEmpty()) && (args == null || args.size == 0)
                    && (order == null || order.isEmpty())
        }
    }

    private val context: Context
        get() = requireNotNull(host.context())
    private val main = Handler(Looper.getMainLooper())
    private var notifiedRevision: Long = -1
    private var notifiedSharing = false
    private var observingBalance = false
    private val balanceChanged = Runnable { notifySharingChanged(context) }
    private val expiry = Runnable {
        notifySharingChanged(
            this.context
        )
    }
    private val listener: CampusRuntime.Listener =
        CampusRuntime.Listener { state: CardSnapshot? -> this.snapshotChanged(state!!) }

    private fun snapshotChanged(state: CardSnapshot) {
        val sharing = this.sharing.getAsBoolean()
        if (state.revision == notifiedRevision && sharing == notifiedSharing) return
        notifiedRevision = state.revision
        notifiedSharing = sharing
        main.removeCallbacks(expiry)
        notifySharingChanged(this.context)
        if (state.ready && sharing) {
            val left = min(
                state.remainingMs,
                min(
                    state.expiresElapsed - SystemClock.elapsedRealtime(),
                    state.expiresAt - System.currentTimeMillis()
                )
            )
            if (left > 0) main.postDelayed(
                expiry,
                max(1, left - RearSnapshotAdapter.DISPLAY_GUARD_MS)
            )
        }
    }

    fun start() {
        host.observe(listener)
        balancePreferenceChanged()
    }

    fun stop() {
        host.unobserve(listener)
        main.removeCallbacks(expiry)
        balancePreferenceChanged()
    }

    fun balancePreferenceChanged() {
        val enabled = sharing.asBoolean && showBalance()
        if (enabled != observingBalance) {
            try {
                if (enabled) {
                    host.observeBalance(balanceChanged)
                    host.setBalanceDemand(true)
                } else {
                    host.unobserveBalance(balanceChanged)
                    host.setBalanceDemand(false)
                }
                observingBalance = enabled
            } catch (_: IncompatibleClassChangeError) {
                // Older hosts can still serve the two payment images.
            }
        }
        notifySharingChanged(context)
    }

    private fun balanceFor(data: RearSnapshotAdapter.Frame, access: RearCallerPolicy.Access?): BalanceSnapshot? {
        if (!data.ready || data.testMode || !sharing.asBoolean || !showBalance()) return null
        if (access != RearCallerPolicy.Access.SELF && access != RearCallerPolicy.Access.APPROVED_HOST) return null
        val revision = host.accountRevision()
        val balance = try {
            host.balance()
        } catch (_: LinkageError) {
            null
        } ?: return null
        if (balance.expiresElapsed <= SystemClock.elapsedRealtime() || balance.text.isBlank()) return null
        val current = host.snapshot()
        return balance.takeIf {
            sharing.asBoolean && showBalance() && host.accountRevision() == revision &&
                current?.ready == true && !current.testMode && current.revision == data.generation
        }
    }

    private fun frame(
        snapshot: CardSnapshot?,
        access: RearCallerPolicy.Access?
    ): RearSnapshotAdapter.Frame {
        return RearSnapshotAdapter.from(
            snapshot,
            host.snapshotInstance(),
            sharing.getAsBoolean(),
            access == RearCallerPolicy.Access.SELF || access == RearCallerPolicy.Access.APPROVED_HOST,
            SystemClock.elapsedRealtime(),
            System.currentTimeMillis()
        )
    }

    fun query(
        uri: Uri?,
        projection: Array<out String>?,
        selection: String?,
        args: Array<out String>?,
        order: String?
    ): Cursor {
        uri(uri)
        require(QueryOptions.isUnfiltered(selection, args, order)) { "Snapshot has no filters" }
        val columns = if (projection == null) COLUMNS.clone() else projection.clone()
        require(columns.isNotEmpty() && columns.size <= ALLOWED.size) { "Invalid projection" }
        val seen: MutableSet<String?> = HashSet<String?>()
        for (name in columns) require(!(!ALLOWED.contains(name) || !seen.add(name))) { "Unknown column" }
        val caller = Binder.getCallingUid()
        var access = RearCallerPolicy.access(this.context, caller)
        if (access == RearCallerPolicy.Access.DENIED) throw SecurityException("Display reader not authorized")
        val wantQr = seen.contains("qr_png") || seen.contains("qr_side")
        val wantBar =
            seen.contains("barcode_png") || seen.contains("barcode_width") || seen.contains("barcode_height")
        var data: RearSnapshotAdapter.Frame?
        var qr: RearQrEncoder.Display? = null
        var bar: RearBarcodeEncoder.Image? = null
        var attempt = 0
        while (true) {
            val snapshot = host.snapshot()
            data = frame(snapshot, access)
            if (!data.ready) break
            try {
                if (wantQr) qr = host.codes()!!.croppedQr(snapshot!!, 360)
                if (wantBar) bar = host.codes()!!.barcode(snapshot!!, 520, 84)
            } catch (failure: Exception) {
                if (attempt == 0 && host.snapshot()!!.revision != snapshot!!.revision) {
                    qr = null
                    bar = null
                    attempt++
                    continue
                }
                data = RearSnapshotAdapter.hidden(
                    data.instanceId,
                    data.generation,
                    data.testMode,
                    "二维码生成失败"
                )
                break
            }
            access = RearCallerPolicy.access(this.context, caller)
            if (access == RearCallerPolicy.Access.DENIED) throw SecurityException("Display reader no longer authorized")
            val latest = frame(host.snapshot(), access)
            if (!latest.ready || latest.generation == data.generation) {
                data = latest
                break
            }
            qr = null
            bar = null
            if (attempt == 1) {
                data = RearSnapshotAdapter.hidden(
                    latest.instanceId,
                    latest.generation,
                    latest.testMode,
                    "请重新读取"
                )
                break
            }
            attempt++
        }
        if (!data.ready) {
            qr = null
            bar = null
        }
        val balance = if (seen.contains("balance_text") || seen.contains("balance_expires_elapsed")) {
            balanceFor(data, access)
        } else null
        val row = arrayOfNulls<Any>(columns.size)
        for (i in columns.indices) when (columns[i]) {
            "ready" -> row[i] = if (data.ready) 1 else 0
            "expires_at" -> row[i] = data.expiresAt
            "expires_elapsed" -> row[i] = data.expiresElapsed
            "remaining_ms" -> row[i] = data.remainingMs
            "generation" -> row[i] = data.generation
            "instance_id" -> row[i] = data.instanceId
            "label" -> row[i] = data.label
            "test_mode" -> row[i] = if (data.testMode) 1 else 0
            "status" -> row[i] = data.status
            "qr_png" -> row[i] = if (qr == null) null else qr.png
            "qr_side" -> row[i] = if (qr == null) 0 else qr.side
            "barcode_png" -> row[i] = if (bar == null) null else bar.png
            "barcode_width" -> row[i] = if (bar == null) 0 else bar.width
            "barcode_height" -> row[i] = if (bar == null) 0 else bar.height
            "balance_text" -> row[i] = balance?.text
            "balance_expires_elapsed" -> row[i] = balance?.expiresElapsed ?: 0L
            else -> throw IllegalArgumentException("Unknown column")
        }
        val cursor = MatrixCursor(columns, 1)
        cursor.addRow(row)
        cursor.setNotificationUri(this.context.getContentResolver(), CONTENT_URI)
        return cursor
    }

    fun getType(uri: Uri?): String {
        uri(uri)
        return "vnd.android.cursor.item/vnd." + AUTHORITY + ".campus"
    }

    companion object {
        const val AUTHORITY: String = "dev.codex.hfutcampus.rear"
        val CONTENT_URI: Uri = Uri.parse("content://" + AUTHORITY + "/campus")
        val COLUMNS: Array<String?> = arrayOf<String?>(
            "ready",
            "expires_at",
            "expires_elapsed",
            "remaining_ms",
            "generation",
            "instance_id",
            "label",
            "test_mode",
            "status",
            "qr_png",
            "qr_side",
            "barcode_png",
            "barcode_width",
            "barcode_height"
        )
        private val ALLOWED = COLUMNS.toSet() + setOf("balance_text", "balance_expires_elapsed")

        fun notifySharingChanged(c: Context?) {
            if (c != null) c.getContentResolver().notifyChange(CONTENT_URI, null)
        }

        private fun uri(uri: Uri?) {
            require(CONTENT_URI == uri) { "Unknown display URI" }
        }
    }
}
