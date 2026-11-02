package com.safeloop.app

import android.app.*
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.UUID

class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val repo = Repository(this)
        if (repo.hasSession) repo.queueRequest("PUT", "/push-devices/${repo.deviceId}", mapOf("fcmToken" to token))
    }
    override fun onMessageReceived(message: RemoteMessage) {
        val site = message.data["siteId"]?.takeIf(::isUuid) ?: return
        val repo = Repository(this)
        if (!pushBelongsTo(message.data["recipientId"], site, repo.currentUser?.uid, repo.siteId)) return
        val type = message.data["type"] ?: return
        val incident = message.data["incidentId"]?.takeIf(::isUuid)
        val eventId = message.data["eventId"] ?: return
        if (type == "incident_alert" && incident != null) {
            repo.queueRequest("PUT", "/incidents/$incident/receipt", mapOf("stage" to "received", "occurredAt" to java.time.Instant.now().toString()), site)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("alerts", "안전 확인 요청", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel("site-updates", "현장 알림", NotificationManager.IMPORTANCE_DEFAULT))
        if (type == "incident_closed" && incident != null) nm.cancel(incident.hashCode())
        val urgent = type == "incident_alert"
        val title = when (type) {
            "incident_alert" -> "동료 안전 확인 요청"
            "incident_verification_overdue" -> "현장 상태 확인이 늦어지고 있어요"
            "incident_observation" -> "현장 상태가 확인됐어요"
            "incident_worker_safe" -> "작업자가 안전 확인에 응답했어요"
            "incident_closed" -> "긴급 대응이 마무리됐어요"
            "work_prompt" -> "작업 상태를 확인해 주세요"
            "high_risk" -> "동료가 고위험 작업 중이에요"
            "high_risk_end" -> "동료가 고위험 작업을 마쳤어요"
            "safety_review" -> "안전 점검 확인 요청"
            "risk_action" -> "위험 요소 조치 요청"
            else -> return
        }
        val intent = Intent(this, MainActivity::class.java).putExtra("siteId", site).putExtra("callId", incident)
            .putExtra("eventId", eventId).putExtra("recipientId", repo.currentUser?.uid).putExtra("notificationType", type).putExtra("entityId", message.data["entityId"])
            .putExtra("peerAlert", urgent)
        val id = if (urgent && incident != null) incident.hashCode() else eventId.hashCode()
        val pending = PendingIntent.getActivity(this, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, if (urgent) "alerts" else "site-updates")
            .setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle(title)
            .setContentText(if (urgent) "본인의 안전을 확보한 뒤 앱에서 동료의 상태를 확인해 주세요." else "앱에서 최신 현장 기록을 확인해 주세요.")
            .setContentIntent(pending).setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT).setAutoCancel(true)
        if (urgent) {
            val dial = PendingIntent.getActivity(this, incident?.hashCode() ?: 119, Intent(this, MainActivity::class.java)
                .setAction("com.safeloop.app.DIAL_119").putExtra("dial119", true).putExtra("recipientId", repo.currentUser?.uid).putExtra("siteId", site).putExtra("callId", incident),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            notification.addAction(0, "현장 확인", pending).addAction(0, "119 전화 화면", dial)
        }
        runCatching { nm.notify(id, notification.build()) }
    }
    private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value.lowercase() }.getOrDefault(false)
}
