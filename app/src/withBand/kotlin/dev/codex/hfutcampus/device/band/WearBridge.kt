package dev.codex.hfutcampus

import org.json.JSONObject
import android.content.SharedPreferences
import dev.codex.hfutcampus.NativePlugin.Host
import dev.codex.hfutcampus.WearProtocol.Sender
import dev.codex.hfutcampus.WearProtocol.Planner
import dev.codex.hfutcampus.SerialOperations.Work
import com.xiaomi.xms.wearable.service.*
import com.xiaomi.xms.wearable.auth.*
import com.xiaomi.xms.wearable.message.*
import com.xiaomi.xms.wearable.node.*
import android.os.Looper
import android.content.Context
import android.os.Handler
import android.os.SystemClock
import java.nio.charset.StandardCharsets
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import java.util.function.LongConsumer
import java.util.function.Supplier

/** Community SimpleFetch SDK flow; Health owns BLE. Keep its connection observer
 * across transient disconnects and reject callbacks from obsolete attempts.  */
class WearBridge internal constructor(host: Host, observer: Runnable?) {
    private val transport: WearTransport
    private val service: WearTransport.Services
    private var serviceObserved = false
    private var messageNode = ""
    private val nodes: WearTransport.Nodes
    private val messages: WearTransport.Messages
    private val auth: WearTransport.Auth
    private val host: Host
    private val prefs: SharedPreferences
    private val observer: Runnable?
    private val main: Handler = Handler(Looper.getMainLooper())
    private val protocol: WearProtocol
    private var running = false
    private var discovering = false
    private var registering = false
    private var promptRequested = false
    private var nodeId = ""
    private var registered = ""
    private var watched = ""
    private var generation: Long = 0
    private var watchRevision: Long = 0
    private var configRevision: Long = 0
    private var snapshotRevision: Long = -1
    private var timeout: Runnable? = null
    val isLinked: Boolean
        get() = !registered.isEmpty()

    private fun status(value: String?) {
        status = value
        if (observer != null) observer.run()
    }

    private val incoming: OnMessageReceivedListener =
        OnMessageReceivedListener { node: String, bytes: ByteArray? ->
            this.onIncoming(
                node,
                bytes
            )
        }

    private fun onIncoming(node: String, bytes: ByteArray?) {
        if (bytes == null || bytes.size > 8192) return
        val raw = String(bytes, StandardCharsets.UTF_8)
        main.post(Runnable {
            if (!running || node != registered) return@Runnable
            var ready = raw == "ready"
            try {
                ready = ready or (JSONObject(raw).optString("type") == "ready")
            } catch (ignored: Exception) {
            }
            transport.confirmedLink()
            if (ready) {
                status("手环互联已就绪")
                return@Runnable
            }
            protocol.receive(node, raw)
        })
    }

    private val changes: CampusRuntime.Listener =
        CampusRuntime.Listener { s: CardSnapshot? -> this.onChange(s!!) }
    private val balanceChanges = Runnable(::onBalanceChange)

    private fun onBalanceChange() {
        if (running) protocol.balanceChanged()
    }

    private fun onChange(s: CardSnapshot) {
        if (!running) return
        val now = host.accountRevision()
        if (now != configRevision) {
            configRevision = now
            protocol.invalidate()
        }
        if (s.revision != snapshotRevision) {
            snapshotRevision = s.revision
            protocol.snapshotChanged(s)
        }
    }

    private val healthConnection: OnServiceConnectionListener =
        object : OnServiceConnectionListener {
            override fun onServiceDisconnected() {
                main.post(Runnable {
                    if (!running) return@Runnable
                    resetRegistration()
                    clearWatch()
                    status("正在等待运动健康恢复连接")
                })
            }

            override fun onServiceConnected() {
                main.post(Runnable {
                    if (!running) return@Runnable
                    refreshNodes(false)
                })
            }
        }

