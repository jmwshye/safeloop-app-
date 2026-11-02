package com.safeloop.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class NotificationAction { MONITOR, DEMO_SELF, DEMO_PEER }

enum class LocationPurpose { CHECK_IN, CHECK_ONLY, RESPONDER }

data class LocationRequest(val purpose:LocationPurpose,val scope:String) {
 fun forScope(active:String)=purpose.takeIf{scope==active}
 companion object {
  fun restore(purpose:String?,scope:String?):LocationRequest? {
   val known=LocationPurpose.entries.find{it.name==purpose}?:return null
   return scope?.takeIf{it.isNotBlank()}?.let{LocationRequest(known,it)}
  }
 }
}

val koreaZone: ZoneId = ZoneId.of("Asia/Seoul")
fun locationLabel(status:String)=when(status){"inside"->"현장 안";"outside"->"현장 밖";else->"미확인"}
fun todayKey(): String = LocalDate.now(koreaZone).toString()
fun moneyText(value: Int): String = String.format(Locale.KOREA,"%,d",value)
fun clockText(value: Long?): String = value?.let { DateTimeFormatter.ofPattern("HH:mm").withZone(koreaZone).format(Instant.ofEpochMilli(it)) } ?: "—"
fun dateLabel(): String = LocalDate.now(koreaZone).format(DateTimeFormatter.ofPattern("M월 d일 EEEE",Locale.KOREA))
fun parseTime(value: Any?): Long? = runCatching { Instant.parse(value?.toString()).toEpochMilli() }.getOrNull()

