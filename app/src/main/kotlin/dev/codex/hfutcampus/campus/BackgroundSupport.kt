package dev.codex.hfutcampus

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/** Read actual platform state; never pretend an OEM autostart switch can be verified.  */
object BackgroundSupport {
    private fun open(a: Activity, intent: Intent?) {
        try {
            a.startActivity(intent)
        } catch (unavailable: Exception) {
            try {
                a.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + a.getPackageName())
                    )
                )
            } catch (ignored: Exception) {
            }
        }
    }

    @JvmStatic fun openAppSettings(a: Activity) {
        open(
            a,
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + a.getPackageName())
            )
        )
    }

    @JvmStatic fun openNotifications(a: Activity) {
        open(
            a,
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(
                Settings.EXTRA_APP_PACKAGE,
                a.getPackageName()
            )
        )
    }

    @JvmStatic fun openBattery(a: Activity) {
        val p = a.getSystemService<PowerManager?>(PowerManager::class.java)
        if (p != null && !p.isIgnoringBatteryOptimizations(a.getPackageName())) open(
            a,
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + a.getPackageName())
            )
        )
        else open(a, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    @JvmStatic fun openAutostart(a: Activity) {
        val intent = Intent().setComponent(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
        )
        if (intent.resolveActivity(a.getPackageManager()) != null) open(
            a,
            intent
        ) else openAppSettings(a)
    }
}
