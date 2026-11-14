package dev.codex.hfutcampus

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.codex.hfutcampus.CampusApi.HttpTransport
import dev.codex.hfutcampus.CampusFees.Reading
import dev.codex.hfutcampus.CampusFees.Reading.Companion.status
import dev.codex.hfutcampus.CampusFees.RoomOption
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.mutableListOf
import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlin.math.min

/** Process-wide account, session and in-flight request shared by all displays.  */
class CampusRuntime {
    fun interface Listener {
        fun onState(state: CardSnapshot)
    }

    fun interface Completion {
        fun onComplete(state: CardSnapshot)
    }

    fun interface SaveCompletion {
        fun complete(success: Boolean, message: String?)
    }

    internal fun interface FeeCompletion {
        fun complete(value: Reading?)
    }

    internal fun interface RoomCompletion {
        fun complete(options: List<RoomOption>)
    }

    internal interface Dispatcher {
        fun post(r: Runnable)
        fun postDelayed(r: Runnable, delay: Long)
        fun remove(r: Runnable) {}
    }

    internal interface Clock {
        fun elapsed(): Long
        fun wall(): Long
    }

    val instanceId: String = UUID.randomUUID().toString()
    private val api: CampusApi
    private val credentials: CampusCredentials?
    private val worker: Executor
    private val main: Dispatcher
    private val clock: Clock
    private val recovery: RecoveryPreferences?
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val waiting: MutableList<Completion> = ArrayList()

    @Volatile
    private var snapshot: CardSnapshot =
        CardSnapshot.Companion.hidden(0, false, "请配置校园卡账户。", "configuration")

    @Volatile
    private var accounts: List<CampusApi.Account> = emptyList()

    @Volatile
    var isConfigured: Boolean = false
        private set

    @Volatile
    var isRemembered: Boolean = false
        private set
    @Volatile private var interactive = false
    @Volatile private var backgroundLogin = false
    @Volatile var loginInProgress = false
        private set
    @Volatile var loginError: String? = null
        private set
    private var nextBackgroundLoginAt = Long.MAX_VALUE
    private var backgroundLoginFailures = 0

    @Volatile
    private var feeProfileKey = ""
    @Volatile
    private var studentAccount = ""
    private var epoch: Long = 0
    private var revision: Long = 0
    private var retryAfter: Long = 0
    private var nextRequestAt: Long = 0
    private var foregroundDemand = false
    private val pluginLeases: MutableMap<String?, Long?> = HashMap<String?, Long?>()
    private val nativeDemand: MutableMap<String?, Long?> = HashMap<String?, Long?>()
    private val balanceUsers = mutableSetOf<String>()
    private val balanceListeners = CopyOnWriteArrayList<Runnable>()
    private val balanceWaiters = mutableListOf<FeeCompletion>()
    private var balanceValue: BalanceSnapshot? = null
    private var balanceInFlight = false
    private var nextBalanceAt = 0L
    private val scheduledBalance = Runnable { runScheduledBalance() }
    private val scheduledRefresh = Runnable { this.runScheduledRefresh() }
    private var completedFetches: Long = 0
    private var successfulFetches: Long = 0
    private var restoredSessions: Long = 0
    private var failures = 0

    @get:Synchronized
    var isBusy: Boolean = false
        private set
    private var automaticRetry = true

    private constructor(context: Context) {
        api = CampusApi(HttpTransport(), CampusApi.Clock { SystemClock.elapsedRealtime() })
        credentials = CampusCredentials(context)
        recovery = RecoveryPreferences(context)
        worker = Executors.newSingleThreadExecutor()
        val handler = Handler(Looper.getMainLooper())
        main = object : Dispatcher {
            override fun post(r: Runnable) {
                handler.post(r)
            }

            override fun postDelayed(r: Runnable, delay: Long) {
                handler.postDelayed(r, delay)
            }

            override fun remove(r: Runnable) {
                handler.removeCallbacks(r)
            }
        }
        clock = object : Clock {
            override fun elapsed(): Long {
                return SystemClock.elapsedRealtime()
            }

            override fun wall(): Long {
                return System.currentTimeMillis()
            }
        }
        api.loginAllowed = { interactive || backgroundLogin }
        api.recoveryAllowed = { backgroundLogin }
    }

