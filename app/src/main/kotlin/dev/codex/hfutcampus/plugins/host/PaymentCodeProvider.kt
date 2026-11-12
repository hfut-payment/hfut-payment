package dev.codex.hfutcampus

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/** Public versioned read API. Only the phone's scheduler can fetch a replacement.  */
class PaymentCodeProvider : ContentProvider() {
    private var runtime: CampusRuntime? = null
    private var registry: PluginRegistry? = null
    private var revision: Long = -1
    private val changed = CampusRuntime.Listener { snapshot: CardSnapshot ->
        if (snapshot.revision == revision) return@Listener
        revision = snapshot.revision
        notifyReaders()
    }

    override fun onCreate(): Boolean {
        runtime = CampusRuntime.Companion.get(checkNotNull(context))
        registry = PluginRegistry.Companion.get(checkNotNull(context))
        runtime!!.addListener(changed)
        return true
    }

    fun notifyReaders() {
        checkNotNull(context).getContentResolver().notifyChange(TEXT, null)
        checkNotNull(context).getContentResolver().notifyChange(QR, null)
    }

    private fun isQr(uri: Uri?): Boolean {
        if (QR == uri) return true
        if (TEXT == uri) return false
        throw IllegalArgumentException("Unknown payment API path")
    }

    private fun authorize(pkg: String?, uid: Int, qr: Boolean) {
        if (uid != Process.myUid() && !registry!!.canRead(
                pkg,
                uid,
                if (qr) PluginRegistry.Companion.QR else PluginRegistry.Companion.TEXT
            )
        ) {
            throw SecurityException("Payment plugin grant required")
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        args: Array<out String>?,
        sort: String?
    ): Cursor {
        val qr = isQr(uri)
        require(!(projection != null || selection != null || args != null || sort != null)) { "Use the fixed v1 projection" }
        val uid = Binder.getCallingUid()
        val pkg = getCallingPackage()
        authorize(pkg, uid, qr)
        val snapshot = runtime!!.current()
        var image: RearQrEncoder.Image? = null
        if (qr && snapshot.ready) {
            try {
                image = PaymentCodes.Companion.shared().qr(snapshot, 360)
            } catch (unavailable: Exception) { /* Return a hidden frame. */
            }
        }
        authorize(pkg, uid, qr)
        val latest = runtime!!.current()
        val ready = latest.ready && latest.revision == snapshot.revision && (!qr || image != null)
        val cursor = MatrixCursor(if (qr) QR_COLUMNS else TEXT_COLUMNS, 1)
        val row = cursor.newRow().add(1).add(runtime!!.instanceId).add(latest.revision)
            .add(if (ready) 1 else 0).add(if (ready) latest.expiresAt else 0)
            .add(if (ready) latest.expiresElapsed else 0)
            .add(if (ready) latest.remainingMs else 0).add(PaymentCodes.Companion.PROFILE)
        if (qr) row.add(if (ready) image!!.png else null).add(if (ready) image!!.side else 0)
        else row.add(if (ready) latest.code else null)
        cursor.setNotificationUri(checkNotNull(context).getContentResolver(), uri)
        return cursor
    }

    override fun getType(uri: Uri): String {
        return "vnd.android.cursor.item/vnd.hfut.payment." + (if (isQr(uri)) "qr" else "text")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw UnsupportedOperationException()
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        where: String?,
        args: Array<out String>?
    ): Int {
        throw UnsupportedOperationException()
    }

    override fun delete(uri: Uri, where: String?, args: Array<out String>?): Int {
        throw UnsupportedOperationException()
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (arg != null || extras != null || !("visible" == method || "hidden" == method)) {
            throw UnsupportedOperationException("Unknown payment API call")
        }
        val pkg = getCallingPackage()
        val uid = Binder.getCallingUid()
        if (!registry!!.canRead(pkg, uid, PluginRegistry.Companion.TEXT) && !registry!!.canRead(
                pkg,
                uid,
                PluginRegistry.Companion.QR
            )
        ) {
            throw SecurityException("Payment plugin grant required")
        }
        // Visibility affects scheduler demand only. There is no refresh-now method.
        runtime!!.setPluginVisible(pkg, "visible" == method)
        return Bundle.EMPTY
    }

    override fun shutdown() {
        if (runtime != null) runtime!!.removeListener(changed)
        super.shutdown()
    }

    companion object {
        const val AUTHORITY: String = "dev.codex.hfutcampus.payment"
        val TEXT: Uri = Uri.parse("content://" + AUTHORITY + "/v1/text")
        val QR: Uri = Uri.parse("content://" + AUTHORITY + "/v1/qr")
        private val TEXT_COLUMNS = arrayOf<String?>(
            "api", "instance_id", "revision", "ready", "expires_at",
            "expires_elapsed", "remaining_ms", "profile", "code"
        )
        private val QR_COLUMNS = arrayOf<String?>(
            "api", "instance_id", "revision", "ready", "expires_at",
            "expires_elapsed", "remaining_ms", "profile", "qr_png", "qr_side"
        )
    }
}
