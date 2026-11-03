package com.safeloop.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/**
 * 폰의 음성 인식으로 듣는다. 녹음을 저장하지 않고 글자만 넘겨받는다.
 * 인식 서비스나 권한이 없으면 아무 일도 하지 않으므로 화면과 버튼은 그대로 동작한다.
 */
class VoiceListener(context: Context, private val continuous: Boolean, private val onPartial: (String) -> Unit, private val onDone: (String) -> Unit = {}, private val onHeard: (List<String>) -> Unit = {}, private val onSettled: (List<String>) -> Unit = {}, private val onGap: () -> Unit = {}) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var heard = ""
    private var words = emptyList<String>()
    private var settle: Runnable? = null
    private var lastGap = 0L
    private var retryDelayMs = 150L
    private val listen = Runnable { log("listen active=$active"); if (active) runCatching { recognizer?.startListening(request()) }.onFailure { log("listen failed $it"); finish() } }

    fun start(delayMs: Long = 0) {
        log("start active=$active available=${available(app)} mic=${hasMicrophone(app)} delay=$delayMs")
        if (active || !available(app) || !hasMicrophone(app)) return
        active = true
        heard = ""
        recognizer = runCatching { SpeechRecognizer.createSpeechRecognizer(app).apply { setRecognitionListener(callback) } }.getOrNull()
        if (recognizer == null) { active = false; return }
        handler.postDelayed(listen, delayMs)
    }

    fun stop() {
        active = false
        handler.removeCallbacks(listen)
        settle?.let(handler::removeCallbacks)
        runCatching { recognizer?.cancel(); recognizer?.destroy() }
        recognizer = null
    }

    private fun finish() {
        val text = heard
        stop()
        onDone(text)
    }

    private fun request() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
        .apply {
            if (continuous) {
                // 말이 잠깐 멈춰도 끊지 않고, 지원하는 폰에서는 응답 말을 우선 알아듣게 한다.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3_000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3_000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15_000L)
                if (Build.VERSION.SDK_INT >= 33) putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(ANSWER_WORDS))
            }
        }

    private fun log(message: String) { if (BuildConfig.DEBUG) Log.d("SafetyVoice", message) }

    private fun nextRound() {
        settle?.let(handler::removeCallbacks)
        if (!continuous || !active) { finish(); return }
        heard = ""
        words = emptyList()
        runCatching { recognizer?.cancel() }
        val now = System.currentTimeMillis()
        if (now - lastGap >= 4_000) { lastGap = now; onGap() }
        handler.postDelayed(listen, retryDelayMs)
    }

    /** 폰에 따라 최종 결과 없이 끝나므로, 말이 잠시 멈추면 지금까지 들은 말을 끝난 말로 본다. */
    private fun settleLater() {
        settle?.let(handler::removeCallbacks)
        settle = Runnable { log("settled: $words"); onSettled(words) }.also { handler.postDelayed(it, 400) }
    }

    private fun receive(results: Bundle?): Boolean {
        val found = speechCandidates(results)
        if (found.isEmpty()) return false
        log("heard: $found")
        words = found
        heard = found.first()
        onPartial(heard)
        onHeard(found)
        return true
    }

    private val callback = object : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle?) { if (receive(partialResults)) settleLater() }
        override fun onResults(results: Bundle?) { settle?.let(handler::removeCallbacks); receive(results); if (words.isNotEmpty()) onSettled(words); retryDelayMs = 150; nextRound() }
        override fun onError(error: Int) { log("error: $error"); settle?.let(handler::removeCallbacks); if (words.isNotEmpty()) onSettled(words); retryDelayMs = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 400 else 150; nextRound() }
        override fun onReadyForSpeech(params: Bundle?) { log("ready") }
        override fun onBeginningOfSpeech() { log("speech begins") }
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() { log("speech ends") }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    companion object {
        private val ANSWER_WORDS = listOf("괜찮아", "괜찮아요", "도와줘", "도와주세요", "살려주세요")
        fun available(context: Context) = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
        fun hasMicrophone(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
}

/** 인식 후보 전체를 돌려준다. 1등 후보가 틀려도 뒤쪽 후보에 맞는 말이 있는 경우가 많다. */
internal fun speechCandidates(results: Bundle?): List<String> {
    val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
    val pending = results?.getStringArrayList("android.speech.extra.UNSTABLE_TEXT").orEmpty()
    return (texts.mapIndexed { i, text -> text + pending.getOrElse(i) { "" } } + texts).map(String::trim).filter(String::isNotBlank).distinct()
}

/** 4-1 위험 신고를 말로 입력한다. 장갑을 낀 손으로도 쓸 수 있다. */
@Composable fun VoiceReportButton(onText: (String) -> Unit) {
    val context = LocalContext.current
    if (!remember { VoiceListener.available(context) }) return
    var listening by remember { mutableStateOf(false) }
    var heard by remember { mutableStateOf("") }
    val listener = remember { VoiceListener(context, continuous = false, onPartial = { heard = it }, onDone = { text -> listening = false; if (text.isNotBlank()) onText(text) }) }
    DisposableEffect(Unit) { onDispose { listener.stop() } }
    fun begin() { heard = ""; listening = true; listener.start() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) begin() }
    SLButton(if (listening) "듣고 있어요 · 눌러서 끝내기" else "말로 신고하기", {
        when {
            listening -> { listener.stop(); listening = false; if (heard.isNotBlank()) onText(heard) }
            VoiceListener.hasMicrophone(context) -> begin()
            else -> permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }, secondary = true, icon = "mic")
    if (listening) NoteText(heard.ifBlank { "예: 2층 동측 개구부에 덮개가 없어요" })
}

/** 알림 설정의 음성 안내 켜기·끄기 */
@Composable fun VoiceSetting() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(SafetySpeech.enabled(context)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("음성 안내", Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
        Switch(on, { on = it; SafetySpeech.setEnabled(context, it) }, colors = SwitchDefaults.colors(checkedTrackColor = Brand))
    }
    if (on) SLButton("음성 들어보기", { SafetySpeech.say(context, SafetySpeech.SELF_CHECK) }, secondary = true)
    if (SafetySpeech.unavailable) SmallNote("이 폰에서 한국어 음성을 쓸 수 없어요. 진동과 소리로 알려 드려요.", amber = true)
}
