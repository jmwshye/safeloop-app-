package com.safeloop.app

// 말소리를 글자로 받은 뒤의 판단. 기기와 서버 없이 시험할 수 있게 순수 함수로 둔다.

private val NEGATED = Regex("안\\s?(?:아파|다쳤|다쳐)|괜찮")
private val HELP = Regex("도와\\s?(?:줘|조|저|져|주세요|달라)|살려\\s?(?:줘|조|저|주세요)|사람\\s?살려|구해\\s?(?:줘|주세요)|119\\s?(?:불러|좀|전화)|아파(?!트)|다쳤|다쳐\\s?(?:서|요)|못\\s?움직")

/** 호출을 앞당기는 말만 찾는다. 경보를 끄는 말은 옆 사람 말소리에 꺼지면 위험해서 받지 않는다. */
fun helpRequested(text: String): Boolean {
    val spoken = text.replace(Regex("\\s+"), " ").trim()
    return spoken.isNotEmpty() && !NEGATED.containsMatchIn(spoken) && HELP.containsMatchIn(spoken)
}

private const val OKAY_WORD = "(?:괜\\s?찮\\s?아\\s?요?|괜찮어\\s?요?|괜찮습니다|괜찮네요|괜차나\\s?요?|괜찬아\\s?요?|갠찮아\\s?요?|멀쩡해요)"
private val OKAY = Regex("(?:(?:네|예|응|저|나)\\s?)*$OKAY_WORD(?:[.!~,\\s]*$OKAY_WORD)*[.!~\\s]*")

/** 안내 음성이 나오는 중에도 들으므로 마이크에 섞인 안내 문구는 빼고 판단한다. */
private val PROMPT_WORDS = setOf("강한", "충격이", "충격", "감지되었어요", "감지됐어요", "감지", "되었어요", "상태를", "말씀해", "주세요")

/** 짧게 괜찮다고 답한 말만 인정한다. 문장 속 괜찮다는 말이나 옆 사람 대화로 경보가 꺼지면 안 되고, 도움 요청이 항상 먼저다. */
fun okayRequested(text: String): Boolean {
    val spoken = text.replace(Regex("\\s+"), " ").trim()
    if (helpRequested(spoken)) return false
    return OKAY.matches(spoken.split(" ").filterNot { it in PROMPT_WORDS }.joinToString(" "))
}

enum class SpokenAnswer { HELP, OKAY }

/** 인식 후보 중 하나라도 맞으면 답으로 본다. 도움 요청이 항상 먼저이고, 괜찮다는 답은 윗 후보 3개까지만 믿는다. */
fun spokenAnswer(words: List<String>): SpokenAnswer? = when {
    words.any(::helpRequested) -> SpokenAnswer.HELP
    words.take(3).any(::okayRequested) -> SpokenAnswer.OKAY
    else -> null
}

data class RiskSpeech(val category: String, val zone: String?, val note: String)

private val RISK_WORDS = listOf(
    "감전" to listOf("감전", "누전", "전선", "전기", "피복"),
    "충돌" to listOf("부딪", "충돌", "지게차", "크레인", "떨어지는 물건", "낙하물", "자재가 떨어"),
    "넘어짐" to listOf("미끄", "넘어", "걸려", "통로", "적치", "기름", "물이 고"),
    "추락" to listOf("추락", "개구부", "난간", "비계", "사다리", "덮개", "발판", "떨어질"),
)
private val ZONE = Regex("지하\\s?\\d+\\s?층|\\d+\\s?층(?:\\s?[동서남북]측)?|옥상|계단|출입구|엘리베이터|[동서남북]측|주차장|현장 입구")

/** 말한 문장에서 위험 종류와 위치를 채운다. 못 찾으면 기타로 두고 사람이 고친다. */
fun parseRiskSpeech(text: String): RiskSpeech {
    val spoken = text.trim()
    val category = RISK_WORDS.firstOrNull { (_, words) -> words.any(spoken::contains) }?.first ?: "기타"
    return RiskSpeech(category, ZONE.find(spoken)?.value?.replace(Regex("\\s+"), " "), spoken)
}

/** 기압이 오르면 아래로 내려온 것이다. 1hPa는 약 8.3m. 측정값이 아니라 추정이다. */
fun altitudeDropMeters(samples: List<Pair<Long, Float>>, now: Long): Double? {
    fun middle(from: Long, to: Long) = samples.filter { now - it.first in from..to }.map { it.second }.sorted().let { it.getOrNull(it.size / 2) }
    val before = middle(8_000, 14_000) ?: return null
    val after = middle(0, 5_000) ?: return null
    val drop = (after - before) * 8.3
    return drop.takeIf { it.isFinite() && it >= 0.5 && it < 100 }?.let { Math.round(it * 10) / 10.0 }
}

/** 보고서에 적는 충격 설명. 센서가 없으면 추정이라는 말만 남긴다. */
fun impactDetail(call: WorkerCall): String = listOfNotNull("시스템이 추정", call.peakG?.let { "최대 %.1fG".format(java.util.Locale.ROOT, it) },
    call.dropMeters?.let { "약 %.1fm 아래로 이동".format(java.util.Locale.ROOT, it) }).joinToString(" · ")
