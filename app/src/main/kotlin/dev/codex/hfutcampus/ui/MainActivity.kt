package dev.codex.hfutcampus

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.Base64
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.codex.hfutcampus.CampusRuntime.SaveCompletion
import dev.codex.hfutcampus.NativePlugin.Ui
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/** One visual system for the wallet, settings and every application-owned dialog.  */
class MainActivity : Activity() {
    private lateinit var runtime: CampusRuntime
    private lateinit var homeScroll: ScrollView
    private lateinit var loginPage: ScrollView
    private var updateLoginPage: () -> Unit = {}
    private var backgroundLoginPlugins: List<PluginBundle> = emptyList()
    private var accountPage: CampusFeePages.Screen? = null
    private var pluginManagement: CampusFeePages.Screen? = null
    private var state: TextView? = null
    private var emptyTitle: TextView? = null
    private var emptyDetail: TextView? = null
    private var emptyAction: TextView? = null
    private var empty: LinearLayout? = null
    private var homeShortcuts: LinearLayout? = null
    private var settingsShortcuts: LinearLayout? = null
    private var codes: PaymentCodeView? = null
    private var visible = false
    private var settingsOpen = false
    private var modalDepth = 0
    private var shownRevision: Long = -1
    private var permissionGranted: Runnable? = null
    private lateinit var navigation: CampusNavigation
    private lateinit var fees: CampusFeePages
    private lateinit var appUpdatePage: AppUpdatePage
    private lateinit var updateDot: View
    private lateinit var homeSettingsButton: View
    private var updateSettingsSummary: TextView? = null
    @Volatile private var pluginRequest = 0
    private var securePluginPages = 0
    private val pluginPageCleanups = mutableSetOf<Runnable>()
    private data class PluginPage(val id: String, val title: String, val refresh: String?, val fee: JSONObject?)
    private val sheets: MutableList<CampusSheet> = ArrayList<CampusSheet>()
    private val main = Handler(Looper.getMainLooper())
    private var bandStatusRefresh: Runnable? = null
    private val changes =
        CampusRuntime.Listener { snapshot: CardSnapshot -> this.render(snapshot) }
    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (!homeVisible()) return
            render(runtime.current())
            main.postDelayed(this, 1000)
        }
    }

    public override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        runtime = CampusRuntime.Companion.get(this)
        val root = FrameLayout(this)
        root.setBackgroundColor(CampusUi.BG)
        val scroll = ScrollView(this)
        homeScroll = scroll
        scroll.visibility = View.INVISIBLE
        scroll.setFillViewport(true)
        scroll.setClipToPadding(false)
        val body = column()
        body.setPadding(dp(22f), dp(12f), dp(22f), dp(28f))
        scroll.addView(body)
        root.addView(scroll)
        setContentView(root)
        CampusInsets.page(getWindow(), root)
        navigation = CampusNavigation(this, root, scroll, Runnable { this.resumeHome() })
        navigation.onRevealed = { page ->
            if (page == pluginManagement) pluginManagement?.let(::drawPluginManagement)
            if (page == accountPage) accountPage?.let(::drawAccount)
            refreshPluginPage(page)
        }
        fees = CampusFeePages(this, runtime, navigation)
        val header = row()
        val brand = ImageView(this)
        brand.setImageDrawable(getDrawable(R.drawable.ic_brand))
        brand.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
        header.addView(brand, LinearLayout.LayoutParams(dp(38f), dp(38f)))
        val title = text(getString(R.string.app_name), 26, CampusUi.INK)
        CampusUi.medium(title)
        val titlePlace = LinearLayout.LayoutParams(0, -2, 1f)
        titlePlace.leftMargin = dp(12f)
        header.addView(title, titlePlace)
        homeSettingsButton = CampusUi.iconButton(
            this,
            "settings",
            "设置",
            Runnable { settings(header.getChildAt(2)) })
        val settingsFrame = FrameLayout(this)
        settingsFrame.addView(homeSettingsButton, FrameLayout.LayoutParams(-1, -1))
        updateDot = View(this).apply {
            background = CampusUi.shape(this@MainActivity, CampusUi.ERROR, 5f)
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        settingsFrame.addView(updateDot, FrameLayout.LayoutParams(dp(8f), dp(8f), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(8f)
            marginEnd = dp(8f)
        })
        header.addView(settingsFrame, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        appUpdatePage = AppUpdatePage(this, navigation) {
            val available = appUpdatePage.available
            updateDot.visibility = if (available) View.VISIBLE else View.GONE
            homeSettingsButton.contentDescription = if (available) "设置，有应用更新" else "设置"
            updateSettingsSummary?.text = appUpdatePage.summary
        }
        body.addView(header)

        val payment = column()
        payment.setPadding(dp(20f), dp(20f), dp(20f), dp(18f))
        payment.setBackground(CampusUi.shape(this, CampusUi.WHITE, 28f))
        val paymentPlace = LinearLayout.LayoutParams(-1, -2, 1f)
        paymentPlace.topMargin = dp(18f)
        body.addView(payment, paymentPlace)
        val heading = row()
        val name = text("付款码", 18, CampusUi.INK)
        CampusUi.medium(name)
        heading.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        state = text("未登录", 12, CampusUi.PRIMARY)
        val refresh = row().apply {
            minimumHeight = dp(44f)
            setPadding(dp(11f), 0, dp(10f), 0)
            background = CampusUi.ripple(this@MainActivity, CampusUi.TINT, 12f)
            isFocusable = true
            setOnClickListener { this@MainActivity.refresh() }
        }
        refresh.addView(state)
        refresh.addView(CampusUi.icon(this, "refresh", CampusUi.PRIMARY), LinearLayout.LayoutParams(dp(16f), dp(16f)).apply {
            marginStart = dp(7f)
        })
        heading.addView(refresh)
        payment.addView(heading)
        val stage = FrameLayout(this)
        val stageHeight = (resources.configuration.screenHeightDp - 360).coerceIn(190, 280)
        val stagePlace = LinearLayout.LayoutParams(-1, dp(stageHeight.toFloat()), 1f)
        stagePlace.topMargin = dp(8f)
        payment.addView(stage, stagePlace)
        codes = PaymentCodeView()
        stage.addView(codes, FrameLayout.LayoutParams(-1, -1))
        empty = column()
        empty!!.setGravity(Gravity.CENTER)
        empty!!.setPadding(dp(12f), 0, dp(12f), 0)
        val wallet: View = CampusUi.icon(this, "wallet", CampusUi.PRIMARY)
        wallet.setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
        wallet.setBackground(CampusUi.shape(this, CampusUi.TINT, 24f))
        empty!!.addView(wallet, LinearLayout.LayoutParams(dp(68f), dp(68f)))
        emptyTitle = text("添加校园卡", 20, CampusUi.INK)
        CampusUi.medium(requireNotNull(emptyTitle))
        emptyTitle!!.setGravity(Gravity.CENTER)
        emptyTitle!!.setPadding(0, dp(20f), 0, dp(8f))
        empty!!.addView(emptyTitle)
        emptyDetail = text("登录后查看付款码和校园费用", 13, CampusUi.MUTED)
        emptyDetail!!.setGravity(Gravity.CENTER)
        emptyDetail!!.setLineSpacing(dp(3f).toFloat(), 1f)
        empty!!.addView(emptyDetail)
        emptyAction = CampusUi.button(
            this,
            "添加账户",
            false,
            false,
            Runnable { if (runtime.isConfigured) showCurrentStatus() else account() })
        val emptyButton = LinearLayout.LayoutParams(-2, -2)
        emptyButton.topMargin = dp(18f)
        empty!!.addView(emptyAction, emptyButton)
        stage.addView(empty, FrameLayout.LayoutParams(-1, -1))
        fees.attach(body)
        homeShortcuts = column()
        body.addView(homeShortcuts)
        refreshShortcuts()
        syncLoginPolicy()
        loginPage = createLoginPage()
        root.addView(loginPage, FrameLayout.LayoutParams(-1, -1))
        loginPage.visibility = if (runtime.isConfigured) View.GONE else View.VISIBLE
        homeScroll.visibility = if (runtime.isConfigured) View.VISIBLE else View.INVISIBLE
    }

    private fun refreshShortcuts() {
        renderShortcuts(homeShortcuts, true)
        renderShortcuts(settingsShortcuts, false)
    }

    private fun renderShortcuts(container: LinearLayout?, home: Boolean) {
        if (container == null) return
        container.removeAllViews()
        val local: LocalPlugins = LocalPlugins.Companion.get(this)
        var rows: LinearLayout? = null
        for (plugin in local.list()) {
            val shortcut = if (home) plugin.home else plugin.settings
            if (shortcut == null || !local.active(plugin)) continue
            if (rows == null) rows = section(container, "插件入口")
            settingRow(
                rows,
                shortcut.icon,
                shortcut.title,
                shortcut.detail,
                false,
                Runnable {},
                { source -> runPlugin(plugin, "page", source = source) })
        }
        container.setVisibility(if (rows == null) View.GONE else View.VISIBLE)
    }

    fun feeVisible(): Boolean {
        return visible && runtime.isConfigured && modalDepth == 0
    }

    fun showFeeAccount() {
        account()
    }

    private fun dp(value: Float): Int {
        return CampusUi.dp(this, value)
    }

    private fun column(): LinearLayout {
        return CampusUi.column(this)
    }

    private fun row(): LinearLayout {
        return CampusUi.row(this)
    }

    private fun text(value: String?, size: Int, color: Int): TextView {
        return CampusUi.text(this, value, size, color)
    }

    private fun divider(parent: LinearLayout) {
        val line = View(this)
        line.setBackgroundColor(CampusUi.BG)
        val p = LinearLayout.LayoutParams(-1, dp(1f))
        p.leftMargin = dp(58f)
        p.rightMargin = dp(12f)
        parent.addView(line, p)
    }

    private fun homeVisible(): Boolean {
        return visible && runtime.isConfigured && navigation.home() && modalDepth == 0
    }

    private fun suspendHome() {
        runtime.setForegroundDemand(false)
        main.removeCallbacks(tick)
        shownRevision = -1
        if (codes != null) codes!!.clear()
    }

    private fun resumeHome() {
        suspendHome()
        fees.refresh(false)
        if (homeVisible()) {
            runtime.setForegroundDemand(true)
            main.post(tick)
        }
    }

    fun sheet(title: String?, detail: String?): CampusSheet {
        val holder = arrayOf<CampusSheet?>(null)
        val s = CampusSheet(this, title, detail, Runnable {
            modalDepth++
            suspendHome()
        }, Runnable {
            sheets.remove(holder[0])
            modalDepth = max(0, modalDepth - 1)
            resumeHome()
            refreshNativePluginPage()
        })
        holder[0] = s
        sheets.add(s)
        return s
    }

    private fun error(title: String?, detail: String?, retry: Runnable?, warning: Boolean = false) {
        val s = sheet(title, detail)
        if (warning) s.setWarning(true)
        s.action("关闭", false, false, Runnable { s.dismiss() })
        if (retry != null) s.action("重试", true, false, Runnable {
            s.dismiss()
            retry.run()
        })
        s.show()
    }

    private fun confirm(title: String?, detail: String?, label: String?, action: Runnable) {
        val s = sheet(title, detail)
        s.action("取消", false, false, Runnable { s.dismiss() })
        s.action(label, true, true, Runnable {
            s.dismiss()
            action.run()
        })
        s.show()
    }

    private fun refresh() {
        if (!runtime.isConfigured) {
            account()
            return
        }
        runtime.refresh("phone")
    }

    private fun showCurrentStatus() {
        if (!runtime.isConfigured) {
            account()
            return
        }
        val now = runtime.current()
        if (now.ready) {
            if (shownRevision < 0) error(
                "付款码未能显示",
                "请刷新后重试。",
                Runnable { this.refresh() })
            return
        }
        error("暂时无法显示付款码", now.status, Runnable { runtime.refresh("phone-retry") })
    }

    private fun plugins(source: View? = null) {
        if (!runtime.isConfigured) return
        pluginManagement?.let {
            if (navigation.top(it)) drawPluginManagement(it)
            return
        }
        val page = CampusFeePages.Screen(this, navigation, "插件管理")
        drawPluginManagement(page)
        if (navigation.push(page, source, Runnable { pluginManagement = null })) pluginManagement = page
    }

    private fun drawPluginManagement(page: CampusFeePages.Screen) {
        while (page.body.childCount > 1) page.body.removeViewAt(1)
        val local = LocalPlugins.get(this)
        val installed = section(page.body, "已安装")
        if (local.list().isEmpty()) reviewLine(installed, "暂无插件", "拿到插件文件后，可以从这里导入。")
        for (plugin in local.list()) {
            val state = when {
                plugin.legacy() -> "需更新"
                local.dependencyIssues(plugin).isNotEmpty() -> "依赖不可用"
                local.enabled(plugin.id) -> "已启用"
                else -> "已停用"
            }
            settingRow(installed, "wallet", plugin.name, "${plugin.version} · $state", false,
                Runnable {}, { source -> managePlugin(plugin, source) })
        }
        val add = section(page.body, "获取插件")
        settingRow(add, "add", "从文件导入", "安装或更新 .hfp 插件", false, Runnable {
            val select = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            try {
                startActivityForResult(select, IMPORT_PLUGIN)
            } catch (_: RuntimeException) {
                error("无法选择文件", "系统文件选择器未就绪。", null)
            }
        })
        val registry = PluginRegistry.get(this)
        val readers = registry.readers()
        if (readers.isNotEmpty()) {
            val external = section(page.body, "外部扩展")
            for (reader in readers) {
                settingRow(external, "lock", reader.label, if (registry.granted(reader)) "已授权" else "待授权", false,
                    Runnable {}, { source -> manageReader(registry, reader, source) })
            }
        }
    }

    private fun manageReader(registry: PluginRegistry, reader: PluginRegistry.Reader, source: View) {
        val page = CampusFeePages.Screen(this, navigation, reader.label)
        val details = section(page.body, "访问权限")
        val granted = registry.granted(reader)
        reviewLine(details, "应用", reader.packageName)
        reviewLine(details, "签名 SHA-256", reader.fingerprint.chunked(16).joinToString("\n"))
        reviewLine(details, "付款码", "允许在授权期间读取插件声明的付款码格式。")
        settingRow(details, "lock", if (granted) "撤销访问" else "允许访问",
            if (granted) "停止共享付款码" else "仅向此签名的应用共享", granted, Runnable {
                try {
                    if (granted) {
                        registry.revoke(reader.packageName)
                        runtime.setPluginVisible(reader.packageName, false)
                    } else registry.grant(reader)
                    contentResolver.notifyChange(PaymentCodeProvider.TEXT, null)
                    contentResolver.notifyChange(PaymentCodeProvider.QR, null)
                    navigation.pop()
                } catch (_: SecurityException) {
                    error("插件已变化", "请重新核对插件签名和权限。", null)
                }
            })
        navigation.push(page, source, Runnable {})
    }

    private fun managePlugin(plugin: PluginBundle, source: View) {
        val local = LocalPlugins.get(this)
        val page = CampusFeePages.Screen(this, navigation, plugin.name)
        val info = section(page.body, "插件信息")
        reviewLine(info, plugin.id, "版本 ${plugin.version}")
        val issues = local.dependencyIssues(plugin)
        if (issues.isNotEmpty()) reviewLine(info, "依赖不可用", issues.joinToString("\n"), warning = true)
        if (plugin.community?.campusRecharge == true) reviewRechargeAccess(info)
        if (plugin.backgroundLogin) reviewLine(info, "后台登录", backgroundLoginNotice(plugin))
        val actions = section(page.body, "运行设置")
        settingRow(actions, "wallet", "打开插件", "进入插件页面", false, Runnable {}, { entry ->
            if (local.active(plugin)) runPlugin(plugin, "page", source = entry)
        })
        val open = actions.getChildAt(actions.childCount - 1)
        open.visibility = if (local.active(plugin)) View.VISIBLE else View.GONE
        if (!plugin.legacy()) {
            val toggle = switchRow(actions, "启用插件", "立即更新运行状态", local.enabled(plugin.id))
            toggle.setOnCheckedChangeListener { _, checked ->
                try {
                    local.setEnabled(plugin.id, checked)
                    pluginChanged(plugin.id)
                    open.visibility = if (local.active(plugin)) View.VISIBLE else View.GONE
                } catch (problem: IOException) {
                    toggle.setOnCheckedChangeListener(null)
                    toggle.isChecked = local.enabled(plugin.id)
                    toggle.isEnabled = false
                    error("无法更新插件状态", problem.message, null)
                }
            }
        }
        settingRow(actions, "close", "卸载插件", "移除应用内插件包", true, Runnable {
            confirm("卸载插件？", plugin.name + " 将停止运行。", "卸载", Runnable {
                try {
                    local.remove(plugin.id)
                    pluginChanged(plugin.id)
                    navigation.pop()
                } catch (problem: IOException) {
                    error("无法卸载插件", problem.message, null)
                }
            })
        })
        navigation.push(page, source, Runnable {})
    }

    private fun pluginChanged(id: String?) {
        pluginRequest++
        if (id != null) CommunityJobs.cancelDependants(this, id)
        NativePlugins.changed(this, id)
        syncLoginPolicy()
        if (backgroundLoginPlugins.isNotEmpty() && runtime.isConfigured && !runtime.isRemembered) {
            runtime.setRememberPassword(true, SaveCompletion { saved, message ->
                if (saved) PluginForegroundService.restore(this, false)
                else if (visible) error("未能启用后台登录", message, null)
            })
        }
        refreshShortcuts()
        fees.extensionsChanged()
        PluginForegroundService.Companion.restore(this, false)
        render(runtime.current())
    }

    private fun reviewPlugin(plugin: PluginBundle) {
        if (isDestroyed() || isFinishing()) return
        if (LocalPlugins.builtIn(plugin.id)) {
            error("这项功能已内置", if (plugin.id == BuiltInBand.ID) "在「设置 → 小米手环」里管理连接。"
                else "在费用详情页点加号充值。", null)
            return
        }
        val local: LocalPlugins = LocalPlugins.Companion.get(this)
        if (plugin.legacy()) {
            error("插件需要更新", "请导入支持当前宿主的新版插件包。", Runnable { this.plugins() })
            return
        }
        val old = local.find(plugin.id)
        try {
            plugin.checkUpdate(old)
        } catch (invalid: SecurityException) {
            error("无法更新插件", invalid.message, Runnable { this.plugins() })
            return
        }
        val review = CampusFeePages.Screen(this, navigation, if (old == null) "安装插件" else "更新插件")
        val body = review.body
        val identity = section(body, "插件")
        reviewLine(identity, plugin.name, "版本 " + plugin.version)
        val issues = local.dependencyIssues(plugin)
        val spec = plugin.community
        if (issues.isNotEmpty() || spec?.dependencies?.isNotEmpty() == true) {
            val card = section(body, "依赖")
            if (issues.isEmpty()) spec?.dependencies?.keys?.forEach { id ->
                if (CampusRecharge.provides(plugin, id, spec.dependencies.getValue(id))) {
                    reviewLine(card, "校园费用充值", "由主应用提供。每条自动充值规则都需要你单独确认。")
                    return@forEach
                }
                val dependency = local.find(id)
                val invokesRecharge = spec.dependencies[id]?.methods?.any {
                    it in dependency?.community?.campusExports.orEmpty()
                } == true && dependency?.community?.spend != null
                reviewLine(card, dependency?.name ?: id, if (invokesRecharge)
                    "已安装 · 可调用此插件的校园服务；提交操作需要单独授权" else "已安装并通过校验")
            } else issues.forEach { reviewLine(card, "无法安装", it, warning = true) }
        }
        val readText = plugin.canRead("text")
        val readQr = plugin.canRead("qr")
        if (plugin.isNative || readText || readQr || spec?.profile?.isNotEmpty() == true) {
            val card = section(body, "访问资料")
            if (plugin.device != null) reviewLine(card, "设备连接", "使用主应用内置的设备连接与显示服务")
            else if (plugin.isNative) reviewLine(card, "原生扩展", "以主应用的权限运行已签名的 Android 代码")
            if (readText || readQr) {
                val formats = listOfNotNull("付款数字串".takeIf { readText }, "二维码".takeIf { readQr })
                reviewLine(card, formats.joinToString("、"), "在你点击读码时提供当前快照")
            }
            if (spec?.profile?.isNotEmpty() == true) {
                reviewLine(card, "应用已保存的资料",
                    spec.profile.mapNotNull(CampusFeeProfile.labels::get).joinToString("、"))
            }
        }
        if (spec != null) {
            if (spec.campusRecharge) reviewRechargeAccess(section(body, "校园服务"))
            if (spec.origins.isNotEmpty() || spec.links.isNotEmpty() || spec.spend != null) {
                val card = section(body, "网络访问")
                if (spec.origins.isNotEmpty()) reviewLine(card, "联网范围", spec.origins.joinToString("\n"))
                if (spec.campusSession) reviewLine(card, "校园卡会话", "在批准的校园服务上代为验证身份")
                if (spec.origins.any { it.startsWith("http:") }) {
                    reviewLine(card, "校园服务使用 HTTP", "网络中的第三方可能读取或修改请求", warning = true)
                }
                if (spec.links.isNotEmpty()) reviewLine(card, "打开外部链接", spec.links.joinToString("\n"))
                if (spec.spend != null) {
                    val items = CampusFees.Kind.entries.filter { it.key in spec.spend.services }
                        .joinToString("、") { it.title }
                    reviewLine(card, "提交校园服务操作", "$items · 涉及账户资金，需要另行授权")
                }
            }
            if (spec.storage || spec.secrets.isNotEmpty() || spec.timer) {
                val card = section(body, "本机与后台")
                if (spec.storage) reviewLine(card, "保存配置", "数据归此插件所有")
                if (spec.secrets.isNotEmpty()) reviewLine(card, "加密保存密码", "插件只能读取是否已保存")
                if (spec.timer) reviewLine(card, "定时运行", "需要在插件页面单独启用")
            }
        }
        if (plugin.backgroundLogin) {
            reviewLine(section(body, "后台登录"), "此插件要求自动登录", backgroundLoginNotice(plugin))
        }
        val publisher = section(body, "发布者")
        val proof = column().apply { visibility = View.GONE }
        settingRow(publisher, "lock", "核对签名", "查看插件标识和证书指纹", false, Runnable {
            proof.visibility = if (proof.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        reviewLine(proof, plugin.id, plugin.signer.chunked(16).joinToString("\n"))
        publisher.addView(proof)
        var installed = false
        val action = CampusUi.button(this, if (issues.isNotEmpty()) "返回" else if (old == null) "安装并启用" else "授权并更新", true, false, Runnable {
            if (issues.isNotEmpty()) {
                navigation.pop()
                return@Runnable
            }
            try {
                local.install(plugin)
            } catch (invalid: Exception) {
                if (local.find(plugin.id) == plugin) pluginChanged(plugin.id)
                val reason = local.dependencyIssues(plugin).joinToString("\n")
                error("插件安装失败", reason.ifBlank { "请检查包版本和手机可用空间。" }, null)
                return@Runnable
            }
            pluginChanged(plugin.id)
            installed = true
            navigation.pop()
        })
        body.addView(action, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22f) })
        navigation.push(review, null, Runnable { if (installed && pluginManagement == null) main.post { plugins() } })
    }

    private fun reviewRechargeAccess(parent: LinearLayout) {
        reviewLine(parent, "校园费用充值", "查询余额，并按你确认的规则充值。每条自动充值规则都需要单独授权。")
    }

    private fun reviewLine(parent: LinearLayout, title: String, summary: String, warning: Boolean = false) {
        if (parent.childCount > 0) divider(parent)
        val content = column().apply { setPadding(dp(14f), dp(12f), dp(14f), dp(12f)) }
        content.addView(text(title, 15, if (warning) CampusUi.ERROR else CampusUi.INK).also(CampusUi::medium))
        content.addView(text(summary, 13, CampusUi.MUTED).apply {
            setPadding(0, dp(5f), 0, 0)
            setLineSpacing(dp(3f).toFloat(), 1f)
        })
        parent.addView(content)
    }

    internal fun openFeePlugin(plugin: PluginBundle, kind: String, campus: String, source: View?) {
        if (plugin.feeShortcut(kind) == null) return
        runPlugin(plugin, "fee.page", "", FeePluginEntries.context(kind, campus), source)
    }

    internal fun openFeeExtra(plugin: PluginBundle, kind: String, campus: String, id: String, source: View) {
        if (plugin.feeExtras(kind, campus).none { it.id == id }) return
        runPlugin(plugin, "fee.extra", fee = FeePluginEntries.context(kind, campus).put("extra", id), source = source)
    }

    internal fun showCardTopUp() {
        val popup = sheet("充值校园卡", "在支付宝的校园一卡通页面选择合肥工业大学并完成充值。")
        popup.action("取消", false, false, Runnable { popup.dismiss() })
        popup.action("去支付宝", true, false, Runnable {
            popup.dismiss()
            try {
                openAlipay("https://ur.alipay.com/_4kQhV32216tp7bzlDc3E1k")
            } catch (_: android.content.ActivityNotFoundException) {
                error("无法打开支付宝", "请先安装支付宝客户端。", null)
            }
        })
        popup.show()
    }

    private fun openAlipay(url: String) {
        val deepLink = Uri.Builder().scheme("alipays").authority("platformapi").appendPath("startapp")
            .appendQueryParameter("appId", "20000067").appendQueryParameter("url", url).build()
        startActivity(Intent(Intent.ACTION_VIEW, deepLink).setPackage("com.eg.android.AlipayGphone"))
    }

    private fun openPluginLink(url: String, openWith: String) {
        val alipayLink = url.matches(Regex("""https://ur\.alipay\.com/[A-Za-z0-9_-]{1,120}"""))
        require(openWith.isEmpty() || openWith == "alipay" && alipayLink) { "不支持此外部应用链接" }
        try {
            if (alipayLink) openAlipay(url)
            else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (missing: android.content.ActivityNotFoundException) {
            throw IllegalStateException(if (alipayLink) "请先安装支付宝" else "找不到可以打开此链接的应用", missing)
        }
    }

    private fun refreshPluginPage(page: View) {
        if (!visible || modalDepth != 0) return
        val marker = page.tag as? PluginPage ?: return
        val action = marker.refresh ?: return
        val plugin = LocalPlugins.get(this).find(marker.id) ?: return
        runPlugin(plugin, action, fee = marker.fee, replacePage = page)
    }

    private fun refreshNativePluginPage() {
        val page = navigation.current()
        val marker = page.tag as? PluginPage ?: return
        if (LocalPlugins.get(this).find(marker.id)?.isNative == true) {
            main.post { if (navigation.top(page)) refreshPluginPage(page) }
        }
    }

    private fun runPlugin(
        plugin: PluginBundle,
        action: String?,
        read: String? = "",
        fee: JSONObject? = null,
        source: View? = null,
        input: JSONObject? = null,
        passwordInputs: Map<String, String> = emptyMap(),
        approval: CommunityBudget.Approval? = null,
        retained: CampusSheet? = null,
        replacePage: View? = null
    ) {
        val local: LocalPlugins = LocalPlugins.Companion.get(this)
        if (!local.active(plugin)) return
        if (plugin.isNative && action == "background") {
            syncLoginPolicy()
            if (backgroundLoginPlugins.isNotEmpty()) showBackgroundLogin(source)
            else error("后台登录尚未启用", "开启插件服务后，可在账户管理里设置后台保障。", null)
            return
        }
        val request = ++pluginRequest
        val extension = NativePlugins.get(this, plugin)
        if (plugin.isNative && extension == null) {
            error("插件无法运行", "原生接口或代码版本不兼容。", null)
            return
        }
        val event = JSONObject()
        try {
            event.put("action", action)
            if (fee != null) event.put("fee", fee)
            if (input != null && plugin.community != null) event.put("input", input)
            if (extension != null) event.put("state", extension.state())
        } catch (problem: JSONException) {
            error("插件无法运行", "无法读取插件状态。", null)
            return
        } catch (problem: RuntimeException) {
            error("插件无法运行", "无法读取插件状态。", null)
            return
        } catch (problem: LinkageError) {
            error("插件无法运行", "无法读取插件状态。", null)
            return
        }
        val owner: View? = if (!plugin.isNative || fee != null) navigation.current() else null
        fun current(): Boolean = if (retained == null) request == pluginRequest
            else !isDestroyed && retained.isShowing
        val progressReady = if (retained == null) 0L else android.os.SystemClock.uptimeMillis() + 350L
        PluginScripts.run(
            this,
            plugin,
            event,
            if (fee == null) read else "",
            PluginScripts.Result { value: JSONObject?, problem: String? ->
                main.postAtTime(
                    Runnable {
                        if ((!visible && retained == null) || isDestroyed() || !current()) return@Runnable
                        if (!local.active(plugin)) {
                            retained?.dismiss()
                            return@Runnable
                        }
                        if (owner != null && !navigation.top(owner)) {
                            retained?.dismiss()
                            return@Runnable
                        }
                        if (value == null) {
                            if (replacePage != null) return@Runnable
                            val title = if (approval == null) "插件操作未完成" else "提交结果未能确认"
                            if (retained == null) error(title, problem, null, warning = true)
                            else showPluginDialog(plugin, JSONObject().put("title", title).put("detail", problem)
                                .put("warning", true), fee, retained)
                            return@Runnable
                        }
                        val payment = event.optJSONObject("payment")
                        val readRevision =
                            if (payment == null) -1 else payment.optLong("revision", -1)
                        val snapshot = runtime.current()
                        if (readRevision >= 0 && (!snapshot.ready || readRevision != snapshot.revision)) {
                            error("付款码已更新", "请重新打开插件读取当前付款码。", null)
                            return@Runnable
                        }
                        if (extension != null && action !in setOf("page", "fee.page") && value.has("command")) {
                            try {
                                extension.command(value.optString("command"), pluginUi(plugin))
                                if (modalDepth == 0) refreshNativePluginPage()
                            } catch (_: RuntimeException) {
                                error("插件操作失败", "请检查插件版本和系统设置。", null)
                            } catch (_: LinkageError) {
                                error("插件操作失败", "请检查插件版本和系统设置。", null)
                            }
                            return@Runnable
                        }
                        val dialog = value.optJSONObject("dialog")
                        if (replacePage != null && dialog != null) return@Runnable
                        if (dialog != null && plugin.community != null && readRevision < 0) {
                            showPluginDialog(plugin, dialog, fee, retained)
                            return@Runnable
                        }
                        retained?.dismiss()
                        showPluginPage(plugin, value, fee, source, read, payment, readRevision, snapshot, replacePage)
                    }, progressReady)
            }, approval = approval, passwordInputs = passwordInputs,
            mode = if (replacePage == null) CommunityRuntime.Mode.INTERACTIVE else CommunityRuntime.Mode.VIEW,
            alive = ::current)
    }

    private fun showPluginPage(
        plugin: PluginBundle, result: JSONObject, fee: JSONObject?, source: View?,
        read: String?, payment: JSONObject?, readRevision: Long, snapshot: CardSnapshot, replacePage: View? = null
    ) {
        val title = pluginText(result, "title", plugin.name)
        val page = CampusFeePages.Screen(this, navigation, title)
        val refresh = result.optString("refreshOnReturn").takeIf {
            (plugin.isNative || plugin.community != null) && readRevision < 0 &&
                CommunitySpec.KEY.matches(it) && it != "timer"
        }
        val marker = PluginPage(plugin.id, title, refresh, fee)
        page.tag = marker
        val description = pluginText(result, "detail", "")
        if (description.isNotBlank()) page.body.addView(text(description, 14, CampusUi.MUTED).apply {
            setPadding(dp(8f), dp(12f), dp(8f), 0)
            setLineSpacing(dp(3f).toFloat(), 1f)
        })
        result.optJSONObject("status")?.let { status ->
            val label = pluginText(status, "title", "")
            if (label.isNotBlank()) {
                val summary = pluginText(status, "detail", "")
                reviewLine(section(page.body, "状态"), label, summary, warning = status.optBoolean("warning"))
            }
        }
        val fields = result.optJSONArray("fields")
        val form = if (plugin.community != null && readRevision < 0) {
            val body = if (fields != null && fields.length() > 0) section(page.body, "配置").apply {
                setPadding(dp(16f), dp(10f), dp(16f), dp(16f))
            } else page.body
            CommunityForm(body, fields)
        } else null
        val saveEvent = result.optString("autosave").takeIf {
            plugin.community?.storage == true && CommunitySpec.KEY.matches(it) && it !in setOf("page", "fee.page", "fee.extra", "timer")
        }
        val autosave = if (form == null || saveEvent == null || fields == null) null
            else CommunityAutosave(main, form) { input, complete ->
                val event = JSONObject().put("action", saveEvent).put("input", input)
                if (fee != null) event.put("fee", fee)
                val local = LocalPlugins.get(this)
                PluginScripts.run(this, plugin, event, null, PluginScripts.Result { value, problem ->
                    val failure = if (value == null) problem.orEmpty().ifBlank { "自动保存未完成" }
                        else value.optJSONObject("output")?.optString("error")?.take(160)?.takeIf(String::isNotBlank)
                    main.post { complete(failure) }
                }, mode = CommunityRuntime.Mode.AUTOSAVE, alive = { local.active(plugin) })
            }
        if (readRevision < 0) {
            val rows = result.optJSONArray("rows")
            var actions: LinearLayout? = null
            var group = ""
            for (i in 0 until minOf(rows?.length() ?: 0, 8)) {
                val item = rows?.optJSONObject(i) ?: continue
                val label = pluginText(item, "title", "")
                val action = item.optString("event")
                if (label.isBlank() || !action.matches("[a-z][a-z0-9.-]{0,39}".toRegex()) ||
                    action in setOf("page", "fee.page", "fee.extra", "timer")) continue
                val sectionTitle = pluginText(item, "section", "操作")
                if (actions == null || sectionTitle != group) {
                    actions = section(page.body, sectionTitle)
                    group = sectionTitle
                } else divider(actions)
                val icon = item.optString("icon").takeIf {
                    it in setOf("band", "display", "refresh", "power", "lock", "wallet", "settings", "info", "network", "water", "account")
                } ?: "wallet"
                val format = item.optString("read")
                val launch: (View) -> Unit = { anchor ->
                    if (form == null) runPlugin(plugin, action, format, fee, anchor)
                    else if (autosave == null) communityAction(plugin, page, anchor, form, item, action, format, fee)
                    else autosave.flush { valid ->
                        if (valid || item.optBoolean("saveSecret") || item.has("openPlugin") || item.optString("job") == "stop") {
                            communityAction(plugin, page, anchor, form, item, action, format, fee)
                        }
                    }
                }
                if (item.optBoolean("primary")) {
                    actions.addView(CampusUi.button(this, label, true, false, Runnable {}).apply {
                        setOnClickListener(launch)
                    }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(8f), dp(8f), dp(8f), dp(8f)) })
                } else settingRow(actions, icon, label, pluginText(item, "detail", ""), item.optBoolean("danger"),
                    Runnable {}, launch)
            }
        } else if (read == "qr" && result.optBoolean("displayPayment") && payment != null) {
            val png = Base64.decode(payment.optString("pngBase64"), Base64.DEFAULT)
            val qr = ImageView(this).apply {
                setImageBitmap(BitmapFactory.decodeByteArray(png, 0, png.size))
                contentDescription = "付款二维码"
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            val card = section(page.body, "付款码")
            card.addView(qr, LinearLayout.LayoutParams(-1, dp(232f)))
        }
        val secure = readRevision >= 0 || form?.sensitive == true
        if (secure && securePluginPages++ == 0) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val expire = Runnable {
            page.body.visibility = View.INVISIBLE
            if (navigation.top(page)) navigation.pop()
        }
        val listener = if (readRevision >= 0) CampusRuntime.Listener { current ->
            if (!current.ready || current.revision != readRevision ||
                !LocalPlugins.get(this).active(plugin)) expire.run()
        } else null
        val cleanup = Runnable {
            pluginRequest++
            autosave?.close()
            if (secure && --securePluginPages == 0) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            listener?.let(runtime::removeListener)
            main.removeCallbacks(expire)
        }
        pluginPageCleanups.add(cleanup)
        val dispose = Runnable { if (pluginPageCleanups.remove(cleanup)) cleanup.run() }
        val previous = navigation.current().tag as? PluginPage
        val replace = readRevision < 0 && previous?.id == plugin.id &&
            (previous.title == title || result.optBoolean("replace"))
        val shown = if (replacePage != null) {
            val scroll = replacePage.scrollY
            (navigation.top(replacePage) && navigation.replace(page, dispose)).also { if (it) page.post { page.scrollTo(0, scroll) } }
        } else if (replace) navigation.replace(page, dispose) else navigation.push(page, source, dispose)
        if (!shown) {
            dispose.run()
        } else if (listener != null) {
            runtime.addListener(listener)
            main.postDelayed(expire, snapshot.remainingMs)
        }
    }

    private fun showPluginDialog(
        plugin: PluginBundle, result: JSONObject, fee: JSONObject?, existing: CampusSheet? = null, values: JSONObject? = null
    ) {
        val owner = navigation.current()
        val title = pluginText(result, "title", plugin.name)
        val detail = pluginText(result, "detail", "")
        val popup = existing ?: sheet(title, detail)
        existing?.replace(title, detail)
        popup.setWarning(result.optBoolean("warning"))
        val form = CommunityForm(popup.body, result.optJSONArray("fields"), values)
        if (form.sensitive) popup.dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val actions = result.optJSONArray("actions")
        var hasAction = false
        for (i in 0 until minOf(actions?.length() ?: 0, 2)) {
            val item = actions?.optJSONObject(i) ?: continue
            val event = item.optString("event")
            val label = pluginText(item, "title", "")
            if (label.isBlank() || !CommunitySpec.KEY.matches(event) ||
                event in setOf("page", "fee.page", "fee.extra", "timer")) continue
            hasAction = true
            popup.action(label, i == 0, false, Runnable {
                communityAction(plugin, owner, popup.body, form, item, event, "", fee, popup) { input ->
                    showPluginDialog(plugin, result, fee, popup, input)
                }
            })
        }
        if (!hasAction) popup.action("关闭", true, false, Runnable { popup.dismiss() })
        if (existing == null) popup.show()
    }

    private fun communityAction(
        plugin: PluginBundle, page: View, source: View, form: CommunityForm, item: JSONObject,
        action: String, read: String, fee: JSONObject?, popup: CampusSheet? = null,
        restoreDialog: ((JSONObject) -> Unit)? = null
    ) {
        val spec = requireNotNull(plugin.community)
        if (!navigation.top(page) || !LocalPlugins.get(this).active(plugin) || popup?.isShowing == false) return
        try {
            val input = form.values()
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(source.windowToken, 0)
            if (popup == null) page.requestFocus()
            fun submit(permit: CommunityBudget.Approval? = null) {
                val passwords = if (item.optBoolean("saveSecret")) form.takePasswords() else emptyMap()
                val progress = item.optJSONObject("progress")
                val retained = popup?.takeIf { permit != null || progress != null }
                if (retained == null) popup?.dismiss() else retained.replace(
                    progress?.let { pluginText(it, "title", "正在处理") } ?: "正在处理",
                    progress?.let { pluginText(it, "detail", "") }.orEmpty(), loading = true
                )
                runPlugin(plugin, action, read, fee, source, input, passwords, permit, retained)
            }
            when {
                item.has("link") -> {
                    val url = item.getString("link")
                    require(url in spec.links) { "插件未声明此外部链接" }
                    openPluginLink(url, item.optString("openWith"))
                    popup?.dismiss()
                }
                item.optBoolean("openSettings") -> {
                    popup?.dismiss()
                    runPlugin(plugin, "page", source = source)
                }
                item.has("openPlugin") -> {
                    val target = CommunityRuntime.dependency(this, plugin, item.getString("openPlugin"))
                    popup?.dismiss()
                    runPlugin(target, "page", source = source)
                }
                item.optString("job") == "stop" -> {
                    require(spec.timer)
                    val key = item.optString("jobKey")
                    require(key.isEmpty() || CommunitySpec.KEY.matches(key))
                    CommunityJobs.cancel(this, plugin.id, key)
                    syncLoginPolicy()
                    popup?.dismiss()
                    runPlugin(plugin, action, fee = fee, source = source)
                }
                item.optString("job") == "start" -> {
                    require(spec.timer)
                    val automatic = item.optBoolean("spend")
                    val key = item.optString("jobKey")
                    require(key.isEmpty() || CommunitySpec.KEY.matches(key))
                    val store = CommunityStore(this, plugin)
                    val tag = store.configurationTag(key)
                    val rule = if (key.isEmpty()) store.values() else store.values().optJSONObject(key)
                    val permit = if (automatic) {
                        requireNotNull(rule) { "请先保存规则" }
                        CommunityBudget.approval(runtime.feeProfileKey(), rule, rule.optJSONObject("target"))
                    } else null
                    val needsLogin = plugin.backgroundLogin || permit != null
                    val loginAccount = runtime.feeProfileKey().takeIf { needsLogin }
                    require(!needsLogin || runtime.isConfigured && !loginAccount.isNullOrBlank()) { "请先登录校园卡" }
                    val summary = if (permit == null) "允许此插件由系统定期运行。"
                    else "${permit.destination}，每次 ¥${permit.amount}。同一校园卡账户滚动 24 小时累计最多 ${CommunityBudget.MAX_LABEL}。"
                    val description = summary + if (needsLogin) {
                        "\n\n启用后会加密记住密码，允许此任务在后台恢复当前校园卡登录。"
                    } else ""
                    confirm("启用定时任务？", description, "启用", Runnable {
                        fun schedule() {
                            if (!navigation.top(page) || isDestroyed) return
                            try {
                                val local = LocalPlugins.get(this)
                                synchronized(local) {
                                    require(!needsLogin || tag == store.configurationTag(key) &&
                                        runtime.isConfigured && loginAccount == runtime.feeProfileKey() &&
                                        runtime.savedLoginAvailable()) { "规则或账户已变化，请重新确认" }
                                    CommunityJobs.start(this, plugin, permit, key, loginAccount)
                                }
                                syncLoginPolicy()
                                popup?.dismiss()
                                runPlugin(plugin, action, fee = fee, source = source)
                            } catch (failure: Exception) {
                                error("未能启用", failure.message, null)
                            }
                        }
                        if (!navigation.top(page)) return@Runnable
                        if (needsLogin && !runtime.savedLoginAvailable()) {
                            if (!runtime.isConfigured || runtime.feeProfileKey() != loginAccount) return@Runnable
                            runtime.setRememberPassword(true, SaveCompletion { ok, message ->
                                if (ok) schedule() else if (!isDestroyed) error("未能启用", message, null)
                            })
                        } else {
                            schedule()
                        }
                    })
                }
                item.optBoolean("reconcile") -> {
                    require(spec.spend != null)
                    val account = runtime.feeProfileKey()
                    require(account.isNotBlank() && CommunityBudget.pending(this, account))
                    confirm("已核对学校账单？", "请先核对上笔充值是否入账。解除暂停后，原订单仍计入 24 小时额度。",
                        "已核对", Runnable {
                            if (!navigation.top(page) || account != runtime.feeProfileKey() || !LocalPlugins.get(this).active(plugin)) return@Runnable
                            CommunityBudget.acknowledge(this, account)
                            popup?.dismiss()
                            runPlugin(plugin, action, fee = fee, source = source)
                        })
                }
                item.optBoolean("spend") -> {
                    val spending = requireNotNull(spec.spend)
                    val permit = CommunityBudget.approval(runtime.feeProfileKey(), input, item.optJSONObject("target"))
                    require(permit.service.key in spending.services && (fee == null || fee.optString("kind") == permit.service.key))
                    val confirmation = item.optJSONObject("confirmation") ?: JSONObject()
                    val title = pluginText(confirmation, "title", "确认提交")
                    val label = pluginText(confirmation, "action", "确认提交")
                    val notice = pluginText(confirmation, "detail", pluginText(item, "disclaimer", ""))
                    val description = "${permit.destination}\n从校园卡余额划款 ¥${permit.amount}" +
                        if (notice.isBlank()) "" else "\n\n$notice"
                    if (popup == null) confirm(title, description, label, Runnable {
                        if (navigation.top(page) && LocalPlugins.get(this).active(plugin)) submit(permit)
                    }) else {
                        popup.replace(title, description)
                        popup.action("返回修改", false, false, Runnable { restoreDialog?.invoke(input) })
                        popup.action(label, true, false, Runnable {
                            if (navigation.top(page) && popup.isShowing && LocalPlugins.get(this).active(plugin)) submit(permit)
                        })
                    }
                }
                else -> submit()
            }
        } catch (failure: Exception) {
            if (popup == null) error("插件操作未完成", failure.message, null, warning = true)
            else form.showError(failure.message.orEmpty().ifBlank { "请检查输入后重试" })
        }
    }

    private fun pluginUi(plugin: PluginBundle, pageOpen: () -> Boolean = { true }): Ui {
        return object : Ui {
            fun active(): Boolean {
                return LocalPlugins.Companion.get(this@MainActivity).active(plugin)
            }

            override fun activity(): Activity {
                return this@MainActivity
            }

            override fun sheet(title: String?, detail: String?): CampusSheet {
                return this@MainActivity.sheet(title, detail)
            }

            override fun detail(page: CampusSheet, label: String, value: String?) {
                this@MainActivity.detail(page, label, value)
            }

            override fun error(title: String?, detail: String?) {
                this@MainActivity.error(title, detail, null)
            }

            override fun confirm(
                title: String?,
                detail: String?,
                button: String?,
                action: Runnable
            ) {
                this@MainActivity.confirm(
                    title,
                    detail,
                    button,
                    Runnable { if (active()) action.run() })
            }

            override fun account() {
                this@MainActivity.account()
            }

            override fun permission(permission: String, reason: String?, granted: Runnable) {
                if (!active()) return
                if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                    granted.run()
                    return
                }
                val request = Runnable {
                    permissionGranted = Runnable { if (active()) granted.run() }
                    requestPermissions(arrayOf<String?>(permission), 30)
                }
                if (shouldShowRequestPermissionRationale(permission)) {
                    val rationale = sheet("需要系统权限", reason)
                    rationale.action("取消", false, false, Runnable { rationale.dismiss() })
                    rationale.action("继续", true, false, Runnable {
                        rationale.dismiss()
                        request.run()
                    })
                    rationale.show()
                } else request.run()
            }

            override fun startBackground(action: String?) {
                if (!active() || !runtime.isConfigured || !plugin.backgroundLogin) return
                val start = Runnable {
                    if (active() && pageOpen() && runtime.isConfigured) {
                        val enableBand = plugin.id == BuiltInBand.ID && action == "connect" && !BuiltInBand.enabled(this@MainActivity)
                        if (enableBand) BuiltInBand.enabled(this@MainActivity, true)
                        try {
                            PluginForegroundService.start(this@MainActivity, plugin, action)
                        } catch (_: RuntimeException) {
                            if (enableBand) BuiltInBand.enabled(this@MainActivity, false)
                            error(if (plugin.id == BuiltInBand.ID) "手环连接未启动" else "插件后台未启动",
                                "请检查系统权限和后台设置。")
                        }
                        if (enableBand) syncLoginPolicy()
                    }
                }
                if (runtime.isRemembered) start.run()
                else this@MainActivity.confirm("启用后台登录？",
                    "插件需要加密记住密码，才能在后台恢复校园卡登录。", "启用", Runnable {
                        if (active()) runtime.setRememberPassword(true, SaveCompletion { ok, message ->
                            if (ok) start.run() else error("未能启用后台登录", message)
                        })
                    })
            }
        }
    }

    override fun onRequestPermissionsResult(
        request: Int,
        permissions: Array<String?>,
        grants: IntArray
    ) {
        super.onRequestPermissionsResult(request, permissions, grants)
        if (request != 30) return
        val granted = permissionGranted
        permissionGranted = null
        if (granted != null && grants.size > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) granted.run()
    }

    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request == IMPORT_PLUGIN) {
            if (result != RESULT_OK || data == null || data.getData() == null) return
            val uri = data.getData()
            Thread(Runnable {
                try {
                    getContentResolver().openInputStream(uri!!).use { input ->
                        if (input == null) throw IOException("No plugin input stream")
                        val plugin: PluginBundle = PluginBundle.Companion.read(input)
                        main.post(Runnable { reviewPlugin(plugin) })
                    }
                } catch (invalid: Exception) {
                    main.post(Runnable {
                        if (!isDestroyed()) error(
                            "无法导入插件",
                            "插件格式、签名或权限校验失败。",
                            Runnable { this.plugins() })
                    })
                }
            }, "plugin-import").start()
            return
        }
        if (request != EXPORT_BAND || result != RESULT_OK) return
        val destination = data?.data ?: return
        Thread(Runnable {
            try {
                assets.open(BuiltInBand.INSTALLER).use { source ->
                    checkNotNull(contentResolver.openOutputStream(destination, "wt")).use { source.copyTo(it) }
                }
                main.post { if (!isDestroyed) android.widget.Toast.makeText(this, "手环安装包已保存", android.widget.Toast.LENGTH_SHORT).show() }
            } catch (unavailable: Exception) {
                main.post(Runnable {
                    if (!isDestroyed()) error(
                        "保存失败",
                        "请重新选择可写位置。",
                        Runnable { exportBandInstaller() })
                })
            }
        }, "band-export").start()
    }

    private fun settings(source: View?) {
        if (settingsOpen) return
        val page: CampusFeePages.Screen = CampusFeePages.Screen(this, navigation, "设置")
        val panel: LinearLayout = page.body
        val accounts = section(panel, "账户")
        settingRow(accounts, "account", "账户管理", "登录选项、付款账户与退出登录", false,
            Runnable {}, { source -> account(source) })
        settingRow(accounts, "settings", "费用查询设置", "选择校区、宿舍和洗浴账户", false,
            Runnable {}, { fees.showSettings(it) })
        settingRow(accounts, "lock", "充值设置", "管理校园卡支付密码与待核对订单", false,
            Runnable {}, { rechargeSettings(it) })
        val plugins = section(panel, "扩展")
        if (BuiltInBand.AVAILABLE) {
            settingRow(plugins, "band", "小米手环 · Beta", "管理连接、显示和手环安装包", false,
                Runnable {}, { bandSettings(it) })
        }
        settingRow(
            plugins,
            "settings",
            "插件管理",
            "安装与管理插件",
            false,
            Runnable {}, { source -> plugins(source) })
        settingsShortcuts = column()
        panel.addView(settingsShortcuts)
        renderShortcuts(settingsShortcuts, false)
        val privacy = section(panel, "隐私与数据")
        val balancePrivacy = row().apply { setPadding(dp(16f), dp(10f), dp(12f), dp(10f)) }
        val balanceLabel = text("默认隐藏校园费用余额", 15, CampusUi.INK)
        balancePrivacy.addView(balanceLabel, LinearLayout.LayoutParams(0, -2, 1f))
        balancePrivacy.addView(CampusUi.toggle(this, "默认隐藏校园费用余额", fees.hideByDefault()).apply {
            setOnCheckedChangeListener { _, hidden -> fees.setDefaultHidden(hidden) }
        })
        privacy.addView(balancePrivacy)
        settingRow(privacy, "info", getString(R.string.about_privacy_title), getString(R.string.about_privacy_summary),
            false, Runnable {}, ::showAbout)
        val app = section(panel, "应用")
        updateSettingsSummary = settingRow(app, "refresh", "应用更新", appUpdatePage.summary,
            false, Runnable {}, appUpdatePage::show)
        var version: String? = ""
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName
        } catch (ignored: Exception) {
        }
        val about = text(getString(R.string.app_name) + "  " + version, 12, CampusUi.MUTED)
        about.setGravity(Gravity.CENTER)
        about.setPadding(0, dp(28f), 0, 0)
        panel.addView(about)
        settingsOpen = navigation.push(page, source, Runnable {
            settingsOpen = false
            settingsShortcuts = null
            updateSettingsSummary = null
        })
    }

    private fun showAbout(source: View) {
        val page = CampusFeePages.Screen(this, navigation, getString(R.string.about_privacy_title))
        val identity = column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(20f), 0, 0)
        }
        identity.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_brand)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(56f), dp(56f)))
        identity.addView(text(getString(R.string.app_name), 22, CampusUi.INK).also(CampusUi::medium).apply {
            setPadding(0, dp(10f), 0, dp(4f))
        })
        identity.addView(text(getString(R.string.about_version, BuildConfig.VERSION_NAME), 13, CampusUi.MUTED))
        page.body.addView(identity)

        val disclaimer = section(page.body, getString(R.string.about_disclaimer_title))
        disclaimer.addView(text(getString(R.string.about_disclaimer), 14, CampusUi.INK).apply {
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            setLineSpacing(dp(3f).toFloat(), 1f)
        })
        val privacy = section(page.body, getString(R.string.about_privacy_section))
        reviewLine(privacy, getString(R.string.about_accounts_title), getString(R.string.about_accounts))
        reviewLine(privacy, getString(R.string.about_passwords_title), getString(R.string.about_passwords))
        reviewLine(privacy, getString(R.string.about_sharing_title), getString(R.string.about_sharing))
        if (DailyUsage.available) {
            reviewLine(privacy, getString(R.string.about_usage_title), getString(R.string.about_usage))
        }
        navigation.push(page, source, Runnable {})
    }

    internal fun showRecharge(entry: String, title: String, configure: () -> Unit) {
        val account = runtime.feeProfileKey()
        val revision = runtime.configurationRevision()
        val recharge = CampusRecharge(applicationContext)
        val target = recharge.target(account, entry)
        if (target == null) {
            val page = sheet("先设置充值账户", "在费用查询设置里选好宿舍或填写洗浴手机号，再回来充值。")
            page.action("去设置", true, false, Runnable { page.dismiss(); configure() })
            page.show()
            return
        }
        val pending = CommunityBudget.pending(this, account)
        if (pending || !recharge.hasPassword(account)) {
            val page = sheet(if (pending) "有一笔充值待核对" else "先保存支付密码",
                if (pending) CampusRecharge.PENDING else "在充值设置里保存六位支付密码，再回来充值。")
            page.setWarning(pending)
            page.action("打开充值设置", true, false, Runnable { page.dismiss(); rechargeSettings(null) })
            page.show()
            return
        }
        val page = sheet("$title · 充值", "充值金额从校园卡余额扣除。")
        var submitting = false
        fun showAmount(value: String) {
            val night = target.service == CommunityBudget.Service.ELECTRIC && !CampusRecharge.electricWindow()
            page.replace("$title · 充值", "充值金额从校园卡余额扣除。")
            if (night) page.paragraph("现在可以提交电费充值，可能要等到早上 6 点后到账。", CampusUi.MUTED)
            val amount = input("输入充值金额", false).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                imeOptions = EditorInfo.IME_ACTION_DONE
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                background = null
                setPadding(dp(10f), 0, 0, 0)
                setTextSize(24f)
                CampusUi.medium(this)
                setText(value)
                setSelection(length())
            }
            val amountRow = row().apply {
                gravity = Gravity.CENTER_VERTICAL
                background = CampusUi.shape(this@MainActivity, CampusUi.BG, 14f)
                setPadding(dp(16f), dp(4f), dp(16f), dp(4f))
                val yuan = text("¥", 22, CampusUi.MUTED).also(CampusUi::medium)
                yuan.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                addView(yuan)
                addView(amount, LinearLayout.LayoutParams(0, -2, 1f))
            }
            page.field(amountRow)
            fun confirmAmount() {
                val permit = try {
                    CommunityBudget.approval(account, JSONObject().put("amount", amount.text.toString().trim()), target.json())
                } catch (invalid: IllegalArgumentException) {
                    Toast.makeText(this, invalid.message, Toast.LENGTH_SHORT).show()
                    return
                }
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(amount.windowToken, 0)
                page.replace("确认充值", "${permit.destination}\n从校园卡余额扣除 ¥${permit.amount}\n\n" +
                    "请核对项目和金额。到账以学校账单为准，结果未确认时，请先查账再决定是否重新充值。")
                page.action("修改金额", false, false, Runnable { showAmount(permit.amount) })
                page.action("确认充值", true, false, Runnable {
                    if (submitting) return@Runnable
                    submitting = true
                    page.replace("正在充值", "正在等待学校返回结果，请稍候。", loading = true)
                    Thread({
                        val receipt = try {
                            recharge.pay(entry, permit, revision) { !isDestroyed }
                        } catch (limit: CommunityBudget.LimitReached) {
                            CampusRecharge.Receipt(limit.message.orEmpty())
                        } catch (_: Exception) {
                            if (CommunityBudget.pending(applicationContext, account)) {
                                CampusRecharge.Receipt(CampusRecharge.PENDING, unknown = true)
                            } else CampusRecharge.Receipt("暂时无法完成充值。请核对账户、余额和支付密码后再试。")
                        }
                        main.post {
                            if (isDestroyed) return@post
                            if (receipt.paid) fees.refresh(true)
                            if (!page.isShowing) return@post
                            page.replace(when {
                                receipt.paid -> "充值成功"
                                receipt.unknown -> "充值待确认"
                                else -> "充值未完成"
                            }, if (receipt.paid) "学校已确认这笔充值。余额可能需要一会儿才会更新。" else receipt.status)
                            page.setWarning(!receipt.paid)
                            receipt.orderId?.let { page.paragraph("订单号 $it", CampusUi.MUTED) }
                            page.action("完成", true, false, Runnable { page.dismiss() })
                        }
                    }, "campus-recharge").start()
                })
            }
            amount.setOnEditorActionListener { _, action, event ->
                if (action == EditorInfo.IME_ACTION_DONE ||
                    event?.let { it.keyCode == KeyEvent.KEYCODE_ENTER && it.action == KeyEvent.ACTION_UP } == true) {
                    confirmAmount()
                    true
                } else false
            }
            page.action("继续", true, false, Runnable { confirmAmount() })
        }
        showAmount("")
        page.show()
    }

    private fun rechargeSettings(source: View?) {
        val page = CampusFeePages.Screen(this, navigation, "充值设置")
        val account = runtime.feeProfileKey()
        val recharge = CampusRecharge(applicationContext)
        fun draw() {
            page.body.removeAllViews()
            val security = section(page.body, "支付密码")
            reviewLine(security, if (recharge.hasPassword(account)) "已保存支付密码" else "尚未保存支付密码",
                "六位支付密码加密保存在本机，手动充值和自动充值共用。")
            settingRow(security, "lock", if (recharge.hasPassword(account)) "更换支付密码" else "保存支付密码",
                "修改后需要重新启用自动充值规则", false, Runnable {
                    val dialog = sheet("保存支付密码", "输入校园卡的六位支付密码。")
                    dialog.dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    val pin = input("六位支付密码", true).apply {
                        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                        filters = arrayOf(android.text.InputFilter.LengthFilter(6))
                        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                    }
                    dialog.field(pin)
                    dialog.onDismiss(Runnable { pin.text.clear() })
                    dialog.action("保存", true, false, Runnable {
                        try {
                            recharge.setPassword(account, pin.text.toString())
                            pin.text.clear()
                            dialog.dismiss()
                            draw()
                        } catch (_: Exception) {
                            pin.error = "请确认当前账户已登录，并输入六位数字。"
                        }
                    })
                    dialog.show()
                })
            if (recharge.hasPassword(account)) {
                settingRow(security, "close", "删除支付密码", "删除后会停止已启用的自动充值规则", true, Runnable {
                    confirm("删除支付密码？", "下次充值前需要重新保存。", "删除", Runnable {
                        try {
                            recharge.setPassword(account, null)
                            draw()
                        } catch (_: Exception) {
                            error("未能删除", "请确认当前账户后再试。", null)
                        }
                    })
                })
            }
            if (CommunityBudget.pending(this, account)) {
                val history = section(page.body, "待核对订单")
                reviewLine(history, "充值已暂停", CampusRecharge.PENDING, warning = true)
                settingRow(history, "info", "我已核对账单", "确认学校账单后恢复充值", false, Runnable {
                    confirm("确认已核对账单？", "请先查看学校账单里的最新记录，避免重复充值。", "恢复充值", Runnable {
                        if (runtime.isConfigured && account == runtime.feeProfileKey()) {
                            try {
                                CommunityBudget.acknowledge(this, account)
                                draw()
                            } catch (_: Exception) {
                                error("未能恢复充值", "请稍后再试。", null)
                            }
                        }
                    })
                })
            }
        }
        draw()
        navigation.push(page, source, Runnable {})
    }

    private fun bandSettings(source: View) {
        val page = CampusFeePages.Screen(this, navigation, "小米手环 · Beta")
        val bundle = BuiltInBand.bundle
        val band = NativePlugins.get(this, bundle) as? BandExtension ?: return
        val connection = section(page.body, "连接")
        val status = text("", 14, CampusUi.MUTED).apply { setPadding(dp(14f), dp(12f), dp(14f), dp(12f)) }
        connection.addView(status)
        val toggle = switchRow(connection, "启用手环后台连接", "开启后锁屏也能取码；请先在小米运动健康中连接手环。", BuiltInBand.enabled(this))
        val ui = pluginUi(bundle) { navigation.top(page) }
        settingRow(connection, "wallet", "在手环打开 ${getString(R.string.app_name)}", "连接后在手环上打开付款码", false,
            Runnable { band.command("open", ui) })
        val open = connection.getChildAt(connection.childCount - 1)
        val display = section(page.body, "显示")
        switchRow(display, "显示校园卡余额", "在手环二维码页显示圈存后的余额", band.state().optBoolean("balanceEnabled"))
            .setOnCheckedChangeListener { _, enabled -> band.showBalance(enabled) }
        if (assets.list("")?.contains(BuiltInBand.INSTALLER) == true) {
            val installer = section(page.body, "首次安装")
            settingRow(installer, "add", "导出手环安装包", "用 AstroBox 的「安装快应用」选择导出的文件。安装后断开 AstroBox，回到小米运动健康连接手环。", false,
                Runnable { exportBandInstaller() })
        }
        val background = section(page.body, "后台运行")
        settingRow(background, "power", "后台保障", "调整开机恢复和电池限制，让手环在锁屏后也能取码", false,
            Runnable {}, { showBackgroundLogin(it) })
        var binding = false
        fun refreshBandSettings() {
            val enabled = BuiltInBand.enabled(this)
            binding = true
            toggle.isChecked = enabled
            binding = false
            open.visibility = if (enabled) View.VISIBLE else View.GONE
            background.visibility = if (enabled) View.VISIBLE else View.GONE
            val backgroundTitle = page.body.getChildAt(page.body.indexOfChild(background) - 1)
            backgroundTitle.visibility = background.visibility
            status.text = if (enabled && !PluginForegroundService.running(bundle.id))
                "手环后台已开启，等待连接" else band.state().optString("status")
        }
        toggle.setOnCheckedChangeListener { _, enabled ->
            if (!binding) {
                if (enabled) band.command("connect", ui)
                else {
                    BuiltInBand.enabled(this, false)
                    PluginForegroundService.detach(bundle.id)
                    syncLoginPolicy()
                }
                refreshBandSettings()
            }
        }
        val update = object : Runnable {
            override fun run() {
                if (!visible) return
                if (navigation.top(page)) refreshBandSettings()
                main.postDelayed(this, 1500)
            }
        }
        if (navigation.push(page, source, Runnable {
                main.removeCallbacks(update)
                if (bandStatusRefresh == update) bandStatusRefresh = null
            })) {
            bandStatusRefresh = update
            update.run()
        }
    }

    private fun exportBandInstaller() {
        try {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, BuiltInBand.INSTALLER), EXPORT_BAND)
        } catch (_: RuntimeException) {
            error("无法导出安装包", "系统文件选择器暂时不可用。", null)
        }
    }

    private fun section(parent: LinearLayout, title: String?): LinearLayout {
        val label = text(title, 13, CampusUi.MUTED)
        label.setPadding(dp(8f), dp(24f), 0, dp(10f))
        parent.addView(label)
        val card = column()
        card.setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
        card.setBackground(CampusUi.shape(this, CampusUi.WHITE, 24f))
        parent.addView(card)
        return card
    }

    private fun settingRow(
        card: LinearLayout,
        icon: String?,
        title: String?,
        detail: String?,
        danger: Boolean,
        action: Runnable,
        onSource: ((View) -> Unit)? = null
    ): TextView {
        val line = row()
        line.setPadding(dp(14f), dp(16f), dp(14f), dp(16f))
        line.setMinimumHeight(dp(78f))
        val leading: View =
            CampusUi.icon(this, icon ?: "wallet", if (danger) CampusUi.ERROR else CampusUi.PRIMARY)
        leading.setPadding(dp(9f), dp(9f), dp(9f), dp(9f))
        leading.setBackground(
            CampusUi.shape(
                this,
                if (danger) CampusUi.ERROR_TINT else CampusUi.TINT,
                13f
            )
        )
        line.addView(leading, LinearLayout.LayoutParams(dp(38f), dp(38f)))
        val words = column()
        val label = text(title, 16, if (danger) CampusUi.ERROR else CampusUi.INK)
        CampusUi.medium(label)
        words.addView(label)
        val sub = text(detail, 12, CampusUi.MUTED)
        sub.setPadding(0, dp(5f), 0, 0)
        sub.setLineSpacing(dp(2f).toFloat(), 1f)
        words.addView(sub)
        val wp = LinearLayout.LayoutParams(0, -2, 1f)
        wp.leftMargin = dp(14f)
        wp.rightMargin = dp(8f)
        line.addView(words, wp)
        line.addView(
            CampusUi.icon(this, "chevron", -0x675c4b),
            LinearLayout.LayoutParams(dp(20f), dp(20f))
        )
        line.setFocusable(true)
        line.setBackground(CampusUi.ripple(this, CampusUi.WHITE, 18f))
        line.setOnClickListener { if (onSource == null) action.run() else onSource(it) }
        card.addView(line)
        return sub
    }

    private fun confirmClearAccount() {
        val s = sheet("退出登录？", "退出将删除本机记住的密码并停止该账户的付款码显示。")
        val inline = s.paragraph("", CampusUi.ERROR)
        inline.setVisibility(View.GONE)
        val busy = booleanArrayOf(false)
        val expected = longArrayOf(-1)
        val remove = arrayOf<TextView?>(null)
        s.action("取消", false, false, Runnable { s.dismiss() })
        remove[0] = s.action("退出登录", true, true, Runnable {
            if (busy[0]) return@Runnable
            busy[0] = true
            remove[0]!!.setEnabled(false)
            remove[0]!!.setAlpha(.65f)
            remove[0]!!.setText("正在清除…")
            inline.setVisibility(View.GONE)
            syncLoginPolicy()
            runtime.clear(SaveCompletion { success: Boolean, reason: String? ->
                if (!s.isShowing) return@SaveCompletion
                busy[0] = false
                remove[0]!!.setEnabled(true)
                remove[0]!!.setAlpha(1f)
                remove[0]!!.setText("重试删除")
                if (expected[0] == runtime.configurationRevision() && success) {
                    s.dismiss()
                    render(runtime.current())
                } else {
                    inline.setText(reason)
                    inline.setVisibility(View.VISIBLE)
                }
            })
            CommunityJobs.cancelAccountJobs(this)
            PluginForegroundService.accountCleared(this)
            syncLoginPolicy()
            expected[0] = runtime.configurationRevision()
        })
        s.show()
    }

    private fun input(hint: String?, password: Boolean): EditText {
        val e = CampusUi.edit(this)
        e.setHint(hint)
        e.setTextColor(CampusUi.INK)
        e.setHintTextColor(CampusUi.MUTED)
        e.setTextSize(16f)
        e.setSingleLine(true)
        e.setMinHeight(dp(54f))
        e.setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        e.setBackground(CampusUi.shape(this, CampusUi.BG, 14f))
        e.setInputType(InputType.TYPE_CLASS_TEXT or (if (password) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
        e.setSaveEnabled(false)
        e.setImeOptions(if (password) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT)
        if (Build.VERSION.SDK_INT >= 26) e.setAutofillHints(if (password) View.AUTOFILL_HINT_PASSWORD else View.AUTOFILL_HINT_USERNAME)
        return e
    }

    private fun fieldLabel(parent: LinearLayout, label: String?) {
        val v = text(label, 13, CampusUi.MUTED)
        v.setPadding(dp(2f), dp(12f), 0, dp(8f))
        parent.addView(v)
    }

    private fun syncLoginPolicy() {
        backgroundLoginPlugins = LoginPolicy.backgroundPlugins(this)
        if (visible && backgroundLoginPlugins.isNotEmpty()) LoginPolicy.noteForeground(this)
        LoginPolicy.updateBootReceiver(this, backgroundLoginPlugins)
    }

    private fun backgroundLoginNotice(plugin: PluginBundle): String =
        "启用插件后将锁定记住密码和自动登录。" +
            if (plugin.isNative) "开启设备服务后可在后台恢复登录。"
            else "定时任务需要单独授权后才能在后台恢复登录。"

    private fun switchRow(parent: LinearLayout, title: String, summary: String, checked: Boolean): CompoundButton {
        val line = row().apply { setPadding(dp(14f), dp(12f), dp(12f), dp(12f)) }
        val words = column()
        words.addView(text(title, 15, CampusUi.INK))
        words.addView(text(summary, 12, CampusUi.MUTED).apply { setPadding(0, dp(4f), dp(10f), 0) })
        line.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = CampusUi.toggle(this, title, checked)
        line.addView(toggle)
        parent.addView(line, LinearLayout.LayoutParams(-1, -2))
        return toggle
    }

    private fun createLoginPage(): ScrollView {
        val page = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(CampusUi.BG) }
        val body = column().apply {
            setPadding(dp(24f), dp(32f), dp(24f), dp(36f))
            isFocusableInTouchMode = true
        }
        page.addView(body)
        val brand = ImageView(this).apply { setImageDrawable(getDrawable(R.drawable.ic_brand)) }
        body.addView(brand, LinearLayout.LayoutParams(dp(56f), dp(56f)))
        body.addView(text(getString(R.string.app_name), 30, CampusUi.INK).also(CampusUi::medium).apply {
            setPadding(0, dp(16f), 0, dp(6f))
        })
        body.addView(text("登录后查看付款码和校园费用。", 14, CampusUi.MUTED))
        val fields = section(body, "校园卡账户").apply { setPadding(dp(14f), dp(4f), dp(14f), dp(10f)) }
        fieldLabel(fields, "学号")
        val user = input("请输入学号", false)
        fields.addView(user, LinearLayout.LayoutParams(-1, -2))
        fieldLabel(fields, "校园卡密码")
        val password = input("请输入校园卡密码", true)
        val passwordRow = row()
        passwordRow.addView(password, LinearLayout.LayoutParams(0, -2, 1f))
        val reveal = CampusUi.button(this, "显示", false, false, Runnable {})
        reveal.setOnClickListener {
            val hidden = password.transformationMethod != null
            password.transformationMethod = if (hidden) null else PasswordTransformationMethod.getInstance()
            password.setSelection(password.length())
            reveal.text = if (hidden) "隐藏" else "显示"
        }
        passwordRow.addView(reveal)
        fields.addView(passwordRow, LinearLayout.LayoutParams(-1, -2))
        val branch = arrayOf<String?>("codebarPayinfo")
        val mode = settingRow(fields, "settings", "连接方式", "常规连接", false, Runnable {})
        val modeRow = fields.getChildAt(fields.childCount - 1)
        modeRow.setOnClickListener { connectionMode(branch, mode) }

        val options = section(body, "登录选项")
        var rememberChoice = runtime.savedLoginAvailable()
        var autoChoice = LoginPolicy.automatic(this)
        var previouslySignedIn = runtime.isConfigured
        var updating = false
        val remember = switchRow(options, "记住密码", "加密保存在这台手机", rememberChoice)
        val auto = switchRow(options, "自动登录", "打开应用时登录", autoChoice)
        val forcedNote = text("当前已启用的插件要求强制自动登录和后台运行。", 12, CampusUi.MUTED).apply {
            setPadding(dp(14f), 0, dp(14f), dp(12f))
        }
        options.addView(forcedNote)
        val inline = text("", 13, CampusUi.ERROR).apply { setPadding(dp(4f), dp(10f), dp(4f), 0) }
        body.addView(inline)
        val login = CampusUi.button(this, "登录", true, false, Runnable {})
        val saved = CampusUi.button(this, "用已保存的账户登录", false, false, Runnable {})
        for (button in listOf(login, saved)) {
            body.addView(button, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10f) })
        }
        val about = CampusUi.button(this, getString(R.string.about_privacy_title), false, false, Runnable {})
        about.setOnClickListener(::showAbout)
        body.addView(about, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(12f)
        })
        updateLoginPage = {
            val forced = backgroundLoginPlugins.isNotEmpty()
            val busy = runtime.loginInProgress
            if (previouslySignedIn && !runtime.isConfigured) {
                rememberChoice = runtime.savedLoginAvailable()
                autoChoice = LoginPolicy.automatic(this)
                inline.text = ""
            }
            previouslySignedIn = runtime.isConfigured
            updating = true
            auto.isChecked = forced || autoChoice
            remember.isChecked = forced || autoChoice || rememberChoice
            auto.isEnabled = !busy && !forced
            remember.isEnabled = !busy && !forced && !autoChoice
            updating = false
            forcedNote.visibility = if (forced) View.VISIBLE else View.GONE
            for (view in listOf(user, password, reveal, modeRow, login, saved)) view.isEnabled = !busy
            login.text = if (busy) "正在登录…" else "登录"
            saved.visibility = if (runtime.savedLoginAvailable()) View.VISIBLE else View.GONE
            runtime.loginError?.let { inline.text = it }
            if (runtime.isConfigured) {
                password.text?.clear()
                password.transformationMethod = PasswordTransformationMethod.getInstance()
                reveal.text = "显示"
                user.text?.clear()
            }
        }
        remember.setOnCheckedChangeListener { _, enabled -> if (!updating) rememberChoice = enabled }
        auto.setOnCheckedChangeListener { _, enabled ->
            if (!updating) {
                autoChoice = enabled
                if (enabled) rememberChoice = true
                updateLoginPage()
            }
        }
        fun submit() {
            if (runtime.loginInProgress) return
            val username = user.text.toString().trim()
            val secret = password.text.toString()
            if (username.isEmpty() || secret.isEmpty()) {
                inline.text = "请填写学号和校园卡密码。"
                return
            }
            inline.text = ""
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(password.windowToken, 0)
            val forced = backgroundLoginPlugins.isNotEmpty()
            val selectedAuto = autoChoice
            runtime.signIn(username, secret, requireNotNull(branch[0]), forced || selectedAuto || rememberChoice,
                SaveCompletion { success, message ->
                    if (success) {
                        if (!forced) LoginPolicy.automatic(this, selectedAuto)
                        autoChoice = LoginPolicy.automatic(this)
                        rememberChoice = runtime.isRemembered
                        PluginForegroundService.restore(this, false)
                    } else inline.text = message.orEmpty()
                })
            updateLoginPage()
        }
        login.setOnClickListener { submit() }
        password.setOnEditorActionListener { _, action, _ ->
            (action == EditorInfo.IME_ACTION_DONE).also { if (it) submit() }
        }
        saved.setOnClickListener {
            inline.text = ""
            val forced = backgroundLoginPlugins.isNotEmpty()
            val selectedAuto = autoChoice
            runtime.restoreLogin(forced || selectedAuto || rememberChoice, SaveCompletion { success, message ->
                if (success) {
                    if (!forced) LoginPolicy.automatic(this, selectedAuto)
                    rememberChoice = runtime.isRemembered
                    PluginForegroundService.restore(this, false)
                } else inline.text = message.orEmpty()
            })
            updateLoginPage()
        }
        updateLoginPage()
        return page
    }

    private fun account(source: View? = null) {
        if (!runtime.isConfigured) return
        syncLoginPolicy()
        val page = CampusFeePages.Screen(this, navigation, "账户管理")
        drawAccount(page)
        if (navigation.push(page, source, Runnable { accountPage = null })) accountPage = page
    }

    private fun drawAccount(page: CampusFeePages.Screen) {
        while (page.body.childCount > 1) page.body.removeViewAt(1)
        val account = section(page.body, "当前账户")
        reviewLine(account, runtime.studentAccount(), "校园卡 · 已登录")
        settingRow(account, "wallet", "付款账户", "选择使用的校园卡", false, Runnable { selectAccount() })
        val options = section(page.body, "登录选项")
        val forced = backgroundLoginPlugins.isNotEmpty()
        val auto = LoginPolicy.automatic(this)
        val remember = switchRow(options, "记住密码", "加密保存在这台手机", runtime.isRemembered || forced || auto)
        remember.isEnabled = !forced && !auto
        remember.setOnCheckedChangeListener { _, enabled ->
            remember.isEnabled = false
            runtime.setRememberPassword(enabled, SaveCompletion { ok, message ->
                if (!ok) error("设置未保存", message, null)
                if (navigation.top(page)) drawAccount(page)
            })
        }
        if (forced) {
            settingRow(options, "lock", "自动登录", "已开启 · 由插件要求", false,
                Runnable {}, { showBackgroundLogin(it) })
        } else {
            val toggle = switchRow(options, "自动登录", "打开应用时登录", auto)
            toggle.setOnCheckedChangeListener { _, enabled ->
                if (!enabled || runtime.isRemembered) {
                    LoginPolicy.automatic(this, enabled)
                    drawAccount(page)
                } else {
                    toggle.isEnabled = false
                    remember.isEnabled = false
                    runtime.setRememberPassword(true, SaveCompletion { ok, message ->
                        if (ok) LoginPolicy.automatic(this, true) else error("设置未保存", message, null)
                        if (navigation.top(page)) drawAccount(page)
                    })
                }
            }
        }
        val actions = section(page.body, "账户操作")
        settingRow(actions, "close", "退出登录", "结束当前会话并删除已记住的密码", true,
            Runnable { confirmClearAccount() })
    }

    private fun showBackgroundLogin(source: View?) {
        syncLoginPolicy()
        if (backgroundLoginPlugins.isEmpty()) return
        val page = CampusFeePages.Screen(this, navigation, "自动登录与后台")
        if (backgroundLoginPlugins.isNotEmpty()) {
            val required = section(page.body, "使用后台登录的功能")
            backgroundLoginPlugins.forEach { reviewLine(required, it.name, "可在后台恢复登录") }
        }
        val support = section(page.body, "后台保障")
        switchRow(support, "开机恢复", "重启后恢复已开启的服务与已授权的校园卡定时任务", LoginPolicy.boot(this))
            .setOnCheckedChangeListener { _, enabled -> LoginPolicy.boot(this, enabled) }
        settingRow(support, "power", "电池限制", "查看系统后台运行设置", false,
            Runnable { BackgroundSupport.openBattery(this) })
        settingRow(support, "settings", "自启动", "查看系统自启动设置", false,
            Runnable { BackgroundSupport.openAutostart(this) })
        navigation.push(page, source, Runnable {})
    }

    private fun connectionMode(branch: Array<String?>, value: TextView) {
        val s = sheet("连接方式", "常规连接不可用时，可尝试兼容连接。")
        s.row("wallet", "常规连接", null, "codebarPayinfo" == branch[0], false, Runnable {
            branch[0] = "codebarPayinfo"
            value.setText("常规连接")
            s.dismiss()
        })
        s.row("refresh", "兼容连接", null, "queryCard" == branch[0], false, Runnable {
            branch[0] = "queryCard"
            value.setText("兼容连接")
            s.dismiss()
        })
        s.show()
    }

    private fun selectAccount() {
        if (!runtime.isConfigured) {
            account()
            return
        }
        val rows = runtime.accounts()
        if (rows.isEmpty()) {
            error(
                "尚无可选校园卡",
                "先刷新付款码，再选择付款账户。",
                Runnable { runtime.refresh("accounts") })
            return
        }
        val s = sheet("付款账户", "切换后将获取新的付款码。")
        val current = runtime.current().label
        for (i in rows.indices) {
            val item = rows.get(i)
            val base = if (item.label == null || item.label.isEmpty()) "校园卡" else item.label
            var duplicates = 0
            for (candidate in rows) if (base == candidate.label) duplicates++
            val label = if (duplicates > 1) base + " " + (i + 1) else base
            s.row(
                "wallet",
                label,
                null,
                rows.size == 1 || duplicates == 1 && base == current,
                false,
                Runnable {
                    runtime.selectAccount(item.id)
                    s.dismiss()
                    runtime.refresh("account-selected")
                })
        }
        s.show()
    }

    private fun detail(s: CampusSheet, label: String, value: String?) {
        val name = text(label, 12, CampusUi.MUTED)
        name.setPadding(0, dp(14f), 0, dp(6f))
        s.body.addView(name)
        val data = text(value, 14, CampusUi.INK)
        data.setLineSpacing(dp(3f).toFloat(), 1f)
        if (label.contains("SHA")) {
            data.setTypeface(Typeface.MONOSPACE)
            data.setTextSize(11f)
        }
        s.body.addView(data)
    }

    private fun render(snapshot: CardSnapshot) {
        if (!visible || state == null) return
        val showLogin = !runtime.isConfigured
        if (showLogin != (loginPage.visibility == View.VISIBLE)) {
            loginPage.visibility = if (showLogin) View.VISIBLE else View.GONE
            if (showLogin) {
                pluginRequest++
                syncLoginPolicy()
                navigation.resetToHome()
                suspendHome()
                if (runtime.loginError != null) sheets.toList().forEach { it.dismiss() }
            } else resumeHome()
            updateLoginPage()
        }
        homeScroll.visibility = if (showLogin) View.INVISIBLE else View.VISIBLE
        if (showLogin) {
            updateLoginPage()
            return
        }
        state!!.setText(if (snapshot.ready) ((snapshot.remainingMs + 999) / 1000).toString() + " 秒" else if (!runtime.isConfigured) "未登录" else if ("loading" == snapshot.errorKind) "更新中" else "暂不可用")
        (state!!.parent as View).contentDescription = "${state!!.text}，刷新付款码"
        if (!homeVisible()) {
            codes!!.clear()
            shownRevision = -1
            return
        }
        fees.refresh(false)
        if (!snapshot.ready) {
            codes!!.clear()
            shownRevision = -1
            empty!!.setVisibility(View.VISIBLE)
            val configured = runtime.isConfigured
            val loading = "loading" == snapshot.errorKind
            emptyTitle!!.setText(if (!configured) "添加校园卡" else if (loading) "正在更新付款码" else "暂时无法显示")
            emptyDetail!!.setText(if (!configured) "登录后查看付款码和校园费用" else if (loading) "请稍候" else "点下方查看原因并重试")
            emptyAction!!.setText(if (configured) "查看状态" else "添加账户")
            emptyAction!!.setVisibility(if (loading) View.GONE else View.VISIBLE)
            return
        }
        empty!!.setVisibility(View.GONE)
        if (shownRevision == snapshot.revision) return
        if (codes!!.getWidth() <= 0 || codes!!.getHeight() <= 0) return
        try {
            val qr: RearQrEncoder.Image = PaymentCodes.Companion.shared().qr(snapshot, 512)
            var bar: ByteArray? = null
            val barcode: RearBarcodeEncoder.Image? = PaymentCodes.Companion.shared()
                .barcode(snapshot, min(1024, codes!!.getWidth()), min(256, max(48, dp(56f))))
            if (barcode != null) bar = barcode.png
            val q = BitmapFactory.decodeByteArray(qr.png, 0, qr.png.size)
            val b = if (bar == null) null else BitmapFactory.decodeByteArray(bar, 0, bar.size)
            val latest = runtime.current()
            if (!latest.ready || latest.revision != snapshot.revision || !homeVisible()) {
                codes!!.clear()
                return
            }
            codes!!.show(q, b, qr.moduleCount, qr.modulePixels)
            shownRevision = snapshot.revision
        } catch (e: Exception) {
            codes!!.clear()
            shownRevision = -1
            state!!.setText("显示失败")
            emptyTitle!!.setText("付款码未能显示")
            emptyDetail!!.setText("请稍后刷新重试")
            emptyAction!!.setText("查看状态")
            emptyAction!!.setVisibility(View.VISIBLE)
            empty!!.setVisibility(View.VISIBLE)
        }
    }

    override fun onStart() {
        super.onStart()
        appUpdatePage.onStart()
    }

    public override fun onResume() {
        super.onResume()
        visible = true
        appUpdatePage.onResume()
        DailyUsage.recordForeground(this)
        runtime.setInteractive(true)
        syncLoginPolicy()
        bandStatusRefresh?.run()
        runtime.addListener(changes)
        if (backgroundLoginPlugins.isNotEmpty() || LoginPolicy.automatic(this)) runtime.restoreLogin()
        PluginForegroundService.restore(this, false)
        render(runtime.current())
        resumeHome()
    }

    public override fun onPause() {
        visible = false
        appUpdatePage.onPause()
        bandStatusRefresh?.let(main::removeCallbacks)
        runtime.setInteractive(false)
        pluginRequest++
        suspendHome()
        runtime.removeListener(changes)
        super.onPause()
    }

    override fun onBackPressed() {
        if (!navigation.pop()) super.onBackPressed()
    }

    public override fun onDestroy() {
        visible = false
        main.removeCallbacksAndMessages(null)
        for (s in ArrayList<CampusSheet>(sheets)) if (s.isShowing) s.dismiss()
        pluginPageCleanups.forEach(Runnable::run)
        pluginPageCleanups.clear()
        navigation.destroy()
        super.onDestroy()
    }

    private inner class PaymentCodeView : View(this@MainActivity) {
        private var qr: Bitmap? = null
        private var bar: Bitmap? = null
        private var modules = 0
        private var crop = 0
        private val paint = Paint()

        init {
            setContentDescription("校园卡付款码")
            paint.setFilterBitmap(false)
            paint.setAntiAlias(false)
        }

        fun show(q: Bitmap?, b: Bitmap?, count: Int, pixels: Int) {
            qr = q
            bar = b
            modules = count - 6
            crop = 3 * pixels
            invalidate()
        }

        fun clear() {
            if (qr == null && bar == null) return
            qr = null
            bar = null
            modules = 0
            crop = 0
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            shownRevision = -1
            if (homeVisible()) post(Runnable { render(runtime.current()) })
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (qr == null) return
            val fit = PaymentCodeLayout.fitWallet(
                getWidth(),
                getHeight(),
                modules,
                if (bar == null) 0 else bar!!.getWidth(),
                if (bar == null) 0 else bar!!.getHeight(),
                dp(24f),
                dp(188f),
                dp(56f)
            )
            if (fit.qrSide == 0) return
            if (fit.showBar) canvas.drawBitmap(
                bar!!,
                null,
                Rect(fit.barX, fit.barY, fit.barX + fit.barWidth, fit.barY + fit.barHeight),
                paint
            )
            canvas.drawBitmap(
                qr!!,
                Rect(crop, crop, qr!!.getWidth() - crop, qr!!.getHeight() - crop),
                Rect(fit.qrX, fit.qrY, fit.qrX + fit.qrSide, fit.qrY + fit.qrSide),
                paint
            )
        }
    }

    companion object {
        private const val EXPORT_BAND = 53
        private const val IMPORT_PLUGIN = 54
        private fun pluginText(`object`: JSONObject, key: String?, fallback: String): String {
            val value = `object`.opt(key)
            if (value !is String) return fallback
            val text = value
            return text.substring(0, min(text.length, 240))
        }
    }
}
