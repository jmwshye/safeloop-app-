package com.safeloop.app
import kotlin.math.sqrt
/** Linear acceleration in m/s². Experimental thresholds, not a diagnosis. */
class MotionDetector {
 companion object {
  /** 충격 뒤 움직임이 거의 없는지 지켜보는 시간 */
  const val WATCH_MS=3000L
  /** 센서 값이 빠져도 이만큼은 실제로 지켜봐야 판단한다. 지켜보는 시간의 약 70% */
  const val MIN_OBSERVED_MS=2000L
  /** 한 번 감지한 뒤 다시 감지하지 않는 시간 */
  const val COOLDOWN_MS=20000L
 }
 private var impactAt:Long?=null
 var lastDetectedPeakG:Double=0.0;private set
 private var intervalPeak=0.0
 private var lastAt:Long?=null
 private var cooldownUntil=0L
 private var observedMs=0L
 private var stillMs=0L
 /** 충격 뒤 움직임을 지켜보는 중이면 true. 폰 시험 기록용 */
 val tracking get()=impactAt!=null
 /** 마지막으로 끝난 관찰 결과. 폰 시험 기록용 */
 var lastWindow:String?=null;private set
 fun sample(at:Long,x:Double,y:Double,z:Double,highRisk:Boolean=false):Boolean {
  if(!x.isFinite()||!y.isFinite()||!z.isFinite())return false
  val previous=lastAt
  // 같은 시각에 묶여 온 값은 건너뛴다. 관찰 중인 충격을 지우면 감지를 놓친다.
  if(previous!=null&&at<=previous)return false
  lastAt=at
  if(previous!=null&&at-previous>1000){impactAt=null;observedMs=0;stillMs=0}
  if(at<cooldownUntil)return false
  val a=sqrt(x*x+y*y+z*z)
  if(impactAt==null){if(a>=9.81*(if(highRisk)2.5 else 3.0)){impactAt=at;intervalPeak=a;observedMs=0;stillMs=0};return false}
  intervalPeak=maxOf(intervalPeak,a)
  val dt=if(previous==null)0L else (at-previous).coerceIn(0,200)
  observedMs+=dt;if(a<1.5)stillMs+=dt
  if(at-impactAt!!<WATCH_MS)return false
  impactAt=null
  val suspected=observedMs>=MIN_OBSERVED_MS&&stillMs.toDouble()/observedMs>=0.7
  lastWindow="window observed=${observedMs}ms still=${stillMs}ms peak=%.1fG detected=$suspected".format(java.util.Locale.ROOT,intervalPeak/9.80665)
  if(suspected){cooldownUntil=at+COOLDOWN_MS;lastDetectedPeakG=intervalPeak/9.80665}
  return suspected
 }
}
