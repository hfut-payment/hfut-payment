package dev.codex.hfutcampus

import dev.codex.hfutcampus.CampusFees.Reading
import dev.codex.hfutcampus.CampusFees.RoomOption
import dev.codex.hfutcampus.CampusFees.fields
import dev.codex.hfutcampus.CampusFees.parse
import dev.codex.hfutcampus.CampusFees.roomFields
import dev.codex.hfutcampus.CampusFees.rooms
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.UnsupportedEncodingException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Collections
import kotlin.Array
import kotlin.AssertionError
import kotlin.ByteArray
import kotlin.Exception
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.Throws
import kotlin.also
import kotlin.arrayOf
import kotlin.math.min

/** Shared, serialized campus-card session. No Android dependency, no sensitive logging.  */
class CampusApi(private val transport: Transport, private val clock: Clock) {
    fun interface Clock {
        fun elapsed(): Long
    }

    fun interface Transport {
        @Throws(Failure::class)
        fun call(
            method: String,
            path: String,
            token: String,
            values: Map<String, String>,
            login: Boolean
        ): JSONObject
    }

    class Failure(val kind: String, message: String?, val retryable: Boolean) : Exception(message)
    class Account internal constructor(
        val id: String,
        val label: String,
        val account: String,
        val payacc: String,
        val paytype: String
    )

    class Result internal constructor(
        val code: String,
        val label: String,
        val deadline: Long,
        val recovered: Boolean,
        val accounts: List<Account>
    )

    private var username = ""
    private var password = ""
    private var token = ""
    private var branch = "codebarPayinfo"
    private var selected = ""
    private var available: MutableList<Account> = mutableListOf<Account>()
    private var blocked = false
    internal var loginAllowed: () -> Boolean = { true }
    internal var recoveryAllowed: () -> Boolean = { true }

    @Synchronized
    internal fun authenticate() {
        if (username.isEmpty() || password.isEmpty() || blocked) {
            throw Failure("credentials", "请检查登录信息。", false)
        }
        if (token.isEmpty()) login()
    }

    @Synchronized
    internal fun saveCredentials(store: CampusCredentials) {
        check(username.isNotEmpty() && password.isNotEmpty())
        store.save(username, password, branch)
    }

    @Synchronized
    fun configure(username: String, password: String, branch: String) {
        this.username = username.trim { it <= ' ' }
        this.password = password
        this.branch = branch
        token = ""
        selected = ""
        blocked = false
        available = mutableListOf<Account>()
    }

    @Synchronized
    fun select(id: String) {
        selected = id
    }

    @Synchronized
    fun clear() {
        configure("", "", "codebarPayinfo")
    }

    @Throws(Failure::class)
    private fun login(allowed: () -> Boolean = loginAllowed) {
        if (!allowed()) throw Failure("login_paused", "请在手机上打开应用登录。", false)
        token = ""
        available = mutableListOf<Account>()
        val form = LinkedHashMap<String, String>()
        form.put("username", username)
        form.put("password", password)
        form.put("grant_type", "password")
        form.put("logintype", "sno")
        try {
            val json = transport.call("POST", "/berserker-auth/oauth/token", "", form, true)
            val raw = json.opt("access_token")
            if (raw !is String || raw.trim { it <= ' ' }.isEmpty()) throw Failure(
                "credentials",
                "登录失败，请核对学号和校园卡密码。",
                false
            )
            var value = raw.trim { it <= ' ' }
            while (value.lowercase().startsWith("bearer ")) value =
                value.substring(7).trim { it <= ' ' }
            if (value.isEmpty() || value.contains("\r") || value.contains("\n")) throw Failure(
                "protocol",
                "登录令牌格式不正确。",
                false
            )
            token = "bearer " + value
        } catch (error: Failure) {
            if (error.kind == "credentials") blocked = true
            throw error
        }
    }

    private fun recoverLogin() {
        if (!recoveryAllowed()) throw Failure("session_expired", "登录已失效，请重新登录。", false)
        login()
    }

