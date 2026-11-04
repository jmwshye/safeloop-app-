package com.safeloop.app
import org.junit.Assert.*
import org.junit.Test
class MotionDetectorTest {
 @Test fun moderateImpactRequiresHighRiskModeAndSubsequentStillness() {
  fun alerts(highRisk:Boolean,afterImpact:Double):Int {
   val detector=MotionDetector()
   detector.sample(0,26.0,0.0,0.0,highRisk)
   return (20L..10000L step 20).count{detector.sample(it,afterImpact,0.0,0.0,highRisk)}
  }
  assertEquals(0,alerts(false,0.0))
  assertEquals(1,alerts(true,0.0))
  assertEquals(0,alerts(true,5.0))
 }
 @Test fun stillnessAloneDoesNotAlert(){val d=MotionDetector();for(t in 0L..10000L step 20)assertFalse(d.sample(t,0.0,0.0,0.0))}
 @Test fun impactThenStillnessAlertsOnce(){val d=MotionDetector();assertFalse(d.sample(0,32.0,0.0,0.0));var alerts=0;for(t in 20L..15000L step 20)if(d.sample(t,0.1,0.0,0.0))alerts++;assertEquals(1,alerts)}
 @Test fun continuousMovementDoesNotAlert(){val d=MotionDetector();d.sample(0,32.0,0.0,0.0);for(t in 20L..10000L step 20)assertFalse(d.sample(t,5.0,0.0,0.0))}
 @Test fun stillnessIsJudgedAboutThreeSecondsAfterImpact(){
  val d=MotionDetector();d.sample(0,32.0,0.0,0.0)
  val first=(20L..15000L step 20).first{d.sample(it,0.1,0.0,0.0)}
  assertTrue("$first",first in 3000L..3100L)
 }
 @Test fun secondDropIsIgnoredForTwentySecondsThenDetected(){
  val d=MotionDetector()
  fun drop(start:Long):Boolean{d.sample(start,32.0,0.0,0.0);return (start+20..start+5000 step 20).any{d.sample(it,0.1,0.0,0.0)}}
  assertTrue(drop(0))
  assertFalse(drop(5020))
  assertTrue(drop(23100))
 }
 @Test fun samplesDeliveredInTheSameMillisecondKeepTheCandidate(){
  // 폰은 센서 값을 몇 개씩 묶어 보내기도 한다. 같은 시각 값 때문에 관찰 중인 충격이 지워지면 안 된다.
  val d=MotionDetector();d.sample(0,32.0,0.0,0.0);var alerts=0
  for(t in 20L..15000L step 20){if(d.sample(t,0.1,0.0,0.0))alerts++;if(d.sample(t,0.1,0.0,0.0))alerts++}
  assertEquals(1,alerts)
 }
 @Test fun sensorGapInvalidatesCandidate(){val d=MotionDetector();d.sample(0,32.0,0.0,0.0);for(t in 7000L..15000L step 20)assertFalse(d.sample(t,0.0,0.0,0.0))}
}
