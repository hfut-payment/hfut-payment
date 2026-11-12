package dev.codex.hfutcampus

import android.content.Context

internal interface BandExtension : NativePlugin {
    fun showBalance(enabled: Boolean)
}

internal object BuiltInBand {
    const val ID = "hfut.band"
    const val VERSION = "2.0.6"
    const val REVISION = 200106
    const val INSTALLER = "HFUT-Campus-Band-$VERSION.rpk"
    const val AVAILABLE = BuildConfig.BAND_SUPPORTED
    val bundle by lazy(PluginBundle::builtInBand)

    private fun prefs(context: Context) = context.getSharedPreferences("campus-recovery", Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = AVAILABLE && prefs(context).getBoolean("service-enabled", false)

    fun enabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("service-enabled", enabled).apply()
    }
}
