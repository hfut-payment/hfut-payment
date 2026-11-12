package dev.codex.hfutcampus

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import java.io.File
import java.io.FilenameFilter
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.TreeMap

internal class LocalPlugins private constructor(private val context: Context) {
    private val root: File
    private val grants: SharedPreferences
    private val installed = TreeMap<String?, PluginBundle>()
    private val enabledNative = HashSet<String?>()

    init {
        root = File(context.getFilesDir(), "runtime-plugins")
        grants = context.getSharedPreferences("runtime-plugins", Context.MODE_PRIVATE)
        val files =
            root.listFiles(FilenameFilter { dir: File?, name: String? -> name!!.matches("[a-z][a-z0-9.-]{2,63}\\.hfp".toRegex()) })
        for (file in files.orEmpty()) {
            try {
                AtomicFile(file).openRead().use { input ->
                    val plugin: PluginBundle = PluginBundle.Companion.read(input)
                    if (file.getName() == plugin.id + ".hfp" && !builtIn(plugin.id)) {
                        installed.put(plugin.id, plugin)
                        if (plugin.isNative && nativeGrant(plugin)) enabledNative.add(plugin.id)
                    }
                }
            } catch (invalid: Exception) { /* An invalid local package stays inactive. */
            }
        }
    }

    @Synchronized
    fun list(): List<PluginBundle> {
        return ArrayList<PluginBundle>(installed.values)
    }

    @Synchronized
    fun find(id: String?): PluginBundle? {
        return if (id == BuiltInBand.ID) {
            if (BuiltInBand.AVAILABLE) BuiltInBand.bundle else null
        } else installed[id]
    }

    @Synchronized
    fun enabled(id: String?): Boolean {
        if (id == BuiltInBand.ID) return BuiltInBand.AVAILABLE
        val plugin = installed.get(id)
        return plugin != null && !plugin.legacy() && storedGrant(plugin)
    }

    @Synchronized
    fun active(plugin: PluginBundle): Boolean {
        if (plugin.id == BuiltInBand.ID) return BuiltInBand.AVAILABLE && plugin === BuiltInBand.bundle
        return installed.get(plugin.id) == plugin && enabled(plugin.id) && dependencyIssues(plugin).isEmpty()
    }

    @Synchronized
    fun dependencyIssues(plugin: PluginBundle): List<String> {
        val issues = linkedSetOf<String>()
        val depths = HashMap<String, Int>()
        val visiting = HashSet<String>()
        fun visit(bundle: PluginBundle, depth: Int) {
            if (depth >= 8 || !visiting.add(bundle.id)) {
                issues.add("插件依赖存在循环或超过八层")
                return
            }
            val previous = depths[bundle.id] ?: -1
            if (depth > previous) {
                depths[bundle.id] = depth
                bundle.community?.dependencies?.forEach { (id, requirement) ->
                    if (CampusRecharge.provides(bundle, id, requirement)) return@forEach
                    val target = if (id == plugin.id) plugin else installed[id]
                    val exports = target?.community?.exports
                    when {
                        id == CampusRecharge.PROVIDER && CampusRecharge.signedAutomation(bundle) ->
                            issues.add("请更新自动充值插件，以使用主应用的充值服务")
                        target == null -> issues.add("请先安装依赖 $id")
                        target.signer != requirement.signer -> issues.add("${target.name} 的发布者签名不匹配")
                        target.revision < requirement.revision -> issues.add("请先更新 ${target.name}")
                        exports == null || !exports.containsAll(requirement.methods) ->
                            issues.add("${target.name} 缺少所需接口")
                        id != plugin.id && !enabled(id) -> issues.add("请先启用 ${target.name}")
                        else -> visit(target, depth + 1)
                    }
                }
            }
            visiting.remove(bundle.id)
        }
        visit(plugin, 0)
        return issues.toList()
    }

