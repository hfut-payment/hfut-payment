package dev.codex.hfutcampus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/** Records the commitment before transmitting it. A crash or timeout cannot silently retry it. */
internal object CommunityBudget {
    private const val MAX_YUAN = 999
    const val MAX_CENTS = MAX_YUAN * 100
    const val MAX_LABEL = "¥$MAX_YUAN"
    private const val DAY = 24 * 60 * 60 * 1000L
    private const val PREFS = "community-spend-v2"
    private val submissions = mutableMapOf<String, String>()

    class LimitReached : IllegalArgumentException("最近 24 小时累计充值不能超过 $MAX_LABEL")

    enum class Service(val key: String, val item: String, val payType: String) {
        ELECTRIC("electric", "261", "101"), WATER("water", "223", "101"), NETWORK("network", "281", "1");

        fun valid(target: String): Boolean = when (this) {
            ELECTRIC -> target.matches(Regex("300(?:10|[1-9])[0-9]{3}[12][12]"))
            WATER -> target.matches(Regex("[0-9]{11}"))
            NETWORK -> target == "current"
        }

        fun query(target: String): Map<String, String> = mapOf("type" to "IEC", "feeitemid" to item) + when (this) {
            ELECTRIC -> mapOf("room" to target)
            WATER -> mapOf("level" to "1", "telPhone" to target)
            NETWORK -> mapOf("level" to "0")
        }

        fun comment(target: String): String = when (this) {
            ELECTRIC -> "房间：$target"
            WATER -> "undefined：$target"
            NETWORK -> "undefined：undefined"
        }
    }

    data class Approval(
        val account: String, val room: String, val cents: Int,
        val service: Service = Service.ELECTRIC
    ) {
        val amount: String get() = BigDecimal.valueOf(cents.toLong(), 2).toPlainString()
        val destination: String get() = when (service) {
            Service.ELECTRIC -> CampusFees.xcRoomLabel(room) + if (room.endsWith("2")) " · 空调用电" else " · 常规用电"
            Service.WATER -> "洗浴账户 · 手机尾号 " + room.takeLast(4)
            Service.NETWORK -> "当前登录账户的校园网"
        }
    }

    fun approval(account: String, values: JSONObject, target: JSONObject? = null): Approval {
        val service = if (target == null) Service.ELECTRIC else requireNotNull(
            Service.entries.firstOrNull { it.key == target.optString("kind") }) { "未知充值项目" }
        val room = target?.optString("id") ?: values.optString("room")
        val amount = values.optString("amount")
        require(account.isNotBlank()) { "请先登录校园卡" }
        require(service.valid(room)) { "请先配置此充值项目的账户" }
        require(amount.matches(Regex("-?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)"))) { "请输入充值金额" }
        val value = amount.toBigDecimal()
        require(value >= BigDecimal("0.01")) { "充值金额不能低于 ¥0.01" }
        require(value <= BigDecimal.valueOf(MAX_YUAN.toLong())) { "充值金额不能高于 $MAX_LABEL" }
        require(value.scale() <= 2) { "充值金额最多保留两位小数" }
        val cents = value.movePointRight(2).intValueExact()
        return Approval(account, room, cents, service)
    }

    @Synchronized
    fun reserve(context: Context, approval: Approval, order: String) {
        val recent = checkAvailable(context, approval)
        require(recent.none { it.optString("order") == order }) { "此订单已经提交" }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val next = JSONArray(recent).put(JSONObject().put("time", now).put("cents", approval.cents)
            .put("order", order).put("unknown", true))
        check(prefs.edit().putString(approval.account, next.toString()).commit())
        submissions[approval.account] = order
    }

    @Synchronized
    fun endSubmission(account: String, order: String) {
        if (submissions[account] == order) submissions.remove(account)
    }

    @Synchronized
    fun checkAvailable(context: Context, approval: Approval): List<JSONObject> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val entries = JSONArray(prefs.getString(approval.account, "[]"))
        return checkAvailable(entries, System.currentTimeMillis(), approval.cents)
    }

    internal fun checkAvailable(entries: JSONArray, now: Long, cents: Int): List<JSONObject> {
        val recent = (0 until entries.length()).map { entries.getJSONObject(it) }
            .filter { it.optBoolean("unknown") || now - it.getLong("time") < DAY }
        require(recent.none { it.optBoolean("unknown") }) { "上笔充值结果待确认，请先核对学校账单" }
        if (recent.sumOf { it.getInt("cents") } + cents > MAX_CENTS) throw LimitReached()
        return recent
    }

    @Synchronized
    fun acknowledge(context: Context, account: String, order: String? = null) {
        require(order != null || account !in submissions) { "充值正在提交，请稍后核对账单" }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val entries = JSONArray(prefs.getString(account, "[]"))
        for (i in 0 until entries.length()) {
            val item = entries.getJSONObject(i)
            if (order == null || order == item.getString("order")) item.put("unknown", false)
        }
        check(prefs.edit().putString(account, entries.toString()).commit())
    }

    @Synchronized
    fun pending(context: Context, account: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val entries = JSONArray(prefs.getString(account, "[]"))
        return (0 until entries.length()).any { entries.getJSONObject(it).optBoolean("unknown") }
    }
}
