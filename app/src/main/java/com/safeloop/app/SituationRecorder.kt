package com.safeloop.app

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 도움을 요청하면 뒤쪽 카메라로 상황을 30초 남긴다. 주변 대화가 담기지 않도록 소리는 녹음하지 않는다.
 * 영상은 이 폰에만 저장하고 작업자가 직접 보고 지울 수 있다. 녹화가 실패해도 호출은 그대로 진행된다.
 */
object SituationRecorder {
    private const val LIMIT_MS = 30_000L
    private var recording: Recording? = null
    private var provider: ProcessCameraProvider? = null
    var active by mutableStateOf(false)
        private set
    /** 영상을 저장하거나 지우면 바뀌어서 화면이 다시 읽게 한다. */
    var revision by mutableIntStateOf(0)
        private set

    private fun prefs(context: Context) = context.getSharedPreferences("safeloop", Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("situationVideo", true)
    fun setEnabled(context: Context, value: Boolean) { prefs(context).edit().putBoolean("situationVideo", value).apply() }
    fun file(context: Context, callId: String) = File(File(context.filesDir, "situation").apply { mkdirs() }, callId.filter { it.isLetterOrDigit() || it == '-' } + ".mp4")

    fun start(activity: ComponentActivity, callId: String, onFinished: (Int) -> Unit) {
        if (active) return
        val target = file(activity, callId)
        val main = ContextCompat.getMainExecutor(activity)
        active = true
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            val started = runCatching {
                val cameras = future.get()
                provider = cameras
                val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.SD, FallbackStrategy.higherQualityOrLowerThan(Quality.SD))).build()
                val video = VideoCapture.withOutput(recorder)
                cameras.unbindAll()
                val camera = cameras.bindToLifecycle(activity, CameraSelector.DEFAULT_BACK_CAMERA, video)
                runCatching { camera.cameraControl.enableTorch(true) }
                val output = FileOutputOptions.Builder(target).setDurationLimitMillis(LIMIT_MS).build()
                recording = video.output.prepareRecording(activity, output).start(main) { event ->
                    if (event is VideoRecordEvent.Finalize) {
                        val seconds = TimeUnit.NANOSECONDS.toSeconds(event.recordingStats.recordedDurationNanos).toInt()
                        finish(target, seconds, onFinished)
                    }
                }
            }
            if (started.isFailure) finish(target, 0, onFinished)
        }, main)
    }

    fun stop() { runCatching { recording?.stop() } }

    private fun finish(target: File, seconds: Int, onFinished: (Int) -> Unit) {
        runCatching { provider?.unbindAll() }
        recording = null
        provider = null
        active = false
        if (seconds <= 0 || target.length() == 0L) target.delete()
        revision++
        onFinished(if (target.exists()) seconds else 0)
    }

    fun delete(context: Context, callId: String) { file(context, callId).delete(); revision++ }

    fun open(context: Context, callId: String) = runCatching {
        val uri = FileProvider.getUriForFile(context, "com.safeloop.app.files", file(context, callId))
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }.isSuccess
}

/** 저장된 상황 영상을 보거나 지운다. 영상이 없으면 아무것도 그리지 않는다. */
@Composable fun SituationVideo(callId: String) {
    val context = LocalContext.current
    val saved = remember(callId, SituationRecorder.revision) { SituationRecorder.file(context, callId).exists() }
    if (!saved) return
    SLCard {
        Text("상황 영상", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
        NoteText("소리 없이 저장했고 이 폰에만 있어요. 내가 직접 보고 지울 수 있어요.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SLButton("보기", { SituationRecorder.open(context, callId) }, Modifier.weight(1f), secondary = true)
            SLButton("지우기", { SituationRecorder.delete(context, callId) }, Modifier.weight(1f), secondary = true)
        }
    }
}

/** 알림 설정의 상황 녹화 켜기·끄기 */
@Composable fun SituationSetting() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(SituationRecorder.enabled(context)) }
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text("도움 요청 때 상황 녹화", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text("소리 없이 30초. 이 폰에만 저장해요", fontSize = 14.sp, color = Muted)
        }
        androidx.compose.material3.Switch(on, { on = it; SituationRecorder.setEnabled(context, it) })
    }
}
