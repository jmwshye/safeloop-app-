package com.safeloop.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

enum class WorkerPage(val label:String,val icon:String){TODAY("오늘","home"),REPORT("신고","report"),RECORDS("기록","records"),PROFILE("내 정보","person")}
data class WorkerActions(
 val dial:()->Unit,val capture:(String,String)->Unit,val attend:()->Unit,val monitor:(Boolean)->Unit,
 val export:(String,String,String,String?)->Unit,val notificationSettings:()->Unit,val notifications:(NotificationAction)->Unit,
 val shareReport:(String,String)->Unit={_,_->},val map:(Double,Double)->Unit={_,_->},val phone:(String)->Unit={},
 val dialIncident:(String)->Unit={},val checkLocation:()->Unit={},val onboardingPermissions:()->Unit={},
 val locate:((android.location.Location?)->Unit)->Unit={it(null)},val cameraResult:CapturedImage?=null,val consumeCamera:()->Unit={}
)

// 오늘 탭은 상태마다 화면이 하나다 (와이어프레임 7장). 호출 카드는 모든 상태에서 맨 위.
@Composable fun HomePage(s:WorkerState,now:Long=System.currentTimeMillis(),onSheet:(String)->Unit={},onPage:(WorkerPage)->Unit={},onMonitoring:(Boolean)->Unit={},onLocation:()->Unit={}){
 s.activeCalls.forEach{CallSummary(it,s.uid){onSheet("call:${it.id}")}}
 var skippedTasks by rememberSaveable(s.todayShift?.id){mutableStateOf(false)}
 val tasksDone=s.checkCount==3&&s.peerChecked
 when{
  s.todayShift==null->TodayBeforeWork(s,onSheet,onLocation)
  s.currentShift==null->TodayAfterWork(s,onSheet,onPage)
  !tasksDone&&!skippedTasks&&!s.monitoring->TodayTasks(s,onSheet){skippedTasks=true}
  else->TodayWorking(s,now,onSheet,onMonitoring)
 }
}

// 1-1 오늘 · 출근 전
@Composable private fun TodayBeforeWork(s:WorkerState,onSheet:(String)->Unit,onLocation:()->Unit){
 val hour=java.time.LocalTime.now(koreaZone).hour
 PageHeading(dateLabel(),"${if(hour<11)"좋은 아침이에요" else "안녕하세요"},\n${s.name} 님")
 SLCard(Modifier.clickable{onSheet("site")}){
  Text("오늘의 현장",fontSize=14.sp,fontWeight=FontWeight.Bold,color=Muted)
  Text(s.site,fontSize=21.sp,fontWeight=FontWeight.ExtraBold,color=Ink)
  Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){when(s.beforeLocation){"inside"->SLPill("현장 안에 있어요");"outside"->SLPill("현장 밖이에요",amber=true);else->SLPill("위치 미확인",neutral=true)};if(s.code.isNotBlank())SLPill("현장 코드 ${s.code}",neutral=true)}
 }
 FlatCard(listOf("오늘 할 일" to s.workOccupation,"작업 위치" to s.workZone,"예상 일당" to if(s.wageKnown)"${moneyText(s.wage)}원" else "미입력"))
 Text("예상 일당은 내 기록용이에요. 실제 지급액과 다를 수 있어요. 작업 전 확인은 출근한 뒤에 해도 돼요.",fontSize=14.sp,lineHeight=22.sp,color=Muted)
 if(!s.preview)SLButton("현장 안인지 확인",onLocation,secondary=true,icon="pin")
 SLButton("출근하기",{onSheet("attendance")})
}

