package com.safeloop.app

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun WorkerApp(controller:WorkerController,actions:WorkerActions){
 val state=controller.state
 var pageName by rememberSaveable{mutableStateOf(WorkerPage.TODAY.name)}
 val page=WorkerPage.valueOf(pageName)
 var recordTimeline by rememberSaveable{mutableStateOf(false)}
 var sheet by rememberSaveable{mutableStateOf<String?>(null)}
 val snackbar=remember{SnackbarHostState()}
 fun navigate(destination:WorkerPage){if(destination==WorkerPage.RECORDS)recordTimeline=false;if(destination==WorkerPage.TODAY)controller.loadDay(todayKey());pageName=destination.name}
 fun open(route:String){when(route){
  "connect"->{sheet=null;controller.beginOnboarding(if(state.preview)"login" else "code")}
  "simulate-self"->actions.notifications{controller.simulateSelf();navigate(WorkerPage.TODAY)}
  "simulate-peer"->actions.notifications{controller.simulateIncoming();navigate(WorkerPage.TODAY)}
  else->{if(route.startsWith("call:"))controller.openCall(route.removePrefix("call:"));if(route.startsWith("risk:"))controller.loadRiskPhotos(route.removePrefix("risk:"));if(route=="peer")controller.loadPeerPhotos();sheet=route}
 }}
 LaunchedEffect(controller.requestedRoute){controller.requestedRoute?.let{open(it);controller.requestedRoute=null}}
 LaunchedEffect(controller.feedback){controller.feedback?.let{snackbar.showSnackbar(it);controller.feedback=null}}
 LaunchedEffect(controller.openCallId,state.calls){val id=controller.openCallId;val call=state.calls.firstOrNull{it.id==id};if(call!=null){navigate(WorkerPage.TODAY);if(call.stage!="self"||!call.isSubject(state.uid))sheet="call:$id";controller.openCallId=null}}
 val onboarding=controller.onboarding
 if(onboarding!=null){WorkerOnboarding(onboarding,controller,actions);return}
 BackHandler(enabled=page!=WorkerPage.TODAY&&sheet==null){navigate(WorkerPage.TODAY)}
 Surface(Modifier.fillMaxSize(),color=CanvasColor){Box(Modifier.fillMaxSize().safeDrawingPadding()){
  Scaffold(containerColor=CanvasColor,contentWindowInsets=WindowInsets(0,0,0,0),snackbarHost={SnackbarHost(snackbar)},topBar={
   Row(Modifier.fillMaxWidth().height(70.dp).padding(horizontal=22.dp),verticalAlignment=Alignment.CenterVertically){
    Box(Modifier.size(36.dp).background(BrandSoft,RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center){SLIcon("link",Modifier.size(22.dp))};Spacer(Modifier.width(9.dp))
    Column(Modifier.weight(1f)){Text("안전고리",fontSize=20.sp,fontWeight=FontWeight.ExtraBold,color=Ink,letterSpacing=(-.7).sp)}
    Box(Modifier.clickable{navigate(WorkerPage.PROFILE)}){SLPill(if(state.preview)"체험 모드" else if(state.connectionProblem!=null)"연결 미확인" else "현장 연결",amber=state.preview)}
    IconButton({open("requests")}){Box{SLIcon("bell",tint=Ink,description="안전 확인 알림");if(state.activeCalls.isNotEmpty())Box(Modifier.align(Alignment.TopEnd).size(6.dp).background(Amber,CircleShape))}}
   }
  },bottomBar={Column {
   EmergencyStrip(actions.dial)
   Row(Modifier.fillMaxWidth().background(Color.White).border(BorderStroke(.5.dp,Line)),horizontalArrangement=Arrangement.SpaceEvenly){WorkerPage.entries.forEach{destination->Column(Modifier.weight(1f).height(66.dp).clickable{navigate(destination)},horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
    Box(Modifier.size(width=39.dp,height=29.dp).background(if(page==destination)BrandSoft else Color.Transparent,RoundedCornerShape(11.dp)),contentAlignment=Alignment.Center){SLIcon(destination.icon,Modifier.size(21.dp),if(page==destination)Brand else Muted)}
    Spacer(Modifier.height(4.dp));Text(destination.label,fontSize=13.sp,fontWeight=if(page==destination)FontWeight.Bold else FontWeight.Medium,color=if(page==destination)Brand else Muted)
   }}}
  }}){padding->Column(Modifier.padding(padding)){
   if(state.connectionProblem!=null){SmallNote("서버 연결 미확인 · ${state.connectionProblem}",amber=true);TextButton(controller::refreshNow){Text("다시 시도")}}
   if(!state.preview&&controller.pendingWrites>0)SmallNote("서버 저장 대기 · ${controller.pendingWrites}건",amber=true)
   if(controller.busy)LinearProgressIndicator(Modifier.fillMaxWidth(),color=Brand,trackColor=BrandSoft)
   key(page){Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start=22.dp,end=22.dp,top=12.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
    when(page){
     WorkerPage.TODAY->HomePage(state,controller.now,::open,::navigate,actions.monitor,actions.checkLocation)
     WorkerPage.REPORT->ReportPage(state,controller.busy,actions.camera,controller::submitRisk,::open,controller::loadMoreRisks)
     WorkerPage.RECORDS->RecordsPage(state,{sheet="export"},::open,controller::loadMonth,controller::loadDay,controller::loadMoreTimeline,recordTimeline)
     WorkerPage.PROFILE->ProfilePage(state,::open,{sheet="notifications"})
    }
   }}
  }}
 }}
 val activeSheet=sheet
 LaunchedEffect(activeSheet,state.calls.firstOrNull{activeSheet=="call:${it.id}"}?.status){if(activeSheet?.startsWith("call:")==true)controller.loadVerificationPhoto(activeSheet.removePrefix("call:"))}
 if(activeSheet!=null){AppSheet((if(state.preview)"체험 모드 · " else "")+sheetTitle(activeSheet),{sheet=null},{if(activeSheet.startsWith("call:"))actions.dialIncident(activeSheet.removePrefix("call:"))else actions.dial()}){
  controller.feedback?.let{SmallNote(it,amber=true)}
  when {
   activeSheet=="attendance"->{SmallNote(if(state.preview)"체험 모드의 출근 기록은 이 기기에 저장됩니다." else "위치를 허용하지 않아도 출근은 저장됩니다. 위치 미확인으로 표시돼요.");DetailRow("현장",state.site);DetailRow("작업 구역",state.zone);DetailRow("예상 일당",if(state.wageKnown)"${moneyText(state.wage)}원" else "미입력");DetailRow("현재 시각",clockText(controller.now));Text("안전점검을 아직 마치지 않아도 출근 기록을 남길 수 있어요.",fontSize=14.sp,lineHeight=24.sp,color=Muted);SLButton("출근 기록 저장",{actions.attend();sheet=null},enabled=!controller.busy,icon="check")}
   activeSheet=="checkout"->{SmallNote("오늘도 수고하셨습니다. 퇴근하면 움직임 감시도 종료돼요.");DetailRow("출근 시각",clockText(state.currentShift?.start));DetailRow("퇴근 시각",clockText(controller.now));state.currentShift?.let{DetailRow("근무 시간","${((controller.now-it.start)/60000).coerceAtLeast(0)}분")};DetailRow("예상 일당",if(state.currentShift?.wageKnown==true)"${moneyText(state.currentShift?.wage?:0)}원" else "미입력");SLButton("퇴근하고 기록 마무리",{controller.checkOut{sheet=null;navigate(WorkerPage.RECORDS);recordTimeline=true}},enabled=!controller.busy,icon="check")}
   activeSheet=="checklist"->ChecklistSheet(state,controller.busy){values->controller.saveChecks(values);sheet=null}
   activeSheet=="peer"->PeerSheet(state,controller.busy,actions.peerCamera,controller::discardCapture,{sheet=null},controller.peerMismatchRequested,{controller.peerMismatchRequested=false}){observed,photo->controller.checkPeer(observed,photo){sheet="peer"}}
   activeSheet=="profile"->EditProfileSheet(state){name,job,zone,wage->controller.saveWorkInfo(name,job,zone,wage);sheet=null}
   activeSheet=="connect"->LaunchedEffect(Unit){open("connect")}
   activeSheet=="site"->{SLPill(if(state.preview)"체험 현장" else "연결된 현장",amber=state.preview);Text(state.site,fontSize=23.sp,fontWeight=FontWeight.Bold,color=Ink);DetailRow("참여 코드",state.code);DetailRow("작업 구역",state.zone);DetailRow("안전 동료",state.buddyName);SmallNote(if(state.preview)"현재는 기능을 살펴보는 체험 모드예요. 실제 현장에 참여하려면 계정과 관리자 참여 코드를 연결해 주세요." else "이 현장의 근로·안전 기록을 앱과 관리자 대시보드에서 함께 확인합니다.");SLButton(if(state.preview)"실제 현장 연결하기" else "다른 현장 연결",{sheet="connect"},icon="link")}
   activeSheet=="requests"->{state.prompts.filter{it.state=="pending"||it.state=="unverified"}.forEach{prompt->MenuRow("shield",if(prompt.state=="pending")"안전 상태 확인 요청" else "응답 없이 만료된 요청",clockText(prompt.dueAt)){sheet="prompt:${prompt.id}"}};if(state.calls.isEmpty())EmptyState("bell","새로운 안전 확인 요청이 없어요","요청이 오면 휴대폰 알림과 이 화면에 표시돼요.");state.calls.forEach{call->CallSummary(call,state.uid){controller.openCall(call.id);sheet="call:${call.id}"}}}
   activeSheet=="call-failed"->{SmallNote("동료 호출 여부를 확인할 수 없어요. 가까운 동료를 부르고, 위험하면 119에 먼저 연락하세요.",amber=true);state.foremanPhone?.let{phone->SLButton("반장님께 전화",{actions.phone(phone)},secondary=true)};SLButton("서버 연결 다시 확인",{controller.retrySignal()})}
   activeSheet.startsWith("ack:")->{val call=state.calls.firstOrNull{it.id==activeSheet.substringAfter(':')};SLPill("사람이 확인");Text("괜찮다고 기록했어요");DetailRow("감지 시각",clockText(call?.createdAt));DetailRow("본인 응답",clockText(call?.respondedAt));SmallNote(if(call?.status=="closed")"본인이 안전하다고 응답한 시각을 남겼어요. 사진은 필요 없어요." else "본인 응답을 남겼어요. 이미 시작한 동료 호출과 현장 대응은 계속돼요.");SLButton("작업으로 돌아가기",{sheet=null;navigate(WorkerPage.TODAY)})}
   activeSheet.startsWith("report:")->{
    val id=activeSheet.substringAfter(':')
    if(state.preview){
     val call=state.calls.firstOrNull{it.id==id}
     if(call!=null){
      Text("사고 경위 보고서 · 체험 기록",fontWeight=FontWeight.Bold)
      previewIncidentLines(call).forEach{Text(it)}
      SLButton("체험 PDF 만들기",{actions.export("","","pdf",id)})
     }
    }else IncidentReportSheet(id,controller,actions.shareReport)
   }
   activeSheet.startsWith("call:")->{val call=state.calls.firstOrNull{it.id==activeSheet.removePrefix("call:")};if(call!=null)CallSheet(call,state,controller.now,controller.busy,{cb->actions.incidentCamera(call.id,cb)},actions.map,actions.phone,actions.locate,{actions.dialIncident(call.id)}){response,photo->controller.respond(call.id,response,photo)}else EmptyState("bell","요청 정보를 불러오고 있어요","연결 상태를 확인해 주세요.")}
   activeSheet.startsWith("risk:")->{state.risks.firstOrNull{it.id==activeSheet.removePrefix("risk:")}?.let{RiskDetailSheet(it,controller,actions.fixCamera)}}
   activeSheet=="high-risk"->HighRiskSheet(state,controller.busy){controller.highRisk(it);sheet=null}
   activeSheet=="carried"->{SmallNote("기기를 몸에 휴대하지 않으면 움직임 감지가 불가능해요.",amber=true);SLButton(if(state.carried)"기기를 휴대하지 않아요" else "지금 몸에 휴대하고 있어요",{controller.setCarried(!state.carried);sheet=null},enabled=!controller.busy)}
   activeSheet.startsWith("prompt:")->{val prompt=state.prompts.firstOrNull{it.id==activeSheet.removePrefix("prompt:")};if(prompt!=null){DetailRow("확인 이유",when(prompt.kind){"high_risk_start"->"고위험 작업 시작";"high_risk_end"->"고위험 작업 종료";"break_return"->"휴식 복귀";"shift_change"->"교대";else->"작업 중 확인"});DetailRow("요청 시각",clockText(prompt.dueAt));SmallNote(if(prompt.state=="pending")"현재 안전 상태를 알려 주세요." else "응답이 없었던 요청은 안전 미확인으로 남아요.");if(prompt.state=="pending"){SLButton("괜찮아요",{controller.answerPrompt(prompt.id,"okay");sheet=null},enabled=!controller.busy);SLButton("도움이 필요해요",{controller.answerPrompt(prompt.id,"help_needed");sheet=null},enabled=!controller.busy,color=Danger)}}else SmallNote("현재 출근의 확인 요청을 찾을 수 없어요. 이미 종료되었거나 연결 중일 수 있어요.")}
   activeSheet=="notifications"->{Row(verticalAlignment=Alignment.CenterVertically){Text("긴급 안전 확인",Modifier.weight(1f));Switch(state.emergencyPush,{controller.notificationPreferences(it,state.sitePush)},enabled=!controller.busy)};Row(verticalAlignment=Alignment.CenterVertically){Text("현장 점검·조치 알림",Modifier.weight(1f));Switch(state.sitePush,{controller.notificationPreferences(state.emergencyPush,it)},enabled=!controller.busy)};SLButton("휴대폰 알림 권한 설정",actions.notificationSettings,secondary=true)}
   activeSheet=="export"->ExportSheet(state,actions.export,actions.shareReport,controller::loadMoreIncidents){sheet="report:$it"}
   activeSheet.startsWith("event:")->{val entry=state.events.firstOrNull{it.id==activeSheet.removePrefix("event:")};if(entry!=null){EventRow(entry,state.preview);DetailRow("원본 기록 ID",entry.id);DetailRow("서버 저장 시각",java.time.Instant.ofEpochMilli(entry.time).atZone(koreaZone).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));var note by remember(entry.id){mutableStateOf("")};SLField("정정할 내용",note,{note=it},singleLine=false);SLButton("원본을 보존하고 정정 요청",{controller.correctEvent(entry.id,note);sheet=null},enabled=!controller.busy&&note.isNotBlank())}}
   activeSheet=="corrections"->{if(state.corrections.isEmpty())SmallNote("정정 요청이 없어요.");state.corrections.forEach{Text(it,color=Ink)}}
   activeSheet=="privacy"->{Text("기록의 첫 번째 주인은 작업자입니다.",fontSize=21.sp,fontWeight=FontWeight.Bold,color=Ink);Text("근로 기록과 직접 확인한 사실, 센서의 추정을 구분해 보관해요. 사진은 현장 확인과 위험 조치에 사용합니다.",fontSize=15.sp,lineHeight=27.sp,color=Muted);SmallNote(if(state.preview)"체험 모드의 근로·안전 기록과 사진은 이 기기에만 저장돼요. 실제 현장에 전송되지 않습니다." else "현장 연결 모드에서는 필요한 확인 상태를 같은 현장의 동료와 책임자에게 공유해요.");Text("근로 기록은 직접 열람하고 내보낼 수 있어요. 위험 신고는 이름을 숨길 수 있어요. 움직임 센서는 이상 신호 주변의 짧은 구간을 저장해요. 위치는 출근 확인과 긴급 상황에서만 사용하며 상시 추적하지 않습니다. 119 버튼은 전화 화면을 열며 실제 통화 연결이나 신고 접수를 증명하지 않아요.",fontSize=15.sp,lineHeight=27.sp,color=Muted)}
   activeSheet=="guide"->{listOf("01" to "휴대폰을 몸에 휴대해 주세요.","02" to "출근 후 필요한 작업 시간에 움직임 감시를 시작하세요.","03" to "충격과 이후 움직임 감소는 위험의 추정 신호예요. 모든 사고를 감지하지는 못합니다.","04" to "알림이 오면 상태를 확인하세요. 동료 요청을 받았다면 본인 안전을 먼저 확보하세요.","05" to "실제 위험한 상황이면 안전 확인 완료를 누르기보다 119에 연락하세요.").forEach{(n,text)->Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){Text(n,fontSize=14.sp,fontWeight=FontWeight.Bold,color=Brand);Text(text,fontSize=15.sp,lineHeight=26.sp,color=Ink)}};SmallNote("감시 중에는 배터리를 사용합니다. 잠금·절전·기기별 센서 동작은 실제 Android 기기로 확인해야 해요.",amber=true)}
   activeSheet=="reset"->{Text("이 기기의 체험 기록을 초기화할까요?",fontSize=20.sp,fontWeight=FontWeight.Bold,color=Ink);Text("추가한 체험용 출퇴근·점검·신고·안전 확인 기록이 초기화됩니다. 실제 서버 기록에는 영향을 주지 않아요.",fontSize=15.sp,lineHeight=26.sp,color=Muted);SLButton("체험 기록 초기화",{controller.resetPreview();sheet=null})}
   activeSheet=="disconnect"->{Text("체험 모드로 전환할까요?",fontSize=20.sp,fontWeight=FontWeight.Bold,color=Ink);Text("현장 연결과 감시를 종료합니다. 서버의 기존 근로·안전 기록은 보존돼요.",fontSize=15.sp,lineHeight=26.sp,color=Muted);SLButton("체험 모드로 전환",{controller.usePreview();sheet=null})}
  }
 }}
 state.selfCall?.let{call->SelfCheckDialog(call,controller.now,controller.busy,{controller.respond(call.id,"safe")},{controller.respond(call.id,"help")},{actions.dialIncident(call.id)},state.preview)}
}

