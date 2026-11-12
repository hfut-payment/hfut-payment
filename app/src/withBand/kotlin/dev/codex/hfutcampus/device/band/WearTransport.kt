package dev.codex.hfutcampus

import java.util.*
import dev.codex.hfutcampus.NativePlugin.Host
import android.os.IBinder.DeathRecipient
import android.app.ActivityManager.RunningAppProcessInfo
import com.xiaomi.xms.wearable.service.OnServiceConnectionListener
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener
import android.os.*
import android.app.ActivityManager
import android.content.*
import android.content.Context
import android.os.Handler
import android.os.Message
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import com.xiaomi.xms.wearable.node.*
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.function.Consumer
import java.util.function.Function
import kotlin.math.max

/** Private IPC boundary. A failed SDK cannot retain stuck callbacks in the
 * process which owns credentials, HTTP sessions and the rear provider.  */
internal class WearTransport(host: Host) {
    internal class Call<T> {
        private var complete = false
        private var value: T? = null
        private var error: Exception? = null
        private val successes: MutableList<Consumer<T?>> = ArrayList<Consumer<T?>>()
        private val failures: MutableList<Consumer<Exception?>> = ArrayList<Consumer<Exception?>>()
        fun addOnSuccessListener(listener: Consumer<T?>): Call<T> {
            if (complete && error == null) listener.accept(value) else if (!complete) successes.add(
                listener
            )
            return this
        }

        fun addOnFailureListener(listener: Consumer<Exception?>): Call<T> {
            if (complete && error != null) listener.accept(error) else if (!complete) failures.add(
                listener
            )
            return this
        }

        fun success(result: T?) {
            if (complete) return
            complete = true
            value = result
            for (c in successes) c.accept(result)
            successes.clear()
            failures.clear()
        }

        fun fail() {
            if (complete) return
            complete = true
            error = IllegalStateException("手环连接正在恢复")
            for (c in failures) c.accept(error)
            successes.clear()
            failures.clear()
        }
    }

    private val host: Host
    private val context: Context?
    private val main: Handler = Handler(Looper.getMainLooper())
    private val receiver: Messenger = Messenger(
        Handler(
            Looper.getMainLooper(),
            Handler.Callback { message: Message? -> this.receive(message!!) })
    )
    private val pending: MutableMap<Int?, Pending> = LinkedHashMap<Int?, Pending>()
    private val observers: MutableList<OnServiceConnectionListener> =
        ArrayList<OnServiceConnectionListener>()
    private var remote: Messenger? = null
    private var workerBinder: IBinder? = null
    private var dyingBinder: IBinder? = null
    private var bound = false
    private var closed = false
    private var healthReady = false
    private var workerPid = 0
    private var nextId = 0
    private val lifecycle = TransportLifecycle(System.nanoTime())
    private var incoming: OnMessageReceivedListener? = null
    private var connection: OnDataChangedListener? = null
    private var handshakeTimeout: Runnable? = null

    private class Pending(val message: Message?, r: Consumer<Bundle>, f: Runnable, t: Runnable) {
        val result: Consumer<Bundle>
        val fail: Runnable
        val timeout: Runnable

        init {
            result = r
            fail = f
            timeout = t
        }
    }

