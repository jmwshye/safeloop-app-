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

enum class WorkerPage(val label:String,val icon:String){TODAY("오늘","home"),SAFETY("안전","shield"),RECORDS("기록","records"),PROFILE("내 정보","person")}
data class WorkerActions(val dial:()->Unit,val camera:((String?)->Unit)->Unit,val attend:()->Unit,val monitor:(Boolean)->Unit,val export:()->Unit,val notificationSettings:()->Unit,val notifications:(()->Unit)->Unit)

@Composable fun HomePage(s:WorkerState,now:Long=System.currentTimeMillis(),onSheet:(String)->Unit={},onPage:(WorkerPage)->Unit={},onMonitoring:(Boolean)->Unit={}){
 PageHeading(dateLabel(),"${s.name} 님,\n오늘도 안전하게.","내가 일한 기록, 서로를 지키는 확인.")
 Row(Modifier.fillMaxWidth().clickable{onSheet("site")}.padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
  SLIcon("helmet",Modifier.size(18.dp),Muted);Text(s.site,Modifier.weight(1f),fontSize=12.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis);SLIcon("chevron",Modifier.size(15.dp),Muted)
 }
 SLCard {
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){SLIcon("clock",Modifier.size(18.dp));Text("오늘의 작업",fontSize=14.sp,fontWeight=FontWeight.Bold)};SLPill(if(s.currentShift!=null)"근무 중" else if(s.todayShift!=null)"퇴근 완료" else "출근 전",neutral=s.todayShift==null)}
  Text(s.zone,fontSize=21.sp,fontWeight=FontWeight.Bold,color=Ink,letterSpacing=(-.5).sp)
  Row(Modifier.fillMaxWidth().padding(vertical=7.dp)){
   Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)){Text("출근 시간",fontSize=12.sp,color=Muted);Text(clockText(s.todayShift?.start),fontSize=25.sp,fontWeight=FontWeight.SemiBold,color=Ink)}
   Box(Modifier.height(47.dp).width(1.dp).background(Line));Spacer(Modifier.width(24.dp))
   Column(Modifier.weight(1.3f),verticalArrangement=Arrangement.spacedBy(6.dp)){Text("오늘 예상 일당",fontSize=12.sp,color=Muted);Row(verticalAlignment=Alignment.Bottom){Text(moneyText(s.todayShift?.wage?:s.wage),fontSize=25.sp,fontWeight=FontWeight.Bold,color=Forest);Text(" 원",fontSize=12.sp,color=Muted,modifier=Modifier.padding(bottom=4.dp))}}
  }
  if(s.currentShift!=null)Text("근무 경과 ${((now-s.currentShift!!.start)/3600000.0).coerceAtLeast(0.0).let{"%.1f".format(it)}}시간 · 예상 금액은 실제 지급액과 달라요.",fontSize=11.sp,color=Muted)
  else Text("안전점검 완료 여부와 관계없이 근로 기록을 남겨요.",fontSize=11.sp,color=Muted)
  SLButton(if(s.currentShift!=null)"퇴근하고 오늘 기록 보기" else if(s.todayShift!=null)"오늘 근로 기록 확인" else "출근하고 오늘 시작하기",onClick={if(s.currentShift!=null)onSheet("checkout")else if(s.todayShift!=null)onPage(WorkerPage.RECORDS)else onSheet("attendance")},icon=if(s.currentShift!=null)"logout" else "arrow")
 }
 SectionHeading("오늘의 안전 준비","전체 보기"){onPage(WorkerPage.SAFETY)}
 SLCard(color=PaleGreen){
  Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){
   Box(Modifier.size(48.dp).background(Color.White,CircleShape),contentAlignment=Alignment.Center){SLIcon("shield",Modifier.size(27.dp))}
   Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)){Text(if(s.checkCount==3)"작업 전 확인을 마쳤어요" else "작업 전, 잠깐만 확인해요",fontSize=16.sp,fontWeight=FontWeight.Bold,color=Forest);Text("추락 구역 · 작업 동선 · 보호구",fontSize=11.sp,color=Forest.copy(alpha=.7f))}
   Text("${s.checkCount}/3",fontSize=22.sp,fontWeight=FontWeight.Bold,color=Forest)
  }
  Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){repeat(3){i->Box(Modifier.weight(1f).height(4.dp).background(if(s.checks.getOrNull(i)=="확인")Forest else Forest.copy(alpha=.12f),CircleShape))}}
  TextButton(onClick={onSheet("checklist")},contentPadding=PaddingValues(0.dp),modifier=Modifier.fillMaxWidth()){Text(if(s.checkCount==3)"확인 내용 보기" else "3가지 확인 시작하기",fontSize=13.sp,fontWeight=FontWeight.Bold);Spacer(Modifier.weight(1f));SLIcon("arrow",Modifier.size(17.dp))}
 }
 SectionHeading("나의 안전 동료","동료 확인"){onSheet("peer")}
 BuddyCard(s){onSheet("peer")}
 SectionHeading("오늘의 기록","전체 기록"){onPage(WorkerPage.RECORDS)}
 SLCard {if(s.events.isEmpty())EmptyState("records","오늘의 첫 기록을 기다리고 있어요","출근과 안전 확인이 여기에 차곡차곡 쌓여요.")else s.events.take(3).forEach{EventRow(it,s.preview)}}
}


