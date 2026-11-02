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
 var sheet by rememberSaveable{mutableStateOf<String?>(null)}
 val snackbar=remember{SnackbarHostState()}
 fun navigate(destination:WorkerPage){pageName=destination.name}
 fun open(route:String){when(route){
  "simulate-self"->actions.notifications{controller.simulateSelf();navigate(WorkerPage.SAFETY)}
  "simulate-peer"->actions.notifications{controller.simulateIncoming();navigate(WorkerPage.SAFETY)}
  else->sheet=route
 }}
 LaunchedEffect(controller.feedback){controller.feedback?.let{snackbar.showSnackbar(it);controller.feedback=null}}
 LaunchedEffect(controller.openCallId,state.calls){val id=controller.openCallId;val call=state.calls.firstOrNull{it.id==id};if(call!=null){navigate(WorkerPage.SAFETY);if(call.stage!="self"||!call.isSubject(state.uid))sheet="call:$id";controller.openCallId=null}}
 BackHandler(enabled=page!=WorkerPage.TODAY&&sheet==null){navigate(WorkerPage.TODAY)}
 Surface(Modifier.fillMaxSize(),color=CanvasColor){Box(Modifier.fillMaxSize().safeDrawingPadding()){
  Scaffold(containerColor=CanvasColor,contentWindowInsets=WindowInsets(0,0,0,0),snackbarHost={SnackbarHost(snackbar)},topBar={
   Row(Modifier.fillMaxWidth().height(70.dp).padding(horizontal=22.dp),verticalAlignment=Alignment.CenterVertically){
    Box(Modifier.size(36.dp).background(PaleGreen,RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center){SLIcon("link",Modifier.size(22.dp))};Spacer(Modifier.width(9.dp))
    Column(Modifier.weight(1f)){Text("안전고리",fontSize=20.sp,fontWeight=FontWeight.ExtraBold,color=Ink,letterSpacing=(-.7).sp);Text("SAFELOOP",fontSize=8.sp,fontWeight=FontWeight.Bold,color=Muted,letterSpacing=2.sp)}
    Box(Modifier.clickable{navigate(WorkerPage.PROFILE)}){SLPill(if(state.preview)"체험 모드" else "현장 연결",amber=state.preview)}
    IconButton({open("requests")}){Box{SLIcon("bell",tint=Ink,description="안전 확인 알림");if(state.activeCalls.isNotEmpty())Box(Modifier.align(Alignment.TopEnd).size(6.dp).background(Amber,CircleShape))}}
   }
  },bottomBar={Column {
   EmergencyStrip(actions.dial)
   Row(Modifier.fillMaxWidth().background(Color.White).border(BorderStroke(.5.dp,Line)),horizontalArrangement=Arrangement.SpaceEvenly){WorkerPage.entries.forEach{destination->Column(Modifier.weight(1f).height(66.dp).clickable{navigate(destination)},horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
    Box(Modifier.size(width=39.dp,height=29.dp).background(if(page==destination)PaleGreen else Color.Transparent,RoundedCornerShape(11.dp)),contentAlignment=Alignment.Center){SLIcon(destination.icon,Modifier.size(21.dp),if(page==destination)Forest else Muted)}
    Spacer(Modifier.height(4.dp));Text(destination.label,fontSize=10.sp,fontWeight=if(page==destination)FontWeight.Bold else FontWeight.Medium,color=if(page==destination)Forest else Muted)
   }}}
  }}){padding->Column(Modifier.padding(padding)){
   if(controller.busy)LinearProgressIndicator(Modifier.fillMaxWidth(),color=Forest,trackColor=PaleGreen)
   key(page){Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start=22.dp,end=22.dp,top=12.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
    when(page){
     WorkerPage.TODAY->HomePage(state,controller.now,::open,::navigate,actions.monitor)
     WorkerPage.SAFETY->SafetyPage(state,::open,actions.monitor)
     WorkerPage.REPORT->ReportPage(state,controller.busy,actions.camera,controller::submitRisk,::open)
     WorkerPage.RECORDS->RecordsPage(state,actions.export,::open)
     WorkerPage.PROFILE->ProfilePage(state,::open,actions.notificationSettings)
    }
   }}
  }}
 }}
 val activeSheet=sheet
 LaunchedEffect(activeSheet,state.calls.firstOrNull{activeSheet=="call:${it.id}"}?.status){if(activeSheet?.startsWith("call:")==true)controller.loadVerificationPhoto(activeSheet.removePrefix("call:"))}
 if(activeSheet!=null){AppSheet(sheetTitle(activeSheet),{sheet=null},actions.dial){
  controller.feedback?.let{SmallNote(it,amber=true)}
  when {
   activeSheet=="attendance"->{SmallNote(if(state.preview)"체험 모드의 출근 기록은 이 기기에 저장됩니다." else "현재 위치로 현장 출근을 확인해요. 위치 권한이 필요할 수 있습니다.");DetailRow("현장",state.site);DetailRow("작업 구역",state.zone);DetailRow("예상 일당","${moneyText(state.wage)}원");DetailRow("현재 시각",clockText(controller.now));Text("안전점검을 아직 마치지 않아도 출근 기록을 남길 수 있어요.",fontSize=13.sp,lineHeight=21.sp,color=Muted);SLButton("출근 기록 저장",{actions.attend();sheet=null},enabled=!controller.busy,icon="check")}
   activeSheet=="checkout"->{SmallNote("오늘도 수고하셨습니다. 퇴근하면 움직임 감시도 종료돼요.");DetailRow("출근 시각",clockText(state.currentShift?.start));DetailRow("퇴근 시각",clockText(controller.now));DetailRow("예상 일당","${moneyText(state.currentShift?.wage?:state.wage)}원");SLButton("퇴근하고 기록 마무리",{controller.checkOut();sheet=null;navigate(WorkerPage.RECORDS)},enabled=!controller.busy,icon="check")}
   activeSheet=="checklist"->ChecklistSheet(state,controller.busy){values->controller.saveChecks(values);sheet=null}
   activeSheet=="peer"->PeerSheet(state,controller.busy,actions.camera){observed,photo->controller.checkPeer(observed,photo);sheet=null}
   activeSheet=="profile"->EditProfileSheet(state){name,zone,wage->controller.saveWorkInfo(name,zone,wage);sheet=null}
   activeSheet=="connect"->ConnectSheet(controller){sheet=null}
   activeSheet=="site"->{SLPill(if(state.preview)"체험 현장" else "연결된 현장",amber=state.preview);Text(state.site,fontSize=23.sp,fontWeight=FontWeight.Bold,color=Ink);DetailRow("참여 코드",state.code);DetailRow("작업 구역",state.zone);DetailRow("안전 동료",state.buddyName);SmallNote(if(state.preview)"현재는 기능을 살펴보는 체험 모드예요. 실제 현장에 참여하려면 계정과 관리자 참여 코드를 연결해 주세요." else "이 현장의 근로·안전 기록을 앱과 관리자 대시보드에서 함께 확인합니다.");if(state.preview)SLButton("실제 현장 연결하기",{sheet="connect"},icon="link")}
   activeSheet=="requests"->{if(state.calls.isEmpty())EmptyState("bell","새로운 안전 확인 요청이 없어요","요청이 오면 휴대폰 알림과 이 화면에 표시돼요.");state.calls.forEach{call->CallSummary(call,state.uid){sheet="call:${call.id}"}}}
   activeSheet.startsWith("call:")->{val call=state.calls.firstOrNull{it.id==activeSheet.removePrefix("call:")};if(call!=null)CallSheet(call,state,controller.busy,actions.camera){response,photo->controller.respond(call.id,response,photo)}else EmptyState("bell","요청 정보를 불러오고 있어요","연결 상태를 확인해 주세요.")}
   activeSheet.startsWith("risk:")->{val risk=state.risks.firstOrNull{it.id==activeSheet.removePrefix("risk:")};if(risk!=null){SLPill(when(risk.status){"verified"->"재확인 완료";"resolved"->"재확인 대기";else->"미조치"},amber=risk.status!="verified");Text(risk.title,fontSize=23.sp,fontWeight=FontWeight.Bold,color=Ink);DetailRow("발견 위치",risk.zone);DetailRow("위험 유형",risk.category);DetailRow("이름 표시",if(risk.anonymous)"관리자에게 숨김" else "이름 표시");if(risk.photo!=null)PhotoPreview(risk.photo);if(risk.note.isNotBlank())Text(risk.note,fontSize=14.sp,lineHeight=23.sp,color=Muted);SmallNote("신고 → 조치 후 사진 → 다른 동료 재확인 순서로 처리됩니다. 조치와 안전 확인은 구분해 기록해요.")}}
   activeSheet=="privacy"->{Text("기록의 첫 번째 주인은 작업자입니다.",fontSize=21.sp,fontWeight=FontWeight.Bold,color=Ink);Text("근로 기록과 직접 확인한 사실, 센서의 추정을 구분해 보관해요. 사진은 현장 확인과 위험 조치에 사용합니다.",fontSize=14.sp,lineHeight=24.sp,color=Muted);SmallNote(if(state.preview)"체험 모드의 근로·안전 기록과 사진은 이 기기에만 저장돼요. 실제 현장에 전송되지 않습니다." else "현장 연결 모드에서는 필요한 확인 상태를 같은 현장의 동료와 책임자에게 공유해요.");Text("위치는 출근 확인에 사용하며 상시 추적하지 않습니다. 119 버튼은 전화 화면을 열며 실제 통화 연결이나 신고 접수를 증명하지 않아요.",fontSize=14.sp,lineHeight=24.sp,color=Muted)}
   activeSheet=="guide"->{listOf("01" to "휴대폰을 몸에 휴대해 주세요.","02" to "출근 후 필요한 작업 시간에 움직임 감시를 시작하세요.","03" to "충격과 이후 움직임 감소는 위험의 추정 신호예요. 모든 사고를 감지하지는 못합니다.","04" to "알림이 오면 상태를 확인하세요. 동료 요청을 받았다면 본인 안전을 먼저 확보하세요.","05" to "실제 위험한 상황이면 안전 확인 완료를 누르기보다 119에 연락하세요.").forEach{(n,text)->Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){Text(n,fontSize=12.sp,fontWeight=FontWeight.Bold,color=Forest);Text(text,fontSize=14.sp,lineHeight=23.sp,color=Ink)}};SmallNote("감시 중에는 배터리를 사용합니다. 잠금·절전·기기별 센서 동작은 실제 Android 기기로 확인해야 해요.",amber=true)}
   activeSheet=="reset"->{Text("이 기기의 체험 기록을 초기화할까요?",fontSize=20.sp,fontWeight=FontWeight.Bold,color=Ink);Text("추가한 체험용 출퇴근·점검·신고·안전 확인 기록이 초기화됩니다. 실제 서버 기록에는 영향을 주지 않아요.",fontSize=14.sp,lineHeight=23.sp,color=Muted);SLButton("체험 기록 초기화",{controller.resetPreview();sheet=null})}
   activeSheet=="disconnect"->{Text("체험 모드로 전환할까요?",fontSize=20.sp,fontWeight=FontWeight.Bold,color=Ink);Text("현장 연결과 감시를 종료합니다. 서버의 기존 근로·안전 기록은 보존돼요.",fontSize=14.sp,lineHeight=23.sp,color=Muted);SLButton("체험 모드로 전환",{controller.usePreview();sheet=null})}
  }
 }}
 state.selfCall?.let{call->SelfCheckDialog(call,controller.now,controller.busy,{controller.respond(call.id,"safe")},actions.dial,state.preview)}
}

