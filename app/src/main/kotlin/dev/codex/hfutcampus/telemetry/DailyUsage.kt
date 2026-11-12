package dev.codex.hfutcampus

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/** Invoked only by the resumed activity. No worker, alarm or foreground service is registered. */
internal object DailyUsage {
    private val attempts = DailyUsageAttempts()
    private val endpoint by lazy {
        BuildConfig.DAILY_USAGE_URL.takeIf(String::isNotEmpty)?.let { URL("$it/rest/v1/rpc/record_daily_usage") }
    }
    val available: Boolean get() = endpoint != null

    fun recordForeground(context: Context) {
        val url = endpoint ?: return
        val prefs = context.getSharedPreferences("daily-usage", Context.MODE_PRIVATE)
        val day = DailyUsageReport.day(Instant.now())
        if (!attempts.begin(day, SystemClock.elapsedRealtime(), prefs.getString("sent-day", null))) return
        Thread({
            try {
                val saved = prefs.getString("token", null)?.takeIf { prefs.getString("token-day", null) == day }
                val token = saved ?: UUID.randomUUID().toString().replace("-", "")
                // Preserve this day's token even if a request reaches the server but its response is lost.
                if (saved == null && !prefs.edit().putString("token-day", day).putString("token", token).commit()) return@Thread
                if (day == DailyUsageReport.day(Instant.now()) && submit(url, DailyUsageReport(day, token))) {
                    prefs.edit().putString("sent-day", day).apply()
                }
            } catch (_: IOException) {
                // Try again only on a later foreground entry.
            } finally {
                attempts.finish()
            }
        }, "campus-daily-usage").start()
    }

    private fun submit(url: URL, report: DailyUsageReport): Boolean {
        val body = report.json().toString().toByteArray(Charsets.UTF_8)
        val connection = url.openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            connection.requestMethod = "POST"
            connection.setRequestProperty("apikey", BuildConfig.DAILY_USAGE_KEY)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            return connection.responseCode in 200..299
        } finally {
            connection.disconnect()
        }
    }
}

internal data class DailyUsageReport(val activityDay: String, val token: String) {
    fun json(): JSONObject = JSONObject().put("activity_day", activityDay).put("daily_token", token)

    companion object {
        private val SHANGHAI = ZoneId.of("Asia/Shanghai")
        fun day(now: Instant): String = now.atZone(SHANGHAI).toLocalDate().toString()
    }
}

internal class DailyUsageAttempts {
    private var inFlight = false
    private var attemptedDay: String? = null
    private var nextAttempt = 0L

    @Synchronized
    fun begin(day: String, elapsed: Long, sentDay: String?): Boolean {
        if (sentDay == day || inFlight || attemptedDay == day && elapsed < nextAttempt) return false
        inFlight = true
        attemptedDay = day
        nextAttempt = elapsed + 5 * 60_000L
        return true
    }

    @Synchronized
    fun finish() {
        inFlight = false
    }
}