private fun sheetTitle(route:String)=when{
 route.startsWith("ack:")->"본인 응답 완료"
 route=="call-failed"->"호출 전달 미확인"
 route.startsWith("report:")->"사고 경위 보고서"
 route.startsWith("call:")->"동료 현장 확인"
 route.startsWith("risk:")->"위험 신고 상세"
 route.startsWith("prompt:")->"작업 중 안전 확인"
 route.startsWith("event:")->"원본 기록과 정정"
 else->mapOf("attendance" to "오늘의 출근","checkout" to "오늘의 퇴근","checklist" to "작업 전 3가지 확인","peer" to "동료 보호구 확인","profile" to "내 작업 정보","connect" to "실제 현장 연결","site" to "나의 현장","requests" to "안전 확인 알림","privacy" to "기록과 개인정보","guide" to "안전 감지 이용 안내","reset" to "체험 기록 초기화","disconnect" to "현장 연결 종료","high-risk" to "고위험 작업","carried" to "기기 휴대 상태","notifications" to "알림 설정","export" to "기록 내보내기","corrections" to "정정 요청")[route]?:"안전고리"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun AppSheet(title:String,onDismiss:()->Unit,onDial:()->Unit,content:@Composable ColumnScope.()->Unit){
 val sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)
 ModalBottomSheet(onDismissRequest=onDismiss,sheetState=sheetState,containerColor=Color.White,dragHandle=null){
  Column(Modifier.fillMaxWidth().fillMaxHeight(.88f)){
   Row(Modifier.fillMaxWidth().padding(start=22.dp,end=8.dp,top=10.dp,bottom=6.dp),verticalAlignment=Alignment.CenterVertically){Text(title,Modifier.weight(1f),fontSize=20.sp,fontWeight=FontWeight.Bold,color=Ink);IconButton(onDismiss){SLIcon("close",tint=Muted,description="닫기")}}
   HorizontalDivider(color=Line)
   Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement=Arrangement.spacedBy(20.dp),content=content)
   EmergencyStrip(onDial)
  }
 }
}

