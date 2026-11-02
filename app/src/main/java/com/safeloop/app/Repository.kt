package com.safeloop.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

class Repository(context: Context) {
    private val context = context.applicationContext
    private val sessions = NestSessionStore(this.context)
    val prefs = this.context.getSharedPreferences("safeloop", Context.MODE_PRIVATE)
    private val http get() = NestHttp(serverUrl, BuildConfig.DEBUG)
    /** 개발 빌드에서만 앱을 다시 만들지 않고 서버 주소를 바꿀 수 있다. 정식 빌드는 빌드할 때 정한 주소만 쓴다. */
    var serverUrl: String
        get() = prefs.getString("apiUrlOverride", null)?.takeIf { BuildConfig.DEBUG } ?: BuildConfig.API_BASE_URL
        set(value) { check(BuildConfig.DEBUG && !hasSession); prefs.edit().putString("apiUrlOverride", value).commit() }
    val currentUser: SessionUser? get() = sessions.read()?.user
    val hasSession: Boolean get() = currentUser != null
    internal val sessionId: String? get() = sessions.read()?.id
    internal val identity: OfflineIdentity get() = sessions.read().let { OfflineIdentity(it?.user?.uid, it?.id, siteId) }
    var siteId: String
        get() = prefs.getString("nestSiteId", "") ?: ""
        set(value) { prefs.edit().putString("nestSiteId", value).commit() }
    val deviceId: String get() = deviceIdFor(currentUser?.uid ?: error("로그인한 계정을 확인해 주세요."))
    private fun deviceIdFor(userId: String): String = synchronized(deviceLock) {
        val installation = prefs.getString("nestInstallationId", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("nestInstallationId", it).commit()
        }
        pushDeviceId(installation, userId)
    }
    suspend fun signIn(email: String, password: String) {
        val result = json("POST", "/auth/login", mapOf("email" to email, "password" to password), authenticated = false)
        val user = result["user"] as? Map<*, *> ?: error("로그인 응답을 확인해 주세요.")
        val newSession = NestSession(SessionUser(user["id"] as String, user["name"] as String, user["email"] as String), result["accessToken"] as String, Instant.parse(result["expiresAt"] as String).toEpochMilli(), UUID.randomUUID().toString())
        withContext(Dispatchers.IO) { clearLocal(); sessions.save(newSession) }
    }
    suspend fun signUp(email: String, password: String, name: String) {
        json("POST", "/auth/signup", mapOf("email" to email, "password" to password, "name" to name), authenticated = false)
        signIn(email, password)
    }
    suspend fun logout() {
        val ending = sessions.read()
        if (ending == null) { withContext(NonCancellable + Dispatchers.IO) { clearLocal() }; return }
        try { json("DELETE", "/push-devices/${deviceIdFor(ending.user.uid)}", expected = OfflineIdentity(ending.user.uid, ending.id, siteId)) }
        catch (_: IOException) { /* Local logout must work while offline. */ }
        finally { withContext(NonCancellable + Dispatchers.IO) { clearLocal(ending.token) } }
    }
    private fun clearLocal(expectedToken: String? = null) {
        if (!sessions.clear(expectedToken)) return
        context.getSystemService(android.app.NotificationManager::class.java).cancelAll()
        context.stopService(Intent(context, SafetyService::class.java))
        WorkManager.getInstance(context).cancelAllWorkByTag(queueTag)
        prefs.edit().remove("nestSiteId").remove("localCallId").remove("localAlert").remove("ackAlert")
            .remove("attendanceId").remove("pendingSignal").remove("signalFailed").remove("signalSession").remove("signalSite").remove("signalAt").remove("highRiskTask").putBoolean("monitoring", false).commit()
        for (folder in listOf("nest-media", "verification")) java.io.File(context.cacheDir, folder).deleteRecursively()
    }
    suspend fun request(method: String, path: String, body: Map<String, Any?>? = null): Map<String, Any?> = json(method, path, body)
    suspend fun get(path: String): Map<String, Any?> = request("GET", path)
    private suspend fun json(method: String, path: String, body: Map<String, Any?>? = null, authenticated: Boolean = true, expected: OfflineIdentity? = null): Map<String, Any?> {
        val boundIdentity = expected ?: if (authenticated) identity else null
        val bytes = body?.let { JSONObject(it).toString().toByteArray(Charsets.UTF_8) }
        val result = send(method, path, bytes, "application/json; charset=utf-8", authenticated, boundIdentity)
        if (result.bytes.isEmpty()) return emptyMap()
        try { return jsonMap(JSONObject(String(result.bytes, Charsets.UTF_8))) }
        catch (error: Exception) { throw IOException("서버 응답 형식을 확인해 주세요.", error) }
    }
    private suspend fun send(method: String, path: String, body: ByteArray? = null, contentType: String? = null, authenticated: Boolean = true, expected: OfflineIdentity? = if (authenticated) identity else null): HttpPayload = withContext(Dispatchers.IO) {
        val session = if (authenticated) sessions.read() ?: throw ApiException(401, "UNAUTHORIZED", "다시 로그인해 주세요.") else null
        if (expected != null && !expected.matches(OfflineIdentity(session?.user?.uid, session?.id, siteId))) throw ApiException(403, "SESSION_CHANGED", "계정 또는 현장이 변경되어 이전 작업을 취소했어요.")
        val token = session?.token
        val response = http.execute(method, path, token, contentType, body)
        if (expected != null && !expected.matches(identity)) throw ApiException(403, "SESSION_CHANGED", "계정 또는 현장이 변경되어 이전 응답을 취소했어요.")
        if (response.status !in 200..299) {
            if (response.status == 401 && token != null) clearLocal(token)
            val json = runCatching { JSONObject(String(response.bytes, Charsets.UTF_8)) }.getOrNull()
            val error = json?.optJSONObject("error")
            val message = error?.optString("message")?.takeIf { it.isNotBlank() }
                ?: json?.optString("message")?.takeIf { it.isNotBlank() }
                ?: if (response.status == 401) "다시 로그인해 주세요." else "서버 요청을 처리하지 못했어요. (${response.status})"
            throw ApiException(response.status, error?.optString("code")?.takeIf { it.isNotBlank() } ?: "HTTP_${response.status}", message)
        }
        response
    }
    suspend fun registerDevice(token: String) { request("PUT", "/push-devices/$deviceId", mapOf("fcmToken" to token)) }
    suspend fun upload(bitmap: Bitmap, purpose: String): String {
        require(purpose in setOf("ppe", "risk_before", "risk_after", "incident_scene"))
        val expected = identity
        val selectedSite = siteId.also { require(it.isNotBlank()) { "현장에 먼저 연결해 주세요." } }
        return withContext(Dispatchers.IO) {
            val jpeg = ByteArrayOutputStream().apply { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, this)) }.toByteArray()
            require(jpeg.size <= 5 * 1024 * 1024) { "사진 크기를 5 MiB 이하로 줄여 주세요." }
            val boundary = "SafeLoop${UUID.randomUUID()}"
            val multipart = ByteArrayOutputStream().apply {
                write("--$boundary\r\nContent-Disposition: form-data; name=\"purpose\"\r\n\r\n$purpose\r\n--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"photo.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
                write(jpeg); write("\r\n--$boundary--\r\n".toByteArray())
            }.toByteArray()
            val response = send("POST", "/sites/$selectedSite/photos", multipart, "multipart/form-data; boundary=$boundary", expected = expected)
            JSONObject(String(response.bytes, Charsets.UTF_8)).getJSONObject("photo").getString("id")
        }
    }
    suspend fun photo(photoId: String): ByteArray {
        UUID.fromString(photoId)
        return send("GET", "/sites/$siteId/photos/$photoId").bytes
    }
    suspend fun report(path: String): ByteArray = send("GET", path).bytes
    internal suspend fun queuedRequest(expected: OfflineIdentity, method: String, path: String, body: Map<String, Any?>) = json(method, path, body, expected = expected)

    /** Queue only explicit idempotent operations; keep the exact body and login/site that created them. */
    fun queueStatuses() = WorkManager.getInstance(context).getWorkInfosByTagFlow(queueTag)
    fun pendingTag() = "nest-pending:${sessionId}:${siteId}"
    fun queueRequest(method: String, path: String, body: Map<String, Any?>, site: String = siteId) {
        val session = sessions.read() ?: return
        require(method == "PUT" || (method == "POST" && body["requestId"] is String))
        val data = workDataOf("method" to method, "path" to path, "site" to site, "uid" to session.user.uid,
            "session" to session.id, "body" to JSONObject(body).toString())
        val work = OneTimeWorkRequestBuilder<CommandWorker>().setInputData(data)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).addTag(queueTag).addTag(pendingTag()).build()
        WorkManager.getInstance(context).enqueue(work)
    }
    fun scheduleSignalRecovery(requestId: String) {
        val session = sessions.read() ?: return
        val work = OneTimeWorkRequestBuilder<SignalRecoveryWorker>()
            .setInputData(workDataOf("requestId" to requestId, "uid" to session.user.uid, "session" to session.id, "site" to siteId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(3, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(queueTag).build()
        WorkManager.getInstance(context).enqueueUniqueWork("signal:$requestId", ExistingWorkPolicy.KEEP, work)
    }

    internal suspend fun recoverSignal(requestId: String, expected: OfflineIdentity): String? {
        if (!expected.matches(identity) || prefs.getString("signalSession",null) != expected.sessionId ||
            prefs.getString("signalSite",null) != siteId) return null
        val saved = prefs.getString("pendingSignal", null)?.let(::JSONObject) ?: return null
        if (saved.optString("requestId") != requestId) return null
        val body = jsonMap(saved).toMutableMap()
        if (body["detectedAt"] == null) prefs.getLong("signalAt",0L).takeIf { it > 0 }?.let {
            body["detectedAt"] = Instant.ofEpochMilli(it).toString()
        }
        val result = queuedRequest(expected, "POST", "/incidents", body)
        val incident = result["incident"] as? Map<*, *> ?: throw IOException("사건 응답이 비어 있어요.")
        val id = incident["id"] as? String ?: throw IOException("사건 번호를 확인할 수 없어요.")
        if (prefs.getString("localAlert", null) == requestId && expected.matches(identity)) {
            prefs.edit().putString("localCallId",id).remove("pendingSignal").remove("signalFailed").commit()
        }
        return id
    }
    companion object { private val deviceLock = Any(); private const val queueTag = "nest-offline-commands" }
}

internal fun jsonMap(json: JSONObject): Map<String, Any?> = json.keys().asSequence().associateWith { jsonValue(json.get(it)) }
private fun jsonValue(value: Any?): Any? = when (value) {
    JSONObject.NULL -> null
    is JSONObject -> jsonMap(value)
    is JSONArray -> (0 until value.length()).map { jsonValue(value.get(it)) }
    else -> value
}

class CommandWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = Repository(applicationContext)
        val expected = OfflineIdentity(inputData.getString("uid"), inputData.getString("session"), inputData.getString("site"))
        if (!expected.matches(repo.identity)) return Result.failure()
        val method = inputData.getString("method") ?: return Result.failure()
        val path = inputData.getString("path") ?: return Result.failure()
        return try {
            val body = jsonMap(JSONObject(inputData.getString("body") ?: "{}"))
            try {
                repo.queuedRequest(expected, method, path, body)
            } catch(error: ApiException) {
                val expiredIncidentDial = method == "PUT" && path.startsWith("/incidents/") && path.endsWith("/dial-intent")
                    && error.code == "INCIDENT_STATE_CONFLICT" && !expected.siteId.isNullOrBlank()
                if (!expiredIncidentDial) throw error
                // Opening 119 after a case was closed is a site event, not part of its closed history.
                repo.queuedRequest(expected, "POST", "/sites/${expected.siteId}/dial-intents", body)
            }
            Result.success()
        } catch (error: ApiException) {
            if (error.retryable && runAttemptCount < 8) Result.retry() else Result.failure()
        } catch (_: IOException) {
            if (runAttemptCount < 8) Result.retry() else Result.failure()
        } catch (_: IllegalArgumentException) { Result.failure() }
    }
}


class SignalRecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = Repository(applicationContext)
        val expected = OfflineIdentity(inputData.getString("uid"),inputData.getString("session"),inputData.getString("site"))
        val requestId = inputData.getString("requestId") ?: return Result.failure()
        return try {
            repo.recoverSignal(requestId, expected)
            Result.success()
        } catch (error: ApiException) {
            if (error.retryable) Result.retry() else Result.failure()
        } catch (_: IOException) { Result.retry() }
    }
}
