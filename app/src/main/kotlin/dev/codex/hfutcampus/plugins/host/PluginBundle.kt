package dev.codex.hfutcampus

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/** Verifies a bounded, signed in-app plugin archive without extracting files.  */
internal class PluginBundle private constructor(
    manifest: JSONObject,
    signer: String,
    script: String,
    dex: ByteArray?,
    archive: ByteArray
) {
    val id: String
    val name: String
    val version: String
    val capability: String
    val device: HostDevice?
    val community: CommunitySpec?
    val signer: String
    val script: String
    val entryClass: String
    val authority: String
    val backgroundLogin: Boolean
    val dex: ByteArray?
    val home: Shortcut?
    val settings: Shortcut?
    private val feeEntries: FeePluginEntries
    val revision: Int
    private val archive: ByteArray

    internal class Shortcut(json: JSONObject) {
        val title: String
        val detail: String
        val icon: String

        init {
            title = json.optString("title").trim { it <= ' ' }
            detail = json.optString("detail").trim { it <= ' ' }
            icon = json.optString("icon", "wallet")
            if (title.isEmpty() || title.length > 60 || detail.length > 120 || !icon.matches("[a-z]{1,20}".toRegex())) {
                throw IOException("插件入口格式无效")
            }
        }
    }

    init {
        id = manifest.getString("id")
        name = manifest.getString("name").trim { it <= ' ' }
        version = manifest.getString("version")
        capability = manifest.getString("capability")
        val api = manifest.getInt("api")
        val trustedDevice = if (signer == HOST_SIGNER) HostDevice.entries.find { it.pluginId == id } else null
        val deviceScript = trustedDevice != null && api == 2 && capability == "page"
        community = if (api == 2) CommunitySpec.read(manifest.getJSONObject("community"), deviceScript) else null
        require(api != 2 || capability != "native")
        val sequence = manifest.get("revision")
        if ((sequence !is Int) || sequence < 1 || api !in 1..2 || !id.matches("[a-z][a-z0-9.-]{2,63}".toRegex()) || name.isEmpty() || name.length > 60 || !version.matches(
                "[0-9]+\\.[0-9]+\\.[0-9]+(?:-[a-z0-9.-]+)?".toRegex()
            ) || version.length > 40
        ) {
            throw IOException("插件清单不兼容")
        }
        revision = sequence
        if (community?.campusRecharge == true &&
            (revision < 11 || capability != "page" || !CampusRecharge.signedAutomation(id, signer))) {
            throw SecurityException("此发布者不能申请校园费用充值权限")
        }
        require(community?.dependencies?.containsKey(id) != true)
        entryClass = manifest.optString("entryClass")
        val declaredAuthority = manifest.optString("contentAuthority")
        authority = if (deviceScript) checkNotNull(trustedDevice).authority else declaredAuthority
        val legacyHook = manifest.optBoolean("backgroundLogin", false)
        if (legacyHook && capability != "native") throw SecurityException("请使用 community.backgroundLogin")
        val nativeHook = if (capability == "native" && api == 1 && manifest.has("community")) {
            val declaration = manifest.getJSONObject("community")
            require(declaration.keys().asSequence().all { it == "backgroundLogin" }) { "原生插件声明了未知社区权限" }
            CommunitySpec.backgroundLogin(declaration)
        } else false
        backgroundLogin = community?.backgroundLogin == true || nativeHook || legacyHook
        if ("native" == capability) {
            if ((HOST_SIGNER != signer) || dex == null || manifest.optInt("nativeApi") != 1 || !entryClass.matches(
                    "[a-zA-Z][a-zA-Z0-9_.]{3,140}".toRegex()
                ) || (!authority.isEmpty() && !authority.matches("[a-z][a-z0-9.]{3,119}".toRegex()))
                || (digest(dex) != manifest.optString("dexSha256"))
            ) {
                throw SecurityException("原生插件的签名、接口或代码校验无效")
            }
        } else if (legacy()) {
            if (HOST_SIGNER != signer) throw SecurityException("旧版插件的签名无效")
        } else if (!capability.matches("page|payment\\.text|payment\\.qr|payment\\.text\\+qr".toRegex())) {
            throw SecurityException("第三方插件声明了不支持的权限")
        } else if (id.startsWith("hfut.") && !deviceScript) {
            throw SecurityException("插件 ID 已保留")
        }
        if ("native" != capability && (dex != null || entryClass.isNotEmpty() || declaredAuthority.isNotEmpty())) throw SecurityException(
            "脚本包不能包含原生代码"
        )
        device = trustedDevice?.takeIf {
            deviceScript || dex != null && entryClass == it.legacyEntry && authority == it.authority
        }
        if (dex == null && archive.size > 256 * 1024) throw IOException("脚本插件包超过大小限制")
        this.signer = signer
        this.script = script
        this.dex = dex
        val points = manifest.optJSONObject("shortcuts")
        val homePoint = if (points == null) null else points.optJSONObject("home")
        val settingsPoint = if (points == null) null else points.optJSONObject("settings")
        home = if (homePoint == null) null else Shortcut(homePoint)
        settings = if (settingsPoint == null) null else Shortcut(settingsPoint)
        feeEntries = FeePluginEntries(points)
        require(community != null || points?.has("feeExtras") != true)
        this.archive = archive
    }

    fun feeShortcut(kind: String): Shortcut? {
        return feeEntries.find(kind)
    }

    fun feeExtras(kind: String, campus: String) = feeEntries.extras(kind, campus)

    fun checkUpdate(installed: PluginBundle?) {
        val deviceMigration = installed?.dex != null && dex == null && device != null && device == installed.device
        val previous = installed?.community
        val rechargeMigration = community?.campusRecharge == true && installed != null && previous != null &&
            CampusRecharge.usesLegacyAutomationDependency(installed) &&
            community.copy(campusRecharge = false, origins = previous.origins, dependencies = previous.dependencies)
                .runtimeContract() == previous.runtimeContract()
        if (installed != null && ((id != installed.id) || (signer != installed.signer) || (capability != installed.capability && !deviceMigration && !(installed.legacy() && this.isNative))
                    || (installed.isNative && authority != installed.authority) ||
                    !deviceMigration && !rechargeMigration && community?.runtimeContract() != previous?.runtimeContract()
                    || revision <= installed.revision)
        ) {
            throw SecurityException("升级需要同一发布者、相同权限和更高版本序号")
        }
    }

    fun legacy(): Boolean {
        return id.startsWith("hfut.") && ("band.display" == capability || "rear.display" == capability)
    }

    val isNative: Boolean
        get() = device != null || dex != null

    fun canRead(format: String?): Boolean {
        return ("text" == format || "qr" == format)
                && (capability == "payment." + format || "payment.text+qr" == capability)
    }

    fun bytes(): ByteArray? {
        return archive.clone()
    }

    companion object {
        val MAX_BYTES: Int = 1024 * 1024
        private const val HOST_SIGNER =
            "0358705ab19083603cce2aff2b519512a3e623b5e8f9d15a0edbcf6d58da135a"

        internal fun builtInBand(): PluginBundle = PluginBundle(
            JSONObject().put("id", BuiltInBand.ID).put("name", "小米手环 · Beta")
                .put("version", BuiltInBand.VERSION).put("revision", BuiltInBand.REVISION).put("api", 2)
                .put("capability", "page").put("community", JSONObject().put("backgroundLogin", true)),
            HOST_SIGNER, "", null, ByteArray(0)
        )

        @Throws(Exception::class)
        fun read(input: InputStream): PluginBundle {
            val archive: ByteArray = bounded(input, MAX_BYTES)
            val entries: MutableMap<String?, ByteArray?> = HashMap<String?, ByteArray?>()
            ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
                var entry: ZipEntry?
                while ((zip.getNextEntry().also { entry = it }) != null) {
                    val name = entry!!.getName()
                    val limit: Int
                    when (name) {
                        "manifest.json", "signer.der" -> limit = 4096
                        "main.js" -> limit = 64 * 1024
                        "signature.bin" -> limit = 1024
                        "classes.dex" -> limit = 1024 * 1024
                        else -> throw IOException("插件包含未知文件")
                    }
                    if (entry.isDirectory() || entries.containsKey(name)) throw IOException("插件包文件重复")
                    entries.put(name, bounded(zip, limit))
                }
            }
            if (!entries.keys.containsAll(
                    mutableListOf<String?>(
                        "manifest.json",
                        "main.js",
                        "signer.der",
                        "signature.bin"
                    )
                )
            ) throw IOException("插件包不完整")
            val manifestBytes = entries.get("manifest.json")
            val script = entries.get("main.js")
            val encodedCert = entries.get("signer.der")
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(encodedCert)) as X509Certificate
            cert.checkValidity()
            val signature = Signature.getInstance("SHA256withRSA")
            signature.initVerify(cert.getPublicKey())
            signature.update(manifestBytes)
            if (!signature.verify(entries.get("signature.bin"))) throw SecurityException("插件签名校验失败")
            val manifest = JSONObject(kotlin.text.String(manifestBytes!!, StandardCharsets.UTF_8))
            if (Companion.digest(script!!) != manifest.getString("scriptSha256")) throw SecurityException(
                "插件脚本校验失败"
            )
            return PluginBundle(
                manifest,
                digest(cert.getEncoded()),
                kotlin.text.String(script, StandardCharsets.UTF_8),
                entries.get("classes.dex"),
                archive
            )
        }

        @Throws(Exception::class)
        private fun digest(data: ByteArray): String {
            val hex = StringBuilder()
            for (b in MessageDigest.getInstance("SHA-256").digest(data)) {
                hex.append(Character.forDigit((b.toInt() ushr 4) and 15, 16))
                    .append(Character.forDigit(b.toInt() and 15, 16))
            }
            return hex.toString()
        }

        @Throws(IOException::class)
        private fun bounded(input: InputStream, limit: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var count: Int
            while ((input.read(buffer).also { count = it }) != -1) {
                if (count > limit - out.size()) throw IOException("插件包超过大小限制")
                out.write(buffer, 0, count)
            }
            return out.toByteArray()
        }
    }
}
