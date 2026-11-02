package com.safeloop.app

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** 작업 중에는 화면을 보지 않으므로 진동과 소리로 먼저 알린다. 일정 시간이 지나면 스스로 멈춘다. */
object SafetyAlarm {
    private val handler = Handler(Looper.getMainLooper())
    private val stopLater = Runnable { stop() }
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    @Synchronized fun start(context: Context, durationMs: Long = 15_000) {
        stop()
        val app = context.applicationContext
        val alarm = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        runCatching {
            val device = if (Build.VERSION.SDK_INT >= 31) app.getSystemService(VibratorManager::class.java).defaultVibrator
                else app.getSystemService(Vibrator::class.java)
            device.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 300), 0), alarm)
            vibrator = device
        }
        runCatching {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ringtone = RingtoneManager.getRingtone(app, uri)?.apply {
                audioAttributes = alarm
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        }
        handler.postDelayed(stopLater, durationMs.coerceAtLeast(1_000))
    }

    @Synchronized fun stop() {
        handler.removeCallbacks(stopLater)
        runCatching { ringtone?.stop() }
        runCatching { vibrator?.cancel() }
        ringtone = null
        vibrator = null
    }
}
