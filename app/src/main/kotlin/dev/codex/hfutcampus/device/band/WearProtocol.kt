package dev.codex.hfutcampus

import org.json.JSONObject
import dev.codex.hfutcampus.WearProtocol
import dev.codex.hfutcampus.CardSnapshot
import dev.codex.hfutcampus.WearProtocol.Sender
import dev.codex.hfutcampus.WearProtocol.Planner
import dev.codex.hfutcampus.PaymentCodes.QrPlan
import dev.codex.hfutcampus.PaymentCodes
import java.util.HashMap
import java.util.UUID
import java.util.function.LongConsumer
import java.util.function.Supplier
import kotlin.math.max

/** Protocol v2 carries the payment string and encoder parameters over the trusted link.  */
class WearProtocol(
    private val sender: Sender,
    private val current: Supplier<CardSnapshot?>,
    private val demand: LongConsumer,
    private val clock: Clock,
    private val planner: Planner,
    private val balance: Supplier<BalanceSnapshot?> = Supplier { null }
) {
    fun interface Sender {
        fun send(node: String?, frame: JSONObject?)
    }

    interface Clock {
        fun elapsed(): Long
        fun wall(): Long
    }

    fun interface Planner {
        @Throws(Exception::class)
        fun plan(snapshot: CardSnapshot?): QrPlan
    }

    private val hostId = UUID.randomUUID().toString()
    private val views: MutableMap<String?, View> = HashMap<String?, View>()
    private var sequence: Long = 0

    private class View(val id: String, val wantsBalance: Boolean) {
        var lease: Long = 0
        var lastRevision: Long = -1
        var lastReady: Boolean = false
        var lastBalance: BalanceSnapshot? = null
    }

    fun receive(node: String?, raw: String) {
        if (raw.length > 1024) return
        try {
            val message = JSONObject(raw)
            if (message.optInt("v") != 2) return
            val id = message.optString("viewSession")
            if (!id.matches("[A-Za-z0-9_-]{1,96}".toRegex())) return
            val type = message.optString("type")
            var view = views.get(node)
            if (type == "card.hidden") {
                if (view != null && view.id == id) disconnected(node)
                return
            }
            val echo = message.optLong("clientSentAt", -1)
            if (echo <= 0) return
            if (type == "card.subscribe") {
                view = View(id, message.optBoolean("balance"))
                views.put(node, view)
            } else if ((type != "card.keepalive") || view == null || (view.id != id) || clock.elapsed() >= view.lease) {
                return
            }
            view.lease = clock.elapsed() + LEASE_MS
            updateDemand()
            sendState(
                node,
                view,
                current.get()!!,
                echo,
                message.optLong("knownRevision", -1) != view.lastRevision
            )
        } catch (invalid: Exception) { /* Malformed and legacy request frames are ignored. */
        }
    }

    fun snapshotChanged(snapshot: CardSnapshot) {
        views.entries.removeIf { clock.elapsed() >= it.value.lease }
        for (entry in HashMap<String?, View?>(views).entries) {
            sendState(entry.key, entry.value!!, snapshot, 0, false)
        }
        updateDemand()
    }

    fun balanceChanged() {
        val state = current.get() ?: return
        snapshotChanged(state)
    }

    private fun sendState(
        node: String?,
        view: View,
        state: CardSnapshot,
        echo: Long,
        resend: Boolean
    ) {
        try {
            var snapshot = state.at(clock.elapsed(), clock.wall())
            if (!resend && snapshot.revision == view.lastRevision && snapshot.ready == view.lastReady) {
                val amount = displayBalance(view, snapshot.ready && !snapshot.testMode)
                if (echo > 0 || amount != view.lastBalance) {
                    val frame = envelope(if (echo > 0) "card.lease" else "card.balance", view)
                    if (echo > 0) frame.put("clientSentAt", echo)
                    if (view.wantsBalance) frame.put("revision", snapshot.revision)
                    sendWithBalance(node, view, frame, amount)
                }
                return
            }
            val frame = envelope("card.snapshot", view).put("revision", snapshot.revision)
            if (echo > 0) frame.put("clientSentAt", echo)
            if (snapshot.ready) {
                val plan = planner.plan(snapshot)
                snapshot = snapshot.at(clock.elapsed(), clock.wall())
                if (snapshot.ready && snapshot.remainingMs >= 2000) {
                    frame.put("code", snapshot.code).put("version", plan.version)
                        .put("mask", plan.mask).put("mode", plan.mode)
                        .put("profile", PaymentCodes.PROFILE).put("expiresAt", snapshot.expiresAt)
                        .put("remainingMs", snapshot.remainingMs)
                }
            }
            val ready = frame.has("code")
            frame.put("ready", ready)
            if (!ready) frame.put("status", snapshot.status)
            sendWithBalance(node, view, frame, displayBalance(view, ready && !snapshot.testMode))
            view.lastRevision = snapshot.revision
            view.lastReady = snapshot.ready
        } catch (unavailable: Exception) { /* The displayed frame keeps its original deadline. */
        }
    }

    private fun displayBalance(view: View, eligible: Boolean): BalanceSnapshot? =
        if (!view.wantsBalance || !eligible) null
        else balance.get()?.takeIf { it.expiresElapsed > clock.elapsed() }

    private fun sendWithBalance(node: String?, view: View, frame: JSONObject, amount: BalanceSnapshot?) {
        val sentAt = clock.wall()
        frame.put("sentAt", sentAt)
        if (view.wantsBalance) {
            val left = amount?.let { it.expiresElapsed - clock.elapsed() } ?: 0L
            if (amount != null && left in 1..120_000L) {
                frame.put("balance", amount.text).put("balanceRemainingMs", left).put("balanceExpiresAt", sentAt + left)
            } else frame.put("balance", JSONObject.NULL)
        }
        sender.send(node, frame)
        view.lastBalance = amount
    }

    @Throws(Exception::class)
    private fun envelope(type: String?, view: View): JSONObject {
        return JSONObject().put("v", 2).put("type", type).put("seq", ++sequence)
            .put("hostId", hostId).put("viewSession", view.id)
    }

    fun invalidate() {
        snapshotChanged(current.get()!!)
    }

    fun invalidate(offline: Boolean) {
        if (!offline) {
            invalidate()
            return
        }
        for (entry in HashMap<String?, View?>(views).entries) {
            try {
                sender.send(entry.key, envelope("card.offline", entry.value!!))
            } catch (unavailable: Exception) {
            }
        }
        clear()
    }

    fun disconnected(node: String?) {
        views.remove(node)
        updateDemand()
    }

    fun clear() {
        views.clear()
        updateDemand()
    }

    private fun updateDemand() {
        var until: Long = 0
        for (view in views.values) until = max(until, view.lease)
        demand.accept(until)
    }

    companion object {
        private const val LEASE_MS: Long = 35000
    }
}
