package com.safeloop.app

import org.junit.Assert.*
import org.junit.Test

class IncidentDeliveryTest {
    @Test fun sharedPhoneRequiresBothRecipientAndSiteForLatePushes() {
        assertTrue(pushBelongsTo("worker-A", "site", "worker-A", "site"))
        assertFalse(pushBelongsTo("worker-A", "site", "worker-B", "site"))
        assertFalse(pushBelongsTo("worker-A", "site", "worker-A", "other"))
        assertFalse(pushBelongsTo(null, "site", "worker-A", "site"))
        assertFalse(pushBelongsTo("worker-A", "site", null, "site"))
    }
    @Test fun sharedPhonePreservesEachAccountRegistrationAcrossTokenChanges() {
        val install = java.util.UUID.randomUUID().toString()
        val workerA = pushDeviceId(install,"worker-A")
        val workerB = pushDeviceId(install,"worker-B")
        assertNotEquals(workerA,workerB)
        assertNotEquals(workerA,pushDeviceId(java.util.UUID.randomUUID().toString(),"worker-A"))
        assertEquals(workerA,pushDeviceId(install,"worker-A"))
    }
    @Test fun closingIncidentReplacesActiveLocationAndKeepsOpenPeerDetailOutOfOtherMonths() {
        val active = WorkerCall("case", subject="worker", latitude=37.5, longitude=127.0)
        val closed = WorkerCall("case", subject="worker", status="closed", resolution="handoff_confirmed")
        val summary = closed.copy(detailLoaded=false,resolution=null)
        assertEquals(37.5,mergeIncidentResults(listOf(active),null,emptyList()).single().latitude)
        val displayed = mergeIncidentResults(emptyList(),closed,listOf(summary)).single()
        assertNull(displayed.latitude)
        assertEquals("handoff_confirmed",displayed.resolution)
        assertEquals(closed,mergeIncidentResults(emptyList(),closed,emptyList()).single())
        assertTrue(mergeIncidentResults(emptyList(),null,emptyList()).isEmpty())
    }
    @Test fun briefShockBetweenTwoEvidenceSamplesKeepsItsActualPeak() {
        val detector = MotionDetector()
        assertFalse(detector.sample(0L, 0.0, 0.0, 0.0))
        assertFalse(detector.sample(20L, 32.0, 0.0, 0.0))
        var detected = false
        for(at in 40L..7400L step 20L) detected = detector.sample(at,0.0,0.0,0.0) || detected
        assertTrue(detected)
        assertEquals(32.0/9.80665,detector.lastDetectedPeakG,0.00001)
    }
}