    @Synchronized
    @Throws(Failure::class)
    fun refresh(): Result {
        if (username.isEmpty() || password.isEmpty()) throw Failure(
            "configuration",
            "请先在手机填写学号和校园卡密码。",
            false
        )
        if (blocked) throw Failure("credentials", "登录信息被拒绝，请修改后重新连接。", false)
        if (token.isEmpty()) login()
        var recovered = false
        for (attempt in 0..1) {
            try {
                if (available.isEmpty()) {
                    val listing = transport.call(
                        "GET",
                        "/berserker-app/ykt/tsm/" + branch,
                        token,
                        mutableMapOf<String, String>(),
                        false
                    )
                    available = Collections.unmodifiableList<Account?>(accounts(listing, branch))
                }
                val accounts = available
                if (accounts.isEmpty()) throw Failure(
                    "account",
                    "没有可用账户，请检查卡片或切换账户接口。",
                    false
                )
                var account: Account? = null
                for (a in accounts) if (a.id == selected) account = a
                if (!selected.isEmpty() && account == null) throw Failure(
                    "account",
                    "所选账户已不可用，请在手机重新选择。",
                    false
                )
                if (account == null) account = accounts.get(0)
                val args = LinkedHashMap<String, String>()
                args.put("account", account.account)
                args.put("payacc", account.payacc)
                args.put("paytype", account.paytype)
                val started = clock.elapsed()
                val reply = transport.call(
                    "GET",
                    "/berserker-app/ykt/tsm/batchGetBarCodeGet",
                    token,
                    args,
                    false
                )
                val data = reply.optJSONObject("data")
                val codes = if (data == null) null else data.optJSONArray("barcode")
                if (codes == null || codes.length() == 0 || (codes.opt(0) !is String)) throw Failure(
                    "barcode",
                    "取码接口没有返回可用的二维码。",
                    false
                )
                val code = codes.opt(0) as String
                if (code.isEmpty() || code.length > 2048) throw Failure(
                    "protocol",
                    "付款码格式不正确。",
                    false
                )
                val seconds = data!!.optDouble("expires", Double.Companion.NaN)
                if (!seconds.isFinite() || seconds <= 0) throw Failure(
                    "protocol",
                    "取码接口未返回有效期。",
                    false
                )
                val deadline = started + (min(60.0, seconds) * 1000).toLong()
                if (deadline - clock.elapsed() < 2000) throw Failure(
                    "network",
                    "响应过慢，码剩余时间不足，请重试。",
                    true
                )
                selected = account.id
                return Result(code, account.label, deadline, recovered, accounts)
            } catch (error: Failure) {
                if (error.kind != "auth") {
                    if (error.kind == "account" || error.kind == "business") available =
                        mutableListOf<Account>()
                    throw error
                }
                token = ""
                if (attempt > 0) throw Failure(
                    "auth_conflict",
                    "重登后仍被拒绝，请稍后重试，避免多端互相顶号。",
                    false
                )
                recoverLogin()
                recovered = true
            }
        }
        throw auth()
    }

    @Synchronized
    @Throws(Failure::class)
    fun queryFee(kind: CampusFees.Kind, profile: CampusFees.Profile, ac: Boolean): Reading {
        val unavailable = profile.unavailable(kind, ac)
        if (!unavailable.isEmpty()) throw Failure("fee_configuration", unavailable, false)
        val card = kind == CampusFees.Kind.CARD
        val path =
            if (card) "/berserker-app/ykt/tsm/getCampusCards" else "/charge/feeitem/getThirdData"
        val fields: Map<String, String> =
            if (card) mutableMapOf<String, String>() else fields(kind, profile, ac)
        var selectedCard = if (selected.startsWith("card:")) selected else ""
        if (card && selectedCard.isEmpty()) {
            for (account in available) {
                if (account.id == selected && "校园卡" == account.label) {
                    selectedCard = "card:" + account.account
                    break
                }
            }
        }
        return parse(
            kind,
            feeRequest(if (card) "GET" else "POST", path, fields),
            selectedCard,
            profile.hefei()
        )
    }

    @Synchronized
    @Throws(Failure::class)
    fun queryRooms(building: String): List<RoomOption> {
        return rooms(feeRequest("POST", "/charge/feeitem/getThirdData", roomFields(building)))
    }

    @Synchronized
    internal fun queryBills(page: Int): CampusBills.Page {
        require(page in 1..CampusBills.MAX_PAGE)
        val fields = mapOf("current" to page.toString(), "size" to CampusBills.PAGE_SIZE.toString())
        return CampusBills.parse(feeRequest("GET", CampusBills.PATH, fields), page)
    }