// 1-2 오늘 · 출근 완료
@Composable private fun TodayTasks(s:WorkerState,onSheet:(String)->Unit,onSkip:()->Unit){
 val checksDone=s.checkCount==3;val left=(if(checksDone)0 else 1)+(if(s.peerChecked)0 else 1)
 PageHeading("${clockText(s.todayShift?.start)} 출근했어요","오늘 할 일이\n${left}가지 있어요")
 SLCard(color=BrandSoft){
  TaskRow("1. 작업 전 3가지 확인","${s.checkCount} / 3 확인",checksDone){onSheet("checklist")}
  HorizontalDivider(color=Color(0xFFBCD3F7))
  TaskRow("2. 동료와 서로 찍기","${s.buddyName} 님과 · 1분이면 끝나요",s.peerChecked){onSheet("peer")}
 }
 if(s.checks.any{it=="개선 필요"||it=="모름"})SmallNote("반장님 확인이 필요한 항목이 있어요.",amber=true)
 BuddySummary(s)
 Text("건너뛰어도 근무 기록은 그대로 남아요.",fontSize=14.sp,lineHeight=22.sp,color=Muted)
 if(checksDone)SLButton("동료 찍기",{onSheet("peer")}) else SLButton("3가지 확인하기",{onSheet("checklist")})
 GhostButton("건너뛰고 작업 시작",onSkip)
}
@Composable private fun TaskRow(title:String,detail:String,done:Boolean,onClick:()->Unit){Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable(onClick=onClick),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
 Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)){Text(title,fontSize=18.sp,fontWeight=FontWeight.ExtraBold,color=Ink);Text(detail,fontSize=14.sp,color=Muted)}
 if(done)SLPill("완료",fact=true) else SLIcon("chevron",Modifier.size(20.dp),Muted)
}}
@Composable private fun BuddySummary(s:WorkerState){SLCard{
 Text("오늘의 안전 동료",fontSize=14.sp,fontWeight=FontWeight.Bold,color=Muted)
 Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){Avatar(s.buddyName,46);Column(Modifier.weight(1f)){Text(s.buddyName,fontSize=18.sp,fontWeight=FontWeight.ExtraBold,color=Ink);Text(s.buddyZone,fontSize=14.sp,color=Muted)};SLPill(peerLabel(s.peerStatus),amber=!s.peerChecked)}
}}
internal fun peerLabel(status:String)=when(status){"photo_confirmed"->"사진 교차 확인";"statement_only"->"사진 없는 확인";"mismatch"->"불일치";else->"촬영 전"}
internal fun durationText(start:Long,end:Long):String{val minutes=((end-start)/60000).coerceAtLeast(0);return "${minutes/60}시간 ${minutes%60}분"}