    val nodes = Nodes()
    val messages = Messages()
    val auth: Auth = Auth()
    val service = Services()
    private var binding: ServiceConnection? = null
    private fun connection(bindEpoch: Long): ServiceConnection {
        return object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
                if (closed || !lifecycle.accepts(bindEpoch) || binding !== this) return
                workerBinder = binder
                remote = Messenger(binder)
                val epoch = lifecycle.epoch
                try {
                    binder.linkToDeath(DeathRecipient {
                        main.post(Runnable {
                            if (!closed && workerBinder === binder && lifecycle.accepts(
                                    epoch
                                )
                            ) restart()
                        })
                    }, 0)
                } catch (error: RemoteException) {
                    restart()
                    return
                }
                val hello: Message = Message.obtain(null, HELLO)
                val data: Bundle = Bundle()
                data.putLong("session", lifecycle.epoch)
                hello.setData(data)
                hello.replyTo = receiver
                try {
                    remote?.send(hello)
                } catch (error: RemoteException) {
                    restart()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (!closed && lifecycle.accepts(bindEpoch) && binding === this) restart()
            }

            override fun onBindingDied(name: ComponentName?) {
                if (!closed && lifecycle.accepts(bindEpoch) && binding === this) restart()
            }

            override fun onNullBinding(name: ComponentName?) {
                if (!closed && lifecycle.accepts(bindEpoch) && binding === this) restart()
            }
        }
    }

    private fun bind() {
        if (closed || bound) return
        main.removeCallbacks(rebind)
        if (dyingBinder?.isBinderAlive == true) {
            main.postDelayed(rebind, 1000)
            return
        }
        dyingBinder = null
        val wait = lifecycle.delay(SystemClock.elapsedRealtime())
        if (wait > 0) {
            main.postDelayed(rebind, wait)
            return
        }
        lifecycle.beginAttempt()
        val attempt = lifecycle.epoch
        binding = connection(lifecycle.epoch)
        try {
            bound = context!!.bindService(
                requireNotNull(host.workerIntent()),
                requireNotNull(binding),
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
            )
        } catch (ignored: RuntimeException) {
        }
        if (handshakeTimeout != null) main.removeCallbacks(handshakeTimeout!!)
        handshakeTimeout =
            Runnable { if (!closed && lifecycle.accepts(attempt) && workerPid == 0) restart() }
        if (bound) main.postDelayed(handshakeTimeout!!, 15000) else restart()
    }

    private fun receive(message: Message): Boolean {
        val data: Bundle = message.getData()
        if (closed || message.sendingUid != Process.myUid() || !lifecycle.accepts(data.getLong("session"))) return true
        if (message.what == HELLO) {
            val pid: Int = data.getInt("pid")
            if (pid <= 0 || pid == Process.myPid() || data.getInt("uid") != Process.myUid() || (data.getString(
                    "process",
                    ""
                ) != context!!.getPackageName() + ":pluginworker")
            ) {
                restart()
                return true
            }
            workerPid = pid
            if (handshakeTimeout != null) main.removeCallbacks(handshakeTimeout!!)
            lifecycle.connected()
            for (p in ArrayList<Pending>(pending.values)) send(p)
        } else if (message.what == REPLY) {
            val p = pending.remove(data.getInt("id"))
            if (p != null) {
                main.removeCallbacks(p.timeout)
                if (data.getBoolean("ok")) {
                    p.result.accept(data)
                } else p.fail.run()
            }
        } else if (message.what == HEALTH) {
            healthReady = data.getBoolean("connected")
            for (l in ArrayList<OnServiceConnectionListener>(observers)) {
                if (healthReady) l.onServiceConnected() else l.onServiceDisconnected()
            }
        } else if (message.what == INCOMING) {
            val bytes: ByteArray? = data.getByteArray("bytes")
            if (incoming != null && bytes != null && bytes.size <= 8192) incoming?.onMessageReceived(
                data.getString("node", ""),
                bytes
            )
        } else if (message.what == CONNECTION && connection != null) {
            val result: DataSubscribeResult = DataSubscribeResult()
            result.setConnectedStatus(data.getInt("status"))
            connection?.onDataChanged(data.getString("node", ""), DataItem.ITEM_CONNECTION, result)
        }
        return true
    }

    private fun send(p: Pending) {
        if (remote == null || workerPid == 0) return
        try {
            remote?.send(p.message)
        } catch (error: RemoteException) {
            restart()
        }
    }

    private fun <T> request(
        operation: String,
        node: String?,
        bytes: ByteArray?,
        decode: Function<Bundle, T?>
    ): Call<T> {
        val call = Call<T>()
        if (closed || lifecycle.recovering() || pending.size >= 32) {
            main.post(Runnable { call.fail() })
            return call
        }
        val id = ++nextId
        val message: Message = Message.obtain(null, OPERATION)
        val data: Bundle = Bundle()
        data.putLong("session", lifecycle.epoch)
        data.putInt("id", id)
        data.putString("op", operation)
        data.putString("node", node)
        if (bytes != null) data.putByteArray("bytes", bytes)
        message.setData(data)
        message.replyTo = receiver
        val timeout = Runnable { if (pending.containsKey(id)) restart() }
        val p = Pending(
            message,
            Consumer { b: Bundle -> call.success(decode.apply(b)) },
            Runnable { call.fail() },
            timeout
        )
        pending.put(id, p)
        main.postDelayed(timeout, (if (operation == "authorize") 65000 else 15000).toLong())
        send(p)
        bind()
        return call
    }

    private fun restart() {
        if (closed || !lifecycle.restart(SystemClock.elapsedRealtime())) return
        remote = null
        healthReady = false
        dyingBinder = workerBinder
        workerBinder = null
        if (handshakeTimeout != null) main.removeCallbacks(handshakeTimeout!!)
        // Kill only our private, same-UID SDK worker. Its death is the hard fence
        // against SDK-local late writes; the main app and Health keep running.
        if (bound) {
            try {
                binding?.let { context!!.unbindService(it) }
            } catch (ignored: RuntimeException) {
            }
            bound = false
        }
        val oldPid = workerPid
        workerPid = 0
        terminate(dyingBinder, oldPid)
        val failed: MutableList<Pending> = ArrayList<Pending>(pending.values)
        pending.clear()
        for (p in failed) {
            main.removeCallbacks(p.timeout)
            p.fail.run()
        }
        for (l in ArrayList<OnServiceConnectionListener>(observers)) l.onServiceDisconnected()
        main.removeCallbacks(rebind)
        main.postDelayed(rebind, max(1000, lifecycle.delay(SystemClock.elapsedRealtime())))
    }

    private val rebind = Runnable { this.bind() }

    init {
        this.host = host
        context = host.context()
        bind()
    }

    fun confirmedLink() {
        lifecycle.confirmed(SystemClock.elapsedRealtime())
    }

    fun closeAfterDelivery() {
        main.postDelayed(Runnable { this.close() }, 500)
    }

    private fun terminate(binder: IBinder?, pid: Int) {
        if (binder == null || !binder.isBinderAlive() || pid == Process.myPid()) return
        val manager: ActivityManager? =
            context!!.getSystemService<ActivityManager?>(ActivityManager::class.java)
        val processes: MutableList<RunningAppProcessInfo>? =
            if (manager == null) null else manager.getRunningAppProcesses()
        if (processes != null) for (p in processes) if ((pid == 0 || p.pid == pid) && p.uid == Process.myUid() && p.processName == context.getPackageName() + ":pluginworker") {
            Process.killProcess(p.pid)
            return
        }
    }

    fun close() {
        if (closed) return
        closed = true
        main.removeCallbacksAndMessages(null)
        if (bound) try {
            binding?.let { context!!.unbindService(it) }
        } catch (ignored: RuntimeException) {
        }
        bound = false
        val pid = workerPid
        workerPid = 0
        remote = null
        terminate(workerBinder, pid)
        val failed: MutableList<Pending> = ArrayList<Pending>(pending.values)
        pending.clear()
        for (p in failed) p.fail.run()
        observers.clear()
        incoming = null
        connection = null
    }

    internal inner class Nodes {
        val connectedNodes: Call<MutableList<Node>>
            get() {
                return request<MutableList<Node>>(
                    "nodes",
                    "",
                    null,
                    Function { b: Bundle ->
                        val result = ArrayList<Node>()
                        val ids: ArrayList<String?>? =
                            b.getStringArrayList("nodes")
                        if (ids != null) for (id in ids) if (id != null && !id.isEmpty()) result.add(
                            Node(id, "")
                        )
                        result
                    })
            }

        fun subscribe(id: String?, item: DataItem?, listener: OnDataChangedListener?): Call<Void?> {
            connection = listener
            return request<Void?>("subscribe", id, null, Function { b: Bundle -> null })
        }

        fun unsubscribe(id: String?, item: DataItem?): Call<Void?> {
            connection = null
            return request<Void?>("unsubscribe", id, null, Function { b: Bundle -> null })
        }

        fun launchWearApp(id: String?, page: String?): Call<Void?> {
            return request<Void?>("launch", id, null, Function { b: Bundle -> null })
        }
    }

    internal inner class Messages {
        fun addListener(id: String?, listener: OnMessageReceivedListener?): Call<Void?> {
            incoming = listener
            return request<Void?>("listen", id, null, Function { b: Bundle -> null })
        }

        fun removeListener(id: String?): Call<Void?> {
            incoming = null
            return request<Void?>("unlisten", id, null, Function { b: Bundle -> null })
        }

        fun sendMessage(id: String?, bytes: ByteArray?): Call<Void?> {
            return request<Void?>("send", id, bytes, Function { b: Bundle -> null })
        }
    }

    internal inner class Auth {
        fun checkPermission(id: String?, permission: Any?): Call<Boolean?> {
            return request<Boolean?>(
                "permission",
                id,
                null,
                Function { b: Bundle -> b.getBoolean("granted") })
        }

        fun requestPermission(id: String?, permission: Any?): Call<Void?> {
            return request<Void?>("authorize", id, null, Function { b: Bundle -> null })
        }
    }

    internal inner class Services {
        fun registerServiceConnectionListener(listener: OnServiceConnectionListener) {
            observers.add(listener)
            if (healthReady) main.post(listener::onServiceConnected)
        }

        fun unregisterServiceConnectionListener(listener: OnServiceConnectionListener?) {
            observers.remove(listener)
        }
    }

    companion object {
        const val HELLO: Int = 1
        const val REPLY: Int = 2
        const val HEALTH: Int = 3
        const val INCOMING: Int = 4
        const val CONNECTION: Int = 5
        const val OPERATION: Int = 6
    }
}