data class WorkEntry(val id:String,val day:String,val start:Long,val end:Long?=null,val wage:Int=180000,val sample:Boolean=false,val wageKnown:Boolean=true,val site:String="",val zone:String="",val siteId:String="",val occupation:String="")
data class WorkerEvent(val id:String,val title:String,val detail:String,val time:Long=System.currentTimeMillis(),val estimate:Boolean=false,val canCorrect:Boolean=false,val siteId:String="")
data class WorkerRisk(val id:String,val title:String,val zone:String,val note:String,val category:String="기타",val status:String="open",val photo:String?=null,val time:Long=System.currentTimeMillis(),val anonymous:Boolean=true,val beforePhotoId:String?=null,val afterPhotoId:String?=null,val afterPhoto:String?=null,val fixerName:String?=null,val verifierName:String?=null,val fixedAt:Long?=null,val verifiedAt:Long?=null,val canRecheck:Boolean=false)
data class WorkPrompt(val id:String,val kind:String,val dueAt:Long,val state:String,val response:String?=null)
data class PeerEvidence(val checkerId:String,val subjectAttendanceId:String,val outcome:String,val checkedAt:Long,val photoId:String?=null,val photo:String?=null)
data class CallRecipient(val id:String,val name:String,val role:String,val delivery:String,val receivedAt:Long?=null,val openedAt:Long?=null,val acceptedAt:Long?=null,val declinedAt:Long?=null)
data class SiteChoice(val id:String,val name:String,val code:String,val foreman:String)
data class WorkerCall(
 val id:String,val subject:String="preview-self",val subjectName:String="김민수",val zone:String="2층 · 설비 작업",
 val stage:String="self",val status:String="pending",val deadline:Long=System.currentTimeMillis()+15000,
 val acceptedBy:String?=null,val arrivedAt:Long?=null,val photo:String?=null,val resolution:String?=null,val overdue:Boolean=false,
 val serverStatus:String="",val observation:String?=null,val photoId:String?=null,val recipients:List<CallRecipient> = emptyList(),
 val detailLoaded:Boolean=true,val latitude:Double?=null,val longitude:Double?=null,val accuracy:Double?=null,val workerResponse:String?=null,val createdAt:Long=0,val respondedAt:Long?=null,val acceptedAt:Long?=null,val dialTimes:List<Long> = emptyList(),val observationNotice:String?=null
) {
 fun isSubject(uid:String)=subject==uid
 fun remainingSeconds(now:Long)=((deadline-now+999)/1000).coerceAtLeast(0)
 fun accept(uid:String):WorkerCall {require(!isSubject(uid)&&stage=="peer"&&status=="pending"){"이미 수락했거나 종료된 요청입니다."};return copy(status="accepted",acceptedBy=uid)}
 fun arrive(uid:String):WorkerCall {require(status=="accepted"&&acceptedBy==uid){"수락한 동료만 현장 도착을 기록할 수 있습니다."};return copy(arrivedAt=System.currentTimeMillis())}
 fun observe(uid:String,condition:String):WorkerCall {require(status=="accepted"&&acceptedBy==uid&&arrivedAt!=null){"현장 도착을 먼저 기록해 주세요."};require(condition in listOf("unconscious","immobile","conscious","appears_safe"));return copy(observation=condition)}
 fun verify(uid:String,localPhoto:String?,reason:String="onsite_action_completed"):WorkerCall {require(status=="accepted"&&acceptedBy==uid&&arrivedAt!=null&&observation!=null){"현장 도착과 상태 확인을 먼저 기록해 주세요."};require(reason in listOf("no_help_needed","onsite_action_completed","handoff_confirmed"));return copy(status="closed",photo=localPhoto,resolution=reason)}
}
data class WorkerState(
 val preview:Boolean=true,val uid:String="preview-self",val name:String="김민수",val job:String="설비 작업자",
 val site:String="성수동 근린생활시설 신축",val code:String="SL2026",val zone:String="2층 · 설비 작업",val wage:Int=180000,
 val reportCallIds:Set<String> = emptySet(),val selectedRisk:WorkerRisk?=null,val buddyName:String="박철수",val buddyZone:String="2층 · 설비 작업",val buddyState:String="확인 대기",
 val carried:Boolean=true,val monitoring:Boolean=false,val checks:List<String> = listOf("","",""),val peerChecked:Boolean=false,
 val work:List<WorkEntry> = emptyList(),val risks:List<WorkerRisk> = emptyList(),val events:List<WorkerEvent> = emptyList(),val calls:List<WorkerCall> = emptyList(),
 val deviceState:String="carried",val wageKnown:Boolean=true,val siteId:String="",val locationStatus:String="unverified",val buddyId:String?=null,val buddyAttendanceId:String?=null,val liveAttendanceId:String?=null,
 val peerEvidence:List<PeerEvidence> = emptyList(),val peerStatus:String="pending",val highRiskTask:String?=null,val highRiskStartedAt:Long?=null,val prompts:List<WorkPrompt> = emptyList(),
 val emergencyPush:Boolean=true,val sitePush:Boolean=true,val connectionProblem:String?=null,val lastSyncedAt:Long?=null,
 val foremanPhone:String?=null,val corrections:List<String> = emptyList(),val nextTimeline:Int?=null,val nextRisks:Int?=null,val nextIncidents:Int?=null,val beforeLocation:String="unverified",val riskCounts:Map<String,Int> = emptyMap()
) {
 fun riskDetail(id:String):WorkerRisk? {
  val detail=selectedRisk?.takeIf{it.id==id}
  val row=risks.firstOrNull{it.id==id} ?: return detail
  if(detail==null)return row
  return row.copy(photo=row.photo ?: detail.photo.takeIf{row.beforePhotoId==detail.beforePhotoId},
   afterPhoto=row.afterPhoto ?: detail.afterPhoto.takeIf{row.afterPhotoId==detail.afterPhotoId})
 }
 fun verifiedBuddyTarget(id:String?):String {
  require(!id.isNullOrBlank() && id==buddyAttendanceId){"동료가 바뀌었어요. 새 동료의 보호구를 다시 확인해 주세요."}
  return id
 }
 fun hasSiteObligation()=currentShift!=null||activeCalls.any{it.isSubject(uid)||it.acceptedBy==uid}
 val todayShift get()=if(preview)work.firstOrNull{!it.sample&&(it.end==null||it.day==todayKey())}
  else work.firstOrNull{it.id==liveAttendanceId&&it.siteId==siteId}
 val currentShift get()=todayShift?.takeIf{it.end==null}
 val workZone get()=todayShift?.zone?.ifBlank { "미입력" } ?: zone
 val workOccupation get()=todayShift?.occupation?.ifBlank { "미입력" } ?: job
 val checkCount get()=checks.count{it.isNotBlank()}
 val activeCalls get()=calls.filter{it.status!="closed"}
 val selfCall get()=activeCalls.firstOrNull{it.isSubject(uid)&&it.stage=="self"}
 val monthWork get()=work.filter{it.day.startsWith(todayKey().take(7))}
 val monthWage get()=monthWork.sumOf{it.wage}
 fun attend(now:Long=System.currentTimeMillis()):WorkerState {require(todayShift==null){"오늘의 출근 기록이 이미 있습니다."};return copy(checks=listOf("","",""),peerEvidence=emptyList(),peerStatus="pending",peerChecked=false,prompts=emptyList(),highRiskTask=null,highRiskStartedAt=null,work=listOf(WorkEntry("work-$now",todayKey(),now,wage=wage,wageKnown=wageKnown,site=site,zone=zone,occupation=job))+work)}
 fun checkout(now:Long=System.currentTimeMillis()):WorkerState {val shift=currentShift?:error("출근 기록이 없습니다.");return copy(work=work.map{if(it.id==shift.id)it.copy(end=now)else it},monitoring=false,highRiskTask=null,highRiskStartedAt=null)}
 companion object {
  fun live(uid:String,name:String)=WorkerState(preview=false,uid=uid,name=name,job="",site="현장 정보 불러오는 중",code="",zone="",wage=0,wageKnown=false,carried=false,deviceState="unverified",buddyName="배정 대기",buddyZone="",buddyState="연결 미확인")
  fun preview():WorkerState {
   val today=LocalDate.now(koreaZone)
   val work=(1L..7L).map{today.minusDays(it)}.filter{it.dayOfWeek.value<6}.map{d->WorkEntry("sample-$d",d.toString(),d.atTime(8,0).atZone(koreaZone).toInstant().toEpochMilli(),d.atTime(17,0).atZone(koreaZone).toInstant().toEpochMilli(),sample=true)}
   return WorkerState(work=work,risks=listOf(WorkerRisk("sample-risk-1","2층 개구부 덮개 미설치","2층 동측","작업 동선 옆 개구부에 덮개 설치가 필요합니다.","추락",time=System.currentTimeMillis()-3*3600000),WorkerRisk("sample-risk-2","통로 적치 자재 정리","1층 출입구","이동 통로 확보와 동료 재확인이 끝났습니다.","넘어짐","verified")))
  }
 }
}