@Composable fun BuddyCard(s:WorkerState,onClick:()->Unit){SLCard {
 Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){Avatar(s.buddyName);Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)){Text(s.buddyName,fontSize=17.sp,fontWeight=FontWeight.Bold,color=Ink);Text(s.buddyZone,fontSize=12.sp,color=Muted)};SLPill(if(s.peerChecked)"확인 완료" else "확인 대기",amber=!s.peerChecked)}
 Text("작업 전 서로의 보호구를 확인해 주세요. 긴급 요청이 오면 본인의 안전을 먼저 확보하세요.",fontSize=12.sp,lineHeight=19.sp,color=Muted)
 SLButton(if(s.peerChecked)"동료 확인 내용 다시 기록" else "동료 보호구 확인하기",onClick,secondary=true,icon="people")
}}

@Composable fun SafetyPage(s:WorkerState,onSheet:(String)->Unit,onMonitoring:(Boolean)->Unit){
 PageHeading("SAFETY CHECK","안전은 서로\n확인할 때 이어져요.","미확인은 사고를 의미하지 않아요. 직접 확인해 주세요.")
 SectionHeading("작업 전 확인")
 SLCard {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text("오늘의 3가지",fontSize=17.sp,fontWeight=FontWeight.Bold,color=Ink);SLPill("${s.checkCount} / 3 확인",amber=s.checkCount<3)}
  listOf("추락 위험 구역·동선","바닥·개구부·안전난간","안전모·턱끈·안전대").forEachIndexed{i,title->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(26.dp).background(if(s.checks[i]=="확인")PaleGreen else CanvasColor,CircleShape),contentAlignment=Alignment.Center){if(s.checks[i]=="확인")SLIcon("check",Modifier.size(14.dp))else Text("${i+1}",fontSize=11.sp,color=Muted)};Text(title,Modifier.weight(1f),fontSize=13.sp,color=Ink);Text(s.checks[i].ifBlank{"미확인"},fontSize=11.sp,color=if(s.checks[i]=="확인")Forest else Muted)}}
  SLButton(if(s.checkCount==3)"점검 내용 수정" else "작업 전 확인하기",{onSheet("checklist")},secondary=s.checkCount==3)
 }
 SectionHeading("나의 안전 동료");BuddyCard(s){onSheet("peer")}
}
@Composable fun EventRow(e:WorkerEvent,preview:Boolean){Row(Modifier.fillMaxWidth().padding(vertical=5.dp),verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(12.dp)){
 Box(Modifier.size(30.dp).background(if(e.estimate)PaleAmber else PaleGreen,CircleShape),contentAlignment=Alignment.Center){SLIcon(if(e.estimate)"activity" else "check",Modifier.size(15.dp),if(e.estimate)Amber else Forest)}
 Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)){Text(e.title,fontSize=13.sp,fontWeight=FontWeight.SemiBold,color=Ink);Text(e.detail,fontSize=11.sp,lineHeight=17.sp,color=Muted);Text(if(preview)"기기 내 체험 기록" else if(e.estimate)"시스템 추정" else "사용자 확인",fontSize=10.sp,color=if(e.estimate)Amber else Muted)};Text(clockText(e.time),fontSize=10.sp,color=Muted)
}}

