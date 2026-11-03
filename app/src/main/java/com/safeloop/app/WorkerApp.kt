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
import androidx.compose.ui.platform.LocalContext
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
 var skippedShift by rememberSaveable{mutableStateOf<String?>(null)}
 val skippedTasks=state.todayShift?.id.let{it!=null&&it==skippedShift}
 val snackbar=remember{SnackbarHostState()}
 fun navigate(destination:WorkerPage){if(destination==WorkerPage.RECORDS)recordTimeline=false;if(destination==WorkerPage.TODAY)controller.loadDay(todayKey());pageName=destination.name}
 fun open(route:String){when(route){
  "connect"->{sheet=null;controller.beginOnboarding(if(state.preview)"login" else "code")}
  "simulate-self"->{navigate(WorkerPage.TODAY);actions.notifications(NotificationAction.DEMO_SELF)}
  "simulate-peer"->{navigate(WorkerPage.TODAY);actions.notifications(NotificationAction.DEMO_PEER)}
  "simulate-checkin"->{navigate(WorkerPage.TODAY);controller.simulatePrompt()}
  else->{if(route.startsWith("call:"))controller.openCall(route.removePrefix("call:"));if(route=="peer")controller.loadPeerPhotos();sheet=route}
 }}
 LaunchedEffect(controller.requestedRoute){controller.requestedRoute?.let{open(it);controller.requestedRoute=null}}
 LaunchedEffect(controller.feedback){controller.feedback?.let{snackbar.showSnackbar(it);controller.feedback=null}}
 LaunchedEffect(controller.openCallId,state.calls){val id=controller.openCallId;val call=state.calls.firstOrNull{it.id==id};if(call!=null){navigate(WorkerPage.TODAY);if(call.stage!="self"||!call.isSubject(state.uid))sheet="call:$id";controller.openCallId=null}}
 // 15초 안에 응답하지 못했거나 도움을 요청하면 3-3 화면을 바로 띄운다.
 val ownCall=state.calls.firstOrNull{it.isSubject(state.uid)&&it.stage=="peer"&&it.status!="closed"}?.id
 val appContext=LocalContext.current
 LaunchedEffect(ownCall){if(ownCall!=null){open("call:$ownCall");if(state.calls.any{it.id==ownCall&&controller.now-it.deadline<60_000})SafetyAlarm.beacon(appContext,voice=if(SituationRecorder.active)SafetySpeech.RECORDING else SafetySpeech.CALLING)}else{if(SafetyAlarm.beaconOn)SafetyAlarm.stop();if(SituationRecorder.active)SituationRecorder.stop()}}
 val onboarding=controller.onboarding
 if(onboarding!=null){WorkerOnboarding(onboarding,controller,actions);return}
 BackHandler(enabled=page!=WorkerPage.TODAY&&sheet==null){navigate(WorkerPage.TODAY)}
 Surface(Modifier.fillMaxSize(),color=CanvasColor){Box(Modifier.fillMaxSize().safeDrawingPadding()){
  Scaffold(containerColor=CanvasColor,contentWindowInsets=WindowInsets(0,0,0,0),snackbarHost={SnackbarHost(snackbar)},topBar={
   Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
    BrandMark();Text("안전고리",Modifier.weight(1f),fontSize=18.sp,fontWeight=FontWeight.ExtraBold,color=Ink)
    if(state.preview)SLPill("체험 모드",amber=true)
    if(state.connectionProblem!=null)SLPill("연결 미확인",amber=true)else if(state.currentShift!=null)SLPill("근무 중")else if(state.todayShift==null)SLPill("출근 전",neutral=true)else SLPill("퇴근 완료",neutral=true)
    IconButton({open("requests")}){Box{SLIcon("bell",tint=Ink,description="안전 확인 알림");if(state.activeCalls.isNotEmpty())Box(Modifier.align(Alignment.TopEnd).size(8.dp).background(Danger,CircleShape))}}
   }
  },bottomBar={Column {
   if(page==WorkerPage.TODAY)TodayAction(state,skippedTasks,::open,::navigate)
   EmergencyStrip(actions.dial)
   HorizontalDivider(color=Line)
   Row(Modifier.fillMaxWidth().background(Color.White)){WorkerPage.entries.forEach{destination->Column(Modifier.weight(1f).height(68.dp).clickable{navigate(destination)},horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp,Alignment.CenterVertically)){
    SLIcon(destination.icon,tint=if(page==destination)Brand else Muted)
    Text(destination.label,fontSize=13.sp,fontWeight=if(page==destination)FontWeight.ExtraBold else FontWeight.Medium,color=if(page==destination)Brand else Muted)
   }}}
  }}){padding->Column(Modifier.padding(padding)){
   if(state.connectionProblem!=null){SmallNote("서버 연결 미확인 · ${state.connectionProblem}",amber=true);TextButton(controller::refreshNow){Text("다시 시도")}}
   if(!state.preview&&controller.pendingWrites>0)SmallNote("서버 저장 대기 · ${controller.pendingWrites}건",amber=true)
   if(controller.busy)LinearProgressIndicator(Modifier.fillMaxWidth(),color=Brand,trackColor=BrandSoft)
   key(page){Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start=20.dp,end=20.dp,top=6.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
    when(page){
     WorkerPage.TODAY->HomePage(state,controller.now,::open,::navigate,actions.monitor,actions.checkLocation,skippedTasks,{skippedShift=state.todayShift?.id},pinned=true)
     WorkerPage.REPORT->ReportPage(state,controller.busy,actions,controller::submitRisk,::open,controller::loadMoreRisks)
     WorkerPage.RECORDS->RecordsPage(state,{sheet="export"},::open,controller::loadMonth,controller::loadDay,controller::loadMoreTimeline,recordTimeline)
     WorkerPage.PROFILE->ProfilePage(state,::open,{sheet="notifications"})
    }
   }}
  }}
 }}
 val activeSheet=sheet
 LaunchedEffect(activeSheet){controller.trackCall(activeSheet?.takeIf{it.startsWith("call:")}?.removePrefix("call:"))}
 LaunchedEffect(activeSheet,state.calls.firstOrNull{activeSheet=="call:${it.id}"}?.status){if(activeSheet?.startsWith("call:")==true)controller.loadVerificationPhoto(activeSheet.removePrefix("call:"))}
 val sheetCall=activeSheet?.takeIf{it.startsWith("call:")}?.let{route->state.calls.firstOrNull{it.id==route.removePrefix("call:")}}?.takeIf{it.status!="closed"}
 // 3-6은 119가 맨 위에 있다. 나머지 긴급 화면은 스크롤해도 띠로 119를 누를 수 있어야 한다.
 val bigDial=sheetCall?.let{it.acceptedBy==state.uid&&it.arrivedAt!=null&&it.observation==null}==true
 if(activeSheet!=null){AppSheet((if(state.preview)"체험 모드 · " else "")+sheetTitle(activeSheet),{sheet=null},{if(activeSheet.startsWith("call:"))actions.dialIncident(activeSheet.removePrefix("call:"))else actions.dial()},tape=sheetCall!=null||activeSheet=="call-failed",strip=!bigDial){
  controller.feedback?.let{SmallNote(it,amber=true)}
  when {
   activeSheet=="attendance"->{SmallNote(if(state.preview)"체험 모드의 출근 기록은 이 기기에 저장됩니다." else "위치를 허용하지 않아도 출근은 저장됩니다. 위치 미확인으로 표시돼요.");DetailRow("현장",state.site);DetailRow("작업 구역",state.zone);DetailRow("예상 일당",if(state.wageKnown)"${moneyText(state.wage)}원" else "미입력");DetailRow("현재 시각",clockText(controller.now));Text("안전점검을 아직 마치지 않아도 출근 기록을 남길 수 있어요.",fontSize=14.sp,lineHeight=24.sp,color=Muted);SLButton("출근 기록 저장",{actions.attend();sheet=null},enabled=!controller.busy,icon="check")}
   activeSheet=="checkout"->CheckoutSheet(state,controller.now,controller.busy){controller.checkOut{sheet=null;navigate(WorkerPage.RECORDS);recordTimeline=true}}
   activeSheet=="checklist"->ChecklistSheet(state,controller.busy){values->controller.saveChecks(values);sheet=null}
   activeSheet=="peer"->key(state.siteId,state.buddyAttendanceId){val target=state.buddyAttendanceId;PeerSheet(state,controller.busy,actions,controller::discardCapture,{sheet=null}){observed,photo->controller.checkPeer(target,observed,photo){sheet="peer"}}}
   activeSheet=="profile"->EditProfileSheet(state){name,job,zone,wage->controller.saveWorkInfo(name,job,zone,wage);sheet=null}
   activeSheet=="connect"->LaunchedEffect(Unit){open("connect")}
   activeSheet=="site"->{SLPill(if(state.preview)"체험 현장" else "연결된 현장",amber=state.preview);Text(state.site,fontSize=23.sp,fontWeight=FontWeight.Bold,color=Ink);DetailRow("참여 코드",state.code);DetailRow("작업 구역",state.zone);DetailRow("안전 동료",state.buddyName);SmallNote(if(state.preview)"현재는 기능을 살펴보는 체험 모드예요. 실제 현장에 참여하려면 계정과 관리자 참여 코드를 연결해 주세요." else "이 현장의 근로·안전 기록을 앱과 관리자 대시보드에서 함께 확인합니다.");SLButton(if(state.preview)"실제 현장 연결하기" else "다른 현장 연결",{sheet="connect"},icon="link")}
   activeSheet=="requests"->{state.prompts.filter{it.state=="pending"||it.state=="unverified"}.forEach{prompt->MenuRow("shield",if(prompt.state=="pending")"안전 상태 확인 요청" else "응답 없이 만료된 요청",clockText(prompt.dueAt)){sheet="prompt:${prompt.id}"}};if(state.calls.isEmpty())EmptyState("bell","새로운 안전 확인 요청이 없어요","요청이 오면 휴대폰 알림과 이 화면에 표시돼요.");state.calls.forEach{call->CallSummary(call,state.uid){controller.openCall(call.id);sheet="call:${call.id}"}}}
   activeSheet=="call-failed"->CallFailedSheet(state.foremanPhone,controller.busy,actions.dial,actions.phone){controller.retrySignal();sheet=null}
   activeSheet.startsWith("ack:")->AckSheet(state.calls.firstOrNull{it.id==activeSheet.substringAfter(':')}){sheet=null;navigate(WorkerPage.TODAY)}
   activeSheet.startsWith("report:")->{
    val id=activeSheet.substringAfter(':')
    if(state.preview){
     state.calls.firstOrNull{it.id==id}?.let{call->PreviewReportSheet(call,state){actions.export("","","pdf",id)}}
    }else IncidentReportSheet(id,controller,actions.shareReport)
   }
   activeSheet.startsWith("call:")->{val call=state.calls.firstOrNull{it.id==activeSheet.removePrefix("call:")};if(call!=null)CallSheet(call,state,controller.now,controller.incidentBusy(call.id),actions,{actions.dialIncident(call.id)},{sheet="call-failed"}){response,photo->controller.respond(call.id,response,photo)}else EmptyState("bell","요청 정보를 불러오고 있어요","연결 상태를 확인해 주세요.")}
   activeSheet.startsWith("risk:")->{
    val id=activeSheet.removePrefix("risk:")
    DisposableEffect(id,state.siteId){controller.loadRiskPhotos(id);onDispose{controller.closeRisk(id)}}
    val risk=state.riskDetail(id)
    if(risk!=null)RiskDetailSheet(risk,controller,actions) else SmallNote("신고 정보를 불러오고 있어요.")
   }
   activeSheet=="high-risk"->HighRiskSheet(state,controller.busy){controller.highRisk(it);sheet=null}
   activeSheet=="carried"->{SmallNote("기기를 몸에 휴대하지 않으면 움직임 감지가 불가능해요.",amber=true);SLButton(if(state.carried)"기기를 휴대하지 않아요" else "지금 몸에 휴대하고 있어요",{controller.setCarried(!state.carried);sheet=null},enabled=!controller.busy)}
   activeSheet.startsWith("prompt:")->PromptSheet(state.prompts.firstOrNull{it.id==activeSheet.removePrefix("prompt:")},controller.busy){answer->controller.answerPrompt(activeSheet.removePrefix("prompt:"),answer);sheet=null}
   activeSheet=="notifications"->{Row(verticalAlignment=Alignment.CenterVertically){Text("긴급 안전 확인",Modifier.weight(1f));Switch(state.emergencyPush,{controller.notificationPreferences(it,state.sitePush)},enabled=!controller.busy)};Row(verticalAlignment=Alignment.CenterVertically){Text("현장 점검·조치 알림",Modifier.weight(1f));Switch(state.sitePush,{controller.notificationPreferences(state.emergencyPush,it)},enabled=!controller.busy)};VoiceSetting();SituationSetting();SLButton("휴대폰 알림 권한 설정",actions.notificationSettings,secondary=true)}
   activeSheet=="export"->ExportSheet(state,actions.export,actions.shareReport,controller::loadMoreIncidents){sheet="report:$it"}
   activeSheet.startsWith("event:")->{val entry=state.events.firstOrNull{it.id==activeSheet.removePrefix("event:")};if(entry!=null){EventRow(entry,state.preview);DetailRow("원본 기록 ID",entry.id);if(entry.hash.isNotBlank())DetailRow("기록 연결값",entry.hash.take(12));DetailRow("서버 저장 시각",java.time.Instant.ofEpochMilli(entry.time).atZone(koreaZone).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));if(state.preview||entry.canCorrect){var note by remember(entry.id){mutableStateOf("")};SLField("정정할 내용",note,{note=it},singleLine=false);SLButton("원본을 보존하고 정정 요청",{controller.correctEvent(entry.id,note);sheet=null},enabled=!controller.busy&&note.isNotBlank())}}}
   activeSheet=="corrections"->{if(state.corrections.isEmpty())SmallNote("정정 요청이 없어요.");state.corrections.forEach{Text(it,color=Ink)}}
   activeSheet=="privacy"->PrivacySheet(state.preview)
   activeSheet=="guide"->GuideSheet()
   activeSheet=="reset"->{Text("이 기기의 체험 기록을 초기화할까요?",fontSize=20.sp,fontWeight=FontWeight.Bold,color=Ink);Text("추가한 체험용 출퇴근·점검·신고·안전 확인 기록이 초기화됩니다. 실제 서버 기록에는 영향을 주지 않아요.",fontSize=15.sp,lineHeight=26.sp,color=Muted);SLButton("체험 기록 초기화",{controller.resetPreview();sheet=null})}
   activeSheet=="disconnect"->{Text("체험 모드로 전환할까요?",fontSize=20.sp,fontWeight=FontWeight.Bold,color=Ink);Text("현장 연결과 감시를 종료합니다. 서버의 기존 근로·안전 기록은 보존돼요.",fontSize=15.sp,lineHeight=26.sp,color=Muted);SLButton("체험 모드로 전환",{controller.usePreview();sheet=null})}
  }
 }}
 state.selfCall?.let{call->SelfCheckDialog(call,controller.now,controller.incidentBusy(call.id),{controller.respond(call.id,"safe")},{controller.respond(call.id,"help");actions.record(call.id)},{controller.respondByVoice(call.id)},{actions.dialIncident(call.id)},state.preview)}
}

