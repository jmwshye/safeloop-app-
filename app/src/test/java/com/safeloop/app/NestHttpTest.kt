package com.safeloop.app

import java.io.BufferedInputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class NestHttpTest {
    @Test fun releaseRequiresHttpsAndDebugHttpStaysLocal() {
        assertEquals("https://api.example.test", NestHttp.validateBaseUrl("https://api.example.test/", false))
        assertEquals("http://10.0.2.2:3000", NestHttp.validateBaseUrl("http://10.0.2.2:3000", true))
        for ((url, debug) in listOf("http://10.0.2.2:3000" to false, "http://external.example.test" to true,
            "https://name:password@api.example.test" to true, "https://api.example.test/path" to true,
            "https://api.example.test?token=secret" to true, "" to true)) {
            assertThrows(IllegalArgumentException::class.java) { NestHttp.validateBaseUrl(url, debug) }
        }
    }
    @Test fun authenticatedJsonAndBinaryUseTheConfiguredOrigin() {
        val requests = CopyOnWriteArrayList<String>()
        LocalHttpServer { request ->
            assertEquals("Bearer test-session", request.headers["authorization"])
            assertEquals("POST", request.method)
            assertEquals("/incidents", request.path)
            requests += request.body.toString(Charsets.UTF_8)
            TestResponse(200, byteArrayOf(0, 1, 2, 127, -1), mapOf("Content-Type" to "application/pdf"))
        }.use { server ->
            val client = NestHttp(server.url, true)
            val body = "{\"requestId\":\"same-request-id\"}".toByteArray()
            repeat(2) {
                val result = client.execute("POST", "/incidents", "test-session", "application/json", body)
                assertEquals(200, result.status)
                assertArrayEquals(byteArrayOf(0, 1, 2, 127, -1), result.bytes)
            }
            assertEquals(2, requests.size)
            assertEquals(requests[0], requests[1])
            assertThrows(IllegalArgumentException::class.java) { client.execute("GET", "//other.example.test", "test-session") }
        }
    }
    @Test fun redirectsAreNotFollowedWithBearerTokens() {
        val followed = AtomicInteger()
        LocalHttpServer { request ->
            if (request.path == "/redirect") TestResponse(302, headers = mapOf("Location" to "/target"))
            else { followed.incrementAndGet(); TestResponse(200) }
        }.use { server ->
            val client = NestHttp(server.url, true)
            val error = assertThrows(ApiException::class.java) { client.execute("GET", "/redirect", "secret") }
            assertEquals("API_REDIRECT", error.code)
            assertEquals(0, followed.get())
        }
    }
    @Test fun onlyTransientErrorsMayRetry() {
        for (status in listOf(408, 429, 500, 502, 503)) assertTrue(ApiException(status, "test", "test").retryable)
        for (status in listOf(400, 401, 403, 404, 409, 422)) assertFalse(ApiException(status, "test", "test").retryable)
    }
    @Test fun offlineWorkCannotMoveAcrossAccountsSitesOrNewLogins() {
        val saved = OfflineIdentity("worker", "login-1", "site-1")
        assertTrue(saved.matches(saved.copy()))
        assertFalse(saved.matches(saved.copy(userId = "other-worker")))
        assertFalse(saved.matches(saved.copy(sessionId = "login-2")))
        assertFalse(saved.matches(saved.copy(siteId = "site-2")))
        assertFalse(saved.matches(OfflineIdentity(null, null, "site-1")))
        assertFalse(OfflineIdentity(null, null, "site-1").matches(OfflineIdentity(null, null, "site-1")))
    }
}

private data class TestRequest(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)
private data class TestResponse(val status: Int, val body: ByteArray = ByteArray(0), val headers: Map<String, String> = emptyMap())

/** Test-only HTTP fixture: no Android stubs, external server, or additional dependencies. */
private class LocalHttpServer(private val handle: (TestRequest) -> TestResponse) : Closeable {
    private val socket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
    private val failure = AtomicReference<Throwable?>()
    val url: String get() = "http://127.0.0.1:${socket.localPort}"
    private val thread = Thread {
        while (!socket.isClosed) {
            try {
                socket.accept().use { client ->
                    client.soTimeout = 5000
                    val input = BufferedInputStream(client.getInputStream())
                    fun line(): String {
                        val text = StringBuilder()
                        while (true) {
                            val next = input.read()
                            if (next < 0) error("HTTP header ended early")
                            if (next == 10) return text.toString().trimEnd('\r')
                            check(text.length < 8192)
                            text.append(next.toChar())
                        }
                    }
                    val start = line().split(' ')
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val header = line()
                        if (header.isEmpty()) break
                        headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                    }
                    val body = ByteArray(headers["content-length"]?.toInt() ?: 0)
                    var offset = 0
                    while (offset < body.size) {
                        val count = input.read(body, offset, body.size - offset)
                        check(count > 0); offset += count
                    }
                    val response = try { handle(TestRequest(start[0], start[1], headers, body)) }
                    catch (error: Throwable) { failure.set(error); TestResponse(500) }
                    val heading = "HTTP/1.1 ${response.status} Test\r\nContent-Length: ${response.body.size}\r\nConnection: close\r\n" +
                        response.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n"
                    client.getOutputStream().use { output -> output.write(heading.toByteArray(Charsets.US_ASCII)); output.write(response.body); output.flush() }
                }
            } catch (error: Throwable) { if (!socket.isClosed) failure.compareAndSet(null, error) }
        }
    }.apply { isDaemon = true; start() }
    override fun close() {
        socket.close(); thread.join(1000)
        failure.get()?.let { throw AssertionError("HTTP fixture failed", it) }
    }
}
