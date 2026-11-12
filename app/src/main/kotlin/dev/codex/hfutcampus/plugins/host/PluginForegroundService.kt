package dev.codex.hfutcampus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import android.os.SystemClock

/** Android foreground lifetime and power scheduling shared by background extensions.  */
class PluginForegroundService : Service() {
    private val running: MutableMap<String?, NativePlugin?> = HashMap<String?, NativePlugin?>()
    private val main = Handler(Looper.getMainLooper())
    private val wakeTick = Runnable { this.updateWake() }
    private val snapshotChanged = CampusRuntime.Listener { ignored: CardSnapshot? -> updateWake() }
    private val screen: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            updateWake()
        }
    }
    private val connection: NetworkCallback = object : NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            main.post(wakeTick)
        }

        override fun onLost(network: Network) {
            main.post(wakeTick)
        }
    }
    private var runtime: CampusRuntime? = null
    private var connectivity: ConnectivityManager? = null
    private var power: PowerManager? = null
    private var wake: WakeLock? = null
    private var renewAt: Long = 0
    private var transferUntil: Long = 0
    private var registered = false
    private var screenRegistered = false
    private var wasBusy = false
    private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        active = this
        runtime = CampusRuntime.Companion.get(this)
        power = getSystemService<PowerManager?>(PowerManager::class.java)
        connectivity = getSystemService<ConnectivityManager?>(ConnectivityManager::class.java)
        if (power != null) {
            wake = power!!.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                getPackageName() + ":plugin-display"
            )
            wake!!.setReferenceCounted(false)
        }
        val manager = getSystemService<NotificationManager>(NotificationManager::class.java)
        val channel = NotificationChannel(
            "campus-companion", "插件后台服务",
            NotificationManager.IMPORTANCE_LOW
        )
        channel.setSound(null, null)
        channel.setShowBadge(false)
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET)
        manager.createNotificationChannel(channel)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        startForeground(
            2101, Notification.Builder(this, channel.getId())
                .setSmallIcon(R.drawable.ic_stat_campus).setContentTitle(getString(R.string.app_name))
                .setContentText("插件正在后台运行").setContentIntent(open)
                .setVisibility(Notification.VISIBILITY_SECRET).setOngoing(true).build()
        )
        runtime!!.addListener(snapshotChanged)
        if (connectivity != null) {
            try {
                connectivity!!.registerDefaultNetworkCallback(connection)
                registered = true
            } catch (ignored: RuntimeException) {
            }
        }
        val screens = IntentFilter(Intent.ACTION_SCREEN_ON)
        screens.addAction(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, screens, RECEIVER_NOT_EXPORTED)
        else registerReceiver(screen, screens)
        screenRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        var pending = false
        if (intent == null) pending = restore(this, false)
        else {
            val bundle: PluginBundle? =
                LocalPlugins.Companion.get(this).find(intent.getStringExtra(NativePlugins.ID))
            if (bundle != null && LoginPolicy.allowsService(this, bundle) && bundle.revision == intent.getIntExtra(
                    NativePlugins.REVISION,
                    -1
                )
            ) {
                val plugin = NativePlugins.get(this, bundle)
                if (plugin != null) {
                    running.put(bundle.id, plugin)
                    try {
                        plugin.start(intent.getStringExtra(ACTION))
                    } catch (failure: RuntimeException) {
                        stopPlugin(bundle.id)
                    } catch (failure: LinkageError) {
                        stopPlugin(bundle.id)
                    }
                    syncRuntime()
                    runtime?.let { if (running.containsKey(bundle.id) && !it.isConfigured) it.restoreLogin() }
                }
            }
        }
        if (running.isEmpty() && !pending) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        updateWake()
        return START_STICKY
    }

    private fun stopPlugin(id: String?) {
        val plugin = running.remove(id)
        if (plugin != null) {
            try {
                plugin.stop()
            } catch (ignored: RuntimeException) {
            } catch (ignored: LinkageError) {
            }
        }
        runtime!!.setNativeDemand(id, 0)
        runtime!!.setBalanceDemand(id, false)
        syncRuntime()
    }

    private fun syncRuntime() {
        runtime?.setBackgroundLogin(running.isNotEmpty())
    }

    private fun updateWake() {
        if (destroyed || wake == null) return
        main.removeCallbacks(wakeTick)
        val busy = runtime!!.isBusy
        val now = SystemClock.elapsedRealtime()
        if (wasBusy && !busy) transferUntil = now + 5000
        wasBusy = busy
        val network = if (connectivity == null) null else connectivity!!.getActiveNetwork()
        val caps = if (network == null) null else connectivity!!.getNetworkCapabilities(network)
        val connected =
            caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val canRefresh = runtime!!.current().ready
                || (connected && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        val needed = !running.isEmpty() && !power!!.isInteractive() && connected
                && (busy || now < transferUntil || (canRefresh && runtime!!.hasDisplayDemand()
                && runtime!!.nextRefreshDelayMs() != Long.Companion.MAX_VALUE))
        if (!needed) {
            if (wake!!.isHeld()) wake!!.release()
            return
        }
        if (!wake!!.isHeld() || now >= renewAt) {
            if (wake!!.isHeld()) wake!!.release()
            wake!!.acquire(90000)
            renewAt = now + 60000
        }
        main.postDelayed(wakeTick, if (now < transferUntil) transferUntil - now else 60000)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        destroyed = true
        for (id in running.keys.toTypedArray<String?>()) stopPlugin(id)
        main.removeCallbacksAndMessages(null)
        runtime!!.removeListener(snapshotChanged)
        if (registered) connectivity!!.unregisterNetworkCallback(connection)
        if (screenRegistered) unregisterReceiver(screen)
        if (wake != null && wake!!.isHeld()) wake!!.release()
        active = null
        super.onDestroy()
    }

    companion object {
        private const val ACTION = "plugin-action"
        private var active: PluginForegroundService? = null
        fun running(id: String?): Boolean {
            return active != null && active!!.running.containsKey(id)
        }

        internal fun start(context: Context, plugin: PluginBundle, action: String?) {
            check(LoginPolicy.allowsService(context, plugin)) { "未开启此功能的后台运行" }
            context.startForegroundService(
                Intent(context, PluginForegroundService::class.java)
                    .putExtra(NativePlugins.ID, plugin.id)
                    .putExtra(NativePlugins.REVISION, plugin.revision)
                    .putExtra(ACTION, action)
            )
        }

        fun restore(context: Context, boot: Boolean): Boolean {
            val hooks = LoginPolicy.backgroundPlugins(context)
            LoginPolicy.updateBootReceiver(context, hooks)
            val required = hooks.filter { it.isNative }
            val requestedIds = required.mapTo(hashSetOf()) { it.id }
            active?.running?.keys?.toList()?.filterNot { it in requestedIds }?.forEach(::detach)
            if (required.isEmpty() || boot && !LoginPolicy.boot(context)) return false
            val runtime = CampusRuntime.get(context)
            if (!runtime.savedLoginAvailable()) return false
            var scheduled = false
            for (bundle in required) {
                if (running(bundle.id)) continue
                val plugin = NativePlugins.get(context, bundle)
                if (plugin == null) continue
                try {
                    if (plugin.restore(boot && LoginPolicy.useLegacyBootSetting(context))) {
                        start(context, bundle, "restore")
                        scheduled = true
                    }
                } catch (unavailable: RuntimeException) { /* Retry on the next foreground entry. */
                } catch (unavailable: LinkageError) {
                }
            }
            return scheduled
        }

        fun detach(id: String?) {
            val service: PluginForegroundService? = active
            if (service == null) return
            service.stopPlugin(id)
            if (service.running.isEmpty()) service.stopSelf()
            else service.updateWake()
        }

        fun accountCleared(context: Context) {
            for (bundle in LoginPolicy.backgroundPlugins(context).filter { it.isNative }) {
                val plugin = NativePlugins.get(context, bundle)
                if (plugin == null) continue
                try {
                    plugin.accountCleared()
                } catch (ignored: RuntimeException) {
                } catch (ignored: LinkageError) {
                }
                detach(bundle.id)
            }
        }
    }
}