private fun sheetTitle(route:String)=when{
 route.startsWith("ack:")->"본인 응답"
 route=="call-failed"->"호출 확인 필요"
 route.startsWith("report:")->"사고 경위 보고서"
 route.startsWith("call:")->"긴급 대응"
 route.startsWith("risk:")->"위험 신고 상세"
 route.startsWith("prompt:")->"작업 중 확인"
 route.startsWith("event:")->"원본 기록과 정정"
 else->mapOf("attendance" to "오늘의 출근","checkout" to "퇴근","checklist" to "작업 전 확인","peer" to "작업 전 확인","profile" to "내 작업 정보","connect" to "실제 현장 연결","site" to "나의 현장","requests" to "안전 확인 알림","privacy" to "기록과 개인정보","guide" to "안전 감지 이용 안내","reset" to "체험 기록 초기화","disconnect" to "현장 연결 종료","high-risk" to "고위험 작업","carried" to "기기 휴대 상태","notifications" to "알림 설정","export" to "기록 내보내기","corrections" to "정정 요청")[route]?:"안전고리"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun AppSheet(title:String,onDismiss:()->Unit,onDial:()->Unit,tape:Boolean=false,strip:Boolean=true,content:@Composable ColumnScope.()->Unit){
 val sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)
 ModalBottomSheet(onDismissRequest=onDismiss,sheetState=sheetState,containerColor=Color.White,dragHandle=null){
  Column(Modifier.fillMaxWidth().fillMaxHeight(.92f)){
   if(tape)TapeStrip()
   Row(Modifier.fillMaxWidth().padding(start=20.dp,end=8.dp,top=6.dp,bottom=2.dp),verticalAlignment=Alignment.CenterVertically){Text(title,Modifier.weight(1f),fontSize=14.sp,fontWeight=FontWeight.Bold,color=Muted);IconButton(onDismiss){SLIcon("close",tint=Muted,description="닫기")}}
   Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start=20.dp,end=20.dp,top=4.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(14.dp),content=content)
   if(strip)EmergencyStrip(onDial)
  }
 }
}

