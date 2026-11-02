package com.safeloop.app
import kotlin.math.sqrt
/** Linear acceleration in m/s². Experimental thresholds, not a diagnosis. */
class MotionDetector {
 private var impactAt:Long?=null
 private var lastAt:Long?=null
 private var cooldownUntil=0L
 private var observedMs=0L
 private var stillMs=0L
 fun sample(at:Long,x:Double,y:Double,z:Double):Boolean {
  if(!x.isFinite()||!y.isFinite()||!z.isFinite())return false
  val previous=lastAt;lastAt=at
  if(previous!=null&&(at<=previous||at-previous>1000)){impactAt=null;observedMs=0;stillMs=0}
  if(at<cooldownUntil)return false
  val a=sqrt(x*x+y*y+z*z)
  if(impactAt==null){if(a>=29.43){impactAt=at;observedMs=0;stillMs=0};return false}
  val dt=if(previous==null)0L else (at-previous).coerceIn(0,200)
  observedMs+=dt;if(a<1.5)stillMs+=dt
  if(at-impactAt!!<7000)return false
  impactAt=null
  val suspected=observedMs>=5000&&stillMs.toDouble()/observedMs>=0.7
  if(suspected)cooldownUntil=at+60000
  return suspected
 }
}
