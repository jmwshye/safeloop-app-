package com.safeloop.app

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class ApiException(val status: Int, val code: String, message: String) : IOException(message) {
    val retryable: Boolean get() = status == 408 || status == 429 || status in 500..599
}

data class HttpPayload(val status: Int, val bytes: ByteArray, val contentType: String?)

data class OfflineIdentity(val userId: String?, val sessionId: String?, val siteId: String?) {
    fun matches(current: OfflineIdentity): Boolean = !userId.isNullOrBlank() && !sessionId.isNullOrBlank() && this == current
}

/** One configured origin only. Redirects must never forward a bearer token to another host. */
class NestHttp(baseUrl: String, allowLocalHttp: Boolean) {
    private val base = validateBaseUrl(baseUrl, allowLocalHttp)

    fun execute(method: String, path: String, token: String?, contentType: String? = null, body: ByteArray? = null): HttpPayload {
        require(method in setOf("GET", "POST", "PUT", "DELETE")) { "지원하지 않는 요청 방식입니다." }
        require(path.startsWith("/") && !path.startsWith("//") && !path.contains('\\') && !path.contains('#')) { "API 경로를 확인해 주세요." }
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json, image/jpeg, application/pdf, text/csv")
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType ?: "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            if (status in 300..399) throw ApiException(status, "API_REDIRECT", "API 주소가 변경되었습니다. 서버 주소를 확인해 주세요.")
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val bytes = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 25 * 1024 * 1024) throw IOException("서버 응답이 너무 큽니다.")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: ByteArray(0)
            return HttpPayload(status, bytes, connection.contentType)
        } finally { connection.disconnect() }
    }

    companion object {
        fun validateBaseUrl(value: String, allowLocalHttp: Boolean): String {
            val uri = try { URI(value.trim()) } catch (_: Exception) { throw IllegalArgumentException("API 주소를 확인해 주세요.") }
            val local = uri.host in setOf("localhost", "127.0.0.1", "10.0.2.2", "[::1]", "::1")
            require(uri.host != null && (uri.scheme == "https" || (allowLocalHttp && uri.scheme == "http" && local))) { "API는 HTTPS 주소를 사용해 주세요. 개발용 HTTP는 에뮬레이터·USB 연결에서만 허용됩니다." }
            require(uri.userInfo == null && uri.query == null && uri.fragment == null && (uri.path.isNullOrEmpty() || uri.path == "/")) { "API 주소에는 경로·계정 정보를 넣지 마세요." }
            return value.trim().trimEnd('/')
        }
    }
}
