package com.safeloop.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val koreaZone: ZoneId = ZoneId.of("Asia/Seoul")
fun todayKey(): String = LocalDate.now(koreaZone).toString()
fun moneyText(value: Int): String = String.format(Locale.KOREA,"%,d",value)
fun clockText(value: Long?): String = value?.let { DateTimeFormatter.ofPattern("HH:mm").withZone(koreaZone).format(Instant.ofEpochMilli(it)) } ?: "—"
fun dateLabel(): String = LocalDate.now(koreaZone).format(DateTimeFormatter.ofPattern("M월 d일 EEEE",Locale.KOREA))
fun parseTime(value: Any?): Long? = runCatching { Instant.parse(value?.toString()).toEpochMilli() }.getOrNull()

data class WorkEntry(val id:String,val day:String,val start:Long,val end:Long?=null,val wage:Int=180000,val sample:Boolean=false)
data class WorkerEvent(val id:String,val title:String,val detail:String,val time:Long=System.currentTimeMillis(),val estimate:Boolean=false)
data class WorkerRisk(val id:String,val title:String,val zone:String,val note:String,val category:String="기타",val status:String="open",val photo:String?=null,val time:Long=System.currentTimeMillis(),val anonymous:Boolean=true)
data class WorkerCall(
 val id:String,val subject:String="preview-self",val subjectName:String="김민수",val zone:String="2층 · 설비 작업",
 val stage:String="self",val status:String="pending",val deadline:Long=System.currentTimeMillis()+15000,
 val acceptedBy:String?=null,val arrivedAt:Long?=null,val photo:String?=null,val resolution:String?=null,val overdue:Boolean=false
) {
 fun isSubject(uid:String)=subject==uid
 fun remainingSeconds(now:Long)=((deadline-now+999)/1000).coerceAtLeast(0)
 fun accept(uid:String):WorkerCall {require(!isSubject(uid)&&stage=="peer"&&status=="pending"){"이미 수락했거나 종료된 요청입니다."};return copy(status="accepted",acceptedBy=uid)}
 fun arrive(uid:String):WorkerCall {require(status=="accepted"&&acceptedBy==uid){"수락한 동료만 현장 도착을 기록할 수 있습니다."};return copy(arrivedAt=System.currentTimeMillis())}
 fun verify(uid:String,localPhoto:String?):WorkerCall {require(status=="accepted"&&acceptedBy==uid&&arrivedAt!=null){"현장 도착을 먼저 기록해 주세요."};require(!localPhoto.isNullOrBlank()){ "현장 주변 사진이 필요합니다." };return copy(status="closed",photo=localPhoto,resolution="peer_photo")}
}
data class WorkerState(
 val preview:Boolean=true,val uid:String="preview-self",val name:String="김민수",val job:String="설비 작업자",
 val site:String="성수동 근린생활시설 신축",val code:String="SL2026",val zone:String="2층 · 설비 작업",val wage:Int=180000,
 val buddyName:String="박철수",val buddyZone:String="2층 · 설비 작업",val buddyState:String="확인 대기",
 val carried:Boolean=true,val monitoring:Boolean=false,val checks:List<String> = listOf("","",""),val peerChecked:Boolean=false,
 val work:List<WorkEntry> = emptyList(),val risks:List<WorkerRisk> = emptyList(),val events:List<WorkerEvent> = emptyList(),val calls:List<WorkerCall> = emptyList()
) {
 val currentShift get()=work.firstOrNull{it.day==todayKey()&&it.end==null&&!it.sample}
 val todayShift get()=work.firstOrNull{it.day==todayKey()&&!it.sample}
 val checkCount get()=checks.count{it=="확인"}
 val activeCalls get()=calls.filter{it.status!="closed"}
 val selfCall get()=activeCalls.firstOrNull{it.isSubject(uid)&&it.stage=="self"}
 val monthWork get()=work.filter{it.day.startsWith(todayKey().take(7))}
 val monthWage get()=monthWork.sumOf{it.wage}
 fun attend(now:Long=System.currentTimeMillis()):WorkerState {require(todayShift==null){"오늘의 출근 기록이 이미 있습니다."};return copy(work=listOf(WorkEntry("work-$now",todayKey(),now,wage=wage))+work)}
 fun checkout(now:Long=System.currentTimeMillis()):WorkerState {val shift=currentShift?:error("출근 기록이 없습니다.");return copy(work=work.map{if(it.id==shift.id)it.copy(end=now)else it},monitoring=false)}
 companion object {
  fun preview():WorkerState {
   val today=LocalDate.now(koreaZone)
   val work=(1L..7L).map{today.minusDays(it)}.filter{it.dayOfWeek.value<6}.map{d->WorkEntry("sample-$d",d.toString(),d.atTime(8,0).atZone(koreaZone).toInstant().toEpochMilli(),d.atTime(17,0).atZone(koreaZone).toInstant().toEpochMilli(),sample=true)}
   return WorkerState(work=work,risks=listOf(WorkerRisk("sample-risk-1","2층 개구부 덮개 미설치","2층 동측","작업 동선 옆 개구부에 덮개 설치가 필요합니다.","추락"),WorkerRisk("sample-risk-2","통로 적치 자재 정리","1층 출입구","이동 통로 확보와 동료 재확인이 끝났습니다.","넘어짐","verified")))
  }
 }
}