    // Inject only scheduling, clock and transport for deterministic concurrency tests.
    @JvmOverloads
    internal constructor(
        api: CampusApi,
        main: Dispatcher,
        worker: Executor,
        clock: Clock,
        recovery: RecoveryPreferences? = null
    ) {
        this.api = api
        this.main = main
        this.worker = worker
        this.clock = clock
        this.credentials = null
        this.recovery = recovery
    }

    @Synchronized
    fun configurationRevision(): Long {
        return epoch
    }

    fun current(): CardSnapshot {
        return snapshot.at(clock.elapsed(), clock.wall())
    }

    fun feeProfileKey(): String {
        return feeProfileKey
    }

    internal fun studentAccount(): String = studentAccount

    fun savedLoginAvailable(): Boolean =
        credentials?.hasSaved() == true && recovery?.credentialsUsable() == true

    private fun canFetch(): Boolean = credentials == null || interactive || backgroundLogin

    @Synchronized
    fun setInteractive(visible: Boolean) {
        interactive = visible
        reschedule()
        rescheduleBalance()
    }

    @Synchronized
    fun setBackgroundLogin(enabled: Boolean) {
        if (backgroundLogin == enabled) return
        backgroundLogin = enabled
        reschedule()
        rescheduleBalance()
    }

    private data class Login(val user: String, val password: String, val branch: String)

    @Synchronized
    fun restoreLogin(remember: Boolean? = null, completion: SaveCompletion? = null) {
        if (!savedLoginAvailable()) return
        startLogin(remember, completion, read = ::savedLogin)
    }

    private fun savedLogin(): Login {
        val saved = checkNotNull(credentials?.load())
        return Login(saved.getString("username"), saved.getString("password"), saved.getString("branch"))
    }

    @Synchronized
    internal fun prepareCommunityJob(account: String, authorized: () -> Boolean, completion: SaveCompletion) {
        if (!savedLoginAvailable()) {
            main.post(Runnable { completion.complete(false, "请在手机登录并记住密码后再运行。") })
            return
        }
        if (!isConfigured && !loginInProgress) {
            startLogin(null, completion, approvedJob = authorized) {
                savedLogin().also {
                    check(feeKey(it.user) == account) { "已保存的校园卡账户与任务不符。" }
                }
            }
            return
        }
        // The same worker serializes this check behind any ongoing login.
        val expected = epoch
        worker.execute {
            var message: String? = null
            try {
                check(expected == configurationRevision() && isConfigured && feeProfileKey == account) {
                    "当前校园卡账户与任务不符，请重新确认。"
                }
                api.verifyCommunityJobSession(authorized)
            } catch (failure: Exception) {
                message = if (failure is CampusApi.Failure || failure is IllegalStateException) failure.message
                    else "暂时无法恢复校园卡会话。"
            }
            finishSave(expected, completion, message == null, message)
        }
    }

    @Synchronized
    fun signIn(user: String, password: String, branch: String, remember: Boolean, completion: SaveCompletion) {
        require(user.isNotBlank() && password.isNotEmpty()) { "请填写学号和校园卡密码。" }
        require(branch == "codebarPayinfo" || branch == "queryCard")
        startLogin(remember, completion) { Login(user.trim(), password, branch) }
    }