private fun sheetTitle(route:String)=when{
 route.startsWith("call:")->"동료 현장 확인"
 route.startsWith("risk:")->"위험 신고 상세"
 else->mapOf("attendance" to "오늘의 출근","checkout" to "오늘의 퇴근","checklist" to "작업 전 3가지 확인","peer" to "동료 보호구 확인","profile" to "내 작업 정보","connect" to "실제 현장 연결","site" to "나의 현장","requests" to "안전 확인 알림","privacy" to "기록과 개인정보","guide" to "안전 감지 이용 안내","reset" to "체험 기록 초기화","disconnect" to "현장 연결 종료")[route]?:"안전고리"
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
 SmallNote("모르면 책임자에게 물어보세요. 확인하지 않은 항목을 완료로 기록하지 않아요.")
 listOf("추락 위험 구역과 동선을 확인했나요?","바닥·개구부·안전난간은 괜찮나요?","안전모·턱끈·안전대를 점검했나요?").forEachIndexed{i,title->Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
  Text("0${i+1}",fontSize=11.sp,fontWeight=FontWeight.Bold,color=Forest);Text(title,fontSize=17.sp,fontWeight=FontWeight.Bold,color=Ink,lineHeight=25.sp)
  Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("확인","개선 필요","모름").forEach{v->val selected=values[i]==v;Surface(Modifier.weight(1f).height(48.dp).clickable{values=values.mapIndexed{index,old->if(index==i)v else old}},color=if(selected)PaleGreen else CanvasColor,shape=RoundedCornerShape(12.dp),border=BorderStroke(1.dp,if(selected)Forest else Line)){Box(contentAlignment=Alignment.Center){Text(v,fontSize=13.sp,fontWeight=if(selected)FontWeight.Bold else FontWeight.Normal,color=if(selected)Forest else Muted)}}}}
 }}
 SLButton("확인 내용 저장",{onSave(values)},enabled=!busy&&values.none{it.isBlank()},icon="check")
}

