package com.safeloop.app

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PreviewFeaturesTest {
    private fun at(hour: Int, minute: Int = 0) = LocalDate.of(2026, 11, 3).atTime(hour, minute).atZone(koreaZone).toInstant().toEpochMilli()
    private fun chained(vararg titles: String) = titles.foldIndexed(emptyList<WorkerEvent>()) { i, events, title ->
        val entry = WorkerEvent("e$i", title, "내용", time = 1000L + i)
        listOf(entry.copy(hash = eventHash(events.firstOrNull()?.hash.orEmpty(), entry))) + events
    }

    @Test fun checkInsAreScheduledOnlyForLaterTimes() {
        assertEquals(2, scheduledPrompts(at(8)).size)
        assertEquals(1, scheduledPrompts(at(14)).size)
        assertTrue(scheduledPrompts(at(16)).isEmpty())
        assertTrue(scheduledPrompts(at(8)).all { it.state == "scheduled" && it.kind == "break_return" })
    }
    @Test fun unansweredCheckInBecomesUnverifiedNotAnAccident() {
        val prompt = WorkPrompt("one", "break_return", at(13), "scheduled")
        assertEquals("scheduled", advancePrompts(listOf(prompt), at(12, 59)).single().state)
        val due = advancePrompts(listOf(prompt), at(13)).single()
        assertEquals("pending", due.state)
        assertEquals("pending", advancePrompts(listOf(due), at(13, 4)).single().state)
        assertEquals("unverified", advancePrompts(listOf(due), at(13, 5)).single().state)
    }
    @Test fun answeredCheckInIsLeftAlone() {
        val answered = WorkPrompt("one", "break_return", at(13), "answered", "okay")
        assertEquals(answered, advancePrompts(listOf(answered), at(18)).single())
    }
    @Test fun recordChainDetectsEditedEvent() {
        val events = chained("출근 기록", "작업 전 확인", "퇴근 기록")
        assertTrue(chainIntact(events))
        assertFalse(chainIntact(events.mapIndexed { i, e -> if (i == 1) e.copy(detail = "고친 내용") else e }))
        assertFalse(chainIntact(events.filterIndexed { i, _ -> i != 1 }))
    }
    @Test fun chainSurvivesTrimmedOldRecords() {
        assertTrue(chainIntact(chained("하나", "둘", "셋", "넷").dropLast(1)))
        assertTrue(chainIntact(emptyList()))
    }
    @Test fun monthlySummaryCountsOnlySelectedMonth() {
        val now = at(9)
        val state = WorkerState(
            work = listOf(WorkEntry("a", "2026-11-03", now, wage = 100), WorkEntry("b", "2026-10-30", now, wage = 900)),
            events = listOf(WorkerEvent("1", "작업 전 확인", "", now), WorkerEvent("2", "동료 교차 확인", "", now), WorkerEvent("3", "위험 요소 신고", "", now), WorkerEvent("4", "작업 전 확인", "", at(9) - 40L * 86_400_000)),
            calls = listOf(WorkerCall("c", createdAt = now)),
        )
        assertEquals(MonthlySummary("2026-11", 1, 100, 1, 1, 1, 1), monthlySummary(state, "2026-11"))
    }
    @Test fun monthlyReportDoesNotCallRequestsAccidents() {
        assertTrue(monthlyLines(MonthlySummary("2026-11", 1, 100, 1, 1, 1, 1)).any { it.contains("사고 건수가 아니에요") })
    }
}
