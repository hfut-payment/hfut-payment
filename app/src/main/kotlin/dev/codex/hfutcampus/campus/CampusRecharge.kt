package dev.codex.hfutcampus

import android.content.Context
import org.json.JSONObject
import java.math.RoundingMode
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset

/** The built-in checkout and the signed automation plugin use the same payment path. */
internal class CampusRecharge(private val app: Context) {
    data class Target(val service: CommunityBudget.Service, val id: String) {
        fun json(): JSONObject = JSONObject().put("kind", service.key).put("id", id)
    }

    data class Receipt(val status: String, val paid: Boolean = false, val unknown: Boolean = false,
                       val orderId: String? = null) {
        fun json(): JSONObject = JSONObject().put("status", status).put("paid", paid)
            .put("unknown", unknown).put("orderId", orderId)
    }

    private data class Card(val actual: Long, val pending: Long?) {
        val afterSettlement = pending?.let { actual + it }
    }

    private val runtime = CampusRuntime.get(app)
    private val secrets = CommunityStore(app, PROVIDER, PROVIDER_SIGNER)

    fun hasPassword(account: String): Boolean = account.isNotBlank() && secrets.hasSecret(PASSWORD, account)

    fun setPassword(account: String, value: String?) {
        require(runtime.isConfigured && account.isNotBlank() && account == runtime.feeProfileKey()) { "请先登录校园卡" }
        if (value == null) secrets.deleteSecret(PASSWORD)
        else {
            require(PIN.matches(value)) { "请输入六位支付密码" }
            secrets.saveSecret(PASSWORD, value, account)
        }
        CommunityJobs.cancel(app, AUTOMATION)
    }

    fun target(account: String, entry: String): Target? {
        val profile = CampusFeeProfile.read(app, account)
        if (!profile.xuancheng()) return null
        val target = when (entry) {
            "regular" -> Target(CommunityBudget.Service.ELECTRIC, profile.meter(false))
            "ac" -> Target(CommunityBudget.Service.ELECTRIC, profile.meter(true))
            "shower" -> Target(CommunityBudget.Service.WATER, profile.phone)
            "net" -> Target(CommunityBudget.Service.NETWORK, "current")
            else -> return null
        }
        return target.takeIf { it.service.valid(it.id) }
    }

    fun status(account: String): JSONObject = JSONObject().put("passwordSaved", hasPassword(account))
        .put("pending", CommunityBudget.pending(app, account))

    fun balances(account: String, revision: Long, target: Target, alive: () -> Boolean): JSONObject {
        val session = session(account, revision, alive)
        val card = card(session) ?: error("无法确认校园卡余额")
        check(alive() && revision == runtime.configurationRevision())
        val fee = fee(session, target) ?: error("暂时无法查询这项余额")
        return JSONObject().put("card", card.actual).put("cardPending", card.pending ?: JSONObject.NULL)
            .put("cardAfterSettlement", card.afterSettlement ?: JSONObject.NULL)
            .put("balanceMilli", balanceMilli(target.service, fee) ?: JSONObject.NULL)
    }

    fun pay(entry: String, approval: CommunityBudget.Approval, revision: Long, rule: JSONObject? = null,
            alive: () -> Boolean): Receipt {
        val account = approval.account
        fun checkTarget(): Target {
            check(alive() && runtime.isConfigured && revision == runtime.configurationRevision() &&
                account == runtime.feeProfileKey()) {
                "账户或充值规则已变化，请重新确认"
            }
            val target = target(account, entry)
            require(target?.service == approval.service && target.id == approval.room) {
                "充值账户已变化，请重新确认"
            }
            require(approval.cents in 1..CommunityBudget.MAX_CENTS)
            if (rule != null && target.service == CommunityBudget.Service.ELECTRIC) {
                check(electricWindow()) { "电费自动充值会在 06:00–23:00 进行" }
            }
            return target
        }

        val target = checkTarget()
        val service = target.service
        val password = secrets.secret(PASSWORD, account)
        require(PIN.matches(password)) { "请重新保存六位支付密码" }
        CommunityBudget.checkAvailable(app, approval)
        val session = session(account, revision, alive)
        val card = card(session) ?: return Receipt("暂时无法确认校园卡余额")
        if (card.actual < approval.cents) return Receipt("校园卡实际余额不足")
        val available = card.afterSettlement ?: return Receipt("暂时无法确认待圈存金额")
        if (available < approval.cents) return Receipt("计入待圈存后，校园卡余额不足")
        checkTarget()
        val fee = fee(session, target) ?: return Receipt("暂时无法查询充值账户")
        if (rule != null) {
            val limits = rule.getJSONObject("limits")
            val threshold = limits.getLong("cardAfterSettlementAbove")
            val below = limits.getLong("belowMilli")
            require(threshold >= 0 && below >= 0)
            val balance = balanceMilli(service, fee) ?: return Receipt("暂时无法确认这项余额")
            if (available <= threshold || balance >= below) return Receipt("重新核对后，充值条件未满足")
        }
        val destination = fee.optJSONObject("map")?.optJSONObject("data")?.takeIf { it.length() > 0 }
            ?: return Receipt("学校未返回充值账户信息")
        return placeOrder(approval, destination, password, { checkTarget() },
            { form -> request(session, PAY_PATH, "POST", form) },
            { order, form ->
                val commitSession = session(account, revision, alive)
                checkTarget()
                CommunityBudget.reserve(app, approval, order)
                try {
                    val response = request(commitSession, PAY_PATH, "POST", form)
                    if (confirmed(response)) CommunityBudget.acknowledge(app, account, order)
                    response
                } catch (_: Exception) {
                    JSONObject()
                } finally {
                    CommunityBudget.endSubmission(account, order)
                }
            })
    }