    init {
        this.host = host
        this.observer = observer
        prefs = requireNotNull(host.context()).getSharedPreferences("campus-recovery", Context.MODE_PRIVATE)
        transport = WearTransport(host)
        service = transport.service
        nodes = transport.nodes
        messages = transport.messages
        auth = transport.auth
        protocol = WearProtocol(
            Sender { node: String?, message: JSONObject? ->
                this.send(
                    node!!,
                    requireNotNull(message)
                )
            },
            Supplier { requireNotNull(host.snapshot()) },
            LongConsumer { elapsedTime: Long -> host.displayUntil(elapsedTime) },
            object : WearProtocol.Clock {
                override fun elapsed(): Long {
                    return SystemClock.elapsedRealtime()
                }

                override fun wall(): Long {
                    return System.currentTimeMillis()
                }
            },
            Planner { snapshot: CardSnapshot? -> requireNotNull(host.codes()).plan(requireNotNull(snapshot)) },
            Supplier { if (prefs.getBoolean(BandPlugin.SHOW_BALANCE, false)) currentBalance() else null })
    }

    // The SDK holds process-wide listeners and executes tasks concurrently. All
    // add/remove mutations, including cleanup from an old service, share one queue.
    private fun mutate(
        needed: BooleanSupplier,
        operation: Supplier<WearTransport.Call<Void?>?>,
        success: Runnable,
        failure: Runnable
    ) {
        mutations.submit(needed, Work { done: Runnable? ->
            try {
                operation.get()!!.addOnSuccessListener(Consumer { value: Void? ->
                    main.post(Runnable {
                        try {
                            success.run()
                        } finally {
                            done!!.run()
                        }
                    })
                })
                    .addOnFailureListener(Consumer { error: Exception? ->
                        main.post(Runnable {
                            try {
                                failure.run()
                            } finally {
                                done!!.run()
                            }
                        })
                    })
            } catch (error: RuntimeException) {
                try {
                    failure.run()
                } finally {
                    done!!.run()
                }
            }
        })
    }

    private fun removeMessage(id: String) {
        if (!id.isEmpty()) mutate(
            BooleanSupplier { true },
            Supplier { messages.removeListener(id) },
            Runnable {},
            Runnable {})
    }

    private fun clearWatch() {
        watchRevision++
        val old = watched
        watched = ""
        if (!old.isEmpty()) mutate(
            BooleanSupplier { true },
            Supplier { nodes.unsubscribe(old, DataItem.ITEM_CONNECTION) },
            Runnable {},
            Runnable {})
    }

    fun start(requestPermission: Boolean) {
        if (running) return
        running = true
        configRevision = host.accountRevision()
        host.observe(changes)
        try { host.observeBalance(balanceChanges) } catch (_: LinkageError) { }
        updateBalanceSetting()
        service.registerServiceConnectionListener(healthConnection)
        serviceObserved = true
        nodeId = prefs.getString("bound-node", "").orEmpty()
        if (!nodeId.isEmpty()) watch(nodeId)
        refreshNodes(requestPermission)
    }

    private fun currentBalance(): BalanceSnapshot? = try { host.balance() } catch (_: LinkageError) { null }

    fun updateBalanceSetting() {
        try { host.setBalanceDemand(running && prefs.getBoolean(BandPlugin.SHOW_BALANCE, false)) } catch (_: LinkageError) { }
        if (running) protocol.balanceChanged()
    }

    private fun cancelTimeout() {
        if (timeout != null) {
            main.removeCallbacks(timeout!!)
            timeout = null
        }
    }

    private fun current(attempt: Long): Boolean {
        return running && attempt == generation
    }

    private fun deadline(attempt: Long, delay: Long) {
        cancelTimeout()
        timeout = Runnable {
            if (current(attempt)) {
                resetRegistration()
                status("连接响应超时，正在自动重试")
            }
        }
        main.postDelayed(timeout!!, delay)
    }

    private fun watch(id: String) {
        try {
            watchInternal(id)
        } catch (unavailable: RuntimeException) {
            watched = ""
            status("正在等待运动健康连接")
        }
    }

