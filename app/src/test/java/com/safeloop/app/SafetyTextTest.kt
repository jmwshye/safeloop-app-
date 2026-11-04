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
    @Test fun similarSoundingAnswersCount() {
        listOf("괜찮어", "괜찮어요", "괜차나", "괜차나요", "괜찬아요", "갠찮아요", "괜 찮아요", "괜찮 아요").forEach { assertTrue(it, okayRequested(it)) }
        listOf("도와조", "도와저", "도와져", "살려조").forEach { assertTrue(it, helpRequested(it)) }
    }
    @Test fun anyCandidateCanAnswerAndHelpComesFirst() {
        assertEquals(SpokenAnswer.OKAY, spokenAnswer(listOf("관찬아", "괜찮아요")))
        assertEquals(SpokenAnswer.HELP, spokenAnswer(listOf("도와 뭐", "도와줘")))
        assertEquals(SpokenAnswer.HELP, spokenAnswer(listOf("괜찮아요", "도와줘")))
        assertNull(spokenAnswer(listOf("안녕하세요", "감사합니다")))
        assertNull(spokenAnswer(emptyList()))
    }
    @Test fun lowCandidatesCannotStopTheAlarm() {
        assertNull(spokenAnswer(listOf("하나", "둘", "셋", "괜찮아요")))
    }
    @Test fun theShorterPromptNeverAnswers() {
        val prompt = SafetySpeech.SELF_CHECK.replace(".", "")
        assertNull(spokenAnswer(listOf(prompt)))
        assertTrue(okayRequested("$prompt 괜찮아"))
    }
    @Test fun originalPhotoSitsNextToTheMaskedOne() {
        assertEquals("/a/b.original.jpg", originalPathOf("/a/b.jpg"))
    }
    @Test fun answersCountEvenWhenThePromptIsHeardToo() {
        listOf("강한 충격이 감지되었어요 괜찮아", "상태를 말씀해 주세요 괜찮아요", "괜찮아 상태를 말씀해 주세요").forEach { assertTrue(it, okayRequested(it)) }
        assertTrue(helpRequested("강한 충격이 감지되었어요 도와줘"))
    }
    @Test fun thePromptAloneNeverAnswers() {
        val prompt = "강한 충격이 감지되었어요. 상태를 말씀해 주세요."
        assertFalse(okayRequested(prompt.replace(".", ""))); assertFalse(helpRequested(prompt))
        assertFalse(okayRequested("상태를 말씀해 주세요"))
    }
    @Test fun okayInsideOtherTalkDoesNotStopTheCall() {
        listOf("", "안 괜찮아요", "괜찮지 않아", "괜찮아요 도와주세요", "저기 괜찮아요 여기로 가져오세요", "그거 괜찮아 보여", "도와줘").forEach { assertFalse(it, okayRequested(it)) }
    }
    @Test fun englishAndRussianAnswersCount() {
        listOf("help me", "Help!", "somebody help", "save me", "헬프 미", "헬프미", "помогите", "Помоги").forEach { assertTrue(it, helpRequested(it)) }
        listOf("I'm okay", "i'm fine.", "I am OK", "Yes, I'm okay", "아임 오케이", "아임파인", "я в порядке", "Всё хорошо", "Да, я в порядке").forEach { assertTrue(it, okayRequested(it)) }
        assertEquals(SpokenAnswer.HELP, spokenAnswer(listOf("Help me")))
        assertEquals(SpokenAnswer.OKAY, spokenAnswer(listOf("I'm okay")))
    }
    @Test fun foreignTalkThatIsNotAnAnswerIsIgnored() {
        listOf("helpful tip", "I don't need help", "I'm okay, no help", "я в порядке, помогите не нужно").forEach { assertFalse(it, helpRequested(it)) }
        // '오케이'나 'okay'만 말하면 옆 사람 말일 수 있어 경보를 끄지 않는다.
        listOf("okay", "오케이", "that's okay with me", "I'm not okay", "я не в порядке", "help me I'm okay").forEach { assertFalse(it, okayRequested(it)) }
    }
    @Test fun answerAfterNearbyTalkCountsWhenSaidAfterAPause() {
        // 폰에서 옆 사람 말이 15초 내내 한 문장으로 이어진 경우. 잠깐 멈춘 뒤 말한 답만 떼어 본다.
        val nearby = "자기 얼굴이 기록된 채로 들어가면"
        assertNull(spokenAnswer(listOf("$nearby 괜찮아")))
        assertEquals(SpokenAnswer.OKAY, spokenAnswer(latestSpeech(nearby, listOf("$nearby 괜찮아"))))
        assertNull(spokenAnswer(latestSpeech(nearby, listOf("$nearby 그거 괜찮아 보여"))))
        // 앞말에 '괜찮'이 있어도 뒤에 한 도움 요청은 받는다.
        assertEquals(SpokenAnswer.HELP, spokenAnswer(latestSpeech("아까는 괜찮다고 했는데", listOf("아까는 괜찮다고 했는데 도와줘"))))
        // 앞부분이 고쳐지면 전체 문장으로만 판단한다.
        assertEquals(listOf("자기 얼굴 기록된 채 괜찮아"), latestSpeech(nearby, listOf("자기 얼굴 기록된 채 괜찮아")))
        assertEquals(listOf("괜찮아"), latestSpeech("", listOf("괜찮아")))
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
