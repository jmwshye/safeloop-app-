package com.safeloop.app

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 작업 중에는 화면을 보지 않으므로 진동과 소리로 먼저 알린다. 정해진 시간이 지나면 스스로 멈춘다. */
object SafetyAlarm {
    private const val VOICE_MS = 5_000L
    private val handler = Handler(Looper.getMainLooper())
    private val stopLater = Runnable { stop() }
    private var ringLater: Runnable? = null
    private var blink: Runnable? = null
    private var torch: Pair<CameraManager, String>? = null
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private val alarmSound = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    /** 구조 신호가 울리는 중이면 true. 화면에서 끌 수 있어야 한다. */
    var beaconOn by mutableStateOf(false)
        private set

    /** 안내 음성을 먼저 읽고 알람을 울린다. 말소리를 들어야 할 때는 ring=false로 진동만 쓴다. */
    @Synchronized fun start(context: Context, durationMs: Long = 15_000, voice: String? = null, ring: Boolean = true) {
        stop()
        val app = context.applicationContext
        vibrate(app)
        if (voice != null) SafetySpeech.say(app, voice)
        if (ring) ringAfter(app, if (voice != null && SafetySpeech.enabled(app)) VOICE_MS else 0)
        handler.postDelayed(stopLater, durationMs.coerceAtLeast(1_000))
    }

    /** 15초 안에 응답이 없으면 동료가 찾을 수 있게 사이렌과 손전등을 켠다. */
    @Synchronized fun beacon(context: Context, durationMs: Long = 60_000, voice: String? = null) {
        stop()
        val app = context.applicationContext
        vibrate(app)
        if (voice != null) SafetySpeech.say(app, voice)
        ringAfter(app, if (voice != null && SafetySpeech.enabled(app)) VOICE_MS else 0)
        startTorch(app)
        beaconOn = true
        handler.postDelayed(stopLater, durationMs)
    }

    @Synchronized fun stop() {
        handler.removeCallbacks(stopLater)
        ringLater?.let(handler::removeCallbacks)
        ringLater = null
        runCatching { ringtone?.stop() }
        runCatching { vibrator?.cancel() }
        ringtone = null
        vibrator = null
        stopTorch()
        beaconOn = false
        SafetySpeech.stop()
    }

    private fun vibrate(app: Context) = runCatching {
        val device = if (Build.VERSION.SDK_INT >= 31) app.getSystemService(VibratorManager::class.java).defaultVibrator
            else app.getSystemService(Vibrator::class.java)
        device.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 300), 0), alarmSound)
        vibrator = device
    }

    private fun ringAfter(app: Context, delayMs: Long) {
        val task = Runnable {
            runCatching {
                val uri = RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ringtone = RingtoneManager.getRingtone(app, uri)?.apply {
                    audioAttributes = alarmSound
                    if (Build.VERSION.SDK_INT >= 28) isLooping = true
                    play()
                }
            }
        }
        ringLater = task
        handler.postDelayed(task, delayMs)
    }

    private fun startTorch(app: Context) = runCatching {
        val manager = app.getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it)[CameraCharacteristics.FLASH_INFO_AVAILABLE] == true } ?: return@runCatching
        torch = manager to id
        var lit = false
        val step = object : Runnable {
            override fun run() {
                lit = !lit
                runCatching { manager.setTorchMode(id, lit) }
                handler.postDelayed(this, 400)
            }
        }
        blink = step
        handler.post(step)
    }

    private fun stopTorch() {
        blink?.let(handler::removeCallbacks)
        blink = null
        torch?.let { (manager, id) -> runCatching { manager.setTorchMode(id, false) } }
        torch = null
    }
}