    private fun startLogin(
        remember: Boolean?, completion: SaveCompletion?,
        approvedJob: (() -> Boolean)? = null, read: () -> Login
    ) {
        if (isConfigured || loginInProgress || (approvedJob == null && !canFetch())) return
        loginInProgress = true
        loginError = null
        nextBackgroundLoginAt = Long.MAX_VALUE
        changed("正在登录…", "loading")
        val expected = epoch
        worker.execute {
            var verified: Login? = null
            var error: String? = null
            var retryable = false
            try {
                check(expected == configurationRevision())
                val login = read()
                api.configure(login.user, login.password, login.branch)
                if (approvedJob == null) api.authenticate() else {
                    api.verifyCommunityJobSession(approvedJob)
                    check(approvedJob()) { "自动任务已暂停或取消。" }
                }
                check(expected == configurationRevision())
                if (remember != null && credentials != null) {
                    recovery?.credentialsUsable(false)
                    if (remember) api.saveCredentials(credentials) else credentials.clear()
                }
                verified = login
            } catch (failure: CampusApi.Failure) {
                error = failure.message
                retryable = failure.retryable
            } catch (failure: IllegalStateException) {
                error = failure.message ?: "登录信息无法安全读取或保存，请重新输入。"
            } catch (_: Exception) {
                error = "登录信息无法安全读取或保存，请重新输入。"
            }
            if (verified == null) api.clear()
            synchronized(this) {
                if (expected != epoch) return@execute
                loginInProgress = false
                loginError = error
                if (verified != null) {
                    if (remember != null) recovery?.credentialsUsable(remember)
                    studentAccount = verified.user
                    feeProfileKey = feeKey(verified.user)
                    isRemembered = savedLoginAvailable()
                    isConfigured = true
                    backgroundLoginFailures = 0
                } else if (retryable && remember == null && approvedJob == null && backgroundLogin) {
                    val delay = min(60_000L, 5_000L shl min(backgroundLoginFailures++, 4))
                    nextBackgroundLoginAt = clock.elapsed() + delay
                }
                publish(CardSnapshot.hidden(++revision, false, error ?: "已登录", if (error == null) "" else "login"))
                finishSave(expected, completion, verified != null, error)
            }
        }
    }

    @Synchronized
    fun setRememberPassword(remember: Boolean, completion: SaveCompletion) {
        val store = credentials ?: return
        if (!isConfigured) return
        val expected = epoch
        worker.execute {
            if (expected != configurationRevision()) return@execute
            var error: String? = null
            try {
                recovery?.credentialsUsable(false)
                if (remember) api.saveCredentials(store) else store.clear()
            } catch (_: Exception) {
                error = "未能修改已保存的密码，请重试。"
            }
            synchronized(this) {
                if (expected != epoch) return@synchronized
                if (error == null) recovery?.credentialsUsable(remember)
                isRemembered = savedLoginAvailable()
                notifyListeners()
                finishSave(expected, completion, error == null, error)
            }
        }
    }

    @Synchronized
    fun setForegroundDemand(visible: Boolean) {
        foregroundDemand = visible
        reschedule()
    }

    @Synchronized
    fun setNativeDemand(id: String?, until: Long) {
        if (until <= clock.elapsed()) {
            if (nativeDemand.remove(id) == null) return
        } else if (nativeDemand.put(id, until) == until) return
        reschedule()
        rescheduleBalance()
        notifyListeners()
    }

    @Synchronized
    internal fun balance(id: String): BalanceSnapshot? =
        balanceValue?.takeIf { id in balanceUsers && isConfigured && it.expiresElapsed > clock.elapsed() }

    @Synchronized
    internal fun setBalanceDemand(id: String?, enabled: Boolean) {
        if (id == null) return
        if (if (enabled) balanceUsers.add(id) else balanceUsers.remove(id)) rescheduleBalance()
    }

    internal fun observeBalance(listener: Runnable) { balanceListeners.addIfAbsent(listener) }

    internal fun unobserveBalance(listener: Runnable) { balanceListeners.remove(listener) }

    private fun publishBalance(value: BalanceSnapshot?) {
        if (balanceValue == value) return
        balanceValue = value
        main.post(Runnable { balanceListeners.forEach(Runnable::run) })
    }

    private fun needsBalance(now: Long): Boolean = isConfigured && canFetch() &&
        balanceUsers.any { (nativeDemand[it] ?: 0L) > now }

    private fun rescheduleBalance() {
        main.remove(scheduledBalance)
        val now = clock.elapsed()
        val expiry = balanceValue?.expiresElapsed ?: Long.MAX_VALUE
        val refresh = if (!balanceInFlight && needsBalance(now)) nextBalanceAt else Long.MAX_VALUE
        val next = minOf(expiry, refresh)
        if (next != Long.MAX_VALUE) main.postDelayed(scheduledBalance, (next - now).coerceAtLeast(0L))
    }

