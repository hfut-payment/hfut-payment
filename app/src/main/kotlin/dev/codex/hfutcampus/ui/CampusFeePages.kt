package dev.codex.hfutcampus

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

private const val HIDDEN_FEE = "***"
private val feeNumber = Regex("""[-+−]?\d+(?:[.,]\d+)*""")

/** Fee tiles, details and account-specific configuration. */
class CampusFeePages(private val activity: MainActivity, private val runtime: CampusRuntime, private val nav: CampusNavigation) {
    class Screen(activity: Activity, nav: CampusNavigation, title: String) : ScrollView(activity) {
        @JvmField val body = CampusUi.column(activity)
        init {
            setBackgroundColor(CampusUi.BG)
            isFillViewport = true
            clipToPadding = false
            fun dp(n: Float) = CampusUi.dp(activity, n)
            body.setPadding(dp(22f), dp(8f), dp(22f), dp(32f))
            body.isFocusableInTouchMode = true
            addView(body)
            val bar = CampusUi.row(activity)
            bar.addView(CampusUi.iconButton(activity, "back", "返回") { nav.pop() }, LinearLayout.LayoutParams(dp(48f), dp(48f)))
            val heading = CampusUi.text(activity, title, 24, CampusUi.INK)
            CampusUi.medium(heading)
            heading.setPadding(dp(12f), 0, 0, 0)
            bar.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
            body.addView(bar)
        }
    }

    private class Tile(
        val value: TextView?,
        val subtitle: TextView?,
        val networkBody: LinearLayout? = null
    )
    private data class Detail(val kind: CampusFees.Kind, val campus: String, val screen: Screen, val contents: LinearLayout)
    private data class FeeSlot(val kind: CampusFees.Kind, val ac: Boolean = false)
    private data class FeeExtra(val plugin: PluginBundle, val item: FeePluginEntries.Extra)
    private val tiles = mutableMapOf<CampusFees.Kind, Tile>()
    private val readings = mutableMapOf<FeeSlot, CampusFees.Reading>()
    private var feeEpoch = -1L
    private var nextRefresh = 0L
    private var generation = 0
    private var loading = false
    private var grid: LinearLayout? = null
    private var layoutCampus: String? = null
    private var detailPage: Detail? = null
    private var pendingDetails = false
    private val displayPrefs = activity.getSharedPreferences("campus-fee-display", Context.MODE_PRIVATE)
    private var balancesHidden = hideByDefault()
    private var visibilityOverridden = false
    private var balanceEye: CampusUi.Icon? = null
    private val subtitleLineHeight by lazy {
        // Measure a mixed-script line so the Chinese fallback font contributes to its height.
        compactText("赠送 ¥0.00", 11, CampusUi.MUTED).run {
            measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            measuredHeight
        }
    }