@Composable private fun PeerSheet(s:WorkerState,busy:Boolean,onPhoto:((String?)->Unit)->Unit,onSave:(String,String?)->Unit){var observed by remember{mutableStateOf("착용")};var photo by remember{mutableStateOf<String?>(null)}
 Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){Avatar(s.buddyName,56);Column(verticalArrangement=Arrangement.spacedBy(5.dp)){Text(s.buddyName,fontSize=21.sp,fontWeight=FontWeight.Bold,color=Ink);Text(s.buddyZone,fontSize=13.sp,color=Muted)}}
 Text("동료의 보호구를 직접 보고 확인해 주세요.",fontSize=15.sp,fontWeight=FontWeight.SemiBold,color=Ink)
 ChoiceTabs(listOf("착용 확인","개선 필요"),if(observed=="착용")0 else 1){observed=if(it==0)"착용" else "미착용"}
 Text("확인 사진 · 선택",fontSize=13.sp,fontWeight=FontWeight.SemiBold,color=Ink);PhotoCapture(photo){onPhoto{if(it!=null)photo=it}}
 SmallNote("이 화면은 작업 전 보호구 확인이에요. 긴급 현장 안전 확인은 별도 요청에서 도착과 주변 사진을 함께 기록합니다.")
 SLButton(if(photo==null)"버튼으로 확인 저장" else "사진과 확인 저장",{onSave(observed,photo)},enabled=!busy,icon="check")
}