    @Synchronized
    private fun runScheduledBalance() {
        val now = clock.elapsed()
        if (balanceValue?.let { it.expiresElapsed <= now } == true) publishBalance(null)
        if (!balanceInFlight && needsBalance(now) && now >= nextBalanceAt) requestBalance()
        rescheduleBalance()
    }

    @Synchronized
    fun setPluginVisible(id: String?, visible: Boolean) {
        val now = clock.elapsed()
        pluginLeases.values.removeIf { deadline: Long? -> deadline!! <= now }
        if (!visible) pluginLeases.remove(id)
        else if (pluginLeases.size < 64 || pluginLeases.containsKey(id)) pluginLeases.put(
            id,
            now + 35000
        )
        reschedule()
    }

    @Synchronized
    fun hasDisplayDemand(): Boolean {
        val now = clock.elapsed()
        return foregroundDemand || nativeDemand.values.stream()
            .anyMatch { deadline: Long? -> now < deadline!! }
                || pluginLeases.values.stream().anyMatch { deadline: Long? -> now < deadline!! }
    }

    private fun reschedule() {
        main.remove(scheduledRefresh)
        val delay = if (!isConfigured && !loginInProgress && backgroundLogin && savedLoginAvailable() &&
            nextBackgroundLoginAt != Long.MAX_VALUE
        ) max(0L, nextBackgroundLoginAt - clock.elapsed()) else nextRefreshDelayMs()
        if (hasDisplayDemand() && delay != Long.Companion.MAX_VALUE) main.postDelayed(
            scheduledRefresh,
            delay
        )
    }

    @Synchronized
    private fun runScheduledRefresh() {
        if (!hasDisplayDemand()) return
        if (!isConfigured && backgroundLogin && clock.elapsed() >= nextBackgroundLoginAt) {
            restoreLogin()
            return
        }
        if (nextRefreshDelayMs() == 0L) requestCard(null)
        else reschedule()
    }

    @Synchronized
    fun completedFetches(): Long {
        return completedFetches
    }

    @Synchronized
    fun successfulFetches(): Long {
        return successfulFetches
    }

    @Synchronized
    fun restoredSessions(): Long {
        return restoredSessions
    }

    @Synchronized
    fun nextRefreshDelayMs(): Long {
        if (!this.isConfigured || this.isBusy || !automaticRetry || !canFetch()) return Long.Companion.MAX_VALUE
        val now = clock.elapsed()
        val s = current()
        return max(
            max(0, max(retryAfter, nextRequestAt) - now),
            if (s.ready) max(0, s.remainingMs - 5000) else 0
        )
    }

    fun accounts(): List<CampusApi.Account> {
        return accounts!!
    }

    fun addListener(l: Listener) {
        listeners.addIfAbsent(l)
        main.post(Runnable { l.onState(current()) })
    }

    fun removeListener(l: Listener?) {
        listeners.remove(l)
    }

    private fun notifyListeners() {
        main.post(Runnable {
            val s = current()
            for (l in listeners) l.onState(s)
        })
    }

    private fun publish(s: CardSnapshot) {
        snapshot = s
        if (credentials != null) PaymentCodes.Companion.shared().discardBefore(s.revision)
        reschedule()
        notifyListeners()
    }

    private fun changed(status: String?, kind: String?) {
        epoch++
        balanceInFlight = false
        nextBalanceAt = 0L
        publishBalance(null)
        val cancelledBalance = balanceWaiters.toList()
        balanceWaiters.clear()
        if (cancelledBalance.isNotEmpty()) main.post(Runnable { cancelledBalance.forEach { it.complete(null) } })
        rescheduleBalance()
        retryAfter = 0
        nextRequestAt = 0
        failures = 0
        automaticRetry = true
        accounts = emptyList()
        publish(CardSnapshot.Companion.hidden(++revision, false, status, kind))
        val cancelled: MutableList<Completion> = ArrayList<Completion>(waiting)
        waiting.clear()
        main.post(Runnable {
            for (c in cancelled) c.onComplete(
                CardSnapshot.Companion.hidden(
                    revision,
                    false,
                    "配置已改变，请重试。",
                    "cancelled"
                )
            )
        })
    }