@Composable private fun ChecklistSheet(s:WorkerState,busy:Boolean,onSave:(List<String>)->Unit){var values by remember(s.checks){mutableStateOf(s.checks)}
 SmallNote("모름을 고르면 반장에게 확인 요청이 가요. 개선이 필요한 항목도 그대로 남겨 주세요.")
 listOf("추락 위험 구역과 동선을 확인했나요?","바닥·개구부·안전난간은 괜찮나요?","안전모·턱끈·안전대를 점검했나요?").forEachIndexed{i,title->Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
  Text("0${i+1}",fontSize=13.sp,fontWeight=FontWeight.Bold,color=Brand);Text(title,fontSize=17.sp,fontWeight=FontWeight.Bold,color=Ink,lineHeight=28.sp)
  Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("확인","개선 필요","모름").forEach{v->val selected=values[i]==v;Surface(Modifier.weight(1f).height(48.dp).clickable{values=values.mapIndexed{index,old->if(index==i)v else old}},color=if(selected)BrandSoft else CanvasColor,shape=RoundedCornerShape(12.dp),border=BorderStroke(1.dp,if(selected)Brand else Line)){Box(contentAlignment=Alignment.Center){Text(v,fontSize=14.sp,fontWeight=if(selected)FontWeight.Bold else FontWeight.Normal,color=if(selected)Brand else Muted)}}}}
 }}
 SLButton("확인 내용 저장",{onSave(values)},enabled=!busy&&values.none{it.isBlank()},icon="check")
}