    fun attach(parent: LinearLayout) {
        val card = CampusUi.column(activity)
        card.setPadding(dp(20), dp(10), dp(20), dp(12))
        card.background = CampusUi.shape(activity, CampusUi.WHITE, 28f)
        place(parent, card, 12)
        val bar = CampusUi.row(activity)
        val title = text("校园费用", 18, CampusUi.INK).also(CampusUi::medium)
        bar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val eye = CampusUi.icon(activity, "eye", CampusUi.INK).apply {
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
            background = CampusUi.ripple(activity, CampusUi.WHITE, 22f)
            isFocusable = true
            setOnClickListener {
                visibilityOverridden = true
                setBalancesHidden(!balancesHidden)
            }
        }
        balanceEye = eye
        updateEye()
        bar.addView(eye, LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(CampusUi.iconButton(activity, "refresh", "刷新费用") { refresh(true) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val settings = CampusUi.iconButton(activity, "settings", "费用查询设置") { showSettings(bar.getChildAt(3)) }
        bar.addView(settings, LinearLayout.LayoutParams(dp(44), dp(44)))
        card.addView(bar)
        grid = CampusUi.column(activity).also { card.addView(it) }
        redrawTiles(profile(), true)
    }

    fun hideByDefault(): Boolean = displayPrefs.getBoolean("hide-by-default", false)

    fun setDefaultHidden(hidden: Boolean) {
        displayPrefs.edit().putBoolean("hide-by-default", hidden).apply()
        if (!visibilityOverridden) setBalancesHidden(hidden)
    }

    private fun setBalancesHidden(hidden: Boolean) {
        balancesHidden = hidden
        updateEye()
        tiles.keys.forEach(::renderTile)
        detailPage?.let(::renderDetail)
    }

    private fun updateEye() {
        balanceEye?.apply {
            symbol = if (balancesHidden) "eyeoff" else "eye"
            contentDescription = if (balancesHidden) "显示校园费用余额" else "隐藏校园费用余额"
        }
    }

    private fun feeText(value: String): String =
        if (balancesHidden) feeNumber.replace(value, HIDDEN_FEE) else value

    private fun redrawTiles(profile: CampusFees.Profile, force: Boolean = false) {
        val grid = grid ?: return
        if (!force && profile.campus == layoutCampus) return
        layoutCampus = profile.campus
        tiles.clear()
        grid.removeAllViews()
        val kinds = profile.cards()
        val fontScale = activity.resources.configuration.fontScale.coerceAtLeast(1f)
        val height = dp(90) + (dp(62) * (fontScale - 1f)).roundToInt()
        kinds.chunked(2).forEach { pair ->
            val row = CampusUi.row(activity)
            grid.addView(row, LinearLayout.LayoutParams(-1, height).apply { topMargin = dp(10) })
            pair.forEachIndexed { i, kind ->
                val tile = CampusUi.column(activity)
                tile.background = CampusUi.ripple(activity, CampusUi.BG, 16f)
                val network = kind == CampusFees.Kind.NETWORK
                val inset = dp(if (network && narrowFees()) 9 else 12)
                tile.setPadding(inset, dp(10), inset, dp(10))
                tile.isFocusable = true
                tile.setOnClickListener { showDetail(kind, tile) }
                row.addView(tile, LinearLayout.LayoutParams(0, -1, 1f).apply { if (i != 0) leftMargin = dp(10) })
                val heading = CampusUi.row(activity)
                heading.addView(CampusUi.icon(activity, kind.icon, CampusUi.PRIMARY), LinearLayout.LayoutParams(dp(16), dp(16)))
                val label = compactText(kind.title, 12, CampusUi.MUTED)
                heading.addView(label, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
                heading.addView(CampusUi.icon(activity, "chevron", CampusUi.MUTED), LinearLayout.LayoutParams(dp(12), dp(12)))
                tile.addView(heading)
                if (network) {
                    val body = CampusUi.column(activity)
                    tile.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
                    tiles[kind] = Tile(null, null, body)
                } else {
                    val value = compactText("", 16, CampusUi.INK).also(CampusUi::medium)
                    val subtitle = compactText("", 11, CampusUi.MUTED)
                    place(tile, value, 8); place(tile, subtitle, 4)
                    tiles[kind] = Tile(value, subtitle)
                }
            }
            if (pair.size == 1) row.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(10) })
        }
        tiles.keys.forEach(::renderTile)
    }

    fun extensionsChanged() {
        detailPage?.let(::renderDetail)
    }

    private fun extras(kind: CampusFees.Kind, profile: CampusFees.Profile): List<FeeExtra> {
        val local = LocalPlugins.get(activity)
        return local.list().filter(local::active).flatMap { plugin ->
            plugin.feeExtras(kind.key, profile.campus).map { FeeExtra(plugin, it) }
        }
    }

    private fun addExtra(
        heading: LinearLayout, kind: CampusFees.Kind, profile: CampusFees.Profile,
        extras: List<FeeExtra>
    ) {
        if (extras.isEmpty()) return
        val item = extras.singleOrNull()?.item
        val glyph = item?.text?.takeIf(String::isNotBlank).orEmpty()
        val view = if (glyph.isNotEmpty() && glyph != "+") text(glyph, 14, CampusUi.PRIMARY).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            CampusUi.medium(this)
            setPadding(dp(8), 0, dp(8), 0)
        } else CampusUi.icon(activity, when {
            item == null -> "more"
            glyph == "+" -> "add"
            else -> item.icon
        }, CampusUi.PRIMARY).apply {
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
        }
        view.background = CampusUi.ripple(activity, CampusUi.BG, 14f)
        view.contentDescription = item?.title ?: "${kind.title}操作"
        view.isFocusable = true
        view.setOnClickListener { source ->
            fun open(extra: FeeExtra) {
                activity.openFeeExtra(extra.plugin, kind.key, profile.campus, extra.item.id, source)
            }
            if (extras.size == 1) open(extras.single())
            else {
                val picker = activity.sheet(kind.title, "请选择要操作的账户")
                extras.forEach { extra ->
                    picker.row(extra.item.icon.ifEmpty { "wallet" }, extra.item.title, "", false, false) {
                        picker.dismiss()
                        open(extra)
                    }
                }
                picker.show()
            }
        }
        heading.addView(view, LinearLayout.LayoutParams(if (view is TextView) -2 else dp(44), dp(44)).apply {
            leftMargin = dp(8)
        })
    }

    private fun get(kind: CampusFees.Kind, ac: Boolean = false): CampusFees.Reading =
        readings[FeeSlot(kind, ac)] ?: CampusFees.Reading.status(
            if (runtime.isConfigured) profile().unavailable(kind, ac).ifEmpty { "等待查询" } else "登录后查看"
        )

    private fun renderTile(kind: CampusFees.Kind) {
        val tile = tiles[kind] ?: return
        val profile = profile()
        val value = get(kind)
        tile.networkBody?.let {
            renderNetworkTile(it, value)
            return
        }
        val amount = requireNotNull(tile.value)
        val hint = requireNotNull(tile.subtitle)
        val electric = kind == CampusFees.Kind.ELECTRIC && profile.dualElectric() && profile.hasDorm() && runtime.isConfigured
        val ac = if (electric) get(kind, true) else null
        val primary = feeText(value.primary)
        amount.text = if (electric) (if (profile.hefei()) "照明  " else "常规  ") + primary else primary
        amount.setTextColor(if (value.available) CampusUi.INK else CampusUi.MUTED)
        amount.textSize = if (value.available) (if (electric) 14f else 17f) else 13f
        val secondary = when {
            ac != null -> "空调  ${feeText(ac.primary)}"
            kind == CampusFees.Kind.CARD && balancesHidden && value.available && value.secondary.isNotEmpty() -> HIDDEN_FEE
            else -> feeText(value.secondary)
        }
        hint.text = secondary
        hint.visibility = if (secondary.isEmpty()) View.GONE else View.VISIBLE
        hint.setTextColor(if (ac?.available == true) CampusUi.INK else CampusUi.MUTED)
        hint.textSize = if (ac != null) 14f else 11f
    }

    private fun prefs() = CampusFeeProfile.preferences(activity, runtime.feeProfileKey())

    private fun profile(campusOverride: String? = null) =
        CampusFeeProfile.read(activity, runtime.feeProfileKey(), campusOverride)

    private fun compactText(value: String, size: Int, color: Int) = text(value, size, color).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun narrowFees() = activity.resources.configuration.screenWidthDp < 380

    private fun renderNetworkTile(parent: LinearLayout, reading: CampusFees.Reading) {
        parent.removeAllViews()
        val usage = reading.network
        if (usage == null) {
            place(parent, compactText(feeText(reading.primary), 13, CampusUi.MUTED), 8)
            return
        }
        val amount = if (usage.exhausted) usage.balance else usage.monthlyFree.remainingText
        val caption = when {
            usage.exhausted -> if (amount == null) "网费未返回" else "剩余网费"
            amount == null -> "免费额度未知"
            else -> "剩余免费"
        }
        val summary = CampusUi.row(activity).apply { gravity = Gravity.TOP }
        val narrow = narrowFees()
        val amountText = amount?.let(::feeText) ?: "—"
        val number = compactText(amountText, if (narrow) 15 else 17, CampusUi.INK).also(CampusUi::medium)
        val amountCaption = compactText(caption, if (narrow) 9 else 10, CampusUi.MUTED)
        // Share a baseline, then match the visible glyph bottoms across Latin and Chinese fonts.
        val numberBounds = Rect()
        val captionBounds = Rect()
        val alignmentText = amount ?: "—"
        number.paint.getTextBounds(alignmentText, 0, alignmentText.length, numberBounds)
        amountCaption.paint.getTextBounds(caption, 0, caption.length, captionBounds)
        amountCaption.translationY = (numberBounds.bottom - captionBounds.bottom).toFloat()
        summary.addView(number, LinearLayout.LayoutParams(0, -2, 1f))
        summary.addView(amountCaption, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4) })
        place(parent, summary, if (narrow) 9 else 8)

