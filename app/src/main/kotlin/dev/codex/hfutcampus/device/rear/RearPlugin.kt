package dev.codex.hfutcampus

import dev.codex.hfutcampus.NativePlugin.Host
import dev.codex.hfutcampus.NativePlugin
import android.content.SharedPreferences
import org.json.JSONObject
import org.json.JSONException
import dev.codex.hfutcampus.NativePlugin.Ui
import dev.codex.hfutcampus.CampusRearProvider
import kotlin.concurrent.Volatile
import dev.codex.hfutcampus.RearCallerPolicy
import dev.codex.hfutcampus.RearCallerPolicy.HostInfo
import android.content.Context
import android.database.Cursor
import android.net.Uri
import java.util.function.BooleanSupplier

class RearPlugin(private val host: Host) : NativePlugin {
    private val prefs: SharedPreferences
    private val provider: CampusRearProvider

    @Volatile
    private var sharing = false

    init {
        prefs = requireNotNull(host.context()).getSharedPreferences("campus-recovery", Context.MODE_PRIVATE)
        provider = CampusRearProvider(host, BooleanSupplier { sharing }) { prefs.getBoolean(SHOW_BALANCE, false) }
    }

    override fun state(): JSONObject {
        try {
            return JSONObject().put("sharing", sharing)
        } catch (impossible: JSONException) {
            throw AssertionError(impossible)
        }
    }

    override fun command(action: String?, ui: Ui?) {
        if (ui == null) return
        when (action) {
            "toggle" -> if (sharing) {
                accountCleared()
                host.stopBackground()
            } else if (!host.accountConfigured()) ui.account()
            else {
                try {
                    if (RearCallerPolicy.inspectHost(requireNotNull(host.context())).approved) ui.startBackground("enable")
                    else review(ui, true)
                } catch (unavailable: RuntimeException) {
                    missing(ui)
                }
            }

            "permission" -> review(ui, false)
            "display" -> display(ui)
            else -> super.command(action, ui)
        }
    }

    private fun display(ui: Ui) {
        val page = ui.sheet("背屏显示选项", null) ?: return
        try {
            page.toggle("显示校园卡余额", "只在背屏亮屏时显示", prefs.getBoolean(SHOW_BALANCE, false)) { enabled ->
                prefs.edit().putBoolean(SHOW_BALANCE, enabled).apply()
                provider.balancePreferenceChanged()
                true
            }
        } catch (_: NoSuchMethodError) {
            ui.error("请更新校园卡应用", "更新后即可设置背屏余额显示。")
            return
        }
        page.action("完成", true, false, Runnable { page.dismiss() })
        page.show()
    }

    private fun missing(ui: Ui) {
        ui.error("背屏暂不可用", "未找到可验证的妙享背屏组件。")
    }

    private fun review(ui: Ui, startAfter: Boolean) {
        val info: HostInfo
        try {
            info = RearCallerPolicy.inspectHost(requireNotNull(host.context()))
        } catch (unavailable: RuntimeException) {
            missing(ui)
            return
        }
        val page = ui.sheet(
            if (info.approved) "背屏读取权限" else "允许背屏显示付款码？",
            "开启显示期间，已授权的妙享背屏可读取当前付款码。"
        )
        if (page == null) return
        ui.detail(page, "应用", info.packageName)
        ui.detail(page, "版本", info.versionName)
        ui.detail(page, "签名 SHA-256", info.signingSha256.joinToString("\n\n"))
        if (info.approved) {
            page!!.action("撤销权限", false, true, Runnable {
                page.dismiss()
                ui.confirm("撤销背屏权限？", "背屏付款码会立即隐藏。", "撤销", Runnable {
                    accountCleared()
                    host.stopBackground()
                    try {
                        RearCallerPolicy.revokeHost(requireNotNull(host.context()))
                    } catch (unavailable: RuntimeException) {
                        ui.error("撤销未完成", "请重新打开授权页面重试。")
                    }
                })
            })
            page.action("完成", true, false, Runnable { page.dismiss() })
        } else {
            page!!.action("取消", false, false, Runnable { page.dismiss() })
            page.action("允许", true, false, Runnable {
                page.dismiss()
                try {
                    RearCallerPolicy.approveHost(requireNotNull(host.context()), info)
                    if (startAfter) ui.startBackground("enable")
                } catch (unavailable: RuntimeException) {
                    ui.error("未能授权", "请重新核对妙享背屏的身份。")
                }
            })
        }
        page.show()
    }

    override fun restore(boot: Boolean): Boolean {
        return prefs.getBoolean("rear-enabled", false)
                && (!boot || prefs.getBoolean("rear-boot", prefs.getBoolean("boot-recovery", true)))
                && RearCallerPolicy.inspectHost(requireNotNull(host.context())).approved
    }

    override fun start(action: kotlin.String?) {
        if (!RearCallerPolicy.inspectHost(requireNotNull(host.context())).approved) throw SecurityException("背屏授权已变化")
        if (sharing) return
        if ("enable" == action) prefs.edit().putBoolean("rear-enabled", true).apply()
        sharing = true
        host.displayUntil(Long.Companion.MAX_VALUE)
        provider.start()
    }

    override fun stop() {
        sharing = false
        host.displayUntil(0)
        provider.stop()
    }

    override fun deactivated(disabled: Boolean) {
        if (disabled) accountCleared()
        CampusRearProvider.Companion.notifySharingChanged(requireNotNull(host.context()))
    }

    override fun accountCleared() {
        prefs.edit().putBoolean("rear-enabled", false).apply()
    }

    override fun query(
        uri: Uri?,
        projection: Array<out String>?,
        selection: kotlin.String?,
        arguments: Array<out String>?,
        order: kotlin.String?
    ): Cursor {
        return provider.query(uri, projection, selection, arguments, order)
    }

    override fun type(uri: Uri?): kotlin.String {
        return provider.getType(uri)
    }

    private companion object {
        const val SHOW_BALANCE = "rear-show-balance"
    }
}