@Composable private fun EditProfileSheet(s:WorkerState,onSave:(String,String,String,Int?)->Unit){
 var name by remember{mutableStateOf(s.name)};var job by remember{mutableStateOf(s.job)};var zone by remember{mutableStateOf(s.zone)};var wage by remember{mutableStateOf(if(s.wageKnown)s.wage.toString()else "")}
 if(s.preview)SLField("이름",name,{name=it})else DetailRow("이름",s.name)
 SLField("직종",job,{job=it});SLField("작업 구역",zone,{zone=it});SLField("예상 일당 · 선택",wage,{wage=it.filter(Char::isDigit).take(8)})
 SmallNote("작업 정보와 예상 일당은 다음 출근부터 적용됩니다. 이미 저장한 출근 기록은 유지돼요.")
 SLButton("내 작업 정보 저장",{onSave(name,job,zone,wage.toIntOrNull())},enabled=job.isNotBlank()&&zone.isNotBlank()&&(wage.isBlank()||(wage.toIntOrNull()?:-1) in 0..10000000))
}

// 3-1 본인 확인 15초. 탭을 숨기고 괜찮아요와 119만 크게 보여준다.
@Composable private fun SelfCheckDialog(call:WorkerCall,now:Long,busy:Boolean,onSafe:()->Unit,onHelp:()->Unit,onVoiceSafe:()->Unit,onDial:()->Unit,preview:Boolean){val remaining=call.remainingSeconds(now);val bars=WindowInsets.safeDrawing.asPaddingValues();val context=LocalContext.current
 // 마이크를 쓰면 처음부터 듣는다. 안내 음성이 나오는 중에 답해도 된다.
 val canListen=remember{VoiceListener.hasMicrophone(context)&&VoiceListener.available(context)};val askHelp by rememberUpdatedState(onHelp);val sayOkay by rememberUpdatedState(onVoiceSafe)
 DisposableEffect(call.id){var asked=false;SafetyAlarm.start(context,remaining*1000,SafetySpeech.SELF_CHECK,listening=canListen);val listener=VoiceListener(context,continuous=true,onPartial={},onHeard={words->if(!asked&&spokenAnswer(words)==SpokenAnswer.HELP){asked=true;askHelp()}},onSettled={words->if(!asked&&spokenAnswer(words)==SpokenAnswer.OKAY){asked=true;sayOkay()}},onGap={SafetyAlarm.pulse(context)});if(canListen)listener.start();onDispose{listener.stop();SafetyAlarm.stop()}}
 Dialog(onDismissRequest={},properties=DialogProperties(dismissOnBackPress=false,dismissOnClickOutside=false,usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
  Surface(Modifier.fillMaxSize(),color=Color.White){Column(Modifier.fillMaxSize().padding(bars)){
   TapeStrip()
   Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start=20.dp,end=20.dp,top=20.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){SLPill("시스템이 추정",amber=true,estimate=true);if(preview)SLPill("체험 모드",amber=true)}
    Text("강한 충격이\n감지되었어요",fontSize=28.sp,lineHeight=36.sp,fontWeight=FontWeight.ExtraBold,color=Ink)
    Text("안전 상태를 확인해 주세요.",fontSize=16.sp,color=Muted)
    Box(Modifier.size(160.dp).align(Alignment.CenterHorizontally),contentAlignment=Alignment.Center){CircularProgressIndicator(progress={(remaining/15f).coerceIn(0f,1f)},modifier=Modifier.fillMaxSize(),color=Danger,trackColor=DangerSoft,strokeWidth=12.dp);Column(horizontalAlignment=Alignment.CenterHorizontally){Text("$remaining",fontSize=64.sp,lineHeight=70.sp,fontWeight=FontWeight.ExtraBold,color=Danger);Text("초 남음",fontSize=14.sp,fontWeight=FontWeight.Bold,color=Muted,maxLines=1)}}
    Text(if(remaining>0)"응답이 없으면 안전 동료와 반장님께 알림이 가요." else "응답 시간이 지났어요. 서버 연결 상태를 확인해 주세요.",Modifier.fillMaxWidth(),fontSize=14.sp,lineHeight=22.sp,color=Muted,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
    if(canListen)NoteText("말로도 답할 수 있어요. '괜찮아요' 또는 '도와줘'",center=true)
   }
   Column(Modifier.padding(start=20.dp,end=20.dp,bottom=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
    SLButton("괜찮아요",onSafe,enabled=!busy,icon="check",color=Ok,large=true)
    SLButton("119 전화",onDial,icon="phone",color=Danger)
    GhostButton("도움이 필요해요",onHelp,enabled=!busy)
   }
  }}
 }
}