    @Throws(Failure::class)
    private fun feeRequest(
        method: String,
        path: String,
        fields: Map<String, String>,
        approvedJob: (() -> Boolean)? = null
    ): JSONObject {
        if (username.isEmpty() || password.isEmpty() || blocked) throw Failure(
            "credentials",
            "请检查登录",
            false
        )
        if (token.isEmpty()) login(approvedJob ?: loginAllowed)
        var attempt = 0
        while (true) {
            try {
                if (approvedJob != null && !approvedJob()) {
                    throw Failure("login_paused", "自动充值任务已暂停或取消。", false)
                }
                return transport.call(method, path, token, fields, false)
            } catch (failure: Failure) {
                if ("auth" != failure.kind) throw failure
                token = ""
                if (attempt != 0) throw failure
                if (approvedJob != null) login(approvedJob) else recoverLogin()
            }
            attempt++
        }
    }

    @Synchronized
    internal fun verifyCommunityJobSession(approvedJob: () -> Boolean) {
        feeRequest("GET", "/berserker-app/ykt/tsm/getCampusCards", emptyMap(), approvedJob)
    }

    @Synchronized
    internal fun communityToken(): String {
        check(username.isNotEmpty() && password.isNotEmpty() && !blocked) { "请先登录校园卡" }
        if (token.isEmpty()) login()
        return token
    }