@Composable private fun PeerSheet(s:WorkerState,busy:Boolean,onPhoto:((String?)->Unit)->Unit,discardPhoto:(String?)->Unit,onDone:()->Unit,mismatchRequested:Boolean,consumeMismatch:()->Unit,onSave:(String,String?)->Unit){var observed by remember{mutableStateOf("")};var photo by remember{mutableStateOf<String?>(null)}
 LaunchedEffect(mismatchRequested){if(mismatchRequested){discardPhoto(photo);observed="미착용";photo=null;consumeMismatch()}}
 Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){Avatar(s.buddyName,56);Column(verticalArrangement=Arrangement.spacedBy(5.dp)){Text(s.buddyName,fontSize=21.sp,fontWeight=FontWeight.Bold,color=Ink);Text(s.buddyZone,fontSize=14.sp,color=Muted)}}
 if(s.peerStatus=="mismatch"){
  SmallNote("말한 것과 달라 보여요. 보호구를 고친 뒤 다시 확인하세요. 위반 모습은 촬영하지 않아요.",amber=true)
  SLButton("고쳤어요, 다시 찍기",{observed="착용";onPhoto{if(it!=null)photo=it}},enabled=!busy&&(s.preview||s.buddyAttendanceId!=null))
 }
 if(s.peerChecked){DetailRow("출근 위치",locationLabel(s.locationStatus));DetailRow("확인",if(s.peerStatus=="photo_confirmed")"사진 교차 확인" else "사진 없는 진술");SLButton("작업 시작",onDone)}
 Text("동료의 보호구를 직접 보고 확인해 주세요.",fontSize=15.sp,fontWeight=FontWeight.SemiBold,color=Ink)
 ChoiceTabs(listOf("착용 확인","개선 필요"),listOf("착용","미착용").indexOf(observed)){if(it==1){discardPhoto(photo);photo=null};observed=if(it==0)"착용" else "미착용"}
 if(observed=="착용"){Text("안전모·턱끈·안전대가 보이도록 찍어 주세요. 얼굴은 필요 없어요.",fontSize=14.sp,fontWeight=FontWeight.SemiBold,color=Ink);PhotoCapture(photo){onPhoto{if(it!=null)photo=it}}}else if(observed=="미착용")SmallNote("불일치 상태는 촬영하지 마세요. 조치 후 다시 확인하세요.",amber=true)
 SmallNote("이 화면은 작업 전 보호구 확인이에요. 긴급 대응은 별도 요청에서 도착·상태를 기록하고 사진은 선택해요.")
 SLButton(when{observed=="미착용"->"불일치 기록";photo==null->"사진 없이 확인";else->"사진과 확인 저장"},{onSave(observed,photo.takeIf{observed=="착용"})},enabled=!busy&&observed.isNotEmpty()&&(s.preview||s.buddyAttendanceId!=null),icon="check")
 s.peerEvidence.forEach{check->DetailRow(if(check.checkerId==s.uid)"${s.name} → ${s.buddyName}" else "${s.buddyName} → ${s.name}",when(check.outcome){"mismatch"->"불일치";else->if(check.photoId!=null)"사진 확인" else "사진 없는 진술"});DetailRow("서버 확인 시각",clockText(check.checkedAt));check.photo?.let{PhotoPreview(it)}}
}

