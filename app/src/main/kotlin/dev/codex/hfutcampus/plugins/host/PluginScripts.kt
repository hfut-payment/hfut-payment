package dev.codex.hfutcampus

import android.content.Context
import android.util.Base64
import androidx.javascriptengine.JavaScriptSandbox
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object PluginScripts {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    // Only accessed on worker. The sandbox has no credential or network API.
    private var sandbox: JavaScriptSandbox? = null

    fun run(
        context: Context,
        plugin: PluginBundle,
        event: JSONObject,
        read: String?,
        completion: Result,
        approval: CommunityBudget.Approval? = null,
        passwordInputs: Map<String, String> = emptyMap(),
        background: Boolean = false,
        mode: CommunityRuntime.Mode = CommunityRuntime.Mode.INTERACTIVE,
        jobAccount: String? = null,
        alive: () -> Boolean = { true }
    ) {
        val app = context.getApplicationContext()
        worker.execute(Runnable {
            val result: JSONObject?
            try {
                if (!JavaScriptSandbox.isSupported()) {
                    completion.complete(null, "当前系统 WebView 不支持插件沙箱")
                    return@Runnable
                }
                if (sandbox == null) sandbox =
                    JavaScriptSandbox.createConnectedInstanceAsync(app).get(5, TimeUnit.SECONDS)
                if (plugin.canRead(read)) event.put("payment", readPayment(app, plugin, read))
                result = if (plugin.community == null) evaluate(plugin, event)
                    else CommunityRuntime(app, plugin, approval, passwordInputs, background, mode, jobAccount, alive, ::evaluate).run(event)
            } catch (failure: Exception) {
                if (sandbox != null) {
                    try {
                        sandbox!!.close()
                    } catch (ignored: RuntimeException) {
                    }
                    sandbox = null
                }
                val message = if (plugin.community != null &&
                    (failure is IllegalStateException || failure is IllegalArgumentException)) {
                    failure.message?.take(180).orEmpty()
                } else ""
                completion.complete(null, message.ifBlank { "插件运行失败或超时，请检查网络、权限和插件配置" })
                return@Runnable
            }
            completion.complete(result, "")
        })
    }

    private fun evaluate(plugin: PluginBundle, event: JSONObject): JSONObject =
        sandbox!!.createIsolate().use { isolate ->
            val code = ("(() => { 'use strict';\n" + plugin.script
                    + "\nreturn JSON.stringify(handle(JSON.parse(" + JSONObject.quote(event.toString()) + ")));\n})()")
            val json = isolate.evaluateJavaScriptAsync(code).get(2, TimeUnit.SECONDS)
            if (json!!.length > 8192) throw IOException("Plugin response too large")
            JSONObject(json)
        }

    @Throws(Exception::class)
    private fun readPayment(app: Context, plugin: PluginBundle, format: String?): JSONObject {
        val local: LocalPlugins = LocalPlugins.Companion.get(app)
        val runtime: CampusRuntime = CampusRuntime.Companion.get(app)
        val snapshot = runtime.current()
        var image: RearQrEncoder.Image? = null
        if (snapshot.ready && local.active(plugin) && "qr" == format) {
            try {
                image = PaymentCodes.Companion.shared().qr(snapshot, 360)
            } catch (unavailable: Exception) { /* Unavailable images return an empty snapshot. */
            }
        }
        val current = runtime.current()
        val ready =
            local.active(plugin) && current.ready && current.revision == snapshot.revision && ("text" == format || image != null)
        val payment: JSONObject =
            JSONObject().put("ready", ready).put("profile", PaymentCodes.Companion.PROFILE)
        if (!ready) return payment
        payment.put("revision", current.revision).put("instanceId", runtime.instanceId)
            .put("expiresAt", current.expiresAt).put("remainingMs", current.remainingMs)
        if (image == null) return payment.put("code", current.code)
        return payment.put("pngBase64", Base64.encodeToString(image.png, Base64.NO_WRAP))
            .put("side", image.side)
    }

    internal fun interface Result {
        fun complete(value: JSONObject?, problem: String?)
    }
}
