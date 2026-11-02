package com.safeloop.app

import java.security.MessageDigest

// 이전 로컬 센서 실험용 모델. 현재 앱의 NestJS 요청·서버 기록에는 사용하지 않음.
data class SafetyEvent(
    val eventId: String = "",
    val incidentGroupId: String = "",
    val siteCode: String = "SITE_2026_A",
    val actorUid: String = "user_park_01",
    val actorName: String = "박태규",
    val targetUid: String = "user_jung_02",
    val targetName: String = "정민철",
    val eventType: String = "SHOCK_DETECTED", // CHECK_IN, SHOCK_DETECTED, SELF_NO_RESPONSE 등
    val factOrEstimate: String = "SYSTEM_ESTIMATE", // HUMAN_FACT 또는 SYSTEM_ESTIMATE
    val shockG: Double = 0.0,
    val latitude: Double = 37.5665,
    val longitude: Double = 126.9780,
    val gpsAccuracyMeters: Double = 8.5,
    val batteryPct: Int = 88,
    val deviceTimestamp: Long = System.currentTimeMillis(),
    val prevEventHash: String = "GENESIS_BLOCK_0000000000000000",
    val currentEventHash: String = ""
)

// 이전 로컬 해시 실험. 실제 기록의 해시 체인은 NestJS 서버에서 생성·검증.
object HashChainEngine {
    var lastHash: String = "GENESIS_BLOCK_0000000000000000"

    fun generateChainedHash(eventId: String, uid: String, type: String, gVal: Double, time: Long): Pair<String, String> {
        val prev = lastHash
        val rawPayload = "$prev|$eventId|$uid|$type|$gVal|$time"
        val bytes = MessageDigest.getInstance("SHA-256").digest(rawPayload.toByteArray())
        val current = bytes.joinToString("") { "%02x".format(it) }
        lastHash = current // 체인 업데이트
        return Pair(prev, current)
    }
}