@Composable private fun CallSheet(c:WorkerCall,s:WorkerState,now:Long,busy:Boolean,onPhoto:((String?)->Unit)->Unit,onMap:(Double,Double)->Unit,onPhone:(String)->Unit,locate:((android.location.Location?)->Unit)->Unit,onDial:()->Unit,onRespond:(String,String?)->Unit){
 var photo by remember(c.id){mutableStateOf<String?>(null)}
 var condition by remember(c.id){mutableStateOf("")}
 var reason by remember(c.id){mutableStateOf("")}
 SLPill(if(s.preview)"체험 요청" else "현장 안전 확인",amber=c.status!="closed")
 Text("${c.subjectName} · ${callStatus(c,s.uid)}",fontSize=24.sp,lineHeight=34.sp,fontWeight=FontWeight.Bold,color=Ink)
 if(c.status!="closed")EmergencyStrip(onDial)
 DetailRow("작업 구역",c.zone);DetailRow("발생 시각",clockText(c.createdAt.takeIf{it>0}));c.acceptedAt?.let{DetailRow("수락 시각",clockText(it))};c.arrivedAt?.let{DetailRow("도착 시각",clockText(it))}
 if(c.acceptedBy==s.uid&&c.arrivedAt==null&&c.latitude!=null&&c.longitude!=null)IncidentDirections(c,locate)
 if(c.latitude!=null&&c.longitude!=null){DetailRow("위치 오차",c.accuracy?.let{"약 ${it.toInt()}m"}?:"미확인");SLButton("지도에서 위치 보기",{onMap(c.latitude,c.longitude)},secondary=true,icon="pin")}else if(c.status!="closed")SmallNote("위치를 확인할 수 없어요. 구역 정보와 직접 연락으로 확인해 주세요.",amber=true)
 c.recipients.forEach{recipient->DetailRow(recipient.name,when{recipient.acceptedAt!=null->"수락";recipient.declinedAt!=null->"참여 어려움";recipient.openedAt!=null->"열람";recipient.receivedAt!=null->"단말 수신";recipient.delivery=="sent"->"FCM 발송 · 수신 미확인";recipient.delivery=="failed"->"발송 실패";recipient.delivery=="cancelled"->"발송 취소";else->"발송 대기"})}
 s.foremanPhone?.let{phone->SLButton("반장님께 전화",{onPhone(phone)},secondary=true,icon="phone")}
 if(c.status!="closed"&&(c.serverStatus=="unanswered"||c.recipients.any{it.id==s.uid&&it.declinedAt!=null}))SmallNote("가까운 동료를 부르고 반장에게 직접 연락하세요. 위험하면 바로 119에 전화하세요.",amber=true)
 if(c.serverStatus=="unanswered")SmallNote("수락한 사람이 없어요. 주변 동료나 반장에게 직접 연락하세요. 이미 초대된 동료는 계속 수락할 수 있어요.",amber=true)
 if(c.deadline>now&&c.stage!="self")DetailRow("다음 호출까지","${c.remainingSeconds(now)}초")
 if(c.status=="closed"){if(!c.detailLoaded){SmallNote("종료된 요청의 세부 내용을 불러오고 있어요.");return};DetailRow("마무리",when(c.resolution){"worker_confirmed_okay"->"본인이 괜찮다고 응답";"no_help_needed"->"현장에서 도움 불필요 확인";"handoff_confirmed"->"구조대·반장에게 인계 확인";"onsite_action_completed"->"현장 조치 완료";else->"종료"});c.observation?.let{DetailRow("직접 확인한 상태",mapOf("unconscious" to "의식 없음","immobile" to "움직이기 어려움","conscious" to "의식 있음","appears_safe" to "안전해 보임")[it]?:it)};if(c.photoId==null&&c.photo==null)SmallNote("현장 사진 없음 · 사진은 선택 항목");c.photo?.let{PhotoPreview(it)};return}
 if(c.isSubject(s.uid)){
  SmallNote("움직일 수 있으면 현재 상태를 알려 주세요. 수락자가 있으면 응답을 전달하고 현장 대응은 계속돼요.")
  SLButton("지금은 괜찮아요",{onRespond("safe",null)},enabled=!busy)
  if(c.serverStatus=="unanswered")SLButton("동료에게 다시 요청",{onRespond("retry",null)},enabled=!busy,secondary=true)
  return
 }
 if(c.acceptedBy!=null&&c.acceptedBy!=s.uid){SmallNote("다른 동료가 확인 중이에요. 본인의 안전을 먼저 확보해 주세요.");return}
 SmallNote("본인의 안전을 먼저 확보하세요. 위급하면 119에 먼저 연락하세요.",amber=true)

 if(c.acceptedBy==null){
  val invited=s.preview||c.recipients.any{it.id==s.uid&&it.role!="foreman"&&it.declinedAt==null}
  SLButton("확인하러 갈게요",{onRespond("accepted",null)},enabled=!busy&&invited,icon="people")
  SLButton("지금 갈 수 없어요",{onRespond("declined",null)},enabled=!busy&&invited,secondary=true)
 }else if(c.arrivedAt==null)SLButton("현장에 도착했어요",{onRespond("arrived",null)},enabled=!busy,icon="pin")
 else if(c.observation==null){
  Text("직접 확인한 상태",fontWeight=FontWeight.Bold,color=Ink)
  listOf("unconscious" to "의식이 없어 보여요","immobile" to "움직이기 어려워 보여요","conscious" to "의식이 있어요","appears_safe" to "안전해 보여요").forEach{(value,label)->Row(Modifier.fillMaxWidth().clickable{condition=value},verticalAlignment=Alignment.CenterVertically){RadioButton(condition==value,{condition=value});Text(label,color=Ink)}}
  SLButton("상태를 기록하고 반장에게 알리기",{onRespond("observe:$condition",null)},enabled=!busy&&condition.isNotEmpty())
 }else{
  DetailRow("119 전화 화면",c.dialTimes.lastOrNull()?.let(::clockText)?:"기록 없음")
  DetailRow("반장 상태 알림",c.observationNotice?:"전달 미확인")
  SmallNote("상황이 안정된 뒤 마무리하세요. 사람 대신 현장 주변만 촬영하고 사진 없이도 완료할 수 있어요.")
  listOf("no_help_needed" to "현장에서 도움 불필요 확인","onsite_action_completed" to "현장 조치 완료","handoff_confirmed" to "구조대·반장에게 인계 확인").forEach{(value,label)->Row(Modifier.fillMaxWidth().clickable{reason=value},verticalAlignment=Alignment.CenterVertically){RadioButton(reason==value,{reason=value});Text(label,color=Ink)}}
  Text("현장 사진 · 선택",fontWeight=FontWeight.Bold,color=Ink);PhotoCapture(photo){onPhoto{if(it!=null)photo=it}}
  SLButton("대응 마무리",{onRespond("finish:$reason",photo)},enabled=!busy&&reason.isNotEmpty(),icon="check")
 }
}

