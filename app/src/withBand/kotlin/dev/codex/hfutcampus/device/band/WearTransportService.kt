package dev.codex.hfutcampus

import com.xiaomi.xms.wearable.tasks.Task
import com.xiaomi.xms.wearable.service.*
import com.xiaomi.xms.wearable.message.*
import com.xiaomi.xms.wearable.node.*
import com.xiaomi.xms.wearable.auth.*
import android.os.*
import android.content.Context
import android.os.Handler
import android.os.Message
import android.os.Process
import android.os.RemoteException
import com.xiaomi.xms.wearable.Wearable
import java.util.ArrayList

/** SDK-only private process. Never creates CampusRuntime or accesses accounts.  */
internal class WearTransportService(private val context: Context) : NativePlugin.Worker {
    private val main: Handler = Handler(Looper.getMainLooper())
    private val receiver: Messenger = Messenger(
        Handler(
            Looper.getMainLooper(),
            Handler.Callback { message: Message? -> this.receive(message!!) })
    )
    private var client: Messenger? = null
    private var session: Long = 0
    private var nodes: NodeApi? = null
    private var messages: MessageApi? = null
    private var auth: AuthApi? = null
    private var service: ServiceApi? = null
    private val connection: OnServiceConnectionListener = object : OnServiceConnectionListener {
        override fun onServiceConnected() {
            main.post(Runnable {
                val b: Bundle = Bundle()
                b.putBoolean("connected", true)
                emit(WearTransport.Companion.HEALTH, b)
            })
        }

        override fun onServiceDisconnected() {
            main.post(Runnable {
                val b: Bundle = Bundle()
                b.putBoolean("connected", false)
                emit(WearTransport.Companion.HEALTH, b)
            })
        }
    }

    private fun emit(type: Int, data: Bundle) {
        if (client == null) return
        data.putLong("session", session)
        val message = Message.obtain(null, type)
        message.setData(data)
        try {
            client?.send(message)
        } catch (ignored: RemoteException) {
        }
    }

    private fun reply(id: Int, success: Boolean) {
        val b: Bundle = Bundle()
        b.putInt("id", id)
        b.putBoolean("ok", success)
        emit(WearTransport.Companion.REPLY, b)
    }

    private fun finish(id: Int, task: Task<*>) {
        task.addOnSuccessListener({ v -> main.post(Runnable { reply(id, true) }) })
            .addOnFailureListener({ e -> main.post(Runnable { reply(id, false) }) })
    }

    private fun receive(message: Message): Boolean {
        if (message.sendingUid != Process.myUid()) return true
        val b: Bundle = message.getData()
        if (message.what == WearTransport.Companion.HELLO) {
            // A worker serves exactly one binding generation. Never relabel old
            // SDK callbacks with a replacement client's generation.
            if (client != null) {
                Process.killProcess(Process.myPid())
                return true
            }
            client = message.replyTo
            session = b.getLong("session")
            val hello: Bundle = Bundle()
            hello.putInt("pid", Process.myPid())
            hello.putInt("uid", Process.myUid())
            hello.putString("process", context.getPackageName() + ":pluginworker")
            emit(WearTransport.Companion.HELLO, hello)
            try {
                nodes = Wearable.getNodeApi(context)
                messages = Wearable.getMessageApi(context)
                auth = Wearable.getAuthApi(context)
                service = Wearable.getServiceApi(context)
                service?.registerServiceConnectionListener(connection)
            } catch (ignored: RuntimeException) {
            }
            return true
        }
        if (message.what != WearTransport.Companion.OPERATION || b.getLong("session") != session) return true
        val id: Int = b.getInt("id")
        val node = b.getString("node", "").orEmpty()
        try {
            val nodes = checkNotNull(nodes)
            val messages = checkNotNull(messages)
            val auth = checkNotNull(auth)
            when (b.getString("op", "")) {
                "nodes" -> nodes.getConnectedNodes().addOnSuccessListener({ found ->
                    main.post(Runnable {
                        val out: Bundle = Bundle()
                        val ids = ArrayList<String?>()
                        if (found != null) for (n in found) if (n != null && n.id != null && !n.id.isEmpty() && n.id.length <= 512 && ids.size < 16) ids.add(
                            n.id
                        )
                        out.putStringArrayList("nodes", ids)
                        out.putInt("id", id)
                        out.putBoolean("ok", true)
                        emit(WearTransport.Companion.REPLY, out)
                    })
                }).addOnFailureListener({ e -> main.post(Runnable { reply(id, false) }) })

                "permission" -> auth.checkPermission(node, Permission.DEVICE_MANAGER)
                    .addOnSuccessListener({ granted ->
                        main.post(Runnable {
                            val out: Bundle = Bundle()
                            out.putInt("id", id)
                            out.putBoolean("ok", true)
                            out.putBoolean("granted", granted)
                            emit(WearTransport.Companion.REPLY, out)
                        })
                    }).addOnFailureListener({ e -> main.post(Runnable { reply(id, false) }) })

                "authorize" -> finish(id, auth.requestPermission(node, Permission.DEVICE_MANAGER))
                "listen" -> finish(id, messages.addListener(node, { from, bytes ->
                    if (bytes == null || bytes.size > 8192) return@addListener
                    main.post(Runnable {
                        val out: Bundle = Bundle()
                        out.putString("node", from)
                        out.putByteArray("bytes", bytes)
                        emit(WearTransport.Companion.INCOMING, out)
                    })
                }))

                "unlisten" -> finish(id, messages.removeListener(node))
                "subscribe" -> finish(
                    id,
                    nodes.subscribe(node, DataItem.ITEM_CONNECTION, { from, item, data ->
                        val status: Int = data.getConnectedStatus()
                        main.post(Runnable {
                            val out: Bundle = Bundle()
                            out.putString("node", from)
                            out.putInt("status", status)
                            emit(WearTransport.Companion.CONNECTION, out)
                        })
                    })
                )

                "unsubscribe" -> finish(id, nodes.unsubscribe(node, DataItem.ITEM_CONNECTION))
                "send" -> {
                    val bytes: ByteArray? = b.getByteArray("bytes")
                    if (bytes == null || bytes.size > 65536) {
                        reply(id, false)
                        return true
                    }
                    finish(id, messages.sendMessage(node, bytes))
                }

                "launch" -> finish(id, nodes.launchWearApp(node, "Card"))
                else -> reply(id, false)
            }
        } catch (error: RuntimeException) {
            reply(id, false)
        }
        return true
    }

    override fun binder(): IBinder? {
        return receiver.getBinder()
    }

    override fun close() {
        if (service != null) try {
            service?.unregisterServiceConnectionListener(connection)
        } catch (ignored: RuntimeException) {
        }
        Process.killProcess(Process.myPid())
    }
}