    @Synchronized
    @Throws(IOException::class)
    fun install(plugin: PluginBundle) {
        require(!builtIn(plugin.id)) { "这项功能已内置到主应用" }
        val old = installed.get(plugin.id)
        plugin.checkUpdate(old)
        checkDependencies(plugin)
        if (plugin.legacy()) throw IOException("请安装适用于当前宿主的插件版本")
        if (!plugin.authority.isEmpty()) {
            val slot = context.getPackageManager().resolveContentProvider(plugin.authority, 0)
            if (slot == null || (context.getPackageName() != slot.packageName) || (PluginContentRouter::class.java.getName() != slot.name)) throw IOException(
                "当前宿主未注册此接口"
            )
            for (other in installed.values) {
                if (plugin.id != other.id && plugin.authority == other.authority) throw IOException(
                    "此接口已分配给另一个插件"
                )
            }
        }
        val enable = old == null || storedGrant(old)
        if (old == null && installed.size >= 24) throw IOException("最多安装 24 个插件")
        if (!root.isDirectory() && !root.mkdirs()) throw IOException("无法创建插件目录")
        val target = file(plugin.id)
        val output = target.startWrite()
        try {
            output.write(plugin.bytes())
            target.finishWrite(output)
        } catch (failure: IOException) {
            target.failWrite(output)
            throw failure
        }
        installed.put(plugin.id, plugin)
        if (plugin.isNative) enabledNative.remove(plugin.id)
        if (old == null || plugin.isNative) setEnabled(plugin.id, enable)
    }

    @Synchronized
    @Throws(IOException::class)
    fun setEnabled(id: String?, value: Boolean) {
        val plugin = installed.get(id)
        requireNotNull(plugin) { "插件尚未安装" }
        if (value) checkDependencies(plugin)
        if (plugin.isNative) {
            val grant = marker(id)
            if (!value) {
                grant.delete()
                if (nativeGrant(plugin)) throw IOException("无法停用插件")
                enabledNative.remove(id)
                return
            }
            val out = grant.startWrite()
            try {
                out.write(plugin.revision.toString().toByteArray(StandardCharsets.US_ASCII))
                grant.finishWrite(out)
                if (!nativeGrant(plugin)) throw IOException("无法保存启用状态")
                enabledNative.add(id)
            } catch (failure: IOException) {
                grant.failWrite(out)
                throw failure
            }
            return
        }
        grants.edit().putBoolean(id, value).apply()
    }

    @Synchronized
    @Throws(IOException::class)
    fun remove(id: String?) {
        setEnabled(id, false)
        installed[id]?.takeIf { it.community != null }?.let {
            try {
                CommunityStore(context, it).clear()
                CommunityJobs.clear(context, it)
            } catch (failure: Exception) {
                throw IOException("无法清除插件数据", failure)
            }
        }
        file(id).delete()
        installed.remove(id)
    }

    @Synchronized
    fun endpoint(authority: String?): PluginBundle? {
        if (authority == null || authority.isEmpty()) return null
        for (plugin in installed.values) {
            if (authority == plugin.authority && enabled(plugin.id)) return plugin
        }
        return null
    }

    fun bindingBundle(id: String?, revision: Int): PluginBundle? {
        if (id == null || !id.matches("[a-z][a-z0-9.-]{2,63}".toRegex())) return null
        if (id == BuiltInBand.ID) return find(id)?.takeIf { it.revision == revision }
        try {
            file(id).openRead().use { input ->
                val bundle: PluginBundle = PluginBundle.Companion.read(input)
                return if (id == bundle.id && revision == bundle.revision && bundle.isNative && nativeGrant(
                        bundle
                    )
                )
                    bundle
                else
                    null
            }
        } catch (unavailable: Exception) {
            return null
        }
    }

    private fun storedGrant(plugin: PluginBundle): Boolean {
        return if (plugin.isNative) enabledNative.contains(plugin.id) else grants.getBoolean(
            plugin.id,
            false
        )
    }

    private fun checkDependencies(plugin: PluginBundle) {
        val issues = dependencyIssues(plugin)
        if (issues.isNotEmpty()) throw IOException(issues.joinToString("\n"))
    }

    private fun nativeGrant(plugin: PluginBundle): Boolean {
        try {
            return plugin.revision.toString() == String(
                marker(plugin.id).readFully(),
                StandardCharsets.US_ASCII
            )
        } catch (missing: IOException) {
            return false
        }
    }

    private fun marker(id: String?): AtomicFile {
        return AtomicFile(File(root, id + ".enabled"))
    }

    private fun file(id: String?): AtomicFile {
        return AtomicFile(File(root, id + ".hfp"))
    }

    companion object {
        fun builtIn(id: String?): Boolean = id == CampusRecharge.PROVIDER || id == BuiltInBand.ID

        private var instance: LocalPlugins? = null

        @Synchronized
        fun get(context: Context): LocalPlugins {
            if (instance == null) instance = LocalPlugins(context.getApplicationContext())
            return instance!!
        }
    }
}
