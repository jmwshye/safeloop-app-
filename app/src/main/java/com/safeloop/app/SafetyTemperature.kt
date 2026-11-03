package com.safeloop.app

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/**
 * 나의 안전 온도. 앱을 꾸준히 쓰며 남긴 기록으로 폰 안에서만 계산하고, 본인에게만 보여 준다.
 * 평가가 아니므로 사고, 호출, 무응답, 결근으로는 내려가지 않고 '모름'과 '개선 필요'도 똑같이 오른다.
 */
enum class TemperatureStep(val label: String, val degrees: Double, val perDay: Int) {
    ATTEND("출근", .1, 1),
    CHECKOUT("퇴근 기록", .1, 1),
    CHECKLIST("작업 전 3가지 확인", .1, 1),
    PEER("동료와 서로 확인", .2, 1),
    CHECK_IN("작업 중 확인 응답", .1, 2),
    RISK("위험 신고·조치·재확인", .1, 2),
    HELP("동료 돕기", .3, 1),
}

const val START_DEGREES = 36.5
const val DAILY_LIMIT = .8

/** 동료를 챙길수록 붙는 이름표. 지금은 본인만 본다. */
enum class TemperatureLevel(val title: String, val from: Double) {
    START("함께 시작", START_DEGREES),
    STEADY("든든한 동료", 39.0),
    TRUSTED("믿음 가는 동료", 42.0),
}

fun temperatureLevel(degrees: Double) = TemperatureLevel.entries.last { degrees >= it.from }
fun nextTemperatureLevel(degrees: Double) = TemperatureLevel.entries.firstOrNull { it.from > degrees }

/** 동료를 확인해 주거나 도운 횟수. 온도를 많이 올리는 기록이다. */
val SafetyTemperature.peerCare get() = (monthCounts[TemperatureStep.PEER] ?: 0) + (monthCounts[TemperatureStep.HELP] ?: 0)

data class SafetyTemperature(val degrees: Double, val monthGain: Double, val todayGain: Double, val monthCounts: Map<TemperatureStep, Int>, val activeDays: Set<String>)

/** 체험 기록과 서버 기록의 제목을 같은 단계로 묶는다. 온도를 낮추는 기록은 없다. */
fun temperatureStep(title: String): TemperatureStep? = when (title) {
    "작업 전 확인", "작업 전 점검" -> TemperatureStep.CHECKLIST
    "동료 교차 확인", "동료 확인", "보호구 재확인" -> TemperatureStep.PEER
    "작업 중 상태 응답" -> TemperatureStep.CHECK_IN
    "위험 요소 신고", "위험 신고", "위험 조치", "위험 재확인" -> TemperatureStep.RISK
    "동료 수락", "현장 확인 수락", "현장 도착" -> TemperatureStep.HELP
    else -> null
}

private fun dayOf(time: Long) = Instant.ofEpochMilli(time).atZone(koreaZone).toLocalDate().toString()
private fun round1(value: Double) = Math.round(value * 10) / 10.0

fun safetyTemperature(work: List<WorkEntry>, events: List<WorkerEvent>, today: LocalDate = LocalDate.now(koreaZone)): SafetyTemperature {
    val steps = mutableMapOf<String, MutableList<TemperatureStep>>()
    fun add(day: String, step: TemperatureStep) { if (day <= today.toString()) steps.getOrPut(day) { mutableListOf() } += step }
    work.forEach { entry -> add(entry.day, TemperatureStep.ATTEND); if (entry.end != null) add(entry.day, TemperatureStep.CHECKOUT) }
    events.forEach { event -> temperatureStep(event.title)?.let { add(dayOf(event.time), it) } }
    // 같은 날 같은 기록을 여러 번 남겨도 정해진 횟수까지만 오른다.
    val counted = steps.mapValues { (_, list) -> TemperatureStep.entries.flatMap { step -> List(minOf(list.count { it == step }, step.perDay)) { step } } }
    val gains = counted.mapValues { (_, list) -> minOf(list.sumOf { it.degrees }, DAILY_LIMIT) }
    val month = YearMonth.from(today).toString()
    val monthDays = counted.filterKeys { it.startsWith(month) }
    return SafetyTemperature(
        degrees = round1(minOf(START_DEGREES + gains.values.sum(), 99.9)),
        monthGain = round1(gains.filterKeys { it.startsWith(month) }.values.sum()),
        todayGain = round1(gains[today.toString()] ?: 0.0),
        monthCounts = TemperatureStep.entries.associateWith { step -> monthDays.values.sumOf { list -> list.count { it == step } } },
        activeDays = monthDays.keys,
    )
}

/** 온도 막대의 채움 정도. 36.5도에서 시작해 50도에서 가득 찬다. */
fun temperatureFill(degrees: Double) = ((degrees - START_DEGREES) / (50.0 - START_DEGREES)).coerceIn(.02, 1.0).toFloat()