    @Synchronized
    fun configure(user: String, password: String, branch: String, remember: Boolean) {
        configure(user, password, branch, remember, null)
    }

    @Synchronized
    fun configure(
        user: String,
        password: String,
        branch: String,
        remember: Boolean,
        completion: SaveCompletion?
    ) {
        require(!(user.trim { it <= ' ' }
            .isEmpty() || password.isEmpty())) { "请填写学号和校园卡密码。" }
        require(!(branch != "codebarPayinfo" && branch != "queryCard")) { "账户接口不正确。" }
        feeProfileKey = feeKey(user)
        studentAccount = user.trim()
        this.isConfigured = true
        this.isRemembered = false
        changed("登录信息已更新。", "")
        if (recovery != null) recovery.credentialsUsable(false)
        val expected = epoch
        worker.execute(Runnable {
            synchronized(this) {
                if (expected != epoch) {
                    finishSave(expected, completion, false, "配置已改变，请重试")
                    return@Runnable
                }
            }
            api.configure(user, password, branch)
            var saved = false
            var problem = ""
            try {
                if (credentials != null) {
                    if (remember) {
                        credentials.save(user, password, branch)
                        saved = true
                    } else credentials.clear()
                }
            } catch (e: Exception) {
                problem = "账户未能安全保存，请重试"
            }
            synchronized(this) {
                if (expected != epoch) {
                    finishSave(expected, completion, false, "配置已改变，请重试")
                    return@Runnable
                }
                this.isRemembered = saved
                if (recovery != null) recovery.credentialsUsable(saved)
                if (!problem.isEmpty()) publish(
                    CardSnapshot.Companion.hidden(
                        ++revision,
                        false,
                        problem,
                        "storage"
                    )
                )
                else notifyListeners()
                finishSave(expected, completion, problem.isEmpty(), problem)
            }
        })
    }

    private fun finishSave(expected: Long, c: SaveCompletion?, success: Boolean, message: String?) {
        if (c != null) main.post(Runnable {
            val current = expected == configurationRevision()
            c.complete(current && success, if (current) message else "配置已改变，请重试")
        })
    }

    @Synchronized
    fun clear() {
        clear(null)
    }

    @Synchronized
    fun clear(completion: SaveCompletion?) {
        if (recovery != null) recovery.credentialsUsable(false)
        nativeDemand.clear()
        pluginLeases.clear()
        balanceUsers.clear()
        this.isRemembered = false
        this.isConfigured = this.isRemembered
        loginInProgress = false
        loginError = null
        nextBackgroundLoginAt = Long.MAX_VALUE
        backgroundLoginFailures = 0
        feeProfileKey = ""
        studentAccount = ""
        changed("登录信息已清除。", "configuration")
        val expected = epoch
        worker.execute(Runnable {
            api.clear()
            var problem = ""
            try {
                if (credentials != null) credentials.clear()
            } catch (e: Exception) {
                problem = "账户已停用，但删除未完成，请重试"
            }
            finishSave(expected, completion, problem.isEmpty(), problem)
        })
    }

    @Synchronized
    fun selectAccount(id: String) {
        changed("账户已切换，请刷新。", "")
        worker.execute(Runnable { api.select(id) })
    }

    @Synchronized
    fun refreshIfDue(reason: String?) {
        if (nextRefreshDelayMs() == 0L) refresh(reason)
    }

    fun refresh(reason: String?) {
        requestCard(null)
    }

    internal fun communitySession(expected: Long): String {
        val result = java.util.concurrent.CompletableFuture<String>()
        worker.execute {
            try {
                check(expected == configurationRevision() && isConfigured) { "请先登录校园卡" }
                val token = api.communityToken()
                check(expected == configurationRevision()) { "校园卡账户已变化" }
                result.complete(token)
            } catch (failure: Exception) {
                result.completeExceptionally(failure)
            }
        }
        return result.get(25, java.util.concurrent.TimeUnit.SECONDS)
    }

