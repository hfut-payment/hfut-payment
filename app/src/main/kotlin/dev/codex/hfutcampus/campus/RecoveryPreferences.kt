package dev.codex.hfutcampus

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.Settings

internal object LoginPolicy {
    private fun prefs(context: Context) = context.getSharedPreferences("campus-login-policy", Context.MODE_PRIVATE)
    fun automatic(context: Context) = prefs(context).getBoolean("automatic", false)
    fun automatic(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("automatic", enabled).apply()
    }

    fun boot(context: Context) = prefs(context).getBoolean("boot", true)
    fun useLegacyBootSetting(context: Context) = !prefs(context).contains("boot")
    fun boot(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("boot", enabled).apply()
        updateBootReceiver(context, backgroundPlugins(context))
    }

    fun noteForeground(context: Context) {
        val count = bootCount(context)
        if (count >= 0 && prefs(context).getInt("foreground-boot", -1) != count) {
            prefs(context).edit().putInt("foreground-boot", count).apply()
        }
    }

    fun canRunCampusJob(context: Context): Boolean {
        if (boot(context)) return true
        val count = bootCount(context)
        return count >= 0 && count == prefs(context).getInt("foreground-boot", -1)
    }

    private fun bootCount(context: Context) =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    fun backgroundPlugins(context: Context): List<PluginBundle> {
        val local = LocalPlugins.get(context)
        val hooks = local.list().filter { it.backgroundLogin && local.active(it) }
        return if (BuiltInBand.enabled(context)) hooks + BuiltInBand.bundle else hooks
    }

    fun allowsService(context: Context, bundle: PluginBundle): Boolean =
        bundle.isNative && bundle.backgroundLogin && LocalPlugins.get(context).active(bundle) &&
            (bundle.id != BuiltInBand.ID || BuiltInBand.enabled(context))

    fun updateBootReceiver(context: Context, hooks: List<PluginBundle>) {
        val manager = context.packageManager
        val receiver = ComponentName(context, RecoveryReceiver::class.java)
        val desired = if (boot(context) && hooks.any { it.isNative })
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (manager.getComponentEnabledSetting(receiver) != desired) {
            manager.setComponentEnabledSetting(receiver, desired, PackageManager.DONT_KILL_APP)
        }
    }
}

/** A tombstone prevents interrupted account updates from restoring old credentials.  */
internal class RecoveryPreferences(private val store: Store) {
    internal interface Store {
        fun usable(): Boolean
        fun usable(value: Boolean)
    }

    constructor(context: Context) : this(object : Store {
        val prefs: SharedPreferences =
            context.getSharedPreferences("campus-recovery", Context.MODE_PRIVATE)

        override fun usable(): Boolean {
            return prefs.getBoolean("credentials-usable", true)
        }

        override fun usable(value: Boolean) {
            prefs.edit().putBoolean("credentials-usable", value).apply()
        }
    })

    fun credentialsUsable(): Boolean {
        return store.usable()
    }

    fun credentialsUsable(usable: Boolean) {
        store.usable(usable)
    }
}
