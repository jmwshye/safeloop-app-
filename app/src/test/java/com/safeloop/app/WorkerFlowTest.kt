package com.safeloop.app

import org.junit.Assert.*
import org.junit.Test

class WorkerFlowTest {
 @Test fun answeringPreviewSensorSignalStopsLaterSirenAndTorch(){
  // 체험 모드에서 실제로 흔들어 생긴 신호는 신호 id가 호출 id다. 답하면 서비스가 15초 뒤 손전등을 켜지 않아야 한다.
  assertEquals("signal",answeredSensorAlert("signal","safe",preview=true,localAlert="signal",localCallId=null))
  assertEquals("signal",answeredSensorAlert("signal","help",preview=true,localAlert="signal",localCallId=null))
  assertNull(answeredSensorAlert("menu-demo","safe",preview=true,localAlert="signal",localCallId=null))
  // 서버 모드는 서버 호출 id로 맞추고, 도움 요청은 괜찮다는 응답으로 오인되지 않게 표시하지 않는다.
  assertEquals("signal",answeredSensorAlert("incident","safe",preview=false,localAlert="signal",localCallId="incident"))
  assertNull(answeredSensorAlert("incident","help",preview=false,localAlert="signal",localCallId="incident"))
  assertNull(answeredSensorAlert("signal","safe",preview=false,localAlert="signal",localCallId=null))
  assertNull(answeredSensorAlert("signal","safe",preview=true,localAlert=null,localCallId=null))
 }
 @Test fun highRiskCodesAreShownInKorean(){assertEquals("비계 위 작업",highRiskLabel("scaffold"));assertEquals("고소작업대 작업",highRiskLabel("aerial_lift"));assertEquals("",highRiskLabel(null))}
 @Test fun timelineNeverShowsRawResponseCodes(){listOf("safe","help","accepted","arrived","declined","retry","observe:conscious","finish:no_help_needed").forEach{assertFalse(it,responseLabel(it).any{c->c in 'a'..'z'})}}
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
 @Test fun pendingLocationRetainsPurposeAfterRecreationAndNeverMovesBetweenSessions() {
  val request=LocationRequest(LocationPurpose.CHECK_ONLY,"session:site")
  assertEquals(LocationPurpose.CHECK_ONLY,LocationRequest.restore(request.purpose.name,request.scope)?.forScope("session:site"))
  assertNull(LocationRequest.restore(null,"session:site"))
  assertNull(LocationRequest.restore("obsolete","session:site"))
  assertNull(LocationRequest.restore("CHECK_IN","old:site")?.forScope("new:site"))
  assertEquals(LocationPurpose.RESPONDER,LocationRequest.restore("RESPONDER","session:site")?.forScope("session:site"))
 }
 @Test fun reassignmentInvalidatesCapturedBuddyAndBlocksMisfiledEvidence() {
  val original=WorkerState.live("me","A").copy(buddyAttendanceId="shift-B")
  val capturedFor=original.verifiedBuddyTarget("shift-B")
  val updated=original.copy(buddyAttendanceId="shift-C")
  assertThrows(IllegalArgumentException::class.java){updated.verifiedBuddyTarget(capturedFor)}
  assertEquals("shift-C",updated.verifiedBuddyTarget("shift-C"))
 }
 @Test fun openOvernightWorkUsesSavedProfileAndRestrictsSwitchingOnlyWhileActive() {
  val old=WorkEntry("old","2026-01-01",1L,siteId="A",zone="2층",occupation="설비")
  val state=WorkerState.live("worker","작업자").copy(siteId="A",zone="4층",job="용접",work=listOf(old),liveAttendanceId=old.id)
  assertEquals("old",state.currentShift?.id)
  assertEquals("2층",state.workZone);assertEquals("설비",state.workOccupation);assertTrue(state.hasSiteObligation())
  val ended=state.copy(work=listOf(old.copy(end=2L)),liveAttendanceId=null)
  assertNull(state.copy(liveAttendanceId=null).currentShift);assertEquals("4층",ended.workZone);assertFalse(ended.hasSiteObligation())
  assertTrue(ended.copy(calls=listOf(WorkerCall("incident",subject="other",acceptedBy="worker"))).hasSiteObligation())
  assertFalse(ended.copy(calls=listOf(WorkerCall("incident",subject="other",acceptedBy="another"))).hasSiteObligation())
 }
 @Test fun detailOutsideFirstPageSurvivesListRefreshAndUsesUpdatedRecordWhenReturned() {
  val old=WorkerRisk("101","작업장 통로","2층","확인")
  val page=(1..100).map{old.copy(id=it.toString())}
  val state=WorkerState.live("worker","작업자").copy(risks=page,selectedRisk=old)
  assertEquals(old,state.riskDetail("101"))
  assertNull(state.riskDetail("102"))
  assertEquals("verified",state.copy(risks=page+old.copy(status="verified")).riskDetail("101")?.status)
 }
 @Test fun ppeReportDescribesMismatchEvenWithoutAPhoto() {
  assertEquals("불일치",ppeReportLabel("mismatch","statement"))
  assertEquals("사진 없는 진술",ppeReportLabel("checked","statement"))
  assertEquals("사진 미확인",ppeReportLabel("checked","missing_photo"))
 }
}