        val meter = if (usage.exhausted) usage.monthlyTotal else usage.monthlyFree
        val hasProgress = meter.used != null && meter.limit.signum() > 0
        if (!usage.exhausted && !hasProgress) return
        val progressGroup = CampusUi.column(activity)
        if (usage.exhausted) {
            val legend = CampusUi.row(activity).apply {
                gravity = Gravity.BOTTOM
                isBaselineAligned = false
            }
            val remainingText = meter.remainingText?.let { "剩余 " + it.replace(' ', '\u00A0') } ?: "剩余量未知"
            val remaining = text(feeText(remainingText), 9, CampusUi.MUTED).apply {
                gravity = Gravity.END
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            legend.addView(compactText("免费额度耗尽", 9, CampusUi.MUTED), LinearLayout.LayoutParams(-2, -2))
            legend.addView(remaining, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
            place(progressGroup, legend, 0)
        }
        if (hasProgress) {
            place(progressGroup, CampusUi.progress(activity, compact = true).apply {
                contentDescription = if (usage.exhausted) "月总额度使用进度" else "免费额度使用进度"
                setProgressCompat(meter.progress, false)
            }, if (usage.exhausted) 3 else 0)
        }
        val secondarySlot = FrameLayout(activity).apply {
            // Other cards place their subtitle 4 dp below the main figure.
            minimumHeight = dp(4) * 2 + subtitleLineHeight
            addView(progressGroup, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
        }
        place(parent, secondarySlot, if (narrow) 1 else 0)
    }

    private fun renderNetworkMeters(parent: LinearLayout, profile: CampusFees.Profile, usage: CampusFees.NetworkUsage?) {
        parent.removeAllViews()
        if (!profile.xuancheng() || !runtime.isConfigured) return
        val free = usage?.monthlyFree ?: CampusFees.UsageMeter(null, CampusFees.XC_FREE_GIB.toBigDecimal())
        val total = usage?.monthlyTotal ?: CampusFees.UsageMeter(null, CampusFees.XC_MONTHLY_LIMIT_GIB.toBigDecimal())
        if (usage?.exhausted != true) meter(parent, "本月免费额度", free,
            "用量未返回", if (usage?.estimated == true) "估算" else "")
        meter(parent, "本月限额", total, "本月用量未返回")
        if (usage?.exhausted == true) {
            place(parent, text(if (usage.estimated) "按已用流量估算，免费额度已用完" else "本月免费额度已用完", 11, CampusUi.MUTED), 12)
            place(parent, text("剩余网费  " + (usage.balance?.let(::feeText) ?: "未返回"), 13, CampusUi.INK).also(CampusUi::medium), 4)
        }
    }

    private fun meter(parent: LinearLayout, title: String, meter: CampusFees.UsageMeter, missing: String, note: String = "") {
        place(parent, text(title, 11, CampusUi.MUTED), 10)
        val units = if (note.isEmpty()) meter.text else meter.text + " · " + note
        place(parent, text(feeText(units), 12, CampusUi.INK).also(CampusUi::medium), 3)
        place(parent, CampusUi.progress(activity).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setProgressCompat(meter.progress, false)
        }, 5)
        if (meter.used == null) place(parent, text(missing, 10, CampusUi.MUTED), 3)
    }

    fun clearProfile() { prefs().edit().clear().apply() }

    private fun invalidate() {
        generation++
        loading = false
        nextRefresh = 0
        pendingDetails = false
        readings.clear()
        tiles.keys.forEach(::renderTile)
        detailPage?.let(::renderDetail)
    }

    fun refresh(force: Boolean) {
        if (!activity.feeVisible()) return
        val detail = detailPage?.takeIf { nav.top(it.screen) }?.kind
        if (!nav.home() && detail == null) return
        if (feeEpoch != runtime.configurationRevision()) {
            feeEpoch = runtime.configurationRevision()
            invalidate()
        }
        val profile = profile()
        redrawTiles(profile)
        if (loading) {
            pendingDetails = pendingDetails || force && detail != null
            return
        }
        if (!runtime.isConfigured || !force && SystemClock.elapsedRealtime() < nextRefresh) return
        val kinds = detail?.let(::listOf) ?: tiles.keys.toList()
        val slots = kinds.flatMap {
            if (it == CampusFees.Kind.ELECTRIC && profile.dualElectric()) listOf(FeeSlot(it), FeeSlot(it, true))
            else listOf(FeeSlot(it))
        }
        loading = true
        if (detail == null) nextRefresh = SystemClock.elapsedRealtime() + 120_000
        load(profile, slots, 0, ++generation, detail == null)
    }

    private fun load(profile: CampusFees.Profile, slots: List<FeeSlot>, index: Int, id: Int, batch: Boolean) {
        if (activity.isDestroyed || id != generation) return
        if (!activity.feeVisible() || !nav.home() && detailPage?.let { nav.top(it.screen) } != true || index == slots.size) {
            loading = false
            if (batch && index < slots.size) nextRefresh = 0
            if (pendingDetails) { pendingDetails = false; refresh(true) }
            return
        }
        val slot = slots[index]
        val unavailable = profile.unavailable(slot.kind, slot.ac)
        fun advance(value: CampusFees.Reading) {
            readings[slot] = value
            renderTile(slot.kind)
            detailPage?.takeIf { it.kind == slot.kind }?.let(::renderDetail)
            load(profile, slots, index + 1, id, batch)
        }
        if (unavailable.isNotEmpty()) { advance(CampusFees.Reading.status(unavailable)); return }
        if (readings[slot]?.available != true) {
            readings[slot] = CampusFees.Reading.status("查询中…")
            renderTile(slot.kind)
            detailPage?.takeIf { it.kind == slot.kind }?.let(::renderDetail)
        }
        runtime.requestFee(slot.kind, profile, slot.ac) { value ->
            if (activity.isDestroyed || id != generation) return@requestFee
            if (value == null) { loading = false; return@requestFee }
            advance(value)
        }
    }

    private fun showDetail(kind: CampusFees.Kind, source: View) {
        val profile = profile()
        val screen = Screen(activity, nav, kind.title)
        val top = CampusUi.row(activity)
        val campus = if (profile.xuancheng()) "宣城校区" else if (profile.hefei()) "合肥校区" else "未设置校区"
        val sub = text(campus, 13, CampusUi.MUTED)
        top.addView(sub, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(CampusUi.iconButton(activity, "refresh", "刷新此项") { refresh(true) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val settings = CampusUi.iconButton(activity, "settings", "费用查询设置") { showSettings(top.getChildAt(2)) }
        top.addView(settings, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(10) })
        place(screen.body, top, 12)
        val contents = CampusUi.column(activity)
        screen.body.addView(contents)
        val previous = detailPage
        val page = Detail(kind, profile.campus, screen, contents)
        detailPage = page
        renderDetail(page)
        if (!nav.push(screen, source) { detailPage = previous }) { detailPage = previous; return }
        if (readings[FeeSlot(kind)] == null) refresh(true)
    }

    private fun renderDetail(page: Detail) {
        val profile = profile()
        if (page.campus != profile.campus) return
        val kind = page.kind
        val root = page.contents
        root.removeAllViews()
        if (kind !in profile.cards()) return
        val reading = get(kind)
        val hefeiElectric = profile.hefei() && kind == CampusFees.Kind.ELECTRIC
        val extras = if (hefeiElectric) emptyList() else extras(kind, profile)
        if (kind == CampusFees.Kind.NETWORK && profile.xuancheng()) {
            renderNetworkMeters(panel(root, "额度进度"), profile, reading.network)
        }
        if (kind == CampusFees.Kind.ELECTRIC) {
            if (profile.hasDorm() && profile.roomLabel.isNotEmpty()) info(root, "宿舍", profile.roomLabel)
            if (profile.hasDorm() && profile.dualElectric()) {
                val light = if (profile.hefei()) "照明用电" else "常规用电"
                values(root, light, reading, kind, profile, extras.filter { it.item.slot != "ac" },
                    "regular".takeIf { profile.xuancheng() })
                values(root, "空调用电", get(kind, true), kind, profile, extras.filter { it.item.slot == "ac" },
                    "ac".takeIf { profile.xuancheng() })
            } else values(root, "宿舍用电", reading, kind, profile, extras)
            if (hefeiElectric && runtime.isConfigured && profile.hasDorm() &&
                (profile.hefeiMeters?.light == null || profile.hefeiMeters.ac == null)) {
                option(panel(root, "查询设置"), "settings", "重新选择宿舍", "重新查找照明和空调电表") { showSettings(it) }
            }
        } else {
            values(root, when (kind) {
                CampusFees.Kind.CARD -> "余额明细"
                CampusFees.Kind.NETWORK -> "流量与资费"
                CampusFees.Kind.ELECTRIC -> "宿舍用电"
                CampusFees.Kind.WATER -> "洗浴账户"
                CampusFees.Kind.LAUNDRY -> "洗衣服务"
            }, reading, kind, profile, extras, if (profile.xuancheng()) when (kind) {
                CampusFees.Kind.NETWORK -> "net"
                CampusFees.Kind.WATER -> "shower"
                else -> null
            } else null)
        }
        if (!runtime.isConfigured) {
            place(root, button("登录校园卡", true, activity::showFeeAccount), 14)
        }
        if (kind == CampusFees.Kind.CARD && runtime.isConfigured) {
            val bills = panel(root, "查看账单") { header ->
                header.addView(CampusUi.icon(activity, "chevron", CampusUi.MUTED),
                    LinearLayout.LayoutParams(dp(18), dp(18)))
            }
            bills.background = CampusUi.ripple(activity, CampusUi.WHITE, 24f)
            bills.isFocusable = true
            bills.setOnClickListener { source -> CampusBillPage(activity, runtime, nav).open(source) }
            paragraph(bills, "消费、充值与退款记录")
        }
        if (hefeiElectric) return
        val actions = LocalPlugins.get(activity).list().mapNotNull { plugin ->
            plugin.feeShortcut(kind.key)?.takeIf { LocalPlugins.get(activity).active(plugin) }?.let { plugin to it }
        }
        if (actions.isNotEmpty()) {
            val card = panel(root, "更多服务")
            actions.forEach { (plugin, shortcut) ->
                option(card, shortcut.icon, shortcut.title, shortcut.detail) { source ->
                    activity.openFeePlugin(plugin, kind.key, profile.campus, source)
                }
            }
        }
    }

    private fun values(
        root: LinearLayout, heading: String, reading: CampusFees.Reading,
        kind: CampusFees.Kind, profile: CampusFees.Profile, extras: List<FeeExtra>, recharge: String? = null
    ) {
        val needsSetup = runtime.isConfigured && (profile.xuancheng() || profile.hefei()) && when (kind) {
            CampusFees.Kind.ELECTRIC -> !profile.hasDorm()
            CampusFees.Kind.WATER -> profile.unavailable(kind).isNotEmpty()
            else -> false
        }
        val card = panel(root, heading) { header ->
            if (needsSetup) {
                header.addView(CampusUi.icon(activity, "chevron", CampusUi.MUTED), LinearLayout.LayoutParams(dp(18), dp(18)))
                return@panel
            }
            addExtra(header, kind, profile, extras)
            if (recharge != null && runtime.isConfigured) {
                val topUp = CampusUi.iconButton(activity, "add", "充值$heading") {
                    activity.showRecharge(recharge, heading) { showSettings(null) }
                }
                topUp.background = CampusUi.ripple(activity, CampusUi.BG, 14f)
                header.addView(topUp, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(8) })
            }
            if (kind == CampusFees.Kind.CARD && runtime.isConfigured) {
                val topUp = CampusUi.iconButton(activity, "add", "充值校园卡", activity::showCardTopUp)
                topUp.background = CampusUi.ripple(activity, CampusUi.BG, 14f)
                header.addView(topUp, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(8) })
            }
        }
        if (needsSetup) {
            card.background = CampusUi.ripple(activity, CampusUi.WHITE, 24f)
            card.isFocusable = true
            card.setOnClickListener { showSettings(it) }
            paragraph(card, if (kind == CampusFees.Kind.ELECTRIC) "选择宿舍后查看电费" else "填写洗浴手机号后查看余额")
            return
        }
        if (reading.detail.isEmpty()) paragraph(card, feeText(reading.primary))
        reading.detail.forEach { (key, value) ->
            val row = CampusUi.row(activity)
            val title = text(key, 13, CampusUi.MUTED)
            val amount = text(feeText(value), 15, CampusUi.INK).also(CampusUi::medium)
            amount.gravity = Gravity.END
            row.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(amount, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
            place(card, row, 13)
        }
    }

    private class CampusEditor(
        val view: LinearLayout, var room: String, var building: String, var label: String,
        var hefeiMeters: CampusFees.HefeiMeters?
    ) {
        var phone: EditText? = null
    }

    fun showSettings(source: View?) {
        if (!runtime.isConfigured) { activity.showFeeAccount(); return }
        val key = runtime.feeProfileKey()
        val screen = Screen(activity, nav, "费用查询设置")
        val stored = prefs()
        val previousCampus = stored.getString("campus", "").orEmpty()
        var selected = if (previousCampus == "hf") "hf" else "xc"
        val selector = panel(screen.body, "校区")
        val xc = editor("xc", profile("xc"))
        val hf = editor("hf", profile("hf"))
        val error = text("", 13, CampusUi.ERROR).apply { visibility = View.GONE }
        val ime = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        fun display() {
            xc.view.visibility = if (selected == "xc") View.VISIBLE else View.GONE
            hf.view.visibility = if (selected == "hf") View.VISIBLE else View.GONE
            error.visibility = View.GONE
        }
        place(selector, CampusUi.campusSelector(activity, selected == "hf") { hefei ->
            ime.hideSoftInputFromWindow(screen.windowToken, 0)
            screen.body.requestFocus()
            selected = if (hefei) "hf" else "xc"
            display()
        }, 12)
        place(screen.body, xc.view, 0)
        place(screen.body, hf.view, 0)
        place(screen.body, error, 14)
        place(screen.body, button("保存配置", true) {
            val form = if (selected == "xc") xc else hf
            val tel = form.phone?.text?.toString()?.trim().orEmpty()
            val badPhone = tel.isNotEmpty() && !tel.matches(Regex("[0-9]{11}"))
            if (badPhone) {
                error.text = "请填写 11 位手机号，或留空。"
                error.visibility = View.VISIBLE
            } else if (key == runtime.feeProfileKey()) {
                val before = profile()
                val updated = profile(selected).copy(room = form.room, building = form.building,
                    roomLabel = form.label, phone = tel, hefeiMeters = form.hefeiMeters)
                stored.edit().apply {
                    // Save an existing beta.6 profile before moving the active campus pointer.
                    if (previousCampus in listOf("xc", "hf") && !stored.contains("$previousCampus.room")) {
                        val legacy = profile(previousCampus)
                        saveCampus(legacy)
                    }
                    saveCampus(updated)
                    remove("dailyGiB").remove("xc.dailyGiB").remove("hf.dailyGiB")
                    putString("campus", selected)
                }.apply()
                if (before.campus != updated.campus || before.room != updated.room || before.building != updated.building ||
                    before.hefeiMeters != updated.hefeiMeters) {
                    CommunityJobs.cancelPayments(activity)
                }
                invalidate()
                redrawTiles(profile())
                ime.hideSoftInputFromWindow(screen.windowToken, 0)
                nav.pop()
            }
        }, 14)
        display()
        nav.push(screen, source) {
            val detail = detailPage
            if (detail != null && detail.campus != profile().campus) {
                activity.window.decorView.post { if (nav.top(detail.screen)) nav.pop() }
            }
        }
    }

    private fun android.content.SharedPreferences.Editor.saveCampus(value: CampusFees.Profile) {
        val prefix = value.campus + "."
        putString(prefix + "room", value.room)
        putString(prefix + "building", value.building)
        putString(prefix + "roomLabel", value.roomLabel)
        putString(prefix + "phone", value.phone)
        putString(prefix + "networkAccount", value.networkAccount)
        if (value.hefei()) {
            putString(prefix + "lightBuilding", value.hefeiMeters?.light?.building.orEmpty())
            putString(prefix + "lightRoom", value.hefeiMeters?.light?.room.orEmpty())
            putString(prefix + "acBuilding", value.hefeiMeters?.ac?.building.orEmpty())
            putString(prefix + "acRoom", value.hefeiMeters?.ac?.room.orEmpty())
        }
    }

    private fun editor(campus: String, old: CampusFees.Profile): CampusEditor {
        val xc = campus == "xc"
        val form = CampusEditor(CampusUi.column(activity), old.room, old.building, old.roomLabel, old.hefeiMeters)
        val dorm = panel(form.view, "宿舍用电")
        val select = option(dorm, "power", "选择宿舍", form.label.ifEmpty { "通过楼栋和房间选择" }) { anchor ->
            fun chosen(room: String, building: String, label: String, meters: CampusFees.HefeiMeters? = null) {
                form.room = room; form.building = building; form.label = label
                form.hefeiMeters = meters
                (anchor.tag as TextView).text = label
            }
            if (xc) selectXc { room, building, label -> chosen(room, building, label) } else selectHf(::chosen)
        }
        option(dorm, "trash", "清除宿舍", "取消此项查询") {
            form.room = ""; form.building = ""; form.label = ""
            form.hefeiMeters = null
            select.text = "通过楼栋和房间选择"
        }
        if (xc) {
            val shower = panel(form.view, "洗浴")
            form.phone = input("洗浴绑定手机号", old.phone, InputType.TYPE_CLASS_PHONE, 11)
                .also { place(shower, it, 10) }
        }
        return form
    }

    private fun selectXc(chosen: (String, String, String) -> Unit) {
        val buildings = activity.sheet("选择楼栋", "按宿舍楼的名称选择。")
        for (b in 1..10) buildings.row("power", CampusFees.xcBuilding(b), "", false, false) {
            buildings.dismiss()
            val sides = activity.sheet("选择南楼或北楼", CampusFees.xcBuilding(b))
            for (south in listOf(true, false)) sides.row("power", if (south) "南楼" else "北楼", "", false, false) {
                sides.dismiss()
                val room = activity.sheet("选择寝室", CampusFees.xcBuilding(b) + if (south) " / 南楼" else " / 北楼")
                var digits = ""
                val shown = room.paragraph("请选择三位寝室号", CampusUi.MUTED)
                fun display() { shown.text = digits.ifEmpty { "请选择三位寝室号" } }
                listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("清空", "0", "退格")).forEach { keys ->
                    val row = CampusUi.row(activity)
                    keys.forEachIndexed { i, key ->
                        row.addView(button(key, false) {
                            digits = when (key) {
                                "清空" -> ""
                                "退格" -> digits.dropLast(1)
                                else -> if (digits.length < 3) digits + key else digits
                            }
                            display()
                        }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(8) })
                    }
                    place(room.body, row, 8)
                }
                room.action("确定", true, false) {
                    val id = CampusFees.xcRoom(b, digits, south)
                    if (id.isNotEmpty()) {
                        chosen(id, "", CampusFees.xcRoomLabel(id))
                        room.dismiss()
                    } else shown.text = "还需要选满三位寝室号"
                }
                room.show()
            }
            sides.show()
        }
        buildings.show()
    }