@Composable private fun CallSheet(c:WorkerCall,s:WorkerState,busy:Boolean,onPhoto:((String?)->Unit)->Unit,onRespond:(String,String?)->Unit){var photo by remember(c.id){mutableStateOf<String?>(null)};var safeConfirmed by remember(c.id){mutableStateOf(false)}
 SLPill(if(s.preview)"체험 요청 · 실제 푸시 미전송" else "현장 안전 확인",amber=c.status!="closed")
 Text(if(c.status=="closed")"안전 확인을 마쳤어요." else if(c.isSubject(s.uid))"동료의 확인을\n기다리고 있어요." else "${c.subjectName} 님의\n안전을 확인해 주세요.",fontSize=25.sp,lineHeight=34.sp,fontWeight=FontWeight.Bold,color=Ink)
 DetailRow("작업 구역",c.zone)
 if(c.overdue&&c.status!="closed")SmallNote("사진 확인이 지연되어 책임자에게 확인을 요청했습니다.",amber=true)
 when {
  c.status=="closed"->{SmallNote(if(c.resolution=="peer_photo")"현장 도착과 주변 사진, 동료의 직접 확인이 저장됐어요." else "본인이 괜찮다고 응답한 기록이에요.");c.photo?.let{PhotoPreview(it)}}
  c.isSubject(s.uid)->{Text(if(c.status=="accepted")"동료가 현장 확인을 수락했어요. 실제 위험한 상황이면 아래 119 버튼을 이용하세요." else "본인 응답 시간이 지나 동료의 현장 확인이 필요해요. 주변 동료에게 직접 도움을 요청할 수도 있어요.",fontSize=14.sp,lineHeight=24.sp,color=Muted)}
  c.status=="accepted"&&c.acceptedBy!=s.uid->SmallNote("다른 동료가 현장을 확인하고 있어요. 본인의 안전을 먼저 확보해 주세요.")
  else->{
   val step=if(c.status=="pending")0 else if(c.arrivedAt==null)1 else 2
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("요청 수락","현장 도착","사진 확인").forEachIndexed{i,label->Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)){Box(Modifier.size(31.dp).background(if(i<=step)Forest else CanvasColor,CircleShape),contentAlignment=Alignment.Center){Text("${i+1}",fontSize=12.sp,fontWeight=FontWeight.Bold,color=if(i<=step)Color.White else Muted)};Text(label,fontSize=11.sp,color=if(i<=step)Forest else Muted)}}}
   SmallNote("본인의 안전을 확보한 뒤 접근하세요. 위험이 계속되거나 다친 사람이 있으면 119에 연락하세요.",amber=true)
   if(step==0)SLButton("현장 확인 수락",{onRespond("accepted",null)},enabled=!busy,icon="people")
   if(step==1){Text("작업자와 주변을 직접 볼 수 있는 현장에 도착한 뒤 기록해 주세요.",fontSize=14.sp,lineHeight=23.sp,color=Muted);SLButton("현장에 도착했어요",{onRespond("arrived",null)},enabled=!busy,icon="pin")}
   if(step==2){Text("현장 주변 사진",fontSize=15.sp,fontWeight=FontWeight.Bold,color=Ink);PhotoCapture(photo){onPhoto{if(it!=null)photo=it}};Row(Modifier.fillMaxWidth().clickable{safeConfirmed=!safeConfirmed},verticalAlignment=Alignment.CenterVertically){Checkbox(safeConfirmed,{safeConfirmed=it},colors=CheckboxDefaults.colors(checkedColor=Forest));Text("작업자의 안전을 직접 확인했어요.",fontSize=13.sp,color=Ink)};SLButton("안전 확인 완료 · 사진 제출",{onRespond("verified",photo)},enabled=!busy&&photo!=null&&safeConfirmed,icon="shield")}
  }
 }
}

