package com.safeloop.app

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class SafetyTemperatureTest {
    private val today = LocalDate.of(2026, 11, 3)
    private fun at(day: LocalDate, hour: Int = 9) = day.atTime(hour, 0).atZone(koreaZone).toInstant().toEpochMilli()
    private fun shift(day: LocalDate, done: Boolean = true) = WorkEntry("w$day", day.toString(), at(day, 8), if (done) at(day, 17) else null)
    private fun event(title: String, day: LocalDate = today, hour: Int = 10) = WorkerEvent("$title$day$hour", title, "", at(day, hour))

    @Test fun startsAtTheSameTemperatureForEveryone() {
        val start = safetyTemperature(emptyList(), emptyList(), today)
        assertEquals(36.5, start.degrees, 0.0)
        assertEquals(0.0, start.monthGain, 0.0)
        assertTrue(start.activeDays.isEmpty())
    }
    @Test fun aFullDayRaisesTheTemperature() {
        val day = safetyTemperature(listOf(shift(today)), listOf(event("작업 전 확인"), event("동료 교차 확인"), event("작업 중 상태 응답")), today)
        assertEquals(36.95, day.degrees, 0.06)
        assertEquals(day.todayGain, day.monthGain, 0.0)
        assertEquals(1, day.monthCounts[TemperatureStep.CHECKLIST])
    }
    @Test fun honestAnswersRaiseItTheSame() {
        val honest = safetyTemperature(listOf(shift(today)), listOf(WorkerEvent("a", "작업 전 확인", "모름 · 개선 필요 · 확인", at(today))), today)
        val allYes = safetyTemperature(listOf(shift(today)), listOf(WorkerEvent("a", "작업 전 확인", "확인 · 확인 · 확인", at(today))), today)
        assertEquals(allYes.degrees, honest.degrees, 0.0)
    }
    @Test fun incidentsAndMissedChecksNeverLowerIt() {
        val calm = safetyTemperature(listOf(shift(today)), listOf(event("작업 전 확인")), today)
        val rough = safetyTemperature(listOf(shift(today)), listOf(event("작업 전 확인"), event("이상 움직임 감지"), event("체크인 응답 없음"), event("본인 응답 없음"), event("119 전화 화면 열기")), today)
        assertEquals(calm.degrees, rough.degrees, 0.0)
    }
    @Test fun repeatingTheSameRecordIsCapped() {
        val spam = safetyTemperature(emptyList(), List(20) { event("위험 신고", hour = 8 + it % 10) }, today)
        assertEquals(36.7, spam.degrees, 0.001)
        val busy = safetyTemperature(listOf(shift(today)), TemperatureStep.entries.flatMap { step -> listOf("작업 전 확인", "동료 확인", "작업 중 상태 응답", "위험 신고", "현장 확인 수락").map { event(it) } }, today)
        assertEquals(37.1, busy.degrees, 0.001)
    }
    @Test fun missedDaysDoNotCoolItDown() {
        val earlier = today.minusDays(10)
        val t = safetyTemperature(listOf(shift(earlier)), listOf(event("작업 전 확인", earlier)), today)
        assertEquals(36.75, t.degrees, 0.06)
        assertEquals(0.0, t.monthGain, 0.0)
    }
    @Test fun monthlyNumbersCoverOnlyThisMonth() {
        val t = safetyTemperature(listOf(shift(today), shift(today.minusDays(1)), shift(LocalDate.of(2026, 10, 30))), emptyList(), today)
        assertEquals(2, t.monthCounts[TemperatureStep.ATTEND])
        assertEquals(setOf("2026-11-02", "2026-11-03"), t.activeDays)
        assertTrue(t.degrees > 36.5 + t.monthGain - 0.001)
    }
    @Test fun futureRecordsAreIgnored() {
        assertEquals(36.5, safetyTemperature(listOf(shift(today.plusDays(1))), emptyList(), today).degrees, 0.0)
    }
    @Test fun serverAndDemoTitlesMeanTheSameStep() {
        assertEquals(temperatureStep("작업 전 확인"), temperatureStep("작업 전 점검"))
        assertEquals(temperatureStep("동료 교차 확인"), temperatureStep("동료 확인"))
        assertEquals(temperatureStep("동료 수락"), temperatureStep("현장 확인 수락"))
        assertNull(temperatureStep("이상 움직임 감지"))
    }
    @Test fun barStartsVisibleAndStopsAtFull() {
        assertTrue(temperatureFill(36.5) > 0f)
        assertEquals(1f, temperatureFill(80.0), 0f)
    }
}
