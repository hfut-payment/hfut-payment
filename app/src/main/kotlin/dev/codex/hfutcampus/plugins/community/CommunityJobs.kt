package dev.codex.hfutcampus

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal object CommunityJobs {
    private const val INTERVAL_MS = 15 * 60 * 1000L
    private const val RECHARGE_BACKOFF_MS = 60 * 60 * 1000L

    private fun manager(context: Context) = checkNotNull(context.getSystemService(JobScheduler::class.java))
    private fun component(context: Context) = ComponentName(context, CommunityJobService::class.java)
    private fun pending(context: Context) = manager(context).allPendingJobs.filter { it.service == component(context) }
    private fun status(context: Context) = context.getSharedPreferences("community-jobs-v2", Context.MODE_PRIVATE)

    private fun jobs(context: Context, id: String) = pending(context).filter { it.extras.getString("id") == id }
    private fun taskKey(job: JobInfo) = job.extras.getString("key").orEmpty()
    private fun statusKey(id: String, key: String) = if (key.isEmpty()) id else "$id:$key"
    private fun warningKey(statusKey: String) = "warning:$statusKey"
    private fun retryKey(extras: PersistableBundle) =
        "retry:${extras.getString("id")}:${extras.getString("key")}:${extras.getString("account")}"
    private fun loginAccount(extras: PersistableBundle) =
        (extras.getString("login-account") ?: extras.getString("account"))?.takeIf(String::isNotBlank)

    fun matches(plugin: PluginBundle, extras: PersistableBundle): Boolean =
        plugin.community?.timer == true && plugin.revision == extras.getInt("revision") &&
            plugin.signer == extras.getString("signer")

    private fun validLoginHook(plugin: PluginBundle): Boolean =
        plugin.community?.campusSession == true && plugin.backgroundLogin

    fun approvedLoginAccount(plugin: PluginBundle, extras: PersistableBundle): String? =
        loginAccount(extras)?.takeIf { validLoginHook(plugin) }

    fun currentRule(context: Context, plugin: PluginBundle, extras: PersistableBundle): Boolean =
        extras.getString("configuration") == CommunityStore(context, plugin)
            .configurationTag(extras.getString("key").orEmpty())

    fun state(context: Context, plugin: PluginBundle): JSONObject {
        val scheduled = jobs(context, plugin.id).map(::taskKey).toSet()
        val prefix = "${plugin.id}:"
        val previous = status(context)
        val keys = scheduled + previous.all.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
        fun taskState(key: String): JSONObject {
            val statusKey = statusKey(plugin.id, key)
            return JSONObject().put("enabled", key in scheduled)
                .put("last", previous.getString(statusKey, "").orEmpty())
                .put("warning", previous.getBoolean(warningKey(statusKey), false))
        }
        val tasks = JSONObject()
        keys.filter(String::isNotEmpty).forEach { key -> tasks.put(key, taskState(key)) }
        return taskState("").put("tasks", tasks)
    }

    fun start(context: Context, plugin: PluginBundle, approval: CommunityBudget.Approval?, key: String = "",
              account: String? = null) {
        val local = LocalPlugins.get(context)
        synchronized(local) {
            val spec = requireNotNull(plugin.community)
            require(spec.timer && local.active(plugin))
            val login = approval?.account ?: account
            require(login == null || validLoginHook(plugin)) { "插件未声明后台登录权限" }
            require(!plugin.backgroundLogin || !login.isNullOrBlank())
            schedule(context, plugin, approval, key, login)
        }
    }

    @Synchronized
    private fun schedule(context: Context, plugin: PluginBundle, approval: CommunityBudget.Approval?, key: String,
                         loginAccount: String?) {
        require(key.isEmpty() || CommunitySpec.KEY.matches(key))
        val existing = jobs(context, plugin.id)
        val replaced = existing.firstOrNull { taskKey(it) == key }
        require(replaced != null || existing.size < 8) { "定时任务数量已达上限" }
        val occupied = manager(context).allPendingJobs.map { it.id }.toSet()
        val id = replaced?.id ?: (90200..90250).firstOrNull { it !in occupied } ?: error("定时任务数量已达上限")
        val extras = PersistableBundle().apply {
            putString("id", plugin.id)
            putString("signer", plugin.signer)
            putInt("revision", plugin.revision)
            putString("key", key)
            putString("run", UUID.randomUUID().toString())
            if (loginAccount != null) {
                putString("login-account", loginAccount)
                putString("configuration", CommunityStore(context, plugin).configurationTag(key))
            }
            if (approval != null) {
                putString("account", approval.account)
                putString("room", approval.room)
                putInt("cents", approval.cents)
                putString("service", approval.service.key)
            }
        }
        val job = JobInfo.Builder(id, component(context))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(INTERVAL_MS)
            .setPersisted(true)
            .setExtras(extras).build()
        check(manager(context).schedule(job) == JobScheduler.RESULT_SUCCESS) { "系统未接受定时任务" }
    }

    @Synchronized
    fun cancel(context: Context, id: String, key: String? = null) {
        jobs(context, id).filter { key == null || taskKey(it) == key }.forEach { manager(context).cancel(it.id) }
    }

    fun scheduled(context: Context, params: JobParameters): Boolean =
        manager(context).getPendingJob(params.jobId)?.let {
            it.service == component(context) && it.extras.getString("run") == params.extras.getString("run")
        } == true

    fun rechargeBackoff(context: Context, extras: PersistableBundle): Boolean {
        val saved = status(context)
        val key = retryKey(extras)
        val boot = android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
        return retryBlocked(saved.getLong(key, 0L), saved.getLong("$key:elapsed", 0L),
            saved.getInt("$key:boot", -1), boot, System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime())
    }

    fun deferRecharge(context: Context, extras: PersistableBundle) {
        val key = retryKey(extras)
        val boot = android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
        status(context).edit()
            .putLong(key, System.currentTimeMillis() + RECHARGE_BACKOFF_MS)
            .putLong("$key:elapsed", android.os.SystemClock.elapsedRealtime() + RECHARGE_BACKOFF_MS)
            .putInt("$key:boot", boot)
            .commit()
    }

    internal fun retryBlocked(wallDeadline: Long, elapsedDeadline: Long, savedBoot: Int, boot: Int,
                              wallNow: Long, elapsedNow: Long): Boolean =
        if (savedBoot >= 0 && savedBoot == boot) elapsedNow < elapsedDeadline else wallNow < wallDeadline

    @Synchronized
    fun cancelAccountJobs(context: Context) {
        pending(context).filter { loginAccount(it.extras) != null }.forEach { manager(context).cancel(it.id) }
    }

    @Synchronized
    fun cancelPayments(context: Context) {
        pending(context).filter { it.extras.containsKey("account") }.forEach { manager(context).cancel(it.id) }
    }

    fun cancelDependants(context: Context, id: String, changedKeys: Set<String>? = null) {
        val plugins = LocalPlugins.get(context).list()
        val affected = mutableSetOf(id)
        while (plugins.any { it.id !in affected && it.community?.dependencies?.keys?.any(affected::contains) == true }) {
            plugins.filter { it.community?.dependencies?.keys?.any(affected::contains) == true }
                .forEach { affected.add(it.id) }
        }
        affected.filterNot { it == id }.forEach { cancel(context, it) }
        if (changedKeys == null) cancel(context, id)
        else if (changedKeys.isNotEmpty()) {
            cancel(context, id, "")
            changedKeys.forEach { cancel(context, id, it) }
        }
    }

    fun record(context: Context, plugin: PluginBundle, key: String, message: String, warning: Boolean = false) {
        val statusKey = statusKey(plugin.id, key)
        status(context).edit().putString(statusKey, message.take(180))
            .putBoolean(warningKey(statusKey), warning).apply()
    }

    fun clear(context: Context, plugin: PluginBundle) {
        val saved = status(context)
        val edit = saved.edit()
        saved.all.keys.filter { key ->
            val statusKey = key.removePrefix("warning:").removePrefix("retry:")
            statusKey == plugin.id || statusKey.startsWith("${plugin.id}:")
        }.forEach(edit::remove)
        edit.apply()
    }
}