fun peerVerificationStatus(attendanceId:String,uid:String,buddyId:String?,buddyAttendanceId:String?,foremanId:String,pairs:List<PeerEvidence>):String {
 if(attendanceId.isBlank()||buddyId==null)return "pending"
 val incoming=pairs.firstOrNull{it.subjectAttendanceId==attendanceId&&it.checkerId==buddyId}
 val required=if(buddyId==foremanId)listOf(incoming)else listOf(incoming,pairs.firstOrNull{it.checkerId==uid&&it.subjectAttendanceId==buddyAttendanceId})
 return when{required.any{it?.outcome=="mismatch"}->"mismatch";required.any{it==null}->"pending";required.all{it?.photoId!=null}->"photo_confirmed";else->"statement_only"}
}

internal fun pushBelongsTo(recipient: String?, site: String?, uid: String?, selectedSite: String?) =
 !recipient.isNullOrBlank() && recipient == uid && !site.isNullOrBlank() && site == selectedSite

internal fun ppeReportLabel(outcome:Any?,evidence:Any?):String = when {
 outcome=="mismatch" -> "불일치"
 evidence=="photo" -> "사진 기록"
 evidence=="statement" -> "사진 없는 진술"
 evidence=="missing_check" -> "확인 기록 없음"
 evidence=="missing_photo" -> "사진 미확인"
 else -> "증거 미확인"
}

internal fun pushDeviceId(installation: String, uid: String): String =
 java.util.UUID.nameUUIDFromBytes("$installation:$uid".toByteArray(Charsets.UTF_8)).toString()


internal fun mergeIncidentResults(active: List<WorkerCall>, opened: WorkerCall?, month: List<WorkerCall>): List<WorkerCall> =
 (active + listOfNotNull(opened) + month).distinctBy { it.id }.sortedByDescending { it.createdAt }
