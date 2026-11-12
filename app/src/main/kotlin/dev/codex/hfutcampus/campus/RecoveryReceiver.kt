package dev.codex.hfutcampus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Normal boot/package-update recovery only; no polling alarm, root or exported control.  */
class RecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = if (intent == null) "" else intent.getAction()
        if (Intent.ACTION_BOOT_COMPLETED == action) PluginForegroundService.Companion.restore(
            context,
            true
        )
        else if (Intent.ACTION_MY_PACKAGE_REPLACED == action) PluginForegroundService.Companion.restore(
            context,
            true
        )
    }
}