// 2-1 오늘 · 작업 중. 고위험·미휴대 상태는 맨 위 카드가 바뀐다.
@Composable private fun TodayWorking(s:WorkerState,now:Long,onSheet:(String)->Unit,onMonitoring:(Boolean)->Unit){
 s.prompts.filter{it.state=="pending"}.forEach{prompt->SLCard(color=PaleAmber){Text("안전 상태를 확인해 주세요",fontSize=18.sp,fontWeight=FontWeight.ExtraBold,color=Amber);DetailRow("요청 시각",clockText(prompt.dueAt));SLButton("현재 상태 응답",{onSheet("prompt:${prompt.id}")})}}
 if(s.prompts.any{it.state=="unverified"})SmallNote("응답하지 않은 확인 요청 · 안전 미확인",amber=true)
 when{
  !s.carried->SLCard(color=PaleAmber){Text("기기 미휴대",fontSize=26.sp,fontWeight=FontWeight.ExtraBold,color=Ink);Text("지금은 움직임을 감지할 수 없어요.",fontSize=16.sp,color=Ink);SLButton("휴대 중이에요",{onSheet("carried")})}
  s.monitoring&&s.highRiskTask!=null->SLCard(color=OkSoft){SLPill("감시 강화",fact=true);Text("${highRiskLabel(s.highRiskTask)}\n진행 중이에요",fontSize=26.sp,lineHeight=34.sp,fontWeight=FontWeight.ExtraBold,color=Ink);DetailRow("시작 시각",clockText(s.highRiskStartedAt));SLButton("고위험 작업 끝내기",{onSheet("high-risk")})}
  s.monitoring->SLCard(color=OkSoft){SLPill("움직임 감시 켜짐",fact=true);Text("폰은 주머니에\n넣어 두세요",fontSize=28.sp,lineHeight=38.sp,fontWeight=FontWeight.ExtraBold,color=Ink);Text("화면이 꺼져 있어도 움직임을 지켜봐요.",fontSize=16.sp,color=Ink)}
  else->SLCard(color=BrandSoft){Text("움직임 감시가 꺼져 있어요",fontSize=21.sp,fontWeight=FontWeight.ExtraBold,color=Ink);Text("켜면 강한 충격 뒤 움직임이 줄었을 때 본인 확인을 요청해요. 사고로 단정하지 않아요.",fontSize=15.sp,lineHeight=23.sp,color=Ink);SLButton("감시 켜기",{onMonitoring(true)},icon="activity")}
 }
 val next=s.prompts.firstOrNull{it.state=="scheduled"}
 FlatCard(listOfNotNull("근무 시간" to durationText(s.currentShift!!.start,now),"안전 동료" to "${s.buddyName} · ${if(s.peerChecked)peerLabel(s.peerStatus) else s.buddyState}",next?.let{"다음 확인" to clockText(it.dueAt)}))
 if(s.checkCount<3||!s.peerChecked)SLCard(Modifier.clickable{onSheet(if(s.checkCount<3)"checklist" else "peer")}){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)){Text("남은 작업 전 확인",fontSize=17.sp,fontWeight=FontWeight.ExtraBold,color=Ink);Text("3가지 확인 ${s.checkCount}/3 · 동료 ${peerLabel(s.peerStatus)}",fontSize=14.sp,color=Muted)};SLIcon("chevron",Modifier.size(20.dp),Muted)}}
 if(s.highRiskTask==null)SLCard{Text("비계·사다리·개구부 작업이에요?",fontSize=17.sp,fontWeight=FontWeight.ExtraBold,color=Ink);Text("고위험 작업으로 켜면 감시가 더 민감해져요.",fontSize=14.sp,lineHeight=22.sp,color=Muted);SLButton("고위험 작업 시작",{onSheet("high-risk")},secondary=true)}
 else if(!s.monitoring)SLCard(color=PaleAmber){Text("고위험 작업 중이지만 움직임 감시가 꺼져 있어요.",fontSize=15.sp,color=Amber);SLButton("고위험 작업 끝내기",{onSheet("high-risk")},secondary=true)}
 SLCard{Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("움직임 감시",Modifier.weight(1f),fontSize=17.sp,fontWeight=FontWeight.Bold,color=Ink);Switch(s.monitoring,onMonitoring,colors=SwitchDefaults.colors(checkedTrackColor=Brand))};HorizontalDivider(color=Line);Row(Modifier.fillMaxWidth().clickable{onSheet("carried")}.heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically){Text("기기 휴대 상태",Modifier.weight(1f),fontSize=17.sp,fontWeight=FontWeight.Bold,color=Ink);SLPill(when(s.deviceState){"carried"->"휴대 중";"not_carried"->"미휴대";else->"확인 안 됨"},amber=!s.carried)}}
 SLButton("퇴근하기",{onSheet("checkout")},secondary=true,icon="logout")
}

// 퇴근 후
@Composable private fun TodayAfterWork(s:WorkerState,onSheet:(String)->Unit,onPage:(WorkerPage)->Unit){
 val shift=s.todayShift!!
 PageHeading(dateLabel(),"오늘도\n수고했어요")
 SLCard(color=BrandSoft){
  Text("${clockText(shift.start)} ~ ${clockText(shift.end)}",fontSize=14.sp,fontWeight=FontWeight.Bold,color=Muted)
  Text(durationText(shift.start,shift.end?:shift.start),fontSize=30.sp,fontWeight=FontWeight.ExtraBold,color=Ink)
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text("예상 일당",fontSize=14.sp,color=Muted);Text(if(shift.wageKnown)"${moneyText(shift.wage)}원" else "미입력",fontSize=22.sp,fontWeight=FontWeight.ExtraBold,color=Ink)}
  Text("실제 지급액과 다를 수 있어요.",fontSize=14.sp,color=Muted)
 }
 s.calls.filter{it.status=="closed"}.take(3).forEach{CallSummary(it,s.uid){onSheet("call:${it.id}")}}
 SLCard{if(s.events.isEmpty())EmptyState("records","오늘 남은 기록이 없어요","출근과 안전 확인이 여기에 쌓여요.")else s.events.take(4).forEach{EventRow(it,s.preview)}}
 SLButton("오늘의 기록 보기",{onPage(WorkerPage.RECORDS)})
}