    @Synchronized
    private fun requestBalance(completion: FeeCompletion? = null) {
        completion?.let(balanceWaiters::add)
        if (balanceInFlight) return
        balanceInFlight = true
        val expected = epoch
        requestFeeUncached(CampusFees.Kind.CARD, BALANCE_PROFILE, false) { value ->
            synchronized(this) {
                if (expected != epoch) return@requestFeeUncached
                balanceInFlight = false
                val now = clock.elapsed()
                val text = value?.detail?.get("圈存后余额")
                publishBalance(text?.let { BalanceSnapshot(it, now + BALANCE_TTL_MS) })
                nextBalanceAt = now + if (text == null) 30_000L else 90_000L
                val waiting = balanceWaiters.toList()
                balanceWaiters.clear()
                rescheduleBalance()
                waiting.forEach { it.complete(value) }
            }
        }
    }

    internal fun requestFee(
        kind: CampusFees.Kind,
        profile: CampusFees.Profile,
        ac: Boolean,
        completion: FeeCompletion
    ) {
        if (kind == CampusFees.Kind.CARD) requestBalance(completion)
        else requestFeeUncached(kind, profile, ac, completion)
    }

    private fun requestFeeUncached(
        kind: CampusFees.Kind,
        profile: CampusFees.Profile,
        ac: Boolean,
        completion: FeeCompletion
    ) {
        val expected = configurationRevision()
        worker.execute(Runnable {
            var result: Reading
            if (expected != configurationRevision() || !this.isConfigured || !canFetch()) result =
                status("请先登录")
            else {
                try {
                    result = api.queryFee(kind, profile, ac)
                } catch (failure: CampusApi.Failure) {
                    sessionLost(expected, failure.kind)
                    result =
                        Reading.status((if ("fee_account" == failure.kind) failure.message else "暂不可查")!!)
                } catch (failure: RuntimeException) {
                    result = status("暂不可查")
                }
            }
            val value = result
            main.post(Runnable { completion.complete(if (expected == configurationRevision()) value else null) })
        })
    }

    internal fun requestBills(page: Int, completion: (Result<CampusBills.Page>) -> Unit) {
        val expected = configurationRevision()
        worker.execute {
            val result = runCatching {
                check(expected == configurationRevision() && isConfigured && canFetch()) { "请先登录校园卡" }
                api.queryBills(page)
            }
            (result.exceptionOrNull() as? CampusApi.Failure)?.let { sessionLost(expected, it.kind) }
            main.post {
                if (expected == configurationRevision() && isConfigured) completion(result)
                else completion(Result.failure(IllegalStateException("请重新登录校园卡后查看账单")))
            }
        }
    }

    internal fun requestRooms(building: String, completion: RoomCompletion) {
        val expected = configurationRevision()
        worker.execute(Runnable {
            var result: List<RoomOption> = emptyList()
            if (expected == configurationRevision() && this.isConfigured) {
                try {
                    result = api.queryRooms(building)
                } catch (ignored: CampusApi.Failure) {
                } catch (ignored: RuntimeException) {
                }
            }
            val options = result
            main.post(Runnable { completion.complete(if (expected == configurationRevision()) options else emptyList()) })
        })
    }

    @Synchronized
    fun requestCurrent(completion: Completion) {
        val s = current()
        val expected = epoch
        main.post(Runnable {
            completion.onComplete(
                if (expected == configurationRevision()) current() else CardSnapshot.Companion.hidden(
                    0,
                    false,
                    "配置已变化。",
                    "cancelled"
                )
            )
        })
    }

