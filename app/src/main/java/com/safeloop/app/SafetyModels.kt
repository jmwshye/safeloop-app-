package com.safeloop.app

import java.security.MessageDigest

// [팀 공통 데이터 계약서] Firestore 'safety_events' 컬렉션에 저장될 엄격한 변수 타입 정의
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

// [위변조 방지 엔진] 직전 해시와 현재 데이터를 합쳐 64자리 SHA-256 암호 지문을 찍는 객체
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