fun callStatus(c:WorkerCall,uid:String)=if(c.serverStatus=="unanswered")"수락자 없음" else if(c.status=="closed")"안전 확인 완료" else if(c.status=="accepted")"현장 확인 진행 중" else if(c.stage=="self")"본인 응답 대기" else if(c.isSubject(uid))"동료 응답 대기" else "동료 확인 요청"
@Composable fun CallSummary(c:WorkerCall,uid:String,onClick:()->Unit){Surface(Modifier.fillMaxWidth().clickable(onClick=onClick),color=if(c.status=="closed")OkSoft else PaleAmber,shape=RoundedCornerShape(16.dp)){
 Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
  SLIcon(if(c.status=="closed")"shield" else "bell",tint=if(c.status=="closed")Ok else Amber)
  Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)){Text("${c.subjectName} 님 · ${callStatus(c,uid)}",fontSize=15.sp,fontWeight=FontWeight.Bold,color=if(c.status=="closed")Ok else Amber);Text(c.zone,fontSize=13.sp,color=Muted)};SLIcon("chevron",Modifier.size(17.dp),Muted)
 }
}}

@Composable fun EventRow(e:WorkerEvent,preview:Boolean){Row(Modifier.fillMaxWidth().padding(vertical=5.dp),verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(12.dp)){
 Box(Modifier.size(30.dp).background(if(e.estimate)PaleAmber else OkSoft,CircleShape),contentAlignment=Alignment.Center){SLIcon(if(e.estimate)"activity" else "check",Modifier.size(15.dp),if(e.estimate)Amber else Ok)}
 Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)){Text(e.title,fontSize=16.sp,fontWeight=FontWeight.Bold,color=Ink);Text(e.detail,fontSize=14.sp,lineHeight=21.sp,color=Muted);SLPill(if(e.estimate)"시스템이 추정" else "사람이 확인",amber=e.estimate,fact=!e.estimate,estimate=e.estimate);if(preview)Text("기기 내 체험 기록",fontSize=13.sp,color=Muted)};Text(clockText(e.time),fontSize=14.sp,fontWeight=FontWeight.Bold,color=Muted)
}}

@Composable fun ReportPage(s:WorkerState,busy:Boolean,actions:WorkerActions,onSubmit:(String,String,String,String,Boolean,String?,()->Unit)->Unit,onSheet:(String)->Unit,onMore:()->Unit={}){
 var tab by rememberSaveable{mutableIntStateOf(1)};var title by rememberSaveable{mutableStateOf("")};var zone by rememberSaveable{mutableStateOf(s.zone)};var category by rememberSaveable{mutableStateOf("추락")};var note by rememberSaveable{mutableStateOf("")};var anonymous by rememberSaveable{mutableStateOf(true)};var photo by rememberSaveable{mutableStateOf<String?>(null)}
 OnCameraResult(actions,"risk_before","new"){photo=it.path}
 if(tab==0)ScreenHead("발견한 위험을\n알려 주세요") else ScreenTitle("현장 위험 현황")
 if(tab==0){
  Text("종류",fontSize=14.sp,fontWeight=FontWeight.Bold,color=Muted)
  val kinds=listOf("추락","넘어짐","충돌","감전","기타");PickRow(kinds,kinds.indexOf(category)){category=kinds[it]}
  PhotoCapture(photo,"위험 요소를 찍어요","사람이 아니라 위험한 곳만 찍어요"){actions.capture("risk_before","new")}
  SLField("위치",zone,{zone=it},"예: 2층 동측 개구부")
  SLCard{Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("이름 숨기기",fontSize=16.sp,fontWeight=FontWeight.ExtraBold,color=Ink);Text("사업주에게 신고자가 보이지 않아요",fontSize=14.sp,color=Muted)};Switch(anonymous,{anonymous=it},colors=SwitchDefaults.colors(checkedTrackColor=Brand))}}
  SLButton(if(busy)"신고를 저장하고 있어요…" else "신고 보내기",{onSubmit(title,zone,category,note,anonymous,photo){title="";note="";photo=null;tab=1}},enabled=!busy&&zone.isNotBlank(),icon="arrow")
  GhostButton("현장 위험 현황으로 돌아가기",{tab=1})
 }else{
  Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){val count={status:String->s.riskCounts[status]?:s.risks.count{it.status==status}};SLPill("미조치 ${count("open")}",amber=true);SLPill("재확인 대기 ${count("resolved")}",brand=true);SLPill("완료 ${count("verified")}")}
  if(s.risks.isEmpty())SLCard{EmptyState("shield","아직 등록된 위험이 없어요","발견한 위험을 사진으로 알려 주세요.")}
  s.risks.forEach{risk->SLCard(modifier=Modifier.clickable{onSheet("risk:${risk.id}")}){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){SLPill(risk.category,amber=risk.status=="open",brand=risk.status=="resolved");SLPill(riskStatusLabel(risk),amber=risk.status=="open",brand=risk.status=="resolved")};Text(risk.title,fontSize=18.sp,fontWeight=FontWeight.ExtraBold,color=Ink);Text("${risk.zone} · ${clockText(risk.time)} 신고",fontSize=14.sp,color=Muted)}}
  if(s.nextRisks!=null)SLButton("신고 더 보기",onMore,enabled=!busy,secondary=true)
  Text("신고한 사람 이름은 보이지 않아요.",fontSize=14.sp,color=Muted)
  SLButton("새 신고",{tab=0},icon="plus")
 }
}