    private fun watchInternal(id: String) {
        if (watched == id) return
        clearWatch()
        watched = id
        val revision = watchRevision
        mutate(BooleanSupplier { running && watched == id && watchRevision == revision }, Supplier {
            nodes.subscribe(
                id,
                DataItem.ITEM_CONNECTION,
                OnDataChangedListener { node, item, data ->
                    main.post(
                        Runnable {
                            if (!running || !node.equals(watched) || watchRevision != revision) return@Runnable
                            if (data.getConnectedStatus() === DataSubscribeResult.RESULT_CONNECTION_CONNECTED) {
                                if (!this.isLinked) refreshNodes(false)
                            } else {
                                resetRegistration()
                                status("手环已断开，等待运动健康重连")
                            }
                        })
                })
        }, Runnable {}, Runnable {
            if (running && watched == id && watchRevision == revision) {
                clearWatch()
                status("正在等待运动健康连接")
            }
        })
    }

    fun refreshNodes(requestPermission: Boolean) {
        try {
            discover(requestPermission)
        } catch (unavailable: RuntimeException) {
            resetRegistration()
            status("正在等待运动健康服务")
        }
    }

    private fun discover(requestPermission: Boolean) {
        promptRequested = promptRequested or requestPermission
        if (!running || discovering || registering) return
        if (!nodeId.isEmpty()) watch(nodeId)
        discovering = true
        val attempt = ++generation
        deadline(attempt, 10000)
        nodes.connectedNodes.addOnSuccessListener(Consumer { found: MutableList<Node>? ->
            main.post(
                Runnable {
                    if (!current(attempt)) return@Runnable
                    cancelTimeout()
                    discovering = false
                    var selected: Node? = null
                    for (n in found!!) if (n.id.equals(nodeId)) {
                        selected = n
                        break
                    }
                    if (selected == null && found.size == 1) selected = found.get(0)
                    if (selected == null) {
                        promptRequested = false
                        resetRegistration()
                        status(if (found.isEmpty()) "运动健康未报告已连接手环" else "请在运动健康确认目标手环")
                        return@Runnable
                    }
                    val next: String = selected.id
                    if (next != nodeId) {
                        resetRegistration()
                        nodeId = next
                    }
                    watch(next)
                    if (registered == next) {
                        promptRequested = false
                        return@Runnable
                    }
                    val allowPrompt = promptRequested
                    promptRequested = false
                    val expected = next
                    val authAttempt = ++generation
                    registering = true
                    deadline(authAttempt, 10000)
                    try {
                        auth.checkPermission(expected, Permission.DEVICE_MANAGER)
                            .addOnSuccessListener(Consumer { granted: Boolean? ->
                                main.post(
                                    Runnable {
                                        if (!current(authAttempt)) return@Runnable
                                        if (granted == true) register(expected, authAttempt)
                                        else if (allowPrompt) {
                                            deadline(authAttempt, 60000)
                                            auth.requestPermission(
                                                expected,
                                                Permission.DEVICE_MANAGER
                                            )
                                                .addOnSuccessListener(Consumer { result: Void? ->
                                                    main.post(Runnable {
                                                        if (!current(authAttempt)) return@Runnable
                                                        cancelTimeout()
                                                        registering = false
                                                        refreshNodes(false)
                                                    })
                                                })
                                                .addOnFailureListener(Consumer { error: Exception? ->
                                                    main.post(Runnable {
                                                        if (current(authAttempt)) {
                                                            resetRegistration()
                                                            status("请在运动健康完成互联授权")
                                                        }
                                                    })
                                                })
                                        } else {
                                            resetRegistration()
                                            status("请在手机开启手环连接并完成授权")
                                        }
                                    })
                            }).addOnFailureListener(Consumer { error: Exception? ->
                                main.post(Runnable {
                                    if (current(authAttempt)) {
                                        resetRegistration()
                                        status("运动健康尚未就绪，正在自动重试")
                                    }
                                })
                            })
                    } catch (unavailable: RuntimeException) {
                        resetRegistration()
                        status("运动健康尚未就绪，正在自动重试")
                    }
                })
        }).addOnFailureListener(Consumer { error: Exception? ->
            main.post(Runnable {
                if (current(attempt)) {
                    promptRequested = false
                    resetRegistration()
                    clearWatch()
                    status("正在等待运动健康服务")
                }
            })
        })
    }

