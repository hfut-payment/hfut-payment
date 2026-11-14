package dev.codex.hfutcampus

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

internal data class AppRelease(
    val code: Long, val name: String, val bytes: Long, val sha256: String, val downloads: List<URI> = emptyList()
) {
    val apkName get() = "HFUT-Campus-Companion-$name.apk"

    fun apkUrl(feed: URI): URI {
        val latest = "/releases/latest/download/update.json"
        val giteeManifest = "/raw/master/update.json"
        if (feed.host.equals("gitee.com", ignoreCase = true) && feed.path.endsWith(giteeManifest)) {
            return feed.resolve(feed.path.removeSuffix(giteeManifest) + "/releases/download/v$name/$apkName")
        }
        return if (feed.host.equals("github.com", ignoreCase = true) && feed.path.endsWith(latest)) {
            feed.resolve(feed.path.removeSuffix(latest) + "/releases/download/v$name/$apkName")
        } else feed.resolve(apkName)
    }

    companion object {
        private val version = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[a-z0-9.-]+)?")
        private val digest = Regex("[0-9a-f]{64}")

        fun parse(document: String, installed: Long): AppRelease? {
            val json = JSONObject(document)
            require(json.integer("schema") == 1L)
            val code = json.integer("versionCode")
            val name = json.getString("versionName")
            val bytes = json.integer("bytes")
            val sha256 = json.getString("sha256")
            require(code in 1..2_100_000_000 && name.length <= 48 && version.matches(name))
            require(bytes in 1..(128L * 1024 * 1024) && digest.matches(sha256))
            val release = AppRelease(code, name, bytes, sha256)
            require(json.getString("apk") == release.apkName)
            return release.takeIf { code > installed }
        }

        private fun JSONObject.integer(key: String): Long =
            (get(key) as? Number)?.toString()?.toLongOrNull() ?: error("Invalid $key")
    }
}

class UpdateFileProvider : FileProvider()

internal class AppUpdates(private val context: Context) {
    private val feed = BuildConfig.APP_UPDATE_FEED.takeIf(String::isNotEmpty)?.let(::URI)
    private val mirror = BuildConfig.APP_UPDATE_MIRROR.takeIf(String::isNotEmpty)?.let(::URI)
    val configured get() = feed != null || mirror != null

    fun check(): AppRelease? {
        val checks = listOfNotNull(mirror, feed).distinct().map { url ->
            Callable { runCatching { checkManifest(url) } }
        }
        val results = sources.invokeAll(checks).map { it.get() }
        if (results.none { it.isSuccess }) throw IOException("Update feeds unavailable", results.firstOrNull()?.exceptionOrNull())
        val available = results.mapNotNull { it.getOrNull() }
        val latest = available.maxByOrNull { it.code } ?: return null
        val identity = latest.copy(downloads = emptyList())
        return latest.copy(downloads = available.filter { it.copy(downloads = emptyList()) == identity }
            .flatMap { it.downloads }.distinct())
    }

    private fun checkManifest(url: URI) =
        readRelease(url)?.let { it.copy(downloads = listOf(it.apkUrl(url))) }

    private fun readRelease(url: URI) = AppRelease.parse(readText(url), BuildConfig.VERSION_CODE.toLong())

    private fun readText(url: URI): String =
        request(url, "application/json, application/octet-stream") { input ->
            val body = ByteArrayOutputStream()
            copy(input, body, 128 * 1024L)
            body.toString(Charsets.UTF_8.name())
        }

    fun download(release: AppRelease, onProgress: (Int) -> Unit): File {
        val folder = File(context.cacheDir, "app-update")
        check(folder.isDirectory || folder.mkdirs()) { "无法准备安装包目录" }
        val apk = File(folder, "${release.code}-${release.sha256}.apk")
        if (apk.isFile) {
            val valid = runCatching {
                require(apk.length() == release.bytes)
                val digest = MessageDigest.getInstance("SHA-256")
                apk.inputStream().use { copy(it, null, release.bytes, digest) }
                verify(apk, release, digest.digest())
            }.isSuccess
            if (valid) return apk
            apk.delete()
        }
        var failure: Exception? = null
        for (url in release.downloads) {
            try {
                onProgress(0)
                return downloadFrom(url, release, folder, apk, onProgress)
            } catch (error: Exception) {
                failure = error
            }
        }
        throw failure ?: IOException("No update download URL")
    }

    private fun downloadFrom(url: URI, release: AppRelease, folder: File, apk: File, onProgress: (Int) -> Unit): File {
        val temporary = File.createTempFile("download-", ".apk", folder)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            request(url, "application/octet-stream") { input ->
                temporary.outputStream().use { output ->
                    var lastProgress = -5
                    copy(input, output, release.bytes, digest) { written ->
                        val progress = (written * 100 / release.bytes).toInt()
                        if (progress >= lastProgress + 5) {
                            lastProgress = progress
                            onProgress(progress)
                        }
                    }
                }
            }
            verify(temporary, release, digest.digest())
            check(temporary.renameTo(apk)) { "无法保存安装包" }
            return apk
        } finally {
            temporary.delete()
        }
    }

    private fun verify(apk: File, release: AppRelease, digest: ByteArray) {
        require(apk.length() == release.bytes && digest.hex() == release.sha256) { "安装包校验失败，请重新下载" }
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val candidate = pm.getPackageArchiveInfo(apk.absolutePath, flags)
        require(candidate != null && candidate.packageName == context.packageName &&
            candidate.versionName == release.name && PackageInfoCompat.getLongVersionCode(candidate) == release.code &&
            release.code > BuildConfig.VERSION_CODE) { "安装包与发布版本不符" }
        val installed = pm.getPackageInfo(context.packageName, flags)
        val expected = signers(installed)
        require(expected.isNotEmpty() && signers(candidate) == expected) { "安装包签名不匹配" }
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().mapTo(mutableSetOf()) {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex()
        }
    }

    fun openInstaller(activity: Activity, apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.update", apk)
        activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        return true
    }

    private fun <T> request(initial: URI, accept: String, consume: (InputStream) -> T): T {
        var url = initial
        repeat(6) {
            require(url.scheme.equals("https", ignoreCase = true) && url.host != null &&
                url.rawUserInfo == null && url.rawFragment == null)
            val connection = url.toURL().openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 8_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Accept", accept)
                when (val status = connection.responseCode) {
                    200 -> return connection.inputStream.use(consume)
                    301, 302, 303, 307, 308 -> {
                        val location = connection.getHeaderField("Location") ?: throw IOException("Missing redirect")
                        url = url.resolve(location)
                    }
                    else -> throw IOException("HTTP $status")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    private fun copy(
        input: InputStream, output: OutputStream?, limit: Long, digest: MessageDigest? = null,
        progress: (Long) -> Unit = {}
    ) {
        val buffer = ByteArray(32 * 1024)
        var written = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            written += count
            if (written > limit) throw IOException("Response is larger than expected")
            digest?.update(buffer, 0, count)
            output?.write(buffer, 0, count)
            progress(written)
        }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private companion object {
        val sources = Executors.newFixedThreadPool(2)
    }
}