private fun riskStatusLabel(risk:WorkerRisk)=when(risk.status){"verified"->"재확인 완료";"resolved"->"재확인 대기";else->{val minutes=((System.currentTimeMillis()-risk.time)/60000).coerceAtLeast(0);"미조치 · ${if(minutes<60)"${minutes}분" else if(minutes<1440)"${minutes/60}시간" else "${minutes/1440}일"}째"}}

@Composable fun ChoiceTabs(labels:List<String>,selected:Int,onSelect:(Int)->Unit){Row(Modifier.fillMaxWidth().background(Color(0xFFEEF2F7),RoundedCornerShape(12.dp)).padding(4.dp),horizontalArrangement=Arrangement.spacedBy(3.dp)){labels.forEachIndexed{i,label->Box(Modifier.weight(1f).background(if(selected==i)Color.White else Color.Transparent,RoundedCornerShape(9.dp)).clickable{onSelect(i)}.padding(vertical=12.dp),contentAlignment=Alignment.Center){Text(label,fontSize=15.sp,fontWeight=if(selected==i)FontWeight.ExtraBold else FontWeight.Bold,color=if(selected==i)Brand else Muted)}}}}

@Composable fun RecordsPage(s:WorkerState,onExport:()->Unit,onSheet:(String)->Unit,onMonth:(String)->Unit={},onDay:(String)->Unit={},onMore:()->Unit={},openTimeline:Boolean=false){
 var tab by rememberSaveable(openTimeline){mutableIntStateOf(if(openTimeline)1 else 0)};var month by rememberSaveable{mutableStateOf(todayKey().take(7))};var selectedDay by rememberSaveable{mutableStateOf(todayKey())}
 LaunchedEffect(month){onMonth(month)}
 LaunchedEffect(selectedDay){onDay(selectedDay)}
 val yearMonth=YearMonth.parse(month);val entries=s.work.filter{it.day.startsWith(month)}
 ScreenTitle(if(tab==0)"내 근로 기록" else "오늘의 기록")
 ChoiceTabs(listOf("근로 기록","안전 타임라인"),tab){tab=it}
 if(tab==0){
  SLCard(color=BrandSoft){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("${yearMonth.monthValue}월 근로 현황",Modifier.weight(1f),fontSize=15.sp,fontWeight=FontWeight.Bold,color=Brand);SLPill(if(s.preview)"체험 데이터 포함" else "서버 기록",amber=s.preview)};Row(Modifier.fillMaxWidth()){
   Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(7.dp)){Text("근로일",fontSize=13.sp,color=Muted);Row(verticalAlignment=Alignment.Bottom){Text("${entries.distinctBy{it.day}.size}",fontSize=30.sp,fontWeight=FontWeight.Bold,color=Brand);Text(" 일",fontSize=14.sp,color=Muted,modifier=Modifier.padding(bottom=5.dp))}}
   Column(Modifier.weight(2f),verticalArrangement=Arrangement.spacedBy(7.dp)){Text("예상 일당 합계",fontSize=13.sp,color=Muted);Row(verticalAlignment=Alignment.Bottom){Text(moneyText(entries.sumOf{it.wage}),fontSize=29.sp,fontWeight=FontWeight.Bold,color=Brand);Text(" 원",fontSize=14.sp,color=Muted,modifier=Modifier.padding(bottom=5.dp))}}
  };if(entries.any{!it.wageKnown})Text("일당 미입력 ${entries.count{!it.wageKnown}}건은 합계에서 제외",fontSize=13.sp,color=Amber);Text("예상 금액은 실제 지급액·법정 수당과 다를 수 있어요.",fontSize=13.sp,color=Muted)}
  SLCard {
   Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("${yearMonth.year}년 ${yearMonth.monthValue}월",Modifier.weight(1f),fontSize=17.sp,fontWeight=FontWeight.Bold,color=Ink);IconButton({month=yearMonth.minusMonths(1).toString();selectedDay=YearMonth.parse(month).atDay(1).toString()}){SLIcon("back",Modifier.size(17.dp),Muted,description="이전 달")};IconButton({month=yearMonth.plusMonths(1).toString();selectedDay=YearMonth.parse(month).atDay(1).toString()}){SLIcon("chevron",Modifier.size(17.dp),Muted,description="다음 달")}}
   Row(Modifier.fillMaxWidth()){listOf("일","월","화","수","목","금","토").forEachIndexed{i,label->Text(label,Modifier.weight(1f),fontSize=13.sp,textAlign=TextAlign.Center,color=if(i==0)Danger.copy(alpha=.6f)else Muted)}}
   val offset=yearMonth.atDay(1).dayOfWeek.value%7;val cells=((offset+yearMonth.lengthOfMonth()+6)/7)*7
   repeat(cells/7){week->Row(Modifier.fillMaxWidth()){repeat(7){dow->val day=week*7+dow-offset+1;val date=if(day in 1..yearMonth.lengthOfMonth())yearMonth.atDay(day).toString()else null;val worked=entries.any{it.day==date};Box(Modifier.weight(1f).height(42.dp),contentAlignment=Alignment.Center){if(date!=null)Box(Modifier.size(33.dp).background(if(date==selectedDay)Brand else if(worked)OkSoft else Color.Transparent,CircleShape).clickable{selectedDay=date},contentAlignment=Alignment.Center){Text("$day",fontSize=14.sp,fontWeight=if(worked||date==selectedDay)FontWeight.Bold else FontWeight.Normal,color=if(date==selectedDay)Color.White else if(worked)Ok else if(dow==0)Danger.copy(alpha=.65f)else Ink)}}}}}
   HorizontalDivider(color=Line);Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){Box(Modifier.size(10.dp).background(OkSoft,CircleShape).border(1.5.dp,Ok,CircleShape));Text("근로 기록이 있는 날",fontSize=13.sp,color=Muted)}
  }
  SectionHeading("${LocalDate.parse(selectedDay).monthValue}월 ${LocalDate.parse(selectedDay).dayOfMonth}일의 기록")
  val day=entries.firstOrNull{it.day==selectedDay}
  SLCard {if(day==null)EmptyState("calendar","이 날짜의 근로 기록이 없어요","출근과 퇴근을 기록하면 여기에서 확인할 수 있어요.")else{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(day.site.ifBlank{s.site},Modifier.weight(1f),fontSize=15.sp,fontWeight=FontWeight.Bold,color=Ink);SLPill(if(day.sample)"체험 자료" else if(day.end==null)"근무 중" else "기록 완료",neutral=day.sample)};DetailRow("작업 구역",day.zone.ifBlank{"미입력"});DetailRow("출근 / 퇴근","${clockText(day.start)}  /  ${clockText(day.end)}");DetailRow("예상 일당",if(day.wageKnown)"${moneyText(day.wage)}원" else "미입력")}}
  if(day!=null)SLButton("선택한 날의 안전 기록",{tab=1},secondary=true)
  SLButton("기록 내보내기",onExport,icon="download")
 }else{
  TimelineLegend()
  val rows=s.events.filter{!s.preview||java.time.Instant.ofEpochMilli(it.time).atZone(koreaZone).toLocalDate().toString()==selectedDay}.sortedBy{it.time}
  if(rows.isEmpty())SLCard{EmptyState("records","아직 확인 기록이 없어요","출근과 점검, 동료 확인부터 시작해 보세요.")}
  else FlatBox{rows.forEach{entry->TimelineRow(clockText(entry.time),entry.title,entry.detail.ifBlank{null},estimate=entry.estimate,onClick={onSheet("event:${entry.id}")})}}
  Text("기록은 사업주가 지울 수 없어요. 잘못된 내용은 정정 기록으로만 고칠 수 있어요.",fontSize=14.sp,lineHeight=22.sp,color=Muted)
  if(s.nextTimeline!=null)SLButton("기록 더 보기",onMore,secondary=true)
  SLButton("내 근로 기록 보기",{tab=0})
  GhostButton("정정 요청 확인",{onSheet("corrections")})
 }
}