    private fun register(expected: String, attempt: Long) {
        try {
            registerInternal(expected, attempt)
        } catch (unavailable: RuntimeException) {
            resetRegistration()
            status("互联暂不可用，正在自动重试")
        }
    }

    private fun registerInternal(expected: String, attempt: Long) {
        deadline(attempt, 10000)
        messageNode = expected
        mutate(
            BooleanSupplier { current(attempt) },
            Supplier { messages.addListener(expected, incoming) },
            Runnable {
                if (!current(attempt)) return@Runnable  // reset already queued cleanup after this add

                cancelTimeout()
                registering = false
                registered = expected
                prefs.edit().putString("bound-node", expected).apply()
                status("互联监听已注册，请打开手环校园卡")
                try {
                    messages.sendMessage(expected, "start".toByteArray(StandardCharsets.UTF_8))
                        .addOnFailureListener(
                            Consumer { error: Exception? ->
                                main.post(Runnable {
                                    if (current(attempt)) {
                                        resetRegistration()
                                        status("正在恢复手环互联")
                                    }
                                })
                            })
                } catch (unavailable: RuntimeException) {
                    resetRegistration()
                    status("正在恢复手环互联")
                }
            },
            Runnable {
                if (current(attempt)) {
                    resetRegistration()
                    status("互联暂不可用，正在自动重试")
                }
            })
    }

    private fun send(node: String, message: JSONObject) {
        if (!running || node != registered) return
        val attempt = generation
        try {
            messages.sendMessage(node, message.toString().toByteArray(StandardCharsets.UTF_8))
                .addOnFailureListener(
                    Consumer { error: Exception? ->
                        main.post(Runnable {
                            if (current(attempt) && node == registered) {
                                resetRegistration()
                                status("发送中断，正在等待自动重连")
                            }
                        })
                    })
        } catch (unavailable: RuntimeException) {
            resetRegistration()
            status("发送中断，正在等待自动重连")
        }
    }

    fun openBand() {
        if (!nodeId.isEmpty()) try {
            nodes.launchWearApp(nodeId, "Card").addOnFailureListener(Consumer { error: Exception? ->
                main.post(
                    Runnable { if (running) status("请先在手环安装校园卡应用") })
            })
        } catch (unavailable: RuntimeException) {
            status("暂时无法打开手环校园卡")
        }
    }

    private fun resetRegistration() {
        generation++
        cancelTimeout()
        registering = false
        discovering = registering
        val old = registered
        registered = ""
        val cleanup = messageNode
        messageNode = ""
        removeMessage(cleanup)
        if (!old.isEmpty()) protocol.disconnected(old)
    }

    fun stop() {
        safely(Runnable { protocol.invalidate(true) })
        running = false
        promptRequested = false
        host.unobserve(changes)
        try { host.unobserveBalance(balanceChanges) } catch (_: LinkageError) { }
        updateBalanceSetting()
        protocol.clear()
        resetRegistration()
        clearWatch()
        if (serviceObserved) {
            safely(Runnable { service.unregisterServiceConnectionListener(healthConnection) })
            serviceObserved = false
        }
        transport.closeAfterDelivery()
        status("伴侣服务已停止")
    }

    companion object {
        private val mutations = SerialOperations()

        @Volatile
        private var status: String? = "伴侣服务未启动"
        fun status(): String? {
            return status
        }

        private fun safely(action: Runnable) {
            try {
                action.run()
            } catch (ignored: RuntimeException) {
            }
        }
    }
}