@Composable private fun EditProfileSheet(s:WorkerState,onSave:(String,String,String,Int?)->Unit){
 var name by remember{mutableStateOf(s.name)};var job by remember{mutableStateOf(s.job)};var zone by remember{mutableStateOf(s.zone)};var wage by remember{mutableStateOf(if(s.wageKnown)s.wage.toString()else "")}
 if(s.preview)SLField("이름",name,{name=it})else DetailRow("이름",s.name)
 SLField("직종",job,{job=it});SLField("작업 구역",zone,{zone=it});SLField("예상 일당 · 선택",wage,{wage=it.filter(Char::isDigit).take(8)})
 SmallNote("작업 정보와 예상 일당은 다음 출근부터 적용됩니다. 이미 저장한 출근 기록은 유지돼요.")
 SLButton("내 작업 정보 저장",{onSave(name,job,zone,wage.toIntOrNull())},enabled=job.isNotBlank()&&zone.isNotBlank()&&(wage.isBlank()||(wage.toIntOrNull()?:-1) in 0..10000000))
}

@Composable private fun RiskDetailSheet(risk:WorkerRisk,c:WorkerController,onPhoto:((String?)->Unit)->Unit){
 var after by remember(risk.id){mutableStateOf<String?>(null)}
 SLPill(when(risk.status){"verified"->"재확인 완료";"resolved"->"재확인 대기";else->"미조치"},amber=risk.status!="verified")
 DetailRow("신고 시각",clockText(risk.time));risk.fixedAt?.let{DetailRow("조치 시각",clockText(it));DetailRow("조치까지 걸린 시간","${((it-risk.time)/60000).coerceAtLeast(0)}분")};risk.verifiedAt?.let{DetailRow("재확인 시각",clockText(it))}
 Text(risk.title,fontSize=23.sp,fontWeight=FontWeight.Bold,color=Ink);DetailRow("발견 위치",risk.zone);DetailRow("위험 유형",risk.category)
 if(risk.note.isNotBlank())Text(risk.note,color=Muted)
 Text("조치 전",fontWeight=FontWeight.Bold,color=Ink);risk.photo?.let{PhotoPreview(it)}?:SmallNote("등록된 사진 없음 또는 불러오는 중")
 risk.afterPhoto?.let{Text("조치 후",fontWeight=FontWeight.Bold,color=Ink);PhotoPreview(it)}
 risk.fixerName?.let{DetailRow("조치자",it)};risk.verifierName?.let{DetailRow("재확인자",it)}
 if(risk.status=="open"){Text("조치 후 사진",fontWeight=FontWeight.Bold,color=Ink);PhotoCapture(after){onPhoto{if(it!=null)after=it}};SLButton("조치 완료 기록",{after?.let{c.fixRisk(risk.id,it)}},enabled=!c.busy&&after!=null)}
 if(risk.status=="resolved"){SmallNote("신고자와 실제 조치자 외 다른 동료가 재확인해야 해요.");SLButton("직접 확인하고 완료",{c.recheckRisk(risk.id)},enabled=!c.busy&&risk.canRecheck)}
}