@Composable fun ChoiceTabs(labels:List<String>,selected:Int,onSelect:(Int)->Unit){Row(Modifier.fillMaxWidth().background(Color(0xFFECEFEB),RoundedCornerShape(12.dp)).padding(4.dp),horizontalArrangement=Arrangement.spacedBy(3.dp)){labels.forEachIndexed{i,label->Box(Modifier.weight(1f).background(if(selected==i)Color.White else Color.Transparent,RoundedCornerShape(9.dp)).clickable{onSelect(i)}.padding(vertical=12.dp),contentAlignment=Alignment.Center){Text(label,fontSize=13.sp,fontWeight=if(selected==i)FontWeight.Bold else FontWeight.Medium,color=if(selected==i)Forest else Muted)}}}}

@Composable fun RecordsPage(s:WorkerState,onExport:()->Unit,onSheet:(String)->Unit){
 var tab by rememberSaveable{mutableIntStateOf(0)};var month by rememberSaveable{mutableStateOf(todayKey().take(7))};var selectedDay by rememberSaveable{mutableStateOf(todayKey())}
 val yearMonth=YearMonth.parse(month);val entries=s.work.filter{it.day.startsWith(month)}
 PageHeading("RECORDS THAT PROTECT YOU","내 편이 되는,\n오늘의 기록.","내가 일한 시간과 서로 확인한 안전을 남겨요.")
 ChoiceTabs(listOf("근로 기록","안전 타임라인"),tab){tab=it}
 if(tab==0){
  SLCard(color=PaleGreen){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("${yearMonth.monthValue}월 근로 현황",Modifier.weight(1f),fontSize=14.sp,fontWeight=FontWeight.Bold,color=Forest);SLPill(if(s.preview)"체험 데이터 포함" else "서버 기록")};Row(Modifier.fillMaxWidth()){
   Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(7.dp)){Text("근로일",fontSize=11.sp,color=Muted);Row(verticalAlignment=Alignment.Bottom){Text("${entries.size}",fontSize=30.sp,fontWeight=FontWeight.Bold,color=Forest);Text(" 일",fontSize=12.sp,color=Muted,modifier=Modifier.padding(bottom=5.dp))}}
   Column(Modifier.weight(2f),verticalArrangement=Arrangement.spacedBy(7.dp)){Text("예상 일당 합계",fontSize=11.sp,color=Muted);Row(verticalAlignment=Alignment.Bottom){Text(moneyText(entries.sumOf{it.wage}),fontSize=29.sp,fontWeight=FontWeight.Bold,color=Forest);Text(" 원",fontSize=12.sp,color=Muted,modifier=Modifier.padding(bottom=5.dp))}}
  };Text("예상 금액은 실제 지급액·법정 수당과 다를 수 있어요.",fontSize=10.sp,color=Muted)}
  SLCard {
   Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("${yearMonth.year}년 ${yearMonth.monthValue}월",Modifier.weight(1f),fontSize=17.sp,fontWeight=FontWeight.Bold,color=Ink);IconButton({month=yearMonth.minusMonths(1).toString()}){SLIcon("back",Modifier.size(17.dp),Muted,description="이전 달")};IconButton({month=yearMonth.plusMonths(1).toString()}){SLIcon("chevron",Modifier.size(17.dp),Muted,description="다음 달")}}
   Row(Modifier.fillMaxWidth()){listOf("일","월","화","수","목","금","토").forEachIndexed{i,label->Text(label,Modifier.weight(1f),fontSize=11.sp,textAlign=TextAlign.Center,color=if(i==0)Danger.copy(alpha=.6f)else Muted)}}
   val offset=yearMonth.atDay(1).dayOfWeek.value%7;val cells=((offset+yearMonth.lengthOfMonth()+6)/7)*7
   repeat(cells/7){week->Row(Modifier.fillMaxWidth()){repeat(7){dow->val day=week*7+dow-offset+1;val date=if(day in 1..yearMonth.lengthOfMonth())yearMonth.atDay(day).toString()else null;val worked=entries.any{it.day==date};Box(Modifier.weight(1f).height(42.dp),contentAlignment=Alignment.Center){if(date!=null)Box(Modifier.size(33.dp).background(if(date==selectedDay)Forest else if(worked)PaleGreen else Color.Transparent,CircleShape).clickable{selectedDay=date},contentAlignment=Alignment.Center){Text("$day",fontSize=13.sp,fontWeight=if(worked||date==selectedDay)FontWeight.Bold else FontWeight.Normal,color=if(date==selectedDay)Color.White else if(worked)Forest else if(dow==0)Danger.copy(alpha=.65f)else Ink)}}}}}
   HorizontalDivider(color=Line);Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){Box(Modifier.size(6.dp).background(Forest,CircleShape));Text("근로 기록이 있는 날",fontSize=10.sp,color=Muted)}
  }
  SectionHeading("${LocalDate.parse(selectedDay).monthValue}월 ${LocalDate.parse(selectedDay).dayOfMonth}일의 기록")
  val day=entries.firstOrNull{it.day==selectedDay}
  SLCard {if(day==null)EmptyState("calendar","이 날짜의 근로 기록이 없어요","출근과 퇴근을 기록하면 여기에서 확인할 수 있어요.")else{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(s.site,Modifier.weight(1f),fontSize=14.sp,fontWeight=FontWeight.Bold,color=Ink);SLPill(if(day.sample)"체험 자료" else if(day.end==null)"근무 중" else "기록 완료",neutral=day.sample)};DetailRow("작업 구역",s.zone);DetailRow("출근 / 퇴근","${clockText(day.start)}  /  ${clockText(day.end)}");DetailRow("예상 일당","${moneyText(day.wage)}원")}}
  SLButton("내 근로 기록 내보내기",onExport,secondary=true,icon="download")
 }else{
  SmallNote("사람의 확인과 센서의 추정을 구분해 남겨요. 연결 모드의 원본은 서버에서 관리합니다.")
  if(s.events.isEmpty())SLCard{EmptyState("records","아직 확인 기록이 없어요","출근과 점검, 동료 확인부터 시작해 보세요.")}
  else SLCard{s.events.take(60).forEach{EventRow(it,s.preview)}}
 }
}

