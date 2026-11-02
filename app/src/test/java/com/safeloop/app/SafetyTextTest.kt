package com.safeloop.app

import org.junit.Assert.*
import org.junit.Test

class SafetyTextTest {
    @Test fun helpWordsCallForHelp() {
        listOf("도와줘", "도와 주세요", "살려주세요", "사람 살려", "119 불러", "너무 아파요", "다쳤어요", "못 움직이겠어요").forEach { assertTrue(it, helpRequested(it)) }
    }
    @Test fun ordinaryTalkDoesNotCallForHelp() {
        listOf("", "아파트 2층이에요", "오늘 점심 뭐 먹지", "안 아파요", "안 다쳤어요", "괜찮아요 도와줘 필요 없어요").forEach { assertFalse(it, helpRequested(it)) }
    }
    @Test fun shortOkayAnswersCount() {
        listOf("괜찮아", "괜찮아요", "괜찮아 요", "괜찮습니다", "네 괜찮아요", "저 괜찮아요.", "멀쩡해요", "괜찮아요 괜찮아요", "네 네 괜찮아요", "응 괜찮아").forEach { assertTrue(it, okayRequested(it)) }
    }
    @Test fun okayInsideOtherTalkDoesNotStopTheCall() {
        listOf("", "안 괜찮아요", "괜찮지 않아", "괜찮아요 도와주세요", "저기 괜찮아요 여기로 가져오세요", "그거 괜찮아 보여", "도와줘").forEach { assertFalse(it, okayRequested(it)) }
    }
    @Test fun riskSpeechFillsCategoryAndZone() {
        val opening = parseRiskSpeech("2층 동측 개구부에 덮개가 없어요")
        assertEquals("추락", opening.category); assertEquals("2층 동측", opening.zone); assertEquals("2층 동측 개구부에 덮개가 없어요", opening.note)
        assertEquals("감전", parseRiskSpeech("지하 1층 전선 피복이 벗겨졌어요").category)
        assertEquals("지하 1층", parseRiskSpeech("지하 1층 전선 피복이 벗겨졌어요").zone)
        assertEquals("넘어짐", parseRiskSpeech("출입구 통로에 자재가 쌓였어요").category)
    }
    @Test fun unknownSpeechStaysOtherWithoutZone() {
        val other = parseRiskSpeech("이상한 냄새가 나요")
        assertEquals("기타", other.category); assertNull(other.zone)
    }
    @Test fun faceBoxStaysInsidePhoto() {
        assertEquals(listOf(0, 0, 115, 115), faceBox(0f, 0f, 100f, 100f, 400, 400, keepChin = false))
        assertEquals(listOf(285, 285, 115, 115), faceBox(300f, 300f, 400f, 400f, 400, 400, keepChin = false))
        assertNull(faceBox(500f, 500f, 600f, 600f, 400, 400, keepChin = false))
    }
    @Test fun protectiveGearPhotoKeepsChinVisible() {
        val box = faceBox(100f, 100f, 200f, 200f, 400, 400, keepChin = true)!!
        assertTrue(box[1] + box[3] <= 168)
        val full = faceBox(100f, 100f, 200f, 200f, 400, 400, keepChin = false)!!
        assertTrue(full[1] + full[3] >= 200)
    }
    @Test fun pressureRiseMeansMovedDown() {
        val now = 20_000L
        val samples = (10_000L until 20_000L step 500).map { it to if (it < 13_000) 1000f else 1000.3f }
        assertEquals(2.5, altitudeDropMeters(samples, now)!!, 0.1)
    }
    @Test fun steadyOrMissingPressureGivesNoEstimate() {
        assertNull(altitudeDropMeters((6_000L until 20_000L step 500).map { it to 1000f }, 20_000))
        assertNull(altitudeDropMeters(emptyList(), 20_000))
    }
}