    private fun hefeiDirectory(
        building: String, title: String, detail: String,
        show: (CampusSheet, LinearLayout, List<CampusFees.RoomOption>) -> Unit
    ) {
        val sheet = activity.sheet(title, detail)
        val status = sheet.paragraph("正在查询宿舍目录…", CampusUi.MUTED)
        val list = CampusUi.column(activity)
        sheet.body.addView(list)
        fun populate() {
            status.text = "正在查询宿舍目录…"
            status.visibility = View.VISIBLE
            list.removeAllViews()
            runtime.requestRooms(building) { options ->
                if (!sheet.isShowing) return@requestRooms
                if (options.isEmpty()) {
                    status.text = "没有取得宿舍目录，请稍后重试。"
                    place(list, button("重新查询", false, ::populate), 8)
                } else {
                    status.visibility = View.GONE
                    show(sheet, list, options)
                }
            }
        }
        sheet.show()
        populate()
    }

    private fun selectHf(chosen: (String, String, String, CampusFees.HefeiMeters?) -> Unit) {
        hefeiDirectory("", "选择楼栋", "楼栋和电表由慧新易校提供。") { sheet, list, options ->
            CampusFees.hefeiBuildings(options).forEach { building ->
                val detail = when {
                    building.light != null && building.ac != null -> "照明与空调"
                    building.light != null -> "照明用电"
                    building.ac != null -> "空调用电"
                    else -> ""
                }
                option(list, "power", building.label, detail) {
                    sheet.dismiss()
                    selectHfRoom(building, chosen)
                }
            }
        }
    }