@Composable fun ProfilePage(s:WorkerState,onSheet:(String)->Unit,onSettings:()->Unit){
 PageHeading("MY SAFELOOP","나의 안전고리","기록의 첫 번째 주인은 나입니다.")
 SLCard {Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(15.dp)){Avatar(s.name,60);Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)){Text(s.name,fontSize=23.sp,fontWeight=FontWeight.Bold,color=Ink);Text(s.job,fontSize=12.sp,color=Muted)};SLPill(if(s.preview)"체험 모드" else "현장 연결",amber=s.preview)};HorizontalDivider(color=Line);DetailRow("나의 현장",s.site);DetailRow("작업 구역",s.zone)}
 SLCard {MenuRow("person","내 작업 정보","이름 · 작업 구역 · 예상 일당"){onSheet("profile")}}
 SLCard {MenuRow("shield","기록과 개인정보","체험 기록은 이 기기에 저장됩니다"){onSheet("privacy")}}
 SLCard {MenuRow("settings","체험 기록 초기화","이 기기의 체험 기록만 초기화합니다"){onSheet("reset")}}
 Row(Modifier.fillMaxWidth().padding(top=8.dp,bottom=16.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){SLIcon("link",Modifier.size(15.dp),Muted);Spacer(Modifier.width(7.dp));Text("SAFELOOP  ·  안전은 서로 연결되어 있습니다.",fontSize=10.sp,color=Muted)}
}

@Preview(name="작업자 · 오늘",showBackground=true,widthDp=411,heightDp=1000)
@Composable fun HomeDesignPreview(){WorkerTheme{Column(Modifier.background(CanvasColor).verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){HomePage(WorkerState.preview())}}}

@Preview(name="작업자 · 근로 기록",showBackground=true,widthDp=411,heightDp=1000)
@Composable fun RecordsDesignPreview(){WorkerTheme{Column(Modifier.background(CanvasColor).verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){RecordsPage(WorkerState.preview(),{}, {})}}}
