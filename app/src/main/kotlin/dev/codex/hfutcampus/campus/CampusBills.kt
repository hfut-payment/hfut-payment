package dev.codex.hfutcampus

import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal object CampusBills {
    const val PATH = "/berserker-search/search/personal/turnover"
    const val PAGE_SIZE = 30
    const val MAX_PAGE = Int.MAX_VALUE / PAGE_SIZE

    data class Bill(
        val title: String,
        val type: String,
        val amountCents: Long?,
        val transactionTime: String,
        val postedTime: String,
        val order: String
    ) {
        private val time get() = transactionTime.ifEmpty { postedTime }
        val date: LocalDate? get() = runCatching { LocalDate.parse(time.take(10)) }.getOrNull()
        val clock: String get() = runCatching {
            LocalDateTime.parse(time.replace(' ', 'T')).format(CLOCK)
        }.getOrDefault("")
        val amount: String
            get() {
                val cents = amountCents ?: return "金额未提供"
                val number = BigDecimal.valueOf(cents, 2)
                val sign = when {
                    cents < 0 -> "−"
                    cents > 0 && isIncome(type, title) -> "+"
                    else -> ""
                }
                return "$sign¥${number.abs().toPlainString()}"
            }
        val credit: Boolean get() = (amountCents ?: 0) > 0 && isIncome(type, title)
    }

    data class Page(val current: Int, val rows: List<Bill>, val total: Long) {
        val hasNext get() = current < MAX_PAGE && current.toLong() * PAGE_SIZE < total
    }

    fun parse(response: JSONObject, page: Int): Page {
        val data = response.optJSONObject("data") ?: invalid()
        val records = data.optJSONArray("records") ?: invalid()
        val total = integer(data.opt("total"))?.takeIf { it >= 0 } ?: invalid()
        if (records.length() > PAGE_SIZE) invalid()
        val rows = List(records.length()) { index ->
            val row = records.optJSONObject(index) ?: invalid()
            val type = row.text("turnoverType", 40)
            val description = row.text("resume", 160)
            val cents = integer(row.opt("tranamt"))
            val signed = when {
                cents == null || cents <= 0 -> cents
                isIncome(type, description) -> cents
                isExpense(type, description) -> -cents
                else -> cents
            }
            Bill(description.ifEmpty { type.ifEmpty { "校园卡交易" } }, type, signed,
                row.text("jndatetimeStr", 40), row.text("effectdateStr", 40), row.text("orderId", 128))
        }.sortedByDescending { it.transactionTime.ifEmpty { it.postedTime } }
        return Page(page, rows, total)
    }

    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
    private val CONTROL = Regex("[\\p{Cc}]+")
    private fun isIncome(type: String, description: String): Boolean =
        type in setOf("充值", "补助", "退款", "存款", "圈存") ||
            (type.isEmpty() && listOf("充值", "补助", "退款", "存款", "圈存").any(description::contains))

    private fun isExpense(type: String, description: String): Boolean =
        type in setOf("消费", "扣款", "取款", "支出", "缴费") ||
            (type.isEmpty() && listOf("消费", "扣款", "缴费").any(description::contains))

    private fun JSONObject.text(name: String, limit: Int): String =
        (opt(name) as? String).orEmpty().replace(CONTROL, " ").trim().take(limit)

    private fun integer(value: Any?): Long? = value?.toString()?.toLongOrNull()

    private fun invalid(): Nothing = throw CampusApi.Failure("protocol", "校园卡服务未返回有效账单，请稍后再试。", false)
}
