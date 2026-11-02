package com.safeloop.app

import org.junit.Assert.*
import org.junit.Test

class WorkerFlowTest {
 @Test fun attendanceDoesNotRequireSafetyChecklist(){val before=WorkerState();val after=before.attend();assertEquals(0,after.checkCount);assertNotNull(after.currentShift);assertEquals(180000,after.currentShift!!.wage);assertThrows(IllegalArgumentException::class.java){after.attend()}}
 @Test fun checkoutCompletesWorkEvenWhenChecksAreMissing(){val started=WorkerState(monitoring=true).attend();val finished=started.checkout();assertNull(finished.currentShift);assertNotNull(finished.todayShift!!.end);assertFalse(finished.monitoring);assertEquals(0,finished.checkCount)}
 @Test fun unknownAndNeedsAttentionStillCountAsAnswered(){
  assertEquals(3,WorkerState(checks=listOf("확인","모름","개선 필요")).checkCount)
 }
 @Test fun newShiftClearsPreviousChecklistAndHighRiskMode(){
  val previous=WorkerState(checks=listOf("확인","확인","확인"),peerChecked=true,highRiskTask="ladder",highRiskStartedAt=10)
  val today=previous.attend()
  assertEquals(0,today.checkCount);assertFalse(today.peerChecked);assertNull(today.highRiskTask)
 }
 @Test fun completionRequiresArrivalAndObservationButPhotoIsOptional(){val incoming=WorkerCall("one",subject="someone-else",stage="peer");assertThrows(IllegalArgumentException::class.java){incoming.verify("me",null)};val accepted=incoming.accept("me");assertThrows(IllegalArgumentException::class.java){accepted.verify("me",null)};val arrived=accepted.arrive("me");assertThrows(IllegalArgumentException::class.java){arrived.verify("me",null)};val observed=arrived.observe("me","conscious");assertEquals("closed",observed.verify("me",null).status);assertEquals("photo.jpg",observed.verify("me","photo.jpg").photo)}
 @Test fun liveModeNeverStartsWithDemoRecords(){val live=WorkerState.live("real-user","작업자");assertFalse(live.preview);assertTrue(live.work.isEmpty());assertTrue(live.risks.isEmpty());assertTrue(live.calls.isEmpty());assertFalse(live.wageKnown)}
 @Test fun anotherSiteAttendanceIsNotCurrentShift(){val live=WorkerState.live("real-user","작업자").copy(siteId="one",work=listOf(WorkEntry("shift",todayKey(),System.currentTimeMillis(),siteId="two")));assertNull(live.currentShift);assertNull(live.todayShift)}
 @Test fun anotherPersonCannotTakeOverAnAcceptedRequest(){val call=WorkerCall("one",subject="person",stage="peer");assertThrows(IllegalArgumentException::class.java){call.accept("person")};val accepted=call.accept("me");assertThrows(IllegalArgumentException::class.java){accepted.accept("other")};assertThrows(IllegalArgumentException::class.java){accepted.arrive("other")}}
 @Test fun csvEscapesSpreadsheetExpressionsAndLabelsPreview(){val state=WorkerState(name="=HYPERLINK(test)").attend();val csv=workCsv(state);assertTrue(csv.startsWith("\uFEFF"));assertTrue(csv.contains("'=HYPERLINK(test)"));assertTrue(csv.contains("체험 기록"));assertFalse(csv.contains("서버 기록"))}
 @Test fun peerCompletionRequiresBothDirectionsAndDistinguishesMissingPhotos() {
  val incoming=PeerEvidence("buddy","my-shift","checked",1,"photo-a")
  val outgoing=PeerEvidence("me","buddy-shift","checked",2,"photo-b")
  fun status(rows:List<PeerEvidence>)=peerVerificationStatus("my-shift","me","buddy","buddy-shift","foreman",rows)
  assertEquals("pending",status(listOf(incoming)))
  assertEquals("photo_confirmed",status(listOf(incoming,outgoing)))
  assertEquals("statement_only",status(listOf(incoming,outgoing.copy(photoId=null))))
  assertEquals("mismatch",status(listOf(incoming,outgoing.copy(outcome="mismatch"))))
 }
 @Test fun delegatedForemanCheckDoesNotRequireReciprocalWorkerPhoto() {
  val incoming=PeerEvidence("foreman","my-shift","checked",1,"photo")
  val unrelatedOutgoing=PeerEvidence("me","foreman-shift","mismatch",2)
  assertEquals("photo_confirmed",peerVerificationStatus("my-shift","me","foreman","foreman-shift","foreman",listOf(incoming,unrelatedOutgoing)))
  assertEquals("statement_only",peerVerificationStatus("my-shift","me","foreman",null,"foreman",listOf(incoming.copy(photoId=null))))
 }
}
