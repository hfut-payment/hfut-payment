package dev.codex.hfutcampus

import android.Manifest
import dev.codex.hfutcampus.NativePlugin.Host
import dev.codex.hfutcampus.NativePlugin
import android.content.SharedPreferences
import android.os.Looper
import dev.codex.hfutcampus.WearBridge
import org.json.JSONObject
import org.json.JSONException
import dev.codex.hfutcampus.NativePlugin.Ui
import android.os.Build
import android.content.pm.PackageManager
import dev.codex.hfutcampus.WearTransportService
import android.content.Context
import android.os.Handler
import kotlin.math.min

internal class BandPlugin(private val host: Host) : BandExtension {
    private val prefs: SharedPreferences
    private val main = Handler(Looper.getMainLooper())
    private val discover = Runnable { this.discover() }
    private var bridge: WearBridge? = null
    private var attempts = 0
    private var openRequested = false
    private var linked = false

    init {
        prefs = host.context()!!.getSharedPreferences("campus-recovery", Context.MODE_PRIVATE)
    }

    override fun state(): JSONObject {
        try {
            return JSONObject().put(
                "status",
                if (host.running()) WearBridge.Companion.status() else "手环连接已关闭"
            )
                .put("serviceRunning", host.running())
                .put("balanceEnabled", prefs.getBoolean(SHOW_BALANCE, false))
        } catch (impossible: JSONException) {
            throw AssertionError(impossible)
        }
    }

    override fun command(action: String?, ui: Ui?) {
        if (ui == null) return
        when (action) {
            "connect", "open" -> {
                if (action == "open" && !BuiltInBand.enabled(requireNotNull(host.context()))) return
                if (!host.accountConfigured()) {
                    ui.account()
                    return
                }
                val start = Runnable { ui.startBackground(action) }
                if (Build.VERSION.SDK_INT >= 31) ui.permission(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    "允许附近设备权限后，手环可通过运动健康连接。", start
                )
                else start.run()
            }

            else -> super.command(action, ui)
        }
    }

    override fun showBalance(enabled: Boolean) {
        prefs.edit().putBoolean(SHOW_BALANCE, enabled).apply()
        bridge?.updateBalanceSetting()
    }

    override fun restore(boot: Boolean): Boolean {
        return BuiltInBand.enabled(requireNotNull(host.context()))
                && (!boot || prefs.getBoolean("band-boot", prefs.getBoolean("boot-recovery", true)))
                && (Build.VERSION.SDK_INT < 31
                || host.context()!!
            .checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
    }

    override fun start(action: String?) {
        val user = "restore" != action
        openRequested = openRequested or ("open" == action)
        attempts = 0
        if (bridge == null) {
            bridge = WearBridge(host, Runnable { this.linkChanged() })
            bridge!!.start(user)
        } else if (user) bridge!!.refreshNodes(true)
        linkChanged()
    }

    private fun linkChanged() {
        if (bridge == null) return
        val ready = bridge!!.isLinked
        if (openRequested && ready) {
            openRequested = false
            bridge!!.openBand()
        }
        main.removeCallbacks(discover)
        if (linked != ready) attempts = 0
        linked = ready
        main.postDelayed(discover, if (ready) 120000 else min(60000, 5000L shl min(attempts++, 4)))
    }

    private fun discover() {
        if (bridge == null) return
        bridge!!.refreshNodes(false)
        linkChanged()
    }

    override fun stop() {
        main.removeCallbacks(discover)
        val previous = bridge
        bridge = null
        linked = false
        openRequested = false
        if (previous != null) previous.stop()
    }

    override fun worker(): NativePlugin.Worker {
        return WearTransportService(requireNotNull(host.context()))
    }

    companion object { internal const val SHOW_BALANCE = "band-show-balance" }
}

internal fun createBandPlugin(host: Host): NativePlugin = BandPlugin(host)
