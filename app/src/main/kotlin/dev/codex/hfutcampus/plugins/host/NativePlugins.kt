package dev.codex.hfutcampus

import android.content.Context
import android.content.Intent
import dalvik.system.InMemoryDexClassLoader
import dev.codex.hfutcampus.NativePlugin.Host
import java.nio.ByteBuffer

internal object NativePlugins {
    const val ID: String = "plugin-id"
    const val REVISION: String = "plugin-revision"
    private val active: MutableMap<String?, Loaded?> = HashMap<String?, Loaded?>()

    @Synchronized
    fun get(context: Context, bundle: PluginBundle?): NativePlugin? {
        if (bundle == null || !bundle.isNative || !LocalPlugins.Companion.get(context)
                .active(bundle)
        ) return null
        val old = active.get(bundle.id)
        if (old != null) return if (old.bundle == bundle) old.plugin else null
        val plugin = load(context, bundle, false)
        if (plugin != null) active.put(bundle.id, Loaded(bundle, plugin))
        return plugin
    }

    fun changed(context: Context, id: String?) {
        val old: Loaded?
        synchronized(NativePlugins::class.java) { old = active.remove(id) }
        PluginForegroundService.Companion.detach(id)
        if (old != null) {
            try {
                old.plugin.deactivated(!LocalPlugins.Companion.get(context).enabled(id))
            } catch (ignored: RuntimeException) {
            } catch (ignored: LinkageError) {
            }
        }
        CampusRuntime.Companion.get(context).setNativeDemand(id, 0)
        CampusRuntime.Companion.get(context).setBalanceDemand(id, false)
    }

    fun load(context: Context, bundle: PluginBundle, worker: Boolean): NativePlugin? {
        try {
            val environment = Environment(context.applicationContext, bundle, worker)
            bundle.device?.let { return it.create(environment) }
            val dex = bundle.dex ?: return null
            val loader = InMemoryDexClassLoader(
                ByteBuffer.wrap(dex),
                NativePlugin::class.java.getClassLoader()
            )
            val entry = loader.loadClass(bundle.entryClass)
            if (entry.getClassLoader() !== loader) return null
            return entry.asSubclass<NativePlugin?>(NativePlugin::class.java)
                .getConstructor(Host::class.java)
                .newInstance(environment)
        } catch (unavailable: ReflectiveOperationException) {
            return null
        } catch (unavailable: RuntimeException) {
            return null
        } catch (unavailable: LinkageError) {
            return null
        }
    }

    private class Loaded(val bundle: PluginBundle?, val plugin: NativePlugin)

    private class Environment(
        private val context: Context,
        private val bundle: PluginBundle,
        private val worker: Boolean
    ) : Host {
        fun runtime(): CampusRuntime? {
            check(!worker) { "No payment access in the binding process" }
            return CampusRuntime.Companion.get(context)
        }

        override fun context(): Context {
            return context
        }

        override fun accountConfigured(): Boolean {
            return runtime()!!.isConfigured
        }

        override fun accountSaved(): Boolean {
            return runtime()!!.isRemembered
        }

        override fun accountRevision(): Long {
            return runtime()!!.configurationRevision()
        }

        override fun snapshotInstance(): String? {
            return runtime()!!.instanceId
        }

        override fun snapshot(): CardSnapshot? {
            val current = runtime()!!.current()
            return if (LocalPlugins.Companion.get(context).active(bundle))
                current
            else
                CardSnapshot.Companion.hidden(
                    current.revision,
                    false,
                    "插件已停用",
                    "plugin_disabled"
                )
        }

        override fun balance(): BalanceSnapshot? =
            if (LocalPlugins.get(context).active(bundle)) runtime()!!.balance(bundle.id) else null

        override fun setBalanceDemand(enabled: Boolean) {
            if (!enabled || LocalPlugins.get(context).active(bundle)) runtime()!!.setBalanceDemand(bundle.id, enabled)
        }

        override fun observeBalance(listener: Runnable) = runtime()!!.observeBalance(listener)

        override fun unobserveBalance(listener: Runnable) = runtime()!!.unobserveBalance(listener)

        override fun codes(): PaymentCodes {
            runtime()
            return PaymentCodes.Companion.shared()
        }

        override fun observe(listener: CampusRuntime.Listener) {
            runtime()!!.addListener(listener)
        }

        override fun unobserve(listener: CampusRuntime.Listener?) {
            runtime()!!.removeListener(listener)
        }

        override fun displayUntil(deadline: Long) {
            if (LocalPlugins.Companion.get(context).active(bundle)) runtime()!!.setNativeDemand(
                bundle.id,
                deadline
            )
        }

        override fun workerIntent(): Intent {
            return Intent(context, PluginBindingService::class.java)
                .setAction(context.getPackageName() + ".worker." + bundle.id)
                .putExtra(ID, bundle.id).putExtra(REVISION, bundle.revision)
        }

        override fun running(): Boolean {
            return LocalPlugins.Companion.get(context)
                .active(bundle) && PluginForegroundService.Companion.running(bundle.id)
        }

        override fun stopBackground() {
            if (LocalPlugins.Companion.get(context)
                    .active(bundle)
            ) PluginForegroundService.Companion.detach(bundle.id)
        }
    }
}
