package com.safeloop.app
import org.junit.Assert.*
import org.junit.Test
class MotionDetectorTest {
 @Test fun stillnessAloneDoesNotAlert(){val d=MotionDetector();for(t in 0L..10000L step 20)assertFalse(d.sample(t,0.0,0.0,0.0))}
 @Test fun impactThenStillnessAlertsOnce(){val d=MotionDetector();assertFalse(d.sample(0,32.0,0.0,0.0));var alerts=0;for(t in 20L..15000L step 20)if(d.sample(t,0.1,0.0,0.0))alerts++;assertEquals(1,alerts)}
 @Test fun continuousMovementDoesNotAlert(){val d=MotionDetector();d.sample(0,32.0,0.0,0.0);for(t in 20L..10000L step 20)assertFalse(d.sample(t,5.0,0.0,0.0))}
 @Test fun sensorGapInvalidatesCandidate(){val d=MotionDetector();d.sample(0,32.0,0.0,0.0);for(t in 7000L..15000L step 20)assertFalse(d.sample(t,0.0,0.0,0.0))}
}
