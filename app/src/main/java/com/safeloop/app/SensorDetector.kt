package com.safeloop.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * [SafeLoop 2.0 엣지 신호처리 엔진]
 * 단순 임계값(Threshold)의 오탐(망치질, 계단 이동 등)을 방지하기 위해
 * 1) Low-Pass Filter(저역통과필터) 기반 선형 가속도 추출
 * 2) 3.0G 이상 충격 피크(Impact Peak) 감지
 * 3) 충격 직후 3초간 움직임 편차(Inactivity Variance)를 추적하여 '충격 후 정지(쓰러짐 의심)' 판별
 */
class SensorDetector(
    context: Context,
    private val onTelemetryUpdate: (currentG: Double, linearG: Double, stateText: String) -> Unit,
    private val onShockAndInactivityDetected: (peakG: Double) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // 중력 성분을 분리하기 위한 저역통과필터(Low-Pass Filter) 중력 추정 배열
    private val gravity = FloatArray(3) { 0f }
    private val alpha = 0.8f // 필터 계수

    // 충격 후 무동작(Inactivity) 분석용 상태 변수
    private var isAnalyzingPostImpact = false
    private var impactTimestamp = 0L
    private var recordedPeakG = 0.0
    private val postImpactSamples = mutableListOf<Double>()
    private var lastAlertTime = 0L

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        val ax = event.values[0]
        val ay = event.values[1]
        val az = event.values[2]

        // [Step 1] Low-Pass Filter로 중력(Gravity) 성분 분리 및 순수 선형 가속도 추출
        gravity[0] = alpha * gravity[0] + (1 - alpha) * ax
        gravity[1] = alpha * gravity[1] + (1 - alpha) * ay
        gravity[2] = alpha * gravity[2] + (1 - alpha) * az

        val lx = ax - gravity[0]
        val ly = ay - gravity[1]
        val lz = az - gravity[2]

        // 전체 합성 가속도(중력 포함) 및 순수 충격 선형 가속도(중력 제외) G 단위 계산
        val totalMag = sqrt((ax * ax + ay * ay + az * az).toDouble()) / 9.80665
        val linearMag = sqrt((lx * lx + ly * ly + lz * lz).toDouble()) / 9.80665

        val roundedTotalG = (totalMag * 100).toInt() / 100.0
        val roundedLinearG = (linearMag * 100).toInt() / 100.0
        val now = System.currentTimeMillis()

        // [Step 2] 충격 후 무동작(Inactivity) 3초 검증 창이 열려 있는 경우
        if (isAnalyzingPostImpact) {
            val elapsed = now - impactTimestamp
            // 충격 직후 0.5초(여진 구간) 이후부터 3.0초까지의 움직임 샘플 수집
            if (elapsed in 500..3000) {
                postImpactSamples.add(roundedLinearG)
                onTelemetryUpdate(roundedTotalG, roundedLinearG, "충격 후 움직임 분석 중 (${(3000 - elapsed) / 1000.0}s)")
            } else if (elapsed > 3000) {
                // 3초간 평균 선형 움직임 계산: 계속 걷거나 작업 중이면 평균값이 높고, 쓰러지면 0.35G 이하로 수렴
                val avgPostMotion = if (postImpactSamples.isEmpty()) 0.0 else postImpactSamples.average()
                isAnalyzingPostImpact = false
                postImpactSamples.clear()

                if (avgPostMotion < 0.45) {
                    // 충격 후 정지 확인 -> 실제 위험 의심 트리거!
                    lastAlertTime = now
                    onTelemetryUpdate(roundedTotalG, roundedLinearG, "위험 의심 확정 (충격 후 정지 감지)")
                    onShockAndInactivityDetected(recordedPeakG)
                } else {
                    // 충격 후 활발한 움직임 지속 -> 일상 작업(망치질/이동) 오탐으로 자동 기각!
                    onTelemetryUpdate(roundedTotalG, roundedLinearG, "일상 작업 판정 (충격 후 정상 활동 감지됨)")
                }
            }
            return
        }

        // [Step 3] 평상시 감시: 총 가속도 2.8G 이상 순간 충격 발생 시 1차 후보 등록
        if (roundedTotalG >= 2.8 && (now - lastAlertTime > 5000)) {
            isAnalyzingPostImpact = true
            impactTimestamp = now
            recordedPeakG = roundedTotalG
            postImpactSamples.clear()
            onTelemetryUpdate(roundedTotalG, roundedLinearG, "1차 충격 감지(${roundedTotalG}G)! 후속 움직임 확인 중...")
        } else {
            onTelemetryUpdate(roundedTotalG, roundedLinearG, "정상 감시 중 (Edge Filtering)")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