    private fun session(account: String, revision: Long, alive: () -> Boolean): String {
        check(alive() && runtime.isConfigured && account == runtime.feeProfileKey()) { "请先登录校园卡" }
        return runtime.communitySession(revision).also { check(alive()) { "充值操作已取消" } }
    }

    private fun card(session: String): Card? {
        val rows = request(session, "/berserker-app/ykt/tsm/getCampusCards", "GET").body()
            ?.optJSONObject("data")?.optJSONArray("card") ?: return null
        val card = (0 until rows.length()).mapNotNull(rows::optJSONObject)
            .filter { it.optString("account").isNotBlank() && it.optInt("lostflag") != 1 }
            .singleOrNull() ?: return null
        val actual = integer(card.opt("db_balance"), CENTS) ?: return null
        return Card(actual, integer(card.opt("unsettle_amount"), CENTS))
    }

    private fun fee(session: String, target: Target): JSONObject? =
        request(session, "/charge/feeitem/getThirdData", "POST", target.service.query(target.id)).body()

    private fun request(token: String, path: String, method: String, fields: Map<String, String> = emptyMap()) =
        CommunityNetwork.request(URI(CommunitySpec.CAMPUS + path), method, JSONObject(fields), token)

    companion object {
        internal fun placeOrder(approval: CommunityBudget.Approval, destination: JSONObject, password: String,
                                validate: () -> Unit, prepare: (Map<String, String>) -> JSONObject,
                                submit: (String, Map<String, String>) -> JSONObject): Receipt {
            val service = approval.service
            validate()
            val order = prepare(mapOf(
                "feeitemid" to service.item, "flag" to "choose", "paystep" to "0", "tranamt" to approval.amount,
                "third_party" to JSONObject(destination.toString()).put("myCustomInfo", service.comment(approval.room)).toString()
            )).body()?.optJSONObject("data")?.optString("orderid")?.takeIf(ORDER_ID::matches)
                ?: return Receipt("学校未返回有效订单号")
            val payment = mapOf("orderid" to order, "paystep" to "2", "paytype" to "CARDTSM", "paytypeid" to service.payType)
            validate()
            val keyboard = prepare(payment + ("feeitemid" to "261"))
                .body()?.optJSONObject("data")?.optJSONObject("passwordMap")?.takeIf { it.length() == 1 }
                ?: return Receipt("暂时无法取得支付键盘")
            val uuid = keyboard.keys().next()
            val keypad = keyboard.optString(uuid)
            if (!ORDER_ID.matches(uuid) || keypad.length != 10 || keypad.toSet() != DIGITS) {
                return Receipt("支付键盘格式异常，请稍后再试")
            }
            require(PIN.matches(password))
            val encoded = password.map(keypad::indexOf).joinToString("")
            validate()
            val response = submit(order, payment + mapOf("uuid" to uuid, "isWX" to "0", "password" to encoded))
            return if (confirmed(response)) Receipt("", paid = true, orderId = order)
            else Receipt(PENDING, unknown = true, orderId = order)
        }

        private fun confirmed(response: JSONObject): Boolean {
            val body = response.body()
            return body != null && (body.opt("success") == true || body.optString("msg") == "success") &&
                (!body.has("code") || body.optInt("code") in setOf(0, 200))
        }

        private fun JSONObject.body(): JSONObject? = optJSONObject("body")
            ?.takeIf { optInt("status") in 200..299 && it.opt("success") != false }

        const val PROVIDER = "dev.campusdemo.recharge"
        const val PROVIDER_SIGNER = "d0517a68cf443eff7efa88482f63740bbed92c3261e3992d5d1daf8a5faec725"
        const val AUTOMATION = "dev.campusdemo.auto-recharge"
        private const val AUTOMATION_SIGNER = "6059805b8795e1dbe7c89a8d73d737b6e6f95f13a6e52dd0cf7296ae5fbc01e7"
        const val PENDING = "充值结果未确认，请核对账单状态"
        private const val PASSWORD = "payment-password"
        private const val PAY_PATH = "/blade-pay/pay"
        private val PIN = Regex("[0-9]{6}")
        private val DIGITS = "0123456789".toSet()
        private val ORDER_ID = Regex("[A-Za-z0-9_-]{1,100}")
        private val CENTS = Regex("[+-]?[0-9]{1,12}(?:\\.0{1,8})?")
        private val MILLI = Regex("-?[0-9]{1,12}")
        private val MONEY = Regex("""[¥￥]?\s*(-?[0-9]{1,10}(?:\.[0-9]{1,8})?)\s*(?:元|[（(]\s*元\s*[）)])?""")
        private val ELECTRIC_NAMES = setOf("剩余金额", "剩余电费", "电费余额", "当前余额", "账户余额", "余额")
        private val INLINE = Regex("""(?:剩余金额|剩余电费|电费余额|当前余额|账户余额)\s*[:：]\s*([¥￥]?\s*-?[0-9]{1,10}(?:\.[0-9]{1,8})?)(?![\d.])(?=[ \t]*(?:元|[（(]\s*元\s*[）)]|$|[\r\n,，;；。]))""")
        private val BEIJING = ZoneOffset.ofHours(8)

        private val AUTOMATION_METHODS = setOf("status", "balances", "recharge")

        fun signedAutomation(id: String, signer: String): Boolean = id == AUTOMATION && signer == AUTOMATION_SIGNER

        fun signedAutomation(plugin: PluginBundle): Boolean = signedAutomation(plugin.id, plugin.signer)

        fun officialAutomation(plugin: PluginBundle): Boolean = signedAutomation(plugin) && plugin.revision >= 10

        fun provides(plugin: PluginBundle, id: String, pin: CommunitySpec.Dependency): Boolean =
            id == PROVIDER && officialAutomation(plugin) && pin.signer == PROVIDER_SIGNER &&
                pin.revision == 13 && AUTOMATION_METHODS.containsAll(pin.methods)

        fun usesLegacyAutomationDependency(plugin: PluginBundle): Boolean {
            if (!signedAutomation(plugin) || plugin.revision !in 9..10) return false
            val spec = plugin.community ?: return false
            val pin = spec.dependencies[PROVIDER] ?: return false
            return spec.dependencies.size == 1 && pin.signer == PROVIDER_SIGNER &&
                pin.revision in 12..13 && pin.methods == AUTOMATION_METHODS
        }

        fun electricWindow(now: Instant = Instant.now()): Boolean = now.atOffset(BEIJING).hour in 6..22

        private fun integer(value: Any?, pattern: Regex): Long? = value?.toString()?.trim()
            ?.takeIf(pattern::matches)?.toBigDecimalOrNull()?.longValueExact()

        private fun money(value: Any?): Long? = MONEY.matchEntire(value?.toString()?.trim().orEmpty())
            ?.groupValues?.get(1)?.toBigDecimal()?.movePointRight(3)?.setScale(0, RoundingMode.CEILING)?.longValueExact()

        internal fun balanceMilli(service: CommunityBudget.Service, fee: JSONObject): Long? {
            val map = fee.optJSONObject("map") ?: return null
            if (service == CommunityBudget.Service.WATER) return integer(map.optJSONObject("data")?.opt("accountMoney"), MILLI)
            val details = map.optJSONObject("showData") ?: return null
            val labels = if (service == CommunityBudget.Service.NETWORK) setOf("储值余额") else ELECTRIC_NAMES
            for (key in details.keys()) {
                if (key.substringBefore('（').substringBefore('(').trim() in labels) money(details.opt(key))?.let { return it }
            }
            if (service == CommunityBudget.Service.ELECTRIC) {
                for (key in details.keys()) {
                    val value = details.opt(key) as? String ?: continue
                    INLINE.find(value)?.groupValues?.get(1)?.let { return money(it) }
                }
            }
            return null
        }
    }
}
