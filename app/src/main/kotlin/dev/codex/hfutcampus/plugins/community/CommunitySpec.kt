package dev.codex.hfutcampus

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Signed capabilities. Any change requires a new review and an explicit reinstallation. */
internal data class CommunitySpec(
    val storage: Boolean,
    val origins: Set<String>,
    val secrets: Set<String>,
    val exports: Set<String>,
    val campusExports: Set<String>,
    val dependencies: Map<String, Dependency>,
    val timer: Boolean,
    val campusSession: Boolean,
    val campusRecharge: Boolean,
    val backgroundLogin: Boolean,
    val spend: Spend?,
    val profile: Set<String>,
    val links: Set<String>
) {
    data class Dependency(val signer: String, val revision: Int, val methods: Set<String>)
    data class Spend(val path: String, val amountField: String, val orderField: String, val services: Set<String>)

    // This declaration only takes effect after a separate, account-bound task confirmation.
    fun runtimeContract() = copy(profile = emptySet(), links = emptySet(), backgroundLogin = false,
        spend = spend?.copy(services = emptySet()),
        dependencies = dependencies.mapValues { (_, pin) -> pin.copy(revision = 0) })

    companion object {
        const val CAMPUS = "http://121.251.19.62"
        val KEY = Regex("[a-z][a-z0-9._-]{0,39}")
        private val ID = Regex("[a-z][a-z0-9.-]{2,63}")
        private val PIN = Regex("[0-9a-f]{64}")

        fun backgroundLogin(data: JSONObject): Boolean =
            if (data.has("backgroundLogin")) data.get("backgroundLogin") as Boolean else false

        fun read(data: JSONObject, managedDevice: Boolean = false): CommunitySpec {
            if (managedDevice) require(data.keys().asSequence().all { it == "backgroundLogin" })
            require(data.keys().asSequence().all {
                it in setOf("storage", "origins", "secrets", "exports", "campusExports", "dependencies",
                    "timer", "campusSession", "campusRecharge", "backgroundLogin", "spend", "profile", "links")
            }) { "未知社区权限" }
            fun flag(key: String) = if (data.has(key)) data.get(key) as Boolean else false
            val origins = strings(data, "origins", 6).onEach {
                val uri = URI(it)
                require(uri.host != null && uri.userInfo == null && uri.rawPath.isEmpty() &&
                    uri.rawQuery == null && uri.rawFragment == null &&
                    (uri.scheme == "https" || it == CAMPUS)) { "联网权限必须为明确的来源" }
                require(it == origin(uri)) { "联网来源必须使用标准格式" }
            }
            val dependencies = data.optJSONObject("dependencies") ?: JSONObject()
            require(!data.has("dependencies") || data.optJSONObject("dependencies") != null)
            require(dependencies.length() <= 8)
            val needed = dependencies.keys().asSequence().associateWith { id ->
                require(ID.matches(id))
                val pin = dependencies.getJSONObject(id)
                val signer = pin.getString("signer")
                val revision = pin.getInt("minRevision")
                require(PIN.matches(signer) && revision > 0)
                Dependency(signer, revision, strings(pin, "methods", 8).onEach { require(KEY.matches(it)) })
            }
            val campus = flag("campusSession")
            val recharge = flag("campusRecharge")
            val timer = flag("timer")
            val background = backgroundLogin(data)
            require(!campus || if (recharge) origins.isEmpty() else origins == setOf(CAMPUS))
            require(!background || managedDevice || timer && campus) { "后台登录需要定时任务与校园会话权限" }
            require(!recharge || campus && background && timer && needed.isEmpty() && !data.has("spend")) {
                "校园费用充值需要后台校园会话，不能与其他充值接口混用"
            }
            val spend = if (data.has("spend")) data.getJSONObject("spend").let {
                val path = it.getString("path")
                val amount = it.getString("amountField")
                val order = it.getString("orderField")
                require(campus && path == "/blade-pay/pay" && amount == "tranamt" && order == "orderid")
                val services = if (it.has("services")) strings(it, "services", 3) else setOf("electric")
                require(services.isNotEmpty() && CommunityBudget.Service.entries.map { service -> service.key }.containsAll(services))
                Spend(path, amount, order, services)
            } else null
            val secrets = strings(data, "secrets", 8).onEach { require(KEY.matches(it)) }
            val exports = strings(data, "exports", 8).onEach { require(KEY.matches(it)) }
            val campusExports = strings(data, "campusExports", 8)
            val profile = strings(data, "profile", CampusFeeProfile.labels.size)
            val links = strings(data, "links", 8).onEach {
                val uri = URI(it)
                require(uri.scheme == "https" && uri.host != null && uri.userInfo == null)
            }
            require(CampusFeeProfile.labels.keys.containsAll(profile)) { "未知校园资料权限" }
            require(spend == null || "payment-password" in secrets)
            require(exports.containsAll(campusExports) && (campus || campusExports.isEmpty()))
            return CommunitySpec(
                flag("storage"), origins, secrets, exports, campusExports,
                needed, timer, campus, recharge, background, spend, profile, links
            )
        }

        fun origin(uri: URI): String {
            val host = requireNotNull(uri.host).lowercase()
            val port = uri.port
            val default = if (uri.scheme == "https") 443 else 80
            return uri.scheme + "://" + host + if (port == -1 || port == default) "" else ":$port"
        }

        private fun strings(data: JSONObject, key: String, limit: Int): Set<String> {
            val array = if (data.has(key)) data.getJSONArray(key) else JSONArray()
            require(array.length() <= limit)
            val values = (0 until array.length()).map { array.getString(it) }
            require(values.all { it.length in 1..240 } && values.distinct().size == values.size)
            return values.toSet()
        }
    }
}
