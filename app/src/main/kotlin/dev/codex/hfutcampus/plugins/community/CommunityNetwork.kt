package dev.codex.hfutcampus

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.net.URLEncoder

internal object CommunityNetwork {
    private const val LIMIT = 24 * 1024
    private val formKey = Regex("[a-zA-Z][a-zA-Z0-9_.-]{0,49}")
    private val campusReads = mapOf(
        "/berserker-app/ykt/tsm/getCampusCards" to "GET",
        "/charge/feeitem/getThirdData" to "POST"
    )

    fun check(uri: URI, method: String, spec: CommunitySpec, spending: Boolean): Boolean {
        require(uri.userInfo == null && uri.fragment == null && uri.normalize() == uri)
        val origin = CommunitySpec.origin(uri)
        require(origin in spec.origins && method in setOf("GET", "POST"))
        if (origin != CommunitySpec.CAMPUS) {
            require(!spending && uri.scheme == "https")
            return false
        }
        require(spec.campusSession && uri.rawQuery == null && uri.rawPath == uri.path)
        require(if (spending) method == "POST" && uri.path == spec.spend?.path
                else campusReads[uri.path] == method && uri.path != spec.spend?.path)
        return true
    }

    fun request(uri: URI, method: String, fields: JSONObject, token: String?): JSONObject {
        require(fields.length() <= 24 && fields.keys().asSequence().all(formKey::matches))
        val params = fields.keys().asSequence().map { name ->
            val value = fields.get(name) as String
            require(value.length <= 8192)
            encode(name) + "=" + encode(value)
        }.joinToString("&")
        require(params.toByteArray().size <= LIMIT)
        val url = if (method == "GET" && params.isNotEmpty()) {
            URI(uri.toASCIIString() + if (uri.rawQuery == null) "?$params" else "&$params")
        } else uri
        val connection = url.toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 6000
            connection.readTimeout = 6000
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Connection", "close")
            if (token != null) connection.setRequestProperty("synjones-auth", token)
            if (method == "POST") {
                val bytes = params.toByteArray()
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            if (status in 300..399) return JSONObject().put("status", status)
            val stream = if (status < 400) connection.inputStream else connection.errorStream
            val text = stream?.use(::bounded).orEmpty()
            val response = JSONObject().put("status", status)
            // Authenticated campus responses use JSON objects. Avoid handing off raw error pages.
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (json != null) response.put("body", json)
            else if (token == null) response.put("text", text)
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun bounded(input: InputStream): String {
        val buffer = ByteArray(4096)
        val result = ByteArrayOutputStream()
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(result.size() + count <= LIMIT) { "响应超过 24 KiB" }
            result.write(buffer, 0, count)
        }
        return result.toString("UTF-8")
    }
}
