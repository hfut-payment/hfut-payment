package dev.codex.hfutcampus

import android.os.Build
import android.content.pm.PackageManager
import dev.codex.hfutcampus.CampusRearProvider
import dev.codex.hfutcampus.RearCallerPolicy
import dev.codex.hfutcampus.RearCallerPolicy.HostInfo
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.Process
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.TreeSet

/** Exact host identity plus explicit certificate pinning. No exported approval API.  */
object RearCallerPolicy {
    const val HOST_PACKAGE: String = "com.xiaomi.subscreencenter"
    private const val PREFS = "rear-host-trust-v1"
    private const val PINS = "approved-current-signers"
    fun inspectHost(context: Context): HostInfo {
        try {
            val pm = context.getPackageManager()
            val info = pm.getPackageInfo(
                HOST_PACKAGE,
                if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            )
            val app = pm.getApplicationInfo(HOST_PACKAGE, 0)
            val signatures =
                if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) info.signingInfo!!.getApkContentsSigners() else info.signatures
            val hashes: MutableSet<String?> = TreeSet<String?>()
            if (signatures != null) for (signature in signatures) hashes.add(
                hex(
                    MessageDigest.getInstance(
                        "SHA-256"
                    ).digest(signature.toByteArray())
                )
            )
            check(!hashes.isEmpty()) { "无法读取背屏宿主签名" }
            val system =
                (app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
            val platform =
                pm.checkSignatures("android", HOST_PACKAGE) == PackageManager.SIGNATURE_MATCH
            val shared = info.sharedUserId != null && !info.sharedUserId!!.isEmpty()
            val approved = approvedPins(context)
            val members = pm.getPackagesForUid(app.uid)
            val unique = members != null && members.size == 1 && HOST_PACKAGE == members[0]
            return HostInfo(
                HOST_PACKAGE,
                (info.versionName ?: "未知"),
                app.uid,
                system,
                platform,
                system && !shared && unique && validPins(approved) && approved == hashes,
                hashes,
                shared
            )
        } catch (e: Exception) {
            throw IllegalStateException("未找到可验证的系统背屏宿主")
        }
    }

    private fun approvedPins(c: Context): MutableSet<String?> {
        return TreeSet<String?>(
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(PINS, mutableSetOf<String?>())
        )
    }

    fun validPins(pins: MutableSet<String?>?): Boolean {
        if (pins == null || pins.isEmpty()) return false
        for (pin in pins) if (pin == null || !pin.matches("[0-9a-f]{64}".toRegex())) return false
        return true
    }

    /** Pure decision function also exercised in offline tests. Shared external UIDs are rejected.  */
    fun decide(
        callingUid: Int,
        ownUid: Int,
        host: HostInfo?,
        uidPackages: Array<String?>?,
        pins: MutableSet<String?>?
    ): Access {
        if (callingUid == ownUid) return Access.SELF
        if (host == null || callingUid != host.uid || (HOST_PACKAGE != host.packageName) || !host.systemApp || host.sharedUid
            || !validPins(host.signingSha256) || uidPackages == null || uidPackages.size != 1 || (HOST_PACKAGE != uidPackages[0])
        ) return Access.DENIED
        return if (validPins(pins) && pins == host.signingSha256) Access.APPROVED_HOST else Access.UNAPPROVED_HOST
    }

    fun access(c: Context, callingUid: Int): Access? {
        if (callingUid == Process.myUid()) return Access.SELF
        try {
            val h = inspectHost(c)
            return decide(
                callingUid,
                Process.myUid(),
                h,
                c.getPackageManager().getPackagesForUid(callingUid),
                approvedPins(c)
            )
        } catch (e: Exception) {
            return Access.DENIED
        }
    }

    /** Call only after displaying the reviewed identity in an explicit local approval dialog.  */
    fun approveHost(c: Context, reviewed: HostInfo) {
        if (Binder.getCallingUid() != Process.myUid()) throw SecurityException("Local approval only")
        val current = inspectHost(c)
        val members = c.getPackageManager().getPackagesForUid(current.uid)
        if (reviewed == null || !current.systemApp || current.sharedUid || current.uid != reviewed.uid || (current.packageName != reviewed.packageName) || (current.versionName != reviewed.versionName) || (current.signingSha256 != reviewed.signingSha256) || members == null || members.size != 1 || (HOST_PACKAGE != members[0])) throw SecurityException(
            "宿主已变化，请重新核对"
        )
        check(
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putStringSet(PINS, TreeSet<String?>(current.signingSha256)).commit()
        ) { "无法保存宿主授权" }
        CampusRearProvider.Companion.notifySharingChanged(c)
    }

    fun revokeHost(c: Context) {
        if (Binder.getCallingUid() != Process.myUid()) throw SecurityException("Local revocation only")
        check(
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PINS).commit()
        ) { "无法撤销宿主授权" }
        CampusRearProvider.Companion.notifySharingChanged(c)
    }

    private fun hex(value: ByteArray): String {
        val b = StringBuilder()
        for (n in value) b.append(String.format(Locale.ROOT, "%02x", n.toInt() and 255))
        return b.toString()
    }

    enum class Access {
        SELF, APPROVED_HOST, UNAPPROVED_HOST, DENIED
    }

    class HostInfo @JvmOverloads constructor(
        val packageName: String,
        val versionName: String,
        val uid: Int,
        val systemApp: Boolean,
        val platformSignatureMatch: Boolean,
        val approved: Boolean,
        signers: MutableSet<String?>?,
        val sharedUid: Boolean = false
    ) {
        val signingSha256: MutableSet<String?>

        init {
            signingSha256 = Collections.unmodifiableSet<String?>(TreeSet<String?>(signers))
        }

        fun displayText(): String {
            return ("宿主：" + packageName + "\n版本：" + versionName + "\n系统应用：" + (if (systemApp) "是" else "否")
                    + "\n与Android平台签名一致：" + (if (platformSignatureMatch) "是" else "否")
                    + "\n当前签名SHA-256：\n" + signingSha256.joinToString("\n")
                    + "\n授权后仅此宿主可读取短期付款码；签名变化需重新批准。")
        }
    }
}
