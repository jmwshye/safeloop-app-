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
  else->sheet=route
 }}
 LaunchedEffect(controller.feedback){controller.feedback?.let{snackbar.showSnackbar(it);controller.feedback=null}}
 BackHandler(enabled=page!=WorkerPage.TODAY&&sheet==null){navigate(WorkerPage.TODAY)}
 Surface(Modifier.fillMaxSize(),color=CanvasColor){Box(Modifier.fillMaxSize().safeDrawingPadding()){
  Scaffold(containerColor=CanvasColor,contentWindowInsets=WindowInsets(0,0,0,0),snackbarHost={SnackbarHost(snackbar)},topBar={
   Row(Modifier.fillMaxWidth().height(70.dp).padding(horizontal=22.dp),verticalAlignment=Alignment.CenterVertically){
    Box(Modifier.size(36.dp).background(PaleGreen,RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center){SLIcon("link",Modifier.size(22.dp))};Spacer(Modifier.width(9.dp))
    Column(Modifier.weight(1f)){Text("안전고리",fontSize=20.sp,fontWeight=FontWeight.ExtraBold,color=Ink,letterSpacing=(-.7).sp);Text("SAFELOOP",fontSize=8.sp,fontWeight=FontWeight.Bold,color=Muted,letterSpacing=2.sp)}
    Box(Modifier.clickable{navigate(WorkerPage.PROFILE)}){SLPill(if(state.preview)"체험 모드" else "현장 연결",amber=state.preview)}
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
     WorkerPage.RECORDS->RecordsPage(state,actions.export,::open)
     WorkerPage.PROFILE->ProfilePage(state,::open,actions.notificationSettings)
    }
   }}
  }}
 }}
 val activeSheet=sheet
 if(activeSheet!=null){AppSheet(sheetTitle(activeSheet),{sheet=null},actions.dial){
  controller.feedback?.let{SmallNote(it,amber=true)}
  when {
   activeSheet=="attendance"->{SmallNote(if(state.preview)"체험 모드의 출근 기록은 이 기기에 저장됩니다." else "현재 위치로 현장 출근을 확인해요. 위치 권한이 필요할 수 있습니다.");DetailRow("현장",state.site);DetailRow("작업 구역",state.zone);DetailRow("예상 일당","${moneyText(state.wage)}원");DetailRow("현재 시각",clockText(controller.now));Text("안전점검을 아직 마치지 않아도 출근 기록을 남길 수 있어요.",fontSize=13.sp,lineHeight=21.sp,color=Muted);SLButton("출근 기록 저장",{actions.attend();sheet=null},enabled=!controller.busy,icon="check")}
   activeSheet=="checkout"->{SmallNote("오늘도 수고하셨습니다. 퇴근하면 움직임 감시도 종료돼요.");DetailRow("출근 시각",clockText(state.currentShift?.start));DetailRow("퇴근 시각",clockText(controller.now));DetailRow("예상 일당","${moneyText(state.currentShift?.wage?:state.wage)}원");SLButton("퇴근하고 기록 마무리",{controller.checkOut();sheet=null;navigate(WorkerPage.RECORDS)},enabled=!controller.busy,icon="check")}
   activeSheet=="profile"->EditProfileSheet(state){name,zone,wage->controller.saveWorkInfo(name,zone,wage);sheet=null}
   activeSheet=="site"->{SLPill("체험 현장",amber=true);Text(state.site,fontSize=23.sp,fontWeight=FontWeight.Bold,color=Ink);DetailRow("작업 구역",state.zone);SmallNote("체험 기록은 이 기기에 저장됩니다.")}
   activeSheet=="privacy"->{Text("기록의 첫 번째 주인은 작업자입니다.",fontSize=21.sp,fontWeight=FontWeight.Bold,color=Ink);Text("근로 기록과 직접 확인한 사실, 센서의 추정을 구분해 보관해요. 사진은 현장 확인과 위험 조치에 사용합니다.",fontSize=14.sp,lineHeight=24.sp,color=Muted);SmallNote(if(state.preview)"체험 모드의 근로·안전 기록과 사진은 이 기기에만 저장돼요. 실제 현장에 전송되지 않습니다." else "현장 연결 모드에서는 필요한 확인 상태를 같은 현장의 동료와 책임자에게 공유해요.");Text("위치는 출근 확인에 사용하며 상시 추적하지 않습니다. 119 버튼은 전화 화면을 열며 실제 통화 연결이나 신고 접수를 증명하지 않아요.",fontSize=14.sp,lineHeight=24.sp,color=Muted)}
   activeSheet=="guide"->{listOf("01" to "휴대폰을 몸에 휴대해 주세요.","02" to "출근 후 필요한 작업 시간에 움직임 감시를 시작하세요.","03" to "충격과 이후 움직임 감소는 위험의 추정 신호예요. 모든 사고를 감지하지는 못합니다.","04" to "알림이 오면 상태를 확인하세요. 동료 요청을 받았다면 본인 안전을 먼저 확보하세요.","05" to "실제 위험한 상황이면 안전 확인 완료를 누르기보다 119에 연락하세요.").forEach{(n,text)->Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){Text(n,fontSize=12.sp,fontWeight=FontWeight.Bold,color=Forest);Text(text,fontSize=14.sp,lineHeight=23.sp,color=Ink)}};SmallNote("감시 중에는 배터리를 사용합니다. 잠금·절전·기기별 센서 동작은 실제 Android 기기로 확인해야 해요.",amber=true)}
   activeSheet=="reset"->{Text("이 기기의 체험 기록을 초기화할까요?",fontSize=20.sp,fontWeight=FontWeight.Bold,color=Ink);Text("추가한 체험용 출퇴근·점검·신고·안전 확인 기록이 초기화됩니다. 실제 서버 기록에는 영향을 주지 않아요.",fontSize=14.sp,lineHeight=23.sp,color=Muted);SLButton("체험 기록 초기화",{controller.resetPreview();sheet=null})}
  }
 }}
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

@Composable private fun EditProfileSheet(s:WorkerState,onSave:(String,String,Int)->Unit){var name by remember{mutableStateOf(s.name)};var zone by remember{mutableStateOf(s.zone)};var wage by remember{mutableStateOf(s.wage.toString())}
 if(s.preview){SLField("이름",name,{name=it});SLField("작업 구역",zone,{zone=it});SLField("예상 일당 · 원",wage,{wage=it.filter(Char::isDigit).take(8)});SmallNote("변경한 예상 일당은 다음 출근 기록부터 적용됩니다.");SLButton("내 작업 정보 저장",{onSave(name,zone,wage.toIntOrNull()?:0)},enabled=name.isNotBlank()&&zone.isNotBlank()&&(wage.toIntOrNull()?:-1) in 0..10000000)}else{DetailRow("이름",s.name);DetailRow("작업 구역",s.zone);SmallNote("연결한 현장의 이름과 작업 구역 변경은 현장 책임자에게 문의해 주세요.")}
}