    private fun selectHfRoom(
        building: CampusFees.HefeiBuilding, chosen: (String, String, String, CampusFees.HefeiMeters?) -> Unit
    ) {
        building.unclassified?.let { zone ->
            hefeiDirectory(zone.id, "选择房间", building.label) { sheet, list, options ->
                options.forEach { room ->
                    val name = CampusFees.hefeiRoomName(room, zone)
                    option(list, "power", name, "") {
                        chosen(room.id, zone.id, "${building.label} / $name", null)
                        sheet.dismiss()
                    }
                }
            }
            return
        }
        val sheet = activity.sheet("选择房间", building.label)
        val status = sheet.paragraph("", CampusUi.MUTED)
        val list = CampusUi.column(activity)
        sheet.body.addView(list)
        val zones = listOfNotNull(building.light, building.ac)
        fun load() {
            list.removeAllViews()
            status.visibility = View.VISIBLE
            status.text = "正在读取房间和电表…"
            val byZone = mutableMapOf<String, List<CampusFees.RoomOption>>()
            zones.forEach { zone ->
                runtime.requestRooms(zone.id) { rooms ->
                    if (!sheet.isShowing) return@requestRooms
                    byZone[zone.id] = rooms
                    if (byZone.size != zones.size) return@requestRooms
                    val options = CampusFees.hefeiRooms(building, byZone[building.light?.id].orEmpty(),
                        byZone[building.ac?.id].orEmpty())
                    val incomplete = byZone.values.any { it.isEmpty() }
                    status.visibility = if (incomplete || options.isEmpty()) View.VISIBLE else View.GONE
                    if (incomplete || options.isEmpty()) {
                        status.text = if (options.isEmpty()) "暂时没有读到房间目录。" else "部分电表目录暂时无法加载。"
                        place(list, button("重新查询", false, ::load), 8)
                    }
                    options.forEach { room ->
                        val meters = room.meters
                        val description = when {
                            meters.light != null && meters.ac != null -> "照明与空调"
                            meters.light != null -> "照明用电"
                            else -> "空调用电"
                        }
                        val selected = meters.light ?: requireNotNull(meters.ac)
                        option(list, "power", room.name, description) {
                            chosen(selected.room, selected.building, "${building.label} / ${room.name}", meters)
                            sheet.dismiss()
                        }
                    }
                }
            }
        }
        sheet.show()
        load()
    }

