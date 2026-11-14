package dev.codex.hfutcampus

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.progressindicator.CircularProgressIndicator
import java.time.format.DateTimeFormatter
import java.util.Locale

internal class CampusBillPage(
    private val activity: MainActivity,
    private val runtime: CampusRuntime,
    private val navigation: CampusNavigation
) {
    private val screen = CampusFeePages.Screen(activity, navigation, "校园卡账单")
    private val content = CampusUi.column(activity)
    private val header = CampusUi.row(activity)
    private val count = text("", 13, CampusUi.MUTED)
    private val refresh = CampusUi.iconButton(activity, "refresh", "刷新账单") { load(current) }
    private var current = 1
    private var active = false
    private var loading = false
    private val accountRevision = runtime.configurationRevision()

    fun open(source: View) {
        header.addView(count, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(refresh, LinearLayout.LayoutParams(dp(44), dp(44)))
        add(screen.body, header, 8)
        add(screen.body, content, 4)
        active = navigation.push(screen, source) { active = false }
        if (active) load(1)
    }

    private fun load(page: Int) {
        if (!active || loading) return
        if (!accountIsCurrent()) {
            refresh.isEnabled = false
            count.text = ""
            content.removeAllViews()
            message("请重新打开账单", "校园卡登录状态已变化。")
            return
        }
        current = page
        loading = true
        refresh.isEnabled = false
        count.text = ""
        content.removeAllViews()
        val box = card(content, 0)
        box.gravity = Gravity.CENTER_HORIZONTAL
        add(box, CircularProgressIndicator(activity).apply {
            isIndeterminate = true
            setIndicatorColor(CampusUi.PRIMARY)
        }, 5, -2)
        add(box, text("正在查询账单…", 14, CampusUi.MUTED), 12, -2)
        screen.scrollTo(0, 0)
        runtime.requestBills(page) { result ->
            if (!active || activity.isDestroyed) return@requestBills
            loading = false
            refresh.isEnabled = true
            content.removeAllViews()
            if (!accountIsCurrent()) {
                refresh.isEnabled = false
                message("请重新打开账单", "校园卡登录状态已变化。")
            } else result.fold(::render) { failure ->
                val reason = (failure as? CampusApi.Failure)?.message ?: "请稍后重试。"
                message("暂时无法查看账单", reason) { load(page) }
            }
        }
    }

    private fun accountIsCurrent(): Boolean =
        runtime.isConfigured && accountRevision == runtime.configurationRevision()

    private fun render(page: CampusBills.Page) {
        count.text = "共 ${page.total} 笔"
        if (page.rows.isEmpty()) {
            if (page.current == 1) message("暂无交易记录")
            else message("这一页暂无交易", action = "返回第一页") { load(1) }
        }
        page.rows.groupBy { it.date }.forEach { (date, bills) ->
            val title = date?.format(DAY) ?: "日期未提供"
            add(content, text(title, 14, CampusUi.MUTED).also(CampusUi::medium), 14)
            val group = CampusUi.column(activity).apply {
                background = CampusUi.shape(activity, CampusUi.WHITE, 22f)
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
            add(content, group, 8)
            bills.forEachIndexed { index, bill ->
                if (index > 0) group.addView(View(activity).apply {
                    setBackgroundColor(CampusUi.BG)
                }, LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(10); marginEnd = dp(10) })
                group.addView(billRow(bill))
            }
        }
        if (page.current > 1 || page.rows.isNotEmpty() && page.hasNext) {
            val controls = CampusUi.row(activity)
            controls.addView(button("上一页") { load(page.current - 1) }.apply {
                isEnabled = page.current > 1
                if (!isEnabled) alpha = 0.45f
            }, LinearLayout.LayoutParams(0, -2, 1f))
            controls.addView(text("第 ${page.current} 页", 13, CampusUi.MUTED).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(8), 0)
            }, LinearLayout.LayoutParams(-2, -2))
            controls.addView(button("下一页") { load(page.current + 1) }.apply {
                isEnabled = page.rows.isNotEmpty() && page.hasNext
                if (!isEnabled) alpha = 0.45f
            }, LinearLayout.LayoutParams(0, -2, 1f))
            add(content, controls, 18)
        }
    }

    private fun billRow(bill: CampusBills.Bill): View {
        val row = CampusUi.row(activity).apply {
            setPadding(dp(10), dp(12), dp(10), dp(12))
            background = CampusUi.ripple(activity, CampusUi.WHITE, 14f)
            isFocusable = true
            setOnClickListener { if (accountIsCurrent()) detail(bill) }
        }
        val icon = CampusUi.icon(activity, "receipt", CampusUi.PRIMARY).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = CampusUi.shape(activity, CampusUi.BG, 12f)
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(36), dp(36)))
        val labels = CampusUi.column(activity)
        labels.addView(text(bill.title, 15, CampusUi.INK).also(CampusUi::medium))
        val subtitle = listOf(bill.clock, bill.type).filter(String::isNotEmpty).joinToString(" · ")
        if (subtitle.isNotEmpty()) add(labels, text(subtitle, 12, CampusUi.MUTED), 4)
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginStart = dp(10)
            marginEnd = dp(10)
        })
        val color = when {
            bill.amountCents == null -> CampusUi.MUTED
            bill.credit -> CampusUi.PRIMARY
            else -> CampusUi.INK
        }
        row.addView(text(bill.amount, if (bill.amountCents == null) 12 else 16, color).apply {
            gravity = Gravity.END
            maxWidth = dp(125)
            fontFeatureSettings = "tnum"
            CampusUi.medium(this)
        })
        return row
    }

    private fun detail(bill: CampusBills.Bill) {
        val sheet = activity.sheet("交易详情", bill.title)
        val box = card(sheet.body, 0, CampusUi.BG)
        listOf(
            "交易金额" to bill.amount,
            "交易类型" to bill.type,
            "交易时间" to bill.transactionTime,
            "入账时间" to bill.postedTime,
            "订单号" to bill.order
        ).filter { it.second.isNotEmpty() }.forEachIndexed { index, (label, value) ->
            add(box, text(label, 12, CampusUi.MUTED), if (index == 0) 0 else 14)
            add(box, text(value, 15, CampusUi.INK).apply {
                CampusUi.medium(this)
                setTextIsSelectable(label == "订单号")
            }, 4)
        }
        sheet.show()
    }

    private fun message(title: String, description: String = "", action: String = "重试", tap: (() -> Unit)? = null) {
        val box = card(content, 0)
        add(box, text(title, 16, CampusUi.INK).also(CampusUi::medium), 0)
        if (description.isNotEmpty()) add(box, text(description, 13, CampusUi.MUTED), 6)
        tap?.let { add(box, button(action, it), 12) }
    }

    private fun card(parent: LinearLayout, gap: Int, color: Int = CampusUi.WHITE): LinearLayout =
        CampusUi.column(activity).apply {
            background = CampusUi.shape(activity, color, 22f)
            setPadding(dp(17), dp(16), dp(17), dp(16))
            add(parent, this, gap)
        }

    private fun add(parent: LinearLayout, child: View, gap: Int, width: Int = -1) {
        parent.addView(child, LinearLayout.LayoutParams(width, -2).apply { topMargin = dp(gap) })
    }

    private fun text(value: String, size: Int, color: Int): TextView = CampusUi.text(activity, value, size, color)
    private fun button(label: String, tap: () -> Unit) = CampusUi.button(activity, label, false, false, tap)
    private fun dp(value: Int) = CampusUi.dp(activity, value.toFloat())

    private companion object {
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日  EEEE", Locale.SIMPLIFIED_CHINESE)
    }
}