@Composable private fun HighRiskSheet(s:WorkerState,busy:Boolean,onSave:(String?)->Unit){
 var task by remember{mutableStateOf(s.highRiskTask.orEmpty())}
 SmallNote("고위험 작업 전후에 상태를 확인하고, 움직임 감시가 켜져 있으면 작은 충격에도 확인을 요청해요.")
 if(s.highRiskTask==null){listOf("scaffold" to "비계","ladder" to "사다리","opening" to "개구부","aerial_lift" to "고소작업대").forEach{(value,label)->Row(Modifier.fillMaxWidth().clickable{task=value},verticalAlignment=Alignment.CenterVertically){RadioButton(task==value,{task=value});Text(label,color=Ink)}};SLButton("고위험 작업 시작",{onSave(task)},enabled=!busy&&task.isNotEmpty())}
 else{DetailRow("진행 중",mapOf("scaffold" to "비계","ladder" to "사다리","opening" to "개구부","aerial_lift" to "고소작업대")[s.highRiskTask]?:s.highRiskTask);SLButton("고위험 작업 끝내기",{onSave(null)},enabled=!busy)}
}

@Composable private fun ExportSheet(s:WorkerState,previewExport:(String,String,String,String?)->Unit,share:(String,String)->Unit,more:()->Unit,onReport:(String)->Unit){
 var from by remember{mutableStateOf(java.time.YearMonth.now(koreaZone).atDay(1).toString())};var to by remember{mutableStateOf(todayKey())};var format by remember{mutableStateOf("pdf")}
 if(s.preview)SmallNote("이 기기에 저장한 체험 기록을 내보내요.")
 val presets=listOf("이번 달","지난달","직접 고르기");var period by remember{mutableIntStateOf(0)};ChoiceTabs(presets,period){period=it;if(it<2){val month=java.time.YearMonth.now(koreaZone).minusMonths(it.toLong());from=month.atDay(1).toString();to=if(it==0)todayKey() else month.atEndOfMonth().toString()}}
 SmallNote("고용보험 근로내역과 대조해 빠진 날을 찾거나 정정을 요청할 때 쓸 수 있어요.")
 SLField("시작 날짜",from,{from=it},"YYYY-MM-DD");SLField("종료 날짜",to,{to=it},"YYYY-MM-DD")
 ChoiceTabs(listOf("PDF","CSV"),if(format=="pdf")0 else 1){format=if(it==0)"pdf" else "csv"}
 val valid=runCatching{val start=java.time.LocalDate.parse(from);val end=java.time.LocalDate.parse(to);!end.isBefore(start)&&java.time.temporal.ChronoUnit.DAYS.between(start,end)<=365}.getOrDefault(false)
 SLButton("내 근로 기록 내보내기",{if(s.preview)previewExport(from,to,format,null)else share("/reports/work?from=$from&to=$to&format=$format",format)},enabled=valid,icon="download")
 SectionHeading("사고 경위 보고서")
 val mine=s.calls.filter{it.isSubject(s.uid)}
 if(mine.isEmpty())SmallNote("현재 조회한 달의 본인 사고 기록이 없어요.")
 mine.forEach{call->MenuRow("records","${call.subjectName} · ${clockText(call.createdAt)}",callStatus(call,s.uid)){onReport(call.id)}}
 if(s.nextIncidents!=null)SLButton("사고 기록 더 보기",more,secondary=true)
 SmallNote("원본 기록과 사람의 확인·시스템 추정을 함께 제공해요. 사고 원인이나 법적 판단을 확정하지 않아요.")
}

