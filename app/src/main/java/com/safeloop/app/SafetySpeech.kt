package com.safeloop.app

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** 작업 중에는 화면을 볼 수 없으므로 긴급 안내를 소리로 읽어 준다. 폰의 음성 엔진을 쓰며 인터넷이 필요 없다. */
object SafetySpeech {
    // 듣기 기능이 안내 음성을 사람 말로 착각하지 않도록 응답 말(괜찮아요, 도와줘)을 넣지 않는다.
    const val SELF_CHECK = "강한 충격이 감지되었어요. 상태를 말씀해 주세요."
    const val CALLING = "동료를 부르고 있어요. 움직일 수 있으면 119를 누르세요."
    const val RECORDING = "도움을 요청했어요. 상황을 영상으로 남기고 있어요."
    const val CHECK_IN = "안전하신가요? 괜찮으시면 앱에서 괜찮아요를 눌러 주세요."
    const val PEER_ALERT = "동료 안전 확인 요청이 왔어요. 내 안전을 먼저 확보하세요."

    private var engine: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var afterDone: Runnable? = null
    /** 한국어 음성을 쓸 수 없는 폰이면 true. 이때는 진동과 소리로만 알린다. */
    var unavailable by mutableStateOf(false)
        private set

    private fun prefs(context: Context) = context.getSharedPreferences("safeloop", Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("voiceGuide", true)
    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("voiceGuide", value).apply()
        if (!value) stop()
    }

    fun prepare(context: Context) {
        if (engine != null) return
        engine = runCatching {
            TextToSpeech(context.applicationContext) { status ->
                val voice = engine
                val language = if (status == TextToSpeech.SUCCESS) voice?.setLanguage(Locale.KOREAN) else null
                ready = language != null && language != TextToSpeech.LANG_MISSING_DATA && language != TextToSpeech.LANG_NOT_SUPPORTED
                unavailable = !ready
                if (ready) {
                    voice?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    voice?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) { handler.post(::runAfterDone) }
                        @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { handler.post(::runAfterDone) }
                    })
                    pending?.let(::speak)
                }
                pending = null
            }
        }.getOrNull()
        if (engine == null) unavailable = true
    }

    fun say(context: Context, text: String) {
        if (!enabled(context)) return
        prepare(context)
        if (ready) speak(text) else pending = text
    }

    private fun speak(text: String) { runCatching { engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "safety") } }

    /** 안내 음성이 끝나면 실행한다. 음성이 없거나 응답이 없으면 maxWaitMs 뒤에 실행한다. */
    fun afterSpeech(context: Context, maxWaitMs: Long, action: () -> Unit) {
        val task = Runnable { afterDone?.let(handler::removeCallbacks); afterDone = null; action() }
        afterDone = task
        handler.postDelayed(task, if (enabled(context)) maxWaitMs else 0)
    }

    private fun runAfterDone() { afterDone?.let { handler.removeCallbacks(it); it.run() } }

    fun cancelAfterSpeech() { afterDone?.let(handler::removeCallbacks); afterDone = null }

    fun stop() {
        pending = null
        runCatching { engine?.stop() }
    }
}
