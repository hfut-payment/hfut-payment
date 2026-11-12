package dev.codex.hfutcampus

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Android entry point for a verified plugin's private IPC worker.  */
class PluginBindingService : Service() {
    private var worker: NativePlugin.Worker? = null

    override fun onBind(intent: Intent?): IBinder? {
        if (worker != null || intent == null) return null
        val bundle: PluginBundle? = LocalPlugins.Companion.get(this).bindingBundle(
            intent.getStringExtra(NativePlugins.ID), intent.getIntExtra(NativePlugins.REVISION, -1)
        )
        if (bundle == null) return null
        val plugin = NativePlugins.load(this, bundle, true)
        if (plugin == null) return null
        try {
            worker = plugin.worker()
            return if (worker == null) null else worker!!.binder()
        } catch (invalid: RuntimeException) {
            return null
        } catch (invalid: LinkageError) {
            return null
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopSelf()
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        if (worker != null) worker!!.close()
    }
}
