package dev.codex.hfutcampus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

internal class CommunityRuntime(
    private val app: Context,
    private val root: PluginBundle,
    private val approval: CommunityBudget.Approval?,
    private val passwordInputs: Map<String, String>,
    private val background: Boolean,
    private val mode: Mode,
    private val jobAccount: String?,
    private val alive: () -> Boolean,
    private val evaluate: (PluginBundle, JSONObject) -> JSONObject
) {
    enum class Mode { INTERACTIVE, VIEW, AUTOSAVE }
    private val installed = LocalPlugins.get(app)
    private val runtime = CampusRuntime.get(app)
    private val epoch = runtime.configurationRevision()
    private val account = runtime.feeProfileKey()
    private val deadline = android.os.SystemClock.elapsedRealtime() + 90_000
    private var remaining = 20
    private var order: String? = null
    private var payer: PluginBundle? = null
    private var prepared: Pair<String, String>? = null
    private var committed = false
    private var verifiedTarget: JSONObject? = null

    fun run(event: JSONObject): JSONObject {
        require(approval == null || approval.account == account && runtime.isConfigured) { "校园卡账户已变化或尚未登录" }
        return execute(root, event, emptySet())
    }

    private fun execute(plugin: PluginBundle, initial: JSONObject, stack: Set<String>): JSONObject {
        check(plugin.id !in stack && stack.size < 8) { "插件依赖循环或层数超限" }
        val spec = requireNotNull(plugin.community)
        val store = CommunityStore(app, plugin)
        val event = JSONObject(initial.toString())
        // User-click reads of live payment codes are read-only events, even for plugins with networking.
        val displayOnly = event.has("payment")
        event.put("job", CommunityJobs.state(app, plugin))
        if (spec.spend != null && !displayOnly) event.put("pending", CommunityBudget.pending(app, account))
        while (true) {
            check(alive() && installed.active(plugin)) { "插件已停用或会话已结束" }
            check(remaining-- > 0 && android.os.SystemClock.elapsedRealtime() < deadline) { "操作步骤超过限制" }
            if (spec.storage && !displayOnly) event.put("storage", store.values())
            if (spec.profile.isNotEmpty() && !displayOnly) {
                check(epoch == runtime.configurationRevision()) { "校园卡账户已变化" }
                val fields = stack.fold(spec.profile) { allowed, id ->
                    allowed.intersect(installed.find(id)?.community?.profile.orEmpty())
                }
                event.put("profileAccess", JSONArray(fields))
                event.put("profile", CampusFeeProfile.forPlugin(app, runtime, account, fields))
            }
            if (!displayOnly) event.put("secrets", JSONObject().apply {
                spec.secrets.forEach { put(it, store.hasSecret(it, if (it == "payment-password") account else "")) }
            })
            val page = evaluate(plugin, event)
            val effect = page.optJSONObject("effect") ?: return page
            check(!displayOnly) { "读码事件只能生成页面" }
            val type = effect.getString("type")
            check(mode != Mode.VIEW) { "页面更新只能读取状态" }
            if (mode == Mode.AUTOSAVE) {
                val resolveRechargeTarget = type == "campus.recharge" && effect.optString("method") == "balances" &&
                    effect.optJSONObject("input")?.optBoolean("resolve") == true
                check(type == "plugin.call" || resolveRechargeTarget || type == "storage.set" && plugin == root) {
                    "自动保存只能读取资料或保存设置"
                }
            }
            check(alive() && installed.active(plugin)) { "插件已停用或会话已结束" }
            val next = effect.getString("next")
            require(CommunitySpec.KEY.matches(next) && next !in setOf("page", "fee.page", "fee.extra", "timer"))
            event.put("result", JSONObject.NULL)
            val result = effect(plugin, spec, store, effect, stack)
            event.put("action", next)
            event.put("result", result)
            event.put("state", page.opt("state") ?: JSONObject.NULL)
        }
    }

    private fun effect(
        plugin: PluginBundle, spec: CommunitySpec, store: CommunityStore,
        effect: JSONObject, stack: Set<String>
    ): Any = when (effect.getString("type")) {
        "storage.set" -> {
            require(spec.storage && !background) { "定时任务不能修改已批准的配置" }
            val changes = effect.getJSONObject("values")
            synchronized(installed) {
                check(installed.active(plugin))
                val values = store.values()
                val changed = changes.keys().asSequence().filter { name ->
                    (values.opt(name)?.toString()) != changes.opt(name)?.takeUnless { it == JSONObject.NULL }?.toString()
                }.toSet()
                for (name in changes.keys()) {
                    if (changes.isNull(name)) values.remove(name) else values.put(name, changes.get(name))
                }
                if (changed.isNotEmpty()) {
                    store.put(values)
                    CommunityJobs.cancelDependants(app, plugin.id, changed)
                }
            }
            JSONObject().put("ok", true)
        }
        "secret.save", "secret.delete" -> {
            require(!background && plugin == root)
            val name = effect.getString("name")
            require(name in spec.secrets)
            synchronized(installed) {
                check(installed.active(plugin))
                if (effect.getString("type") == "secret.save") {
                    val value = passwordInputs[effect.getString("field")].orEmpty()
                    if (name == "payment-password") {
                        require(account.isNotBlank() && epoch == runtime.configurationRevision()) { "请先登录校园卡" }
                        require(value.matches(Regex("[0-9]{6}"))) { "请输入六位支付密码" }
                    }
                    store.saveSecret(name, value, if (name == "payment-password") account else "")
                } else store.deleteSecret(name)
            }
            CommunityJobs.cancelDependants(app, plugin.id)
            JSONObject().put("ok", true)
        }
        "fetch" -> fetch(plugin, spec, effect, false)
        "campus.recharge" -> {
            require(spec.campusRecharge && CampusRecharge.signedAutomation(plugin)) { "未获准使用校园费用充值" }
            builtInRecharge(plugin, spec, effect.getString("method"), effect.optJSONObject("input") ?: JSONObject())
        }
        "plugin.call" -> {
            val id = effect.getString("id")
            val name = effect.getString("method")
            val pin = requireNotNull(spec.dependencies[id]) { "未声明依赖" }
            require(name in pin.methods)
            val input = effect.optJSONObject("input") ?: JSONObject()
            if (CampusRecharge.provides(plugin, id, pin)) {
                builtInRecharge(plugin, spec, name, input)
            } else {
                val dependency = dependency(app, plugin, id)
                val target = checkNotNull(dependency.community)
                require(name in target.exports)
                require(name !in target.campusExports || spec.campusSession && root.community?.campusSession == true) {
                    "依赖方法需要校园服务权限"
                }
                execute(dependency, JSONObject().put("action", "call.$name").put("caller", plugin.id)
                    .put("input", input), stack + plugin.id).optJSONObject("output") ?: JSONObject()
            }
        }
        "spend.open", "spend.prepare", "spend.commit" -> spend(plugin, spec, store, effect)
        else -> error("未知插件操作")
    }

    private fun builtInRecharge(plugin: PluginBundle, spec: CommunitySpec, method: String, input: JSONObject): JSONObject {
        require(plugin == root && spec.campusSession && runtime.isConfigured &&
            (!background || jobAccount == account)) { "此操作需要当前校园卡账户授权" }
        val current = { alive() && installed.active(plugin) && epoch == runtime.configurationRevision() &&
            account == runtime.feeProfileKey() }
        check(current())
        val checkout = CampusRecharge(app)
        if (method == "status") return checkout.status(account)
        val entry = input.optString("entry")
        val field = when (entry) {
            "regular", "ac" -> "room"
            "shower" -> "phone"
            else -> "campus"
        }
        require(field in spec.profile)
        val target = checkout.target(account, entry)
        if (method == "balances" && input.optBoolean("resolve")) return JSONObject().put("target", target?.json())
        require(mode == Mode.INTERACTIVE)
        require(target != null && input.optJSONObject("target")?.let {
            it.optString("kind") == target.service.key && it.optString("id") == target.id
        } == true) { "充值账户已变化，请重新保存规则" }
        if (method == "balances") return checkout.balances(account, epoch, target, current)

        require(method == "recharge" && background)
        val approved = requireNotNull(approval) { "此操作需要你先授权自动充值" }
        val rule = CommunityStore(app, plugin).values().getJSONObject(entry)
        require(approved == CommunityBudget.approval(account, rule, rule.getJSONObject("target"))) {
            "充值规则已变化，请重新授权"
        }
        return checkout.pay(entry, approved, epoch, rule, current).json()
    }

    private fun fetch(plugin: PluginBundle, spec: CommunitySpec, effect: JSONObject, spending: Boolean): JSONObject {
        val uri = URI(effect.getString("url"))
        val method = effect.optString("method", "GET")
        val campus = CommunityNetwork.check(uri, method, spec, spending)
        require(!campus || !background || jobAccount != null && jobAccount == account) {
            "校园卡定时任务需要单独授权后台登录"
        }
        val token = if (campus) runtime.communitySession(epoch) else null
        check(alive() && installed.active(root) && installed.active(plugin) &&
            (!campus || epoch == runtime.configurationRevision()))
        val form = effect.optJSONObject("form") ?: JSONObject()
        val response = CommunityNetwork.request(uri, method, form, token)
        if (campus && approval != null && uri.path == "/charge/feeitem/getThirdData") {
            val expected = approval.service.query(approval.room)
            if (form.length() == expected.size && expected.all { (key, value) -> form.optString(key) == value }) {
                val body = response.optJSONObject("body")
                verifiedTarget = if (response.optInt("status") in 200..299 && body?.opt("success") != false)
                    body?.optJSONObject("map")?.optJSONObject("data") else null
            }
        }
        return response
    }

    private fun spend(
        plugin: PluginBundle, spec: CommunitySpec, store: CommunityStore, effect: JSONObject
    ): JSONObject {
        val profile = requireNotNull(spec.spend)
        val approved = requireNotNull(approval) { "此操作需要用户批准充值" }
        val service = approved.service
        require(service.key in profile.services) { "插件未获准为此项目充值" }
        require(approved.account == runtime.feeProfileKey() && epoch == runtime.configurationRevision())
        fun checkProfile() {
            val saved = CampusFeeProfile.read(app, account)
            require(!saved.hefei()) { "当前充值接口仅适用于宣城校区" }
            if (service == CommunityBudget.Service.ELECTRIC && "room" in spec.profile) {
                require(saved.room.isBlank() || approved.room in setOf(saved.meter(false), saved.meter(true))) {
                    "已配置的电表已变化，请重新确认"
                }
            }
            if (service == CommunityBudget.Service.WATER && "phone" in spec.profile) {
                require(saved.phone.isBlank() || approved.room == saved.phone) { "洗浴手机号已变化，请重新确认" }
            }
        }
        checkProfile()
        require(effect.optString("service", "electric") == service.key &&
            effect.optString("target", effect.optString("room")) == approved.room && effect.getString("amount") == approved.amount) {
            "订单金额或账户与用户授权不符"
        }
        val form = effect.getJSONObject("form")
        val url = CommunitySpec.CAMPUS + profile.path
        val request = JSONObject().put("url", url).put("method", "POST").put("form", form)
        require(!form.has("password") && !committed)
        return when (effect.getString("type")) {
            "spend.open" -> {
                require(payer == null && !form.has(profile.orderField))
                require(form.keys().asSequence().all { it in setOf("flag", "paystep", "feeitemid", "third_party", profile.amountField) })
                require(form.optString("flag") == "choose" && form.optString("paystep") == "0" &&
                    form.optString("feeitemid") == service.item)
                val target = requireNotNull(verifiedTarget) { "请先查询已授权的账户" }
                require(target.length() > 0)
                form.put("third_party", JSONObject(target.toString())
                    .put("myCustomInfo", service.comment(approved.room)).toString())
                CommunityBudget.checkAvailable(app, approved)
                payer = plugin
                form.put(profile.amountField, approved.amount)
                val response = fetch(plugin, spec, request, true)
                order = response.optJSONObject("body")?.optJSONObject("data")?.optString(profile.orderField)
                    ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,100}")) }
                response
            }
            "spend.prepare" -> {
                require(payer == plugin && order != null && prepared == null)
                require(form.keys().asSequence().all { it in setOf(profile.orderField, "paystep", "paytype", "paytypeid", "feeitemid") })
                require(form.getString(profile.orderField) == order)
                require(form.optString("paystep") == "2" && form.optString("paytype") == "CARDTSM" &&
                    form.optString("paytypeid") == service.payType && form.optString("feeitemid") == "261" && !form.has("uuid"))
                val response = fetch(plugin, spec, request, true)
                if (response.optInt("status") in 200..299) {
                    val keys = response.optJSONObject("body")?.optJSONObject("data")?.optJSONObject("passwordMap")
                    if (keys?.length() == 1) {
                        val uuid = keys.keys().next()
                        prepared = uuid to keys.getString(uuid)
                    }
                }
                response
            }
            else -> {
                val id = checkNotNull(order)
                val keyboard = checkNotNull(prepared)
                require(payer == plugin && form.keys().asSequence().all {
                    it in setOf(profile.orderField, "paystep", "paytype", "paytypeid", "isWX", "uuid")
                } &&
                    form.getString(profile.orderField) == id)
                val secret = effect.getString("secret")
                require(secret in spec.secrets)
                val keypad = effect.getString("keypad")
                require(keypad.length == 10 && keypad.toSet() == "0123456789".toSet())
                require(form.optString("paystep") == "2" && form.optString("paytype") == "CARDTSM" &&
                    form.optString("paytypeid") == service.payType && form.optString("isWX") == "0" &&
                    form.optString("uuid").matches(Regex("[A-Za-z0-9_-]{1,100}")) &&
                    keyboard == (form.optString("uuid") to keypad))
                val password = store.secret(secret, account)
                require(password.matches(Regex("[0-9]{6}")))
                form.put("password", password.map { keypad.indexOf(it) }.joinToString(""))
                // Get a session first, so a login failure cannot be mistaken for a payment timeout.
                val uri = URI(url)
                CommunityNetwork.check(uri, "POST", spec, true)
                val token = runtime.communitySession(epoch)
                check(alive() && installed.active(root) && installed.active(plugin) &&
                    epoch == runtime.configurationRevision())
                checkProfile()
                CommunityBudget.reserve(app, approved, id)
                committed = true
                try {
                    val response = CommunityNetwork.request(uri, "POST", form, token)
                    val body = response.optJSONObject("body")
                    val paid = response.optInt("status") in 200..299 &&
                        (body?.opt("success") == true || body?.optString("msg") == "success") &&
                        (body?.has("code") != true || body.optInt("code") in setOf(0, 200))
                    if (paid) CommunityBudget.acknowledge(app, approved.account, id)
                    JSONObject().put("paid", paid).put("unknown", !paid).put("orderId", id)
                } catch (_: Exception) {
                    JSONObject().put("unknown", true).put("orderId", id)
                } finally {
                    CommunityBudget.endSubmission(approved.account, id)
                }
            }
        }
    }

    companion object {
        fun dependency(app: Context, plugin: PluginBundle, id: String): PluginBundle {
            val pin = requireNotNull(plugin.community?.dependencies?.get(id)) { "未声明依赖" }
            val local = LocalPlugins.get(app)
            val target = requireNotNull(local.find(id)) { "请先安装依赖 $id" }
            require(target.community != null && target.signer == pin.signer &&
                target.revision >= pin.revision && local.active(target)) { "请启用匹配版本和签名的依赖 $id" }
            return target
        }
    }
}
