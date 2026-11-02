package com.safeloop.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.hardware.*
import android.location.LocationManager
import android.os.*
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlin.math.sqrt

class SafetyService : Service(), SensorEventListener {
    private lateinit var sensors: SensorManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val detector = MotionDetector()
    private val gravity = DoubleArray(3)
    private val samples = ArrayDeque<Pair<Long, Double>>()
    private var raw = false
    private var settlingUntil = 0L
    private var lastSampleAt = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var monitorSession: String? = null
    private var monitorSite = ""
    private var monitorUid: String? = null
    private val prefs by lazy { getSharedPreferences("safeloop", MODE_PRIVATE) }
    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("monitor", "안전 감시 상태", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("alerts", "안전 확인 요청", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 2, Intent(this, SafetyService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "monitor").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("안전고리 · 움직임 감시 중").setContentText("휴대폰을 몸에 휴대하세요.")
            .setOngoing(true).setContentIntent(open).addAction(0, "감시 종료", stop).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(101, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(101, notification)
        val repo = Repository(this)
        monitorSession = repo.sessionId
        monitorSite = repo.siteId
        monitorUid = repo.currentUser?.uid
        if (!prefs.getBoolean("previewMonitoring", false) && (monitorSession == null || monitorSite.isBlank())) {
            notifySelf(null, "현장에 로그인한 뒤 감시를 시작해 주세요."); stopSelf(); return
        }
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) { notifySelf(null, "가속도 센서가 없어 감시를 시작할 수 없습니다."); stopSelf(); return }
        raw = sensor.type == Sensor.TYPE_ACCELEROMETER
        settlingUntil = SystemClock.elapsedRealtime() + 2000
        if (!sensors.registerListener(this, sensor, 20000)) { notifySelf(null, "센서 등록에 실패했습니다."); stopSelf(); return }
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SafeLoop:MotionMonitoring").apply { acquire() }
        prefs.edit().putBoolean("monitoring", true).apply()
        if (!prefs.getBoolean("previewMonitoring", false)) scope.launch { heartbeat() }
    }

    private suspend fun heartbeat() {
        val repo = Repository(this)
        while (scope.isActive) {
            if (repo.sessionId != monitorSession || repo.siteId != monitorSite) { stopSelf(); return }
            try {
                val attendance = repo.get("/attendances/today?siteId=$monitorSite")["attendance"] as? Map<*, *>
                if (attendance == null || attendance["checkedOutAt"] != null) { notifySelf(null, "출근 중인 기록이 없어 감시를 종료했어요."); stopSelf(); return }
                val id = attendance["id"] as String
                val observed = prefs.getString("deviceState", null)
                if (id == prefs.getString("attendanceId", null) && observed in setOf("carried", "not_carried")) {
                    repo.request("PUT", "/attendances/$id/device-status", mapOf("state" to observed, "observedAt" to Instant.now().toString()))
                }
            } catch (error: ApiException) {
                if (error.status == 401 || error.status == 403 || error.code == "SHIFT_NOT_ACTIVE") { stopSelf(); return }
            } catch (_: IOException) { /* A missed heartbeat expires on the server; it is never replayed as a fresh reading. */ }
            delay(30_000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") stopSelf()
        return START_NOT_STICKY
    }
    override fun onSensorChanged(event: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        val v = DoubleArray(3) { i -> val value = event.values[i].toDouble(); if (raw) { gravity[i] = 0.8 * gravity[i] + 0.2 * value; value - gravity[i] } else value }
        if (now < settlingUntil || v.any { !it.isFinite() }) return
        val accelerationG = sqrt(v.sumOf { it * it }) / 9.80665
        if (now - lastSampleAt >= 50) { samples.addLast(now to accelerationG); lastSampleAt = now }
        while (samples.isNotEmpty() && (now - samples.first().first > 15000 || samples.size > 300)) samples.removeFirst()
        if (!detector.sample(now, v[0], v[1], v[2], highRisk = prefs.getString("highRiskTask", null) != null)) return
        if (prefs.getString("pendingSignal", null) != null && prefs.getString("signalSession", null) == monitorSession &&
            prefs.getString("signalSite", null) == monitorSite && prefs.getString("signalAttendance", null) == prefs.getString("attendanceId", null)) return
        val detectedAt = Instant.now()
        val localId = UUID.randomUUID().toString()
        SafetyAlarm.start(this)
        prefs.edit().putString("localAlert", localId).remove("localCallId").remove("signalFailed").apply()
        if (prefs.getBoolean("previewMonitoring", false)) {
            prefs.edit().putLong("previewSignalAt", System.currentTimeMillis()).putString("previewSignalId", localId).apply()
            notifySelf(null, "움직임 변화가 감지되었습니다. 15초 안에 확인해 주세요. · 체험 모드", localId)
            scope.launch { delay(15000); if (prefs.getString("ackAlert", null) != localId) notifySelf(null, "체험: 동료 확인 대기 단계입니다. 실제 알림은 전송하지 않습니다.", localId) }
            return
        }
        val window = samples.map { (at, g) -> mapOf("offsetMs" to (at - now).toInt(), "accelerationG" to g.coerceIn(0.0, 1000.0)) }
        val peakG = detector.lastDetectedPeakG.coerceIn(0.0, 1000.0)
        notifySelf(null, "움직임 변화가 감지되었습니다. 괜찮으세요? 서버 연결 중…", localId)
        scope.launch { reportSignal(localId, detectedAt, peakG, window) }
    }

    private suspend fun reportSignal(requestId: String, detectedAt: Instant, peakG: Double?, window: List<Map<String, Any>>) {
        val repo = Repository(this)
        if (repo.sessionId != monitorSession || repo.siteId != monitorSite) return
        val body = mutableMapOf<String, Any?>("siteId" to monitorSite, "requestId" to requestId, "source" to "abnormal_movement", "detectedAt" to detectedAt.toString(), "sensorWindow" to window)
        prefs.getString("attendanceId", null)?.let { body["attendanceId"] = it }
        peakG?.let { body["peakG"] = it }
        lastLocation()?.let { body["location"] = it }
        prefs.edit().putString("pendingSignal", org.json.JSONObject(body).toString()).putString("signalSession", monitorSession)
            .putString("signalSite", monitorSite).putString("signalAttendance",body["attendanceId"] as? String)
            .putLong("signalAt", detectedAt.toEpochMilli()).commit()
        repo.scheduleSignalRecovery(requestId)
        var incident: Map<*, *>? = null
        // Network retry reuses this exact requestId/body. Server owns the 15/30 second timers.
        for (attempt in 0..1) {
            if (repo.sessionId != monitorSession || repo.siteId != monitorSite) return
            try { incident = repo.request("POST", "/incidents", body)["incident"] as? Map<*, *>; break }
            catch (error: ApiException) { if (!error.retryable) break }
            catch (_: IOException) { }
            if (attempt == 0) delay(1000)
        }
        val id = incident?.get("id") as? String
        if (id != null) {
            if (prefs.getString("localAlert", null) == requestId) prefs.edit().remove("pendingSignal").apply()
            if (prefs.getString("ackAlert", null) == requestId) repo.queueRequest("PUT", "/incidents/$id/worker-response", mapOf("requestId" to UUID.randomUUID().toString(), "response" to "okay"), monitorSite)
            else {
                prefs.edit().putString("localCallId", id).apply()
                notifySelf(id, if (incident?.get("status") == "awaiting_worker") "앱에서 안전 상태를 확인해 주세요." else "동료의 현장 확인이 진행 중입니다.", requestId)
            }
        } else if (prefs.getString("pendingSignal", null) != null && prefs.getString("ackAlert", null) != requestId && repo.sessionId == monitorSession && repo.siteId == monitorSite) {
            prefs.edit().putBoolean("signalFailed", true).apply()
            notifySelf(null, "서버 연결 실패 · 동료 호출 여부를 확인할 수 없습니다. 119 또는 반장에게 직접 연락하세요.", requestId)
        }
    }

    private fun lastLocation(): Map<String, Any>? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val manager = getSystemService(LOCATION_SERVICE) as LocationManager
            val location = manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }.filter { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos in 0..120_000_000_000L }.maxByOrNull { it.elapsedRealtimeNanos } ?: return null
            if (!location.hasAccuracy() || !location.accuracy.isFinite() || location.accuracy !in 0f..100000f || !location.latitude.isFinite() || !location.longitude.isFinite() || location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) null
            else mapOf("latitude" to location.latitude, "longitude" to location.longitude, "accuracyMeters" to location.accuracy.toDouble())
        } catch (_: SecurityException) { null }
    }
    private fun notifySelf(call: String?, body: String, localId: String? = null) {
        val intent = Intent(this, MainActivity::class.java).putExtra("alert", true).putExtra("callId", call).putExtra("localAlert", localId).putExtra("siteId", monitorSite).putExtra("recipientId", monitorUid)
        val open = PendingIntent.getActivity(this, 3, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val dial = PendingIntent.getActivity(this, 119, Intent(this, MainActivity::class.java).putExtra("dial119", true)
            .putExtra("siteId", monitorSite).putExtra("recipientId", monitorUid).putExtra("callId", call), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching { getSystemService(NotificationManager::class.java).notify(103, NotificationCompat.Builder(this, "alerts").setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("안전 확인이 필요합니다").setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(open)
            .addAction(0, "상태 확인", open).addAction(0, "119 전화 화면", dial).setFullScreenIntent(open, true).setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_ALARM).setAutoCancel(true).build()) }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onDestroy() {
        if (::sensors.isInitialized) sensors.unregisterListener(this)
        wakeLock?.let { if (it.isHeld) it.release() }
        prefs.edit().putBoolean("monitoring", false).apply()
        scope.cancel(); super.onDestroy()
    }
}
