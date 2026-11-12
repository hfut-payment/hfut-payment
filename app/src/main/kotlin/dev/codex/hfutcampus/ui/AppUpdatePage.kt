package dev.codex.hfutcampus

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

internal class AppUpdatePage(
    private val activity: Activity, private val navigation: CampusNavigation,
    private val onChanged: () -> Unit
) {
    private val updates = AppUpdates(activity.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private var screen: CampusFeePages.Screen? = null
    private var attempted = false
    private var checking = false
    private var release: AppRelease? = null
    private var apk: File? = null
    private var progress: Int? = null
    private var error: String? = null
    private var foreground = false
    private var promptedVersion = 0L
    private var prompt: AlertDialog? = null
    val available get() = release != null
    val summary: String get() = release?.let { "发现新版 ${it.name}" } ?: "当前版本 ${BuildConfig.VERSION_NAME}"

    fun onStart() {
        check(automatic = true)
    }

    fun onResume() {
        foreground = true
        showAvailableUpdate()
    }

    fun onPause() {
        foreground = false
        prompt?.dismiss()
    }

    fun show(source: View?) {
        val page = CampusFeePages.Screen(activity, navigation, "应用更新")
        if (navigation.push(page, source) { screen = null }) {
            screen = page
            render()
        }
    }

    private fun check(automatic: Boolean = false) {
        if (!updates.configured || checking || progress != null) return
        attempted = true
        checking = true
        error = null
        render()
        io.execute {
            val result = runCatching(updates::check)
            post {
                checking = false
                result.onSuccess { latest ->
                    if (latest != release) apk = null
                    release = latest
                }.onFailure { error = "暂时无法检查更新，请稍后重试。" }
                render()
                if (automatic && result.isSuccess) showAvailableUpdate()
            }
        }
    }

    private fun showAvailableUpdate() {
        val latest = release ?: return
        if (!foreground || screen != null || checking || prompt != null || promptedVersion == latest.code ||
            activity.isFinishing || activity.isDestroyed) return
        promptedVersion = latest.code
        prompt = AlertDialog.Builder(activity)
            .setTitle("发现新版本 ${latest.name}")
            .setMessage("可以下载新版并通过系统安装器更新。")
            .setNegativeButton("稍后", null)
            .setPositiveButton("查看更新") { _, _ -> show(null) }
            .setOnDismissListener { prompt = null }
            .show()
    }

    private fun download(release: AppRelease) {
        if (checking || progress != null) return
        progress = 0
        error = null
        render()
        io.execute {
            val result = runCatching {
                updates.download(release) { percent ->
                    post {
                        progress = percent
                        render()
                    }
                }
            }
            post {
                progress = null
                result.onSuccess { apk = it }.onFailure { failure ->
                    error = when (failure) {
                        is IllegalArgumentException, is IllegalStateException -> failure.message
                        else -> "下载失败，请检查网络后重试。"
                    }
                }
                render()
            }
        }
    }

    private fun install(file: File) {
        if (!file.isFile) {
            apk = null
            error = "安装包已被清理，请重新下载。"
            render()
            return
        }
        try {
            if (!updates.openInstaller(activity, file)) {
                Toast.makeText(activity, "允许安装后，返回这里再点「安装更新」。", Toast.LENGTH_LONG).show()
            }
        } catch (_: Exception) {
            error = "无法打开系统安装器。"
            render()
        }
    }

    private fun post(action: () -> Unit) {
        main.post { if (!activity.isDestroyed) action() }
    }

    private fun render() {
        onChanged()
        val body = screen?.body ?: return
        body.removeAllViews()
        val card = CampusUi.column(activity).apply {
            setPadding(dp(18f), dp(16f), dp(18f), dp(16f))
            background = CampusUi.shape(activity, CampusUi.WHITE, 24f)
        }
        body.addView(card)
        fun line(label: String, size: Int, color: Int, gap: Int) {
            card.addView(CampusUi.text(activity, label, size, color), LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(gap.toFloat())
            })
        }
        fun button(label: String, primary: Boolean, action: () -> Unit) {
            card.addView(CampusUi.button(activity, label, primary, false, action),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12f) })
        }
        line("当前版本 ${BuildConfig.VERSION_NAME}", 17, CampusUi.INK, 0)
        val latest = release
        when {
            !updates.configured -> line("此版本尚未配置更新来源。", 13, CampusUi.MUTED, 8)
            checking -> line("正在检查更新…", 13, CampusUi.MUTED, 8)
            latest != null -> {
                val size = String.format(Locale.ROOT, "%.1f MB", latest.bytes / 1_000_000.0)
                line("发现新版 ${latest.name} · $size", 14, CampusUi.PRIMARY, 8)
            }
            attempted && error == null -> line("已是最新版本。", 13, CampusUi.MUTED, 8)
        }
        error?.let { line(it, 13, CampusUi.ERROR, 8) }
        val percent = progress
        val downloaded = apk
        when {
            percent != null -> {
                line("正在下载 $percent%", 13, CampusUi.MUTED, 12)
                card.addView(CampusUi.progress(activity).apply { setProgressCompat(percent * 10, false) })
            }
            !checking && downloaded != null -> button("安装更新", true) { install(downloaded) }
            !checking && latest != null -> button("下载更新", true) { download(latest) }
        }
        if (updates.configured && !checking && progress == null) button("检查更新", latest == null) { check() }
    }

    private fun dp(value: Float) = CampusUi.dp(activity, value)

    private companion object {
        val io = Executors.newSingleThreadExecutor()
    }
}
