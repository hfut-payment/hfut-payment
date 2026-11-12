package dev.codex.hfutcampus

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest
import kotlin.math.min

/** In-app device grants and legacy external reader grants.  */
class PluginRegistry private constructor(context: Context) {
    private val context: Context
    private val settings: SharedPreferences

    init {
        this.context = context.getApplicationContext()
        settings = this.context.getSharedPreferences("payment-plugins-v1", Context.MODE_PRIVATE)
    }

    class Reader internal constructor(
        val packageName: String,
        val fingerprint: String,
        val label: String,
        val capabilities: Int,
        val uid: Int
    )

    fun readers(): List<Reader> {
        val result = ArrayList<Reader>()
        val pm = context.getPackageManager()
        for (resolved in pm.queryIntentActivities(Intent(ACTION), PackageManager.GET_META_DATA)) {
            if (resolved.activityInfo == null) continue
            val pkg = resolved.activityInfo.packageName
            if (pkg == context.getPackageName()) continue
            if (result.stream().anyMatch { r: Reader? -> r!!.packageName == pkg }) continue
            val reader = inspect(pkg)
            if (reader != null) result.add(reader)
        }
        return result
    }

    private fun inspect(pkg: String): Reader? {
        try {
            val pm = context.getPackageManager()
            val entries = pm.queryIntentActivities(
                Intent(ACTION).setPackage(pkg),
                PackageManager.GET_META_DATA
            )
            if (entries.size != 1 || entries.get(0).activityInfo == null) return null
            val activity = entries.get(0).activityInfo
            if (!activity.exported || activity.metaData == null || !activity.applicationInfo.enabled) return null
            val api = activity.metaData.getInt("hfut.payment.api", 0)
            val caps = activity.metaData.getInt("hfut.payment.capabilities", 0)
            if (api != 1 || caps == 0 || (caps and (TEXT or QR).inv()) != 0) return null
            val flags =
                if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            val info = pm.getPackageInfo(pkg, flags)
            val certs =
                if (Build.VERSION.SDK_INT >= 28) info.signingInfo!!.getApkContentsSigners() else info.signatures
            if (certs == null || certs.size != 1) return null
            val digest = MessageDigest.getInstance("SHA-256").digest(certs[0]!!.toByteArray())
            val hash = StringBuilder()
            for (value in digest) hash.append(Character.forDigit((value.toInt() ushr 4) and 15, 16))
                .append(
                    Character.forDigit(value.toInt() and 15, 16)
                )
            val label = pm.getApplicationLabel(activity.applicationInfo).toString()
            return Reader(
                pkg,
                hash.toString(),
                label.substring(0, min(64, label.length)),
                caps,
                activity.applicationInfo.uid
            )
        } catch (unavailable: Exception) {
            return null
        }
    }

    fun granted(reader: Reader): Boolean {
        return (grantedCapabilities(reader) and reader.capabilities) == reader.capabilities
    }

    private fun grantedCapabilities(reader: Reader): Int {
        if (reader.fingerprint != settings.getString("cert:" + reader.packageName, "")) return 0
        return settings.getInt("caps:" + reader.packageName, 0) and reader.capabilities
    }

    fun grant(reviewed: Reader) {
        val installed = inspect(reviewed.packageName)
        if (installed == null || (installed.fingerprint != reviewed.fingerprint) || installed.capabilities != reviewed.capabilities) throw SecurityException(
            "Plugin changed"
        )
        settings.edit().putString("cert:" + installed.packageName, installed.fingerprint)
            .putInt("caps:" + installed.packageName, installed.capabilities).apply()
    }

    fun revoke(pkg: String?) {
        settings.edit().remove("cert:" + pkg).remove("caps:" + pkg).apply()
    }

    fun canRead(pkg: String?, uid: Int, capability: Int): Boolean {
        if (pkg == null) return false
        val packages = context.getPackageManager().getPackagesForUid(uid)
        if (packages == null || !packages.contentEquals(arrayOf<String>(pkg))) return false
        val reader = inspect(pkg)
        return reader != null && reader.uid == uid && (grantedCapabilities(reader) and capability) == capability
    }

    companion object {
        const val ACTION: String = "dev.codex.hfutcampus.PAYMENT_PLUGIN_V1"
        const val TEXT: Int = 1
        const val QR: Int = 2
        fun get(context: Context): PluginRegistry {
            return PluginRegistry(context)
        }
    }
}