@Composable private fun SelfCheckDialog(call:WorkerCall,now:Long,busy:Boolean,onSafe:()->Unit,onHelp:()->Unit,onDial:()->Unit,preview:Boolean){val remaining=call.remainingSeconds(now)
 Dialog(onDismissRequest={},properties=DialogProperties(dismissOnBackPress=false,dismissOnClickOutside=false,usePlatformDefaultWidth=false)){
  Surface(Modifier.fillMaxWidth().padding(20.dp).widthIn(max=440.dp),shape=RoundedCornerShape(24.dp),color=Color.White){Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(18.dp)){
   SLPill(if(preview)"체험 · 시스템이 추정" else "시스템이 추정",amber=true)
   Box(Modifier.size(60.dp).background(DangerSoft,CircleShape),contentAlignment=Alignment.Center){SLIcon("activity",Modifier.size(31.dp),Danger)}
   Text("강한 충격이\n감지되었어요",fontSize=28.sp,lineHeight=36.sp,fontWeight=FontWeight.ExtraBold,color=Ink,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
   Text("안전 상태를 확인해 주세요.\n괜찮다면 아래 버튼을 눌러 주세요.",fontSize=15.sp,lineHeight=25.sp,color=Muted,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
   Box(Modifier.size(170.dp),contentAlignment=Alignment.Center){CircularProgressIndicator(progress={(remaining/15f).coerceIn(0f,1f)},modifier=Modifier.fillMaxSize(),color=Danger,trackColor=DangerSoft,strokeWidth=10.dp);Column(horizontalAlignment=Alignment.CenterHorizontally){Text("$remaining",fontSize=68.sp,fontWeight=FontWeight.ExtraBold,color=Danger);Text("초 남음",fontSize=13.sp,color=Muted)}}
   Text(if(remaining>0)"응답이 없으면 안전 동료와 현장 책임자에게 알려요." else "응답 시간이 지났어요. 서버 연결 상태를 확인해 주세요.",fontSize=14.sp,lineHeight=22.sp,color=Muted)
   SLButton("괜찮아요",onSafe,enabled=!busy,icon="check",color=Ok)
   SLButton("도움이 필요해요",onHelp,enabled=!busy,secondary=true)
   EmergencyStrip(onDial)
  }}
 }
}