    @Synchronized
    fun requestCard(completion: Completion?) {
        if (completion != null) waiting.add(completion)
        if (credentials != null && (!isConfigured || !canFetch())) {
            complete(current())
            return
        }
        if (this.isBusy) return  // All callers join one in-flight login/code request.

        if (clock.elapsed() < max(retryAfter, nextRequestAt)) {
            complete(current())
            reschedule()
            return
        }
        val requestEpoch = epoch
        this.isBusy = true
        nextRequestAt = clock.elapsed() + MIN_REQUEST_GAP_MS
        reschedule()
        if (!current().ready) publish(
            CardSnapshot.Companion.hidden(
                ++revision,
                false,
                "正在获取二维码…",
                "loading"
            )
        )
        else notifyListeners()
        worker.execute(Runnable {
            var next: CardSnapshot?
            var mayRetry = true
            var nextAccounts: List<CampusApi.Account>? = null
            try {
                if (!canFetch()) throw CampusApi.Failure("login_paused", "后台取码已暂停。", true)
                val result = api.refresh()
                val left = result.deadline - clock.elapsed()
                nextAccounts = Collections.unmodifiableList(result.accounts)
                next = CardSnapshot(
                    true,
                    result.code,
                    result.label,
                    0,
                    clock.wall() + left,
                    left,
                    result.deadline,
                    false,
                    if (result.recovered) "已自动重新登录并恢复二维码。" else "二维码已更新。",
                    "",
                    result.recovered
                )
            } catch (e: CampusApi.Failure) {
                sessionLost(requestEpoch, e.kind)
                mayRetry = e.retryable
                next = CardSnapshot.Companion.hidden(0, false, e.message, e.kind)
            } catch (e: Exception) {
                mayRetry = false
                next = CardSnapshot.Companion.hidden(0, false, "本地取码失败，请重试。", "local")
            }
            synchronized(this) {
                this.isBusy = false
                if (requestEpoch != epoch) {
                    if (!waiting.isEmpty()) requestCard(null) else reschedule()
                    return@Runnable
                }
                completedFetches++
                if (next!!.ready) successfulFetches++
                if (next.recovered) restoredSessions++
                if (nextAccounts != null) accounts = nextAccounts
                automaticRetry = next.ready || mayRetry
                if (next.ready) {
                    failures = 0
                    retryAfter = 0
                } else {
                    failures++
                    retryAfter = clock.elapsed() + min(60000, 5000L shl min(failures - 1, 4))
                }
                if (!next.ready && mayRetry && current().ready) {
                    reschedule()
                    notifyListeners()
                    complete(current())
                    return@Runnable
                }
                next = CardSnapshot(
                    next.ready,
                    next.code,
                    next.label,
                    ++revision,
                    next.expiresAt,
                    next.remainingMs,
                    next.expiresElapsed,
                    next.testMode,
                    next.status,
                    next.errorKind,
                    next.recovered
                )
                publish(next)
                complete(next)
                if (next.ready) {
                    val expected = revision
                    main.postDelayed(Runnable { expire(expected) }, next.remainingMs + 10)
                }
            }
        })
    }

    @Synchronized
    private fun sessionLost(expected: Long, kind: String) {
        if (credentials == null || expected != epoch || kind !in setOf("session_expired", "credentials", "auth_conflict")) return
        isConfigured = false
        loginError = "登录已失效，请重新登录。"
        publishBalance(null)
        publish(CardSnapshot.hidden(++revision, false, loginError, kind))
    }

    @Synchronized
    private fun complete(result: CardSnapshot) {
        val done: MutableList<Completion> = ArrayList<Completion>(waiting)
        waiting.clear()
        val expected = epoch
        main.post(Runnable {
            val safe = if (expected == configurationRevision()) result.at(
                clock.elapsed(),
                clock.wall()
            ) else CardSnapshot.Companion.hidden(0, false, "配置已变化。", "cancelled")
            for (c in done) c.onComplete(safe)
        })
    }

    @Synchronized
    private fun expire(expected: Long) {
        if (snapshot.revision == expected && !current().ready) publish(
            CardSnapshot.Companion.hidden(
                ++revision,
                false,
                "二维码已过期。",
                "expired"
            )
        )
    }

    companion object {
        private var instance: CampusRuntime? = null

        @Synchronized
        @JvmStatic
        fun get(c: Context): CampusRuntime {
            if (instance == null) instance = CampusRuntime(c.getApplicationContext())
            return instance!!
        }

        private const val MIN_REQUEST_GAP_MS: Long = 5000
        private const val BALANCE_TTL_MS = 120_000L
        private val BALANCE_PROFILE = CampusFees.Profile("", "", "", "", "")
        private fun feeKey(user: String): String {
            return UUID.nameUUIDFromBytes(user.trim { it <= ' ' }
                .toByteArray(StandardCharsets.UTF_8)).toString()
        }
    }
}