    class HttpTransport : Transport {
        @Throws(Failure::class)
        override fun call(
            method: String,
            path: String,
            token: String,
            values: Map<String, String>,
            login: Boolean
        ): JSONObject {
            var connection: HttpURLConnection? = null
            try {
                val query = LinkedHashMap<String, String>()
                query.put("synAccessSource", if (path == CampusBills.PATH) "h5" else "app")
                if (method == "GET") query.putAll(values)
                val url = URL("http://121.251.19.62" + path + "?" + encode(query))
                connection = url.openConnection() as HttpURLConnection?
                connection!!.setInstanceFollowRedirects(false)
                val fees =
                    path.endsWith("/getCampusCards") || path == "/charge/feeitem/getThirdData"
                connection.setConnectTimeout(if (fees) 4000 else 10000)
                connection.setReadTimeout(if (fees) 4000 else 10000)
                connection.setRequestMethod(method)
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "HFUT-Campus-Companion/0.2")
                if (!token.isEmpty()) connection.setRequestProperty("synjones-auth", token)
                if (login) {
                    connection.setRequestProperty(
                        "Authorization", "Basic " + Base64.getEncoder().encodeToString(
                            "mobile_service_platform:mobile_service_platform_secret".toByteArray(
                                StandardCharsets.UTF_8
                            )
                        )
                    )
                }
                if ("POST" == method) {
                    connection.setDoOutput(true)
                    connection.setRequestProperty(
                        "Content-Type",
                        "application/x-www-form-urlencoded"
                    )
                    connection.getOutputStream().use { stream ->
                        stream.write(
                            encode(values).toByteArray(
                                StandardCharsets.UTF_8
                            )
                        )
                    }
                }
                val status = connection.getResponseCode()
                val input =
                    if (status >= 400) connection.getErrorStream() else connection.getInputStream()
                val output = ByteArrayOutputStream()
                if (input != null) input.use { stream ->
                    val buffer = ByteArray(4096)
                    var n: Int
                    while ((stream.read(buffer).also { n = it }) != -1) {
                        output.write(buffer, 0, n)
                        if (output.size() > 1000000) throw IOException("size")
                    }
                }
                return decode(status, String(output.toByteArray(), StandardCharsets.UTF_8), login)
            } catch (e: IOException) {
                throw Failure("network", "无法连接校园卡服务器，请检查手机网络。", true)
            } finally {
                if (connection != null) connection.disconnect()
            }
        }
    }

    companion object {
        private fun auth(): Failure {
            return Failure("auth", "校园卡登录已失效。", false)
        }

        fun accounts(payload: JSONObject, branch: String): MutableList<Account?> {
            val out: MutableList<Account?> = ArrayList<Account?>()
            if (branch == "codebarPayinfo") {
                val rows = payload.optJSONArray("data")
                if (rows == null) return out
                for (i in 0..<rows.length()) {
                    val row = rows.optJSONObject(i)
                    if (row == null) continue
                    val code = row.optString("code")
                    if ((code != "CARD" && code != "ACCOUNT") || row.optInt(
                            "lostflag",
                            0
                        ) == 1
                    ) continue
                    if (row.isNull("account") || row.isNull("payacc") || row.isNull("paytype")) continue
                    out.add(
                        Account(
                            row.optString("id", i.toString()),
                            if (code == "CARD") "校园卡" else "电子账户",
                            row.optString("account"),
                            row.optString("payacc"),
                            row.optString("paytype")
                        )
                    )
                }
            } else {
                val data = payload.optJSONObject("data")
                val rows = if (data == null) null else data.optJSONArray("card")
                if (rows == null) return out
                for (i in 0..<rows.length()) {
                    val row = rows.optJSONObject(i)
                    if (row == null || row.optInt("lostflag", 0) == 1) continue
                    val account = row.optString("account")
                    if (!account.isEmpty()) out.add(
                        Account(
                            "card:" + account,
                            "校园卡",
                            account,
                            "###",
                            "1"
                        )
                    )
                    val subs = row.optJSONArray("accinfo")
                    if (subs == null) continue
                    for (j in 0..<subs.length()) {
                        val sub = subs.optJSONObject(j)
                        if (sub == null) continue
                        val type = sub.optString("type")
                        val pair: Array<String?> =
                            type.split("-".toRegex(), limit = 2).toTypedArray()
                        if (pair.size == 2 && !pair[0]!!.isEmpty() && !pair[1]!!.isEmpty()) out.add(
                            CampusApi.Account(
                                "account:" + type,
                                "电子账户",
                                pair[0]!!,
                                pair[1]!!,
                                "1"
                            )
                        )
                    }
                }
            }
            return out
        }

        @Throws(Failure::class)
        fun decode(http: Int, text: String, login: Boolean): JSONObject {
            if (http == 401 || http == 403 || http == 419 || (http >= 300 && http < 400)) {
                if (login) throw Failure("credentials", "登录失败，请核对学号和校园卡密码。", false)
                throw auth()
            }
            if (http == 429) throw Failure("rate_limit", "请求过于频繁，请稍后重试。", true)
            if (http >= 500) throw Failure("network", "校园卡服务暂时不可用。", true)
            val json: JSONObject
            try {
                json = JSONObject(if (text.startsWith("\uFEFF")) text.substring(1) else text)
            } catch (e: Exception) {
                throw Failure("protocol", "校园卡服务未返回预期数据。", false)
            }
            val code = json.optString("code", "")
            val msg = json.optString("msg", json.optString("message", "")).lowercase()
            val words = arrayOf<String?>(
                "未登录",
                "未登陆",
                "登录失效",
                "登陆失效",
                "登录过期",
                "登陆过期",
                "重新登录",
                "重新登陆",
                "其他设备",
                "其它设备",
                "异地登录",
                "token失效",
                "token过期",
                "令牌失效",
                "令牌过期",
                "invalid_token",
                "invalid token",
                "expired token",
                "unauthorized"
            )
            var expired = code == "401" || code == "403" || code == "419"
            for (word in words) if (msg.contains(word!!)) expired = true
            if (expired) {
                if (login) throw Failure("credentials", "登录失败，请核对学号和校园卡密码。", false)
                throw auth()
            }
            if (login && (http >= 400 || json.has("error"))) throw Failure(
                "credentials",
                "登录失败，请核对学号和校园卡密码。",
                false
            )
            if (http >= 400 || (code.isNotEmpty() && code != "200" && code != "0") || json.opt("success") == false) throw Failure(
                "business",
                "校园卡接口拒绝请求，请检查卡片状态。",
                false
            )
            return json
        }

        fun encode(values: Map<String, String>): String {
            val out = StringBuilder()
            for (e in values.entries) {
                if (out.length > 0) out.append('&')
                try {
                    out.append(URLEncoder.encode(e.key, "UTF-8")).append('=')
                        .append(URLEncoder.encode(e.value, "UTF-8"))
                } catch (impossible: UnsupportedEncodingException) {
                    throw AssertionError(impossible)
                }
            }
            return out.toString()
        }
    }
}