class CommunityJobService : JobService() {
    private val main = Handler(Looper.getMainLooper())
    private val active = ConcurrentHashMap<Int, AtomicBoolean>()

    override fun onStartJob(params: JobParameters): Boolean {
        val local = LocalPlugins.get(this)
        val extras = params.extras
        val plugin = local.find(extras.getString("id")) ?: return false
        if (!CommunityJobs.matches(plugin, extras) || !local.active(plugin)) return false
        val key = extras.getString("key").orEmpty()
        val approval = extras.getString("account")?.let { account ->
            val service = CommunityBudget.Service.entries.firstOrNull {
                it.key == extras.getString("service", "electric")
            } ?: return false
            CommunityBudget.Approval(account, extras.getString("room").orEmpty(), extras.getInt("cents"), service)
        }
        val login = CommunityJobs.approvedLoginAccount(plugin, extras)
        if (login == null && (extras.containsKey("login-account") || extras.containsKey("account"))) {
            CommunityJobs.cancel(this, plugin.id, key)
            CommunityJobs.record(this, plugin, key, "插件未声明后台登录权限，任务已停止。", warning = true)
            return false
        }
        val automatic = CampusRecharge.officialAutomation(plugin) && approval != null
        if ((approval != null || plugin.backgroundLogin) && login == null) return false
        if (approval != null && approval.account != login) return false
        if (login != null && !CommunityJobs.currentRule(this, plugin, extras)) {
            CommunityJobs.cancel(this, plugin.id, key)
            return false
        }
        if (login != null && !LoginPolicy.canRunCampusJob(this)) {
            CommunityJobs.record(this, plugin, key, "重启后暂停，请打开应用恢复。", warning = true)
            return false
        }
        if (automatic && CommunityJobs.rechargeBackoff(this, extras)) return false
        if (automatic && approval?.service == CommunityBudget.Service.ELECTRIC && !CampusRecharge.electricWindow()) {
            CommunityJobs.record(this, plugin, key, "电费将在 06:00–23:00 检查并自动充值。")
            return false
        }
        val alive = AtomicBoolean(true)
        active[params.jobId] = alive
        val authorized = {
            alive.get() && CommunityJobs.scheduled(this, params) && local.active(plugin) &&
                (login == null || LoginPolicy.canRunCampusJob(this) && CommunityJobs.currentRule(this, plugin, extras))
        }
        val finish = PluginScripts.Result { value, problem ->
            main.post {
                if (active.remove(params.jobId, alive) && alive.get()) {
                    val output = value?.optJSONObject("output")
                    val status = output?.optString("status")
                    val warning = value == null || output?.optBoolean("warning") == true
                    val result = status?.takeIf { it.isNotBlank() } ?: problem.orEmpty().ifBlank { "检查完成" }
                    val delay = automatic && warning && !CommunityBudget.pending(this, approval.account)
                    if (delay) CommunityJobs.deferRecharge(this, extras)
                    CommunityJobs.record(this, plugin, key, result + if (delay) "\n至少 1 小时后再试。" else "", warning)
                    jobFinished(params, false)
                }
            }
        }
        fun runTask() {
            if (!authorized() || login != null && login != CampusRuntime.get(this).feeProfileKey()) {
                finish.complete(null, "任务已取消或校园卡账户已变化。")
                return
            }
            PluginScripts.run(this, plugin, JSONObject().put("action", "timer").put("task", key), null,
                finish, approval = approval, background = true, jobAccount = login, alive = {
                    authorized() && (login == null || login == CampusRuntime.get(this).feeProfileKey())
                })
        }
        if (login == null) runTask()
        else CampusRuntime.get(this).prepareCommunityJob(login, authorized, CampusRuntime.SaveCompletion { ok, message ->
            if (ok) runTask() else finish.complete(null, message)
        })
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        active.remove(params.jobId)?.set(false)
        return false
    }
}