    private fun option(parent: LinearLayout, icon: String, title: String, subtitle: String, tap: (View) -> Unit): TextView {
        val line = CampusUi.row(activity)
        line.background = CampusUi.ripple(activity, CampusUi.BG, 15f)
        line.setPadding(dp(12), dp(12), dp(12), dp(12))
        line.minimumHeight = dp(48)
        line.addView(CampusUi.icon(activity, icon, CampusUi.PRIMARY), LinearLayout.LayoutParams(dp(22), dp(22)))
        val words = CampusUi.column(activity)
        words.addView(text(title, 15, CampusUi.INK).also(CampusUi::medium))
        val sub = text(subtitle, 12, CampusUi.MUTED)
        if (subtitle.isNotEmpty()) place(words, sub, 5)
        line.addView(words, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12); rightMargin = dp(10) })
        line.addView(CampusUi.icon(activity, "chevron", CampusUi.MUTED), LinearLayout.LayoutParams(dp(18), dp(18)))
        line.isFocusable = true
        line.tag = sub
        line.setOnClickListener(tap)
        place(parent, line, 8)
        return sub
    }

    private fun panel(parent: LinearLayout, title: String, accessory: ((LinearLayout) -> Unit)? = null): LinearLayout = CampusUi.column(activity).also {
        it.setPadding(dp(18), dp(16), dp(18), dp(17))
        it.background = CampusUi.shape(activity, CampusUi.WHITE, 24f)
        val heading = CampusUi.row(activity)
        heading.addView(text(title, 17, CampusUi.INK).also(CampusUi::medium), LinearLayout.LayoutParams(0, -2, 1f))
        accessory?.invoke(heading)
        it.addView(heading)
        place(parent, it, 12)
    }

    private fun info(parent: LinearLayout, title: String, value: String) { paragraph(panel(parent, title), value) }
    private fun paragraph(parent: LinearLayout, value: String) {
        place(parent, text(value, 13, CampusUi.MUTED).apply { setLineSpacing(dp(3).toFloat(), 1f) }, 10)
    }
    private fun input(hintText: String, value: String, type: Int, maxLength: Int) = CampusUi.edit(activity).apply {
        hint = hintText; inputType = type; filters = arrayOf(InputFilter.LengthFilter(maxLength))
        setText(value); setTextColor(CampusUi.INK); setHintTextColor(CampusUi.MUTED)
        textSize = 15f; setSingleLine(true); isSaveEnabled = false
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = CampusUi.shape(activity, CampusUi.BG, 14f)
    }
    private fun text(value: String, size: Int, color: Int) = CampusUi.text(activity, value, size, color)
    private fun button(label: String, primary: Boolean, tap: () -> Unit) = CampusUi.button(activity, label, primary, false, tap)
    private fun dp(value: Int) = CampusUi.dp(activity, value.toFloat())
    private fun place(parent: LinearLayout, child: View, gap: Int) {
        parent.addView(child, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(gap) })
    }

}