@Composable private fun EditProfileSheet(s:WorkerState,onSave:(String,String,Int)->Unit){var name by remember{mutableStateOf(s.name)};var zone by remember{mutableStateOf(s.zone)};var wage by remember{mutableStateOf(s.wage.toString())}
 if(s.preview){SLField("이름",name,{name=it});SLField("작업 구역",zone,{zone=it});SLField("예상 일당 · 원",wage,{wage=it.filter(Char::isDigit).take(8)});SmallNote("변경한 예상 일당은 다음 출근 기록부터 적용됩니다.");SLButton("내 작업 정보 저장",{onSave(name,zone,wage.toIntOrNull()?:0)},enabled=name.isNotBlank()&&zone.isNotBlank()&&(wage.toIntOrNull()?:-1) in 0..10000000)}else{DetailRow("이름",s.name);DetailRow("작업 구역",s.zone);SmallNote("연결한 현장의 이름과 작업 구역 변경은 현장 책임자에게 문의해 주세요.")}
}

@Composable private fun ConnectSheet(controller:WorkerController,onFinish:()->Unit){var signUp by remember{mutableStateOf(false)};var name by remember{mutableStateOf(controller.state.name)};var email by remember{mutableStateOf("")};var password by remember{mutableStateOf("")};var code by remember{mutableStateOf("")};var zone by remember{mutableStateOf(controller.state.zone)}
 SmallNote("관리자가 알려준 현장 코드와 본인 계정으로 연결해요. 체험 기록은 실제 현장 기록과 구분됩니다.")
 ChoiceTabs(listOf("로그인","처음 이용해요"),if(signUp)1 else 0){signUp=it==1}
 SLField("이름",name,{name=it});SLField("이메일",email,{email=it},"worker@example.com")
 Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("비밀번호",fontSize=13.sp,fontWeight=FontWeight.SemiBold,color=Ink);OutlinedTextField(password,{password=it},modifier=Modifier.fillMaxWidth(),singleLine=true,visualTransformation=PasswordVisualTransformation(),placeholder={Text("6자 이상",fontSize=14.sp)},shape=RoundedCornerShape(13.dp),colors=OutlinedTextFieldDefaults.colors(unfocusedBorderColor=Line,focusedBorderColor=Forest))}
 SLField("현장 참여 코드",code,{code=it.uppercase()},"관리자가 알려준 코드");SLField("작업 구역",zone,{zone=it},"예: 2층 · 설비 작업")
 if(!controller.firebaseReady)SmallNote("이 빌드에는 현장 연결 설정이 없습니다. 체험 모드에서 앱을 확인할 수 있어요.",amber=true)
 SLButton(if(controller.busy)"현장에 연결하고 있어요…" else "로그인하고 현장 연결",{controller.connect(email,password,name,code,zone,signUp,onFinish)},enabled=!controller.busy&&name.isNotBlank()&&email.isNotBlank()&&password.length>=6&&code.isNotBlank()&&zone.isNotBlank(),icon="link")
}