@Composable fun ProfilePage(s:WorkerState,onSheet:(String)->Unit,onSettings:()->Unit){
 ScreenTitle("내 정보")
 SLCard {Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){Avatar(s.name,56);Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)){Text(s.name,fontSize=21.sp,fontWeight=FontWeight.ExtraBold,color=Ink);Text(listOf(s.job,s.zone).filter{it.isNotBlank()}.joinToString(" · "),fontSize=14.sp,color=Muted)};SLPill(if(s.preview)"체험 모드" else "현장 연결",amber=s.preview)}}
 SLCard {MenuRow("person","내 작업 정보","직종 · 작업 구역 · 예상 일당"){onSheet("profile")};HorizontalDivider(color=Line);MenuRow("link",if(s.preview)"실제 현장 연결하기" else "나의 현장",if(s.preview)"관리자의 참여 코드로 시작하세요" else "${s.site} · ${s.code}"){onSheet(if(s.preview)"connect" else "site")};HorizontalDivider(color=Line);MenuRow("bell","알림 설정","긴급 호출을 놓치지 않도록"){onSettings()};HorizontalDivider(color=Line);MenuRow("shield","기록과 개인정보","수집하는 정보와 내 열람 권한"){onSheet("privacy")};HorizontalDivider(color=Line);MenuRow("info","안전 감지 이용 안내","휴대 위치 · 감지의 한계"){onSheet("guide")}}
 if(s.preview){SectionHeading("앱 체험 도구");SLCard {Text("실제 센서 감시와 앱의 확인 흐름을 시험할 수 있어요. 아래 시험 신호는 다른 사람에게 전송되지 않습니다.",fontSize=14.sp,lineHeight=22.sp,color=Muted);MenuRow("activity","본인 확인 알림 체험","15초 응답 대기와 무응답 흐름"){onSheet("simulate-self")};HorizontalDivider(color=Line);MenuRow("people","동료 현장 확인 체험","수락 → 도착 → 상태 확인 → 마무리"){onSheet("simulate-peer")};HorizontalDivider(color=Line);MenuRow("clock","작업 중 안전 확인 체험","휴식 복귀 때 한 번 묻는 화면"){onSheet("simulate-checkin")};TextButton({onSheet("reset")}){Text("체험 기록 초기화",fontSize=14.sp,color=Muted)}}}
 else SLCard {MenuRow("logout","연결을 종료하고 체험 모드로","서버 기록은 그대로 보존됩니다"){onSheet("disconnect")}}
 Text("기록의 첫 번째 주인은 나예요.",Modifier.fillMaxWidth(),fontSize=14.sp,color=Muted,textAlign=TextAlign.Center)
}

@Preview(name="작업자 · 오늘",showBackground=true,widthDp=411,heightDp=1000)
@Composable fun HomeDesignPreview(){WorkerTheme{Column(Modifier.background(CanvasColor).verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){HomePage(WorkerState.preview())}}}

@Preview(name="작업자 · 근로 기록",showBackground=true,widthDp=411,heightDp=1000)
@Composable fun RecordsDesignPreview(){WorkerTheme{Column(Modifier.background(CanvasColor).verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){RecordsPage(WorkerState.preview(),{}, {})}}}
