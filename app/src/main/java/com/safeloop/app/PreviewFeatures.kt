package com.safeloop.app

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalTime

// 체험 모드는 서버가 없으므로 체크인 예약, 기록 변경 탐지, 월간 집계를 기기에서 처리한다.

internal const val PROMPT_WINDOW_MS = 5 * 60_000L

/** 점심과 오후 휴식 복귀 때만 묻는다. 작업 중에 수시로 묻지 않는다. */
internal fun scheduledPrompts(now: Long): List<WorkPrompt> {
    val day = Instant.ofEpochMilli(now).atZone(koreaZone).toLocalDate()
    return listOf(LocalTime.of(13, 0), LocalTime.of(15, 30)).map { day.atTime(it).atZone(koreaZone).toInstant().toEpochMilli() }
        .filter { it > now }.map { WorkPrompt("break-$it", "break_return", it, "scheduled") }
}

internal fun advancePrompts(prompts: List<WorkPrompt>, now: Long): List<WorkPrompt> = prompts.map {
    when {
        it.state == "scheduled" && now >= it.dueAt -> it.copy(state = "pending")
        it.state == "pending" && now >= it.dueAt + PROMPT_WINDOW_MS -> it.copy(state = "unverified")
        else -> it
    }
}

internal fun eventHash(previous: String, event: WorkerEvent): String {
    val text = listOf(previous, event.id, event.title, event.detail, event.time, event.estimate).joinToString("|")
    return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/** 최신 기록이 앞에 온다. 저장 개수 제한으로 잘린 가장 오래된 기록은 기준점으로만 쓴다. */
internal fun chainIntact(events: List<WorkerEvent>): Boolean =
    events.zipWithNext().all { (newer, older) -> older.hash.isNotBlank() && eventHash(older.hash, newer) == newer.hash }

internal data class MonthlySummary(val month: String, val workDays: Int, val wageTotal: Int, val checklists: Int, val peerChecks: Int, val risks: Int, val safetyCalls: Int)

internal fun monthlySummary(s: WorkerState, month: String): MonthlySummary {
    fun inMonth(time: Long) = Instant.ofEpochMilli(time).atZone(koreaZone).toLocalDate().toString().startsWith(month)
    val work = s.work.filter { it.day.startsWith(month) }
    val events = s.events.filter { inMonth(it.time) }
    return MonthlySummary(month, work.distinctBy { it.day }.size, work.sumOf { it.wage }, events.count { it.title == "작업 전 확인" },
        events.count { it.title == "동료 교차 확인" }, events.count { it.title == "위험 요소 신고" },
        s.calls.count { it.createdAt > 0 && inMonth(it.createdAt) })
}

internal fun monthlyLines(summary: MonthlySummary): List<String> = listOf(
    "기간 · ${summary.month}",
    "근로일 · ${summary.workDays}일",
    "예상 일당 합계 · ${moneyText(summary.wageTotal)}원 (실제 지급액과 다를 수 있어요)",
    "작업 전 확인 · ${summary.checklists}회",
    "동료 교차 확인 · ${summary.peerChecks}회",
    "위험 신고 · ${summary.risks}건",
    "안전 확인 요청 · ${summary.safetyCalls}건 (사고 건수가 아니에요)",
    "이 기기의 체험 기록이에요. 실제 사고나 신고를 입증하지 않아요.",
)