@Composable private fun SelfCheckDialog(call:WorkerCall,now:Long,busy:Boolean,onSafe:()->Unit,onDial:()->Unit,preview:Boolean){val remaining=call.remainingSeconds(now)
 Dialog(onDismissRequest={},properties=DialogProperties(dismissOnBackPress=false,dismissOnClickOutside=false,usePlatformDefaultWidth=false)){
  Surface(Modifier.fillMaxWidth().padding(20.dp).widthIn(max=440.dp),shape=RoundedCornerShape(24.dp),color=Color.White){Column(Modifier.padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(18.dp)){
   SLPill(if(preview)"체험 · 본인 안전 확인" else "본인 안전 확인",amber=true)
   Box(Modifier.size(60.dp).background(PaleAmber,CircleShape),contentAlignment=Alignment.Center){SLIcon("activity",Modifier.size(31.dp),Amber)}
   Text("괜찮으세요?",fontSize=30.sp,fontWeight=FontWeight.Bold,color=Ink)
   Text("급격한 움직임 변화가 감지됐어요.\n안전한 상태라면 아래 버튼을 눌러 주세요.",fontSize=14.sp,lineHeight=22.sp,color=Muted,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
   Box(Modifier.size(96.dp),contentAlignment=Alignment.Center){CircularProgressIndicator(progress=(remaining/15f).coerceIn(0f,1f),modifier=Modifier.fillMaxSize(),color=Amber,trackColor=PaleAmber,strokeWidth=5.dp);Column(horizontalAlignment=Alignment.CenterHorizontally){Text("$remaining",fontSize=33.sp,fontWeight=FontWeight.Bold,color=Amber);Text("초 남음",fontSize=10.sp,color=Muted)}}
   Text(if(remaining>0)"무응답 시 같은 구역 동료에게 확인을 요청해요." else "응답 시간이 지났어요. 동료 확인을 요청하고 있습니다.",fontSize=12.sp,lineHeight=19.sp,color=Muted)
   SLButton("괜찮아요",onSafe,enabled=!busy&&remaining>0,icon="check")
   EmergencyStrip(onDial)
  }}
 }
}
