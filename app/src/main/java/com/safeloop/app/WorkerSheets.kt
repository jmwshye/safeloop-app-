package com.safeloop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val WarnInk=Color(0xFF5E3A00)

// 1-3 작업 전 3가지
@Composable fun ChecklistSheet(s:WorkerState,busy:Boolean,onSave:(List<String>)->Unit){var values by remember(s.checks){mutableStateOf(s.checks)}
 ScreenHead("3가지만 확인해요")
 listOf("1. 추락 위험 구역을 확인했어요","2. 바닥·개구부·안전난간 상태","3. 안전모·턱끈·안전대 착용").forEachIndexed{i,title->SLCard{
  Text(title,fontSize=17.sp,fontWeight=FontWeight.ExtraBold,color=Ink)
  Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("확인","개선 필요","모름").forEach{v->val on=values[i]==v
   Box(Modifier.weight(1f).height(46.dp).background(if(!on)Color(0xFFEEF2F7) else if(v=="확인")Ok else WarningYellow,RoundedCornerShape(12.dp)).clickable{values=values.mapIndexed{index,old->if(index==i)v else old}},contentAlignment=Alignment.Center){Text(v,fontSize=15.sp,fontWeight=FontWeight.Bold,color=if(!on)Muted else if(v=="확인")Color.White else Ink)}
  }}
 }}
 SLCard(color=PaleAmber){Text("'모름'을 고르면 반장님께 확인 요청이 가요. 솔직하게 골라도 불이익이 없어요.",fontSize=14.sp,lineHeight=22.sp,color=WarnInk)}
 SLButton("저장",{onSave(values)},enabled=!busy&&values.none{it.isBlank()})
}

// 1-4 안전 동료, 1-6 불일치, 1-7 교차 인증 완료. 촬영은 1-5 앱 카메라에서 한다.
@Composable fun PeerSheet(s:WorkerState,busy:Boolean,actions:WorkerActions,discardPhoto:(String?)->Unit,onDone:()->Unit,onSave:(String,String?)->Unit){var observed by rememberSaveable{mutableStateOf("")};var photo by rememberSaveable{mutableStateOf<String?>(null)}
 val subject=s.buddyAttendanceId ?: if(s.preview)"preview-buddy" else ""
 val ready=!busy&&(s.preview||s.buddyAttendanceId!=null)
 OnCameraResult(actions,"peer_check",subject){result->
  discardPhoto(photo)
  observed=if(result.mismatch)"미착용" else "착용"
  photo=result.path
 }
 val capture={actions.capture("peer_check",subject)}
 when{
  s.peerChecked->PeerDone(s,onDone)
  observed=="착용"&&photo!=null->{
   ScreenHead("이 사진으로\n저장할까요?",s.buddyName+" 님의 안전모·턱끈·안전대가 보이면 충분해요.")
   PhotoPreview(reviewPathOf(photo!!))
   NoteText("기록에 저장할 때 얼굴은 자동으로 가려요.")
   SLButton("사진과 확인 저장",{onSave("착용",photo)},enabled=ready,icon="check")
   SLButton("다시 찍기",capture,enabled=ready,secondary=true,icon="camera")
  }
  observed=="미착용"&&s.peerStatus!="mismatch"->{
   ScreenHead("말한 것과\n달라 보여요","불일치로 기록하고 고친 뒤 다시 찍어요. 달라 보이는 모습은 사진으로 남기지 않아요.")
   SLButton("불일치 기록",{onSave("미착용",null);observed=""},enabled=ready,icon="check")
   GhostButton("취소",{observed=""})
  }
  s.peerStatus=="mismatch"->{
   SLPill("불일치 · 개선 필요",amber=true)
   ScreenHead("말한 것과\n달라 보여요","고친 뒤에 다시 찍으면 확인이 끝나요.")
   FlatBox{StepRow("1. 보호구를 고쳐요"){SLPill("지금",amber=true)};HorizontalDivider(color=Line);StepRow("2. 다시 찍어요"){SLPill("대기",neutral=true)}}
   NoteText("달라 보이는 모습은 사진으로 남기지 않아요. 기록에는 '불일치 → 조치 완료'만 남아요.")
   SLButton("고쳤어요, 다시 찍기",capture,enabled=ready,icon="camera")
  }
  else->{
   val mine=s.peerEvidence.any{it.checkerId==s.uid};val theirs=s.peerEvidence.any{it.checkerId!=s.uid}
   ScreenHead("서로 한 장씩\n찍어요","내가 찍고, 동료가 나를 찍으면 확인이 끝나요.")
   SLCard{Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){Avatar(s.buddyName,46);Column(Modifier.weight(1f)){Text(s.buddyName,fontSize=19.sp,fontWeight=FontWeight.ExtraBold,color=Ink);Text("오늘의 안전 동료 · ${s.buddyZone}",fontSize=14.sp,color=Muted)};SLPill(peerLabel(s.peerStatus),amber=true)}}
   FlatBox{StepRow("1. 내가 ${s.buddyName} 님을 찍어요"){if(mine)SLPill("완료",fact=true)else SLPill("대기",neutral=true)};HorizontalDivider(color=Line);StepRow("2. ${s.buddyName} 님이 나를 찍어요"){if(theirs)SLPill("완료",fact=true)else SLPill("대기",neutral=true)}}
   NoteText("얼굴은 안 나와도 돼요. 안전모, 턱끈, 안전대가 보이면 충분해요.")
   if(!s.preview&&s.buddyAttendanceId==null)SmallNote("동료가 출근하면 찍을 수 있어요.",amber=true)
   if(mine)SLButton("작업 화면으로",onDone,secondary=true)
   else{
    SLButton("${s.buddyName} 님 찍기",capture,enabled=ready,icon="camera")
    SLButton("사진 없이 확인하기",{onSave("착용",null)},enabled=ready,secondary=true)
    GhostButton("보호구가 달라 보여요",{observed="미착용"},enabled=ready)
   }
  }
 }
}
@Composable private fun PeerDone(s:WorkerState,onDone:()->Unit){
 SLPill("사람이 확인",fact=true)
 Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){DoneMark();Text("서로 확인했어요",fontSize=26.sp,fontWeight=FontWeight.ExtraBold,color=Ink);Text("오늘도 안전하게 시작해요.",fontSize=16.sp,color=Muted)}
 FlatCard(buildList{
  s.peerEvidence.forEach{check->add((if(check.checkerId==s.uid)"${s.name} → ${s.buddyName}" else "${s.buddyName} → ${s.name}") to (if(s.preview&&check.checkerId!=s.uid)"체험 예시" else if(check.photoId!=null)"사진" else "진술"))}
  s.peerEvidence.maxOfOrNull{it.checkedAt}?.let{add("확인 시각" to clockText(it))}
  add("위치" to if(s.preview)"현장 안 · 체험 예시" else locationLabel(s.locationStatus))
  add("사진" to if(s.peerStatus=="photo_confirmed")"저장됨" else "없음")
 })
 s.peerEvidence.firstNotNullOfOrNull{it.photo}?.let{PhotoPreview(it)}
 NoteText("사진 없이 확인하면 '진술만 있는 확인'으로 표시돼요. 불이익은 없어요.")
 SLButton("작업 시작",onDone)
}

// 2-2 고위험 작업
@Composable fun HighRiskSheet(s:WorkerState,busy:Boolean,onSave:(String?)->Unit){
 var task by remember{mutableStateOf(s.highRiskTask.orEmpty())}
 if(s.highRiskTask==null){
  ScreenHead("고위험 작업을\n시작해요")
  Text("어떤 작업이에요?",fontSize=14.sp,fontWeight=FontWeight.Bold,color=Muted)
  listOf("scaffold","ladder","opening","aerial_lift").forEach{value->RadioRow(highRiskLabel(value),task==value){task=value}}
  SLCard(color=BrandSoft){Text("이 작업을 하는 동안",fontSize=16.sp,fontWeight=FontWeight.ExtraBold,color=Ink);Text("· 움직임 감시가 더 민감해져요\n· 안전 동료에게 상태가 공유돼요\n· 끝내기 전까지만 켜져 있어요",fontSize=14.sp,lineHeight=22.sp,color=Ink)}
  NoteText("작업 중에는 폰을 보지 않아도 돼요. 확인 요청은 작업 전후에만 와요.")
  SLButton("고위험 작업 시작",{onSave(task)},enabled=!busy&&task.isNotEmpty())
 }else{
  ScreenHead("고위험 작업을\n끝낼까요?")
  FlatCard(listOf("진행 중" to highRiskLabel(s.highRiskTask),"시작 시각" to clockText(s.highRiskStartedAt)))
  SLButton("고위험 작업 끝내기",{onSave(null)},enabled=!busy)
 }
}

// 2-3 체크인 요청
@Composable fun PromptSheet(prompt:WorkPrompt?,busy:Boolean,onAnswer:(String)->Unit){
 if(prompt==null){SmallNote("현재 출근의 확인 요청을 찾을 수 없어요. 이미 종료되었거나 연결 중일 수 있어요.");return}
 val reason=when(prompt.kind){"high_risk_start"->"고위험 작업 시작";"high_risk_end"->"고위험 작업 종료";"break_return"->"휴식 복귀";"shift_change"->"교대";else->"작업 중 확인"}
 SLPill("${clockText(prompt.dueAt)} $reason",brand=true)
 SLCard(color=BrandSoft){Text("안전하신가요?",fontSize=30.sp,fontWeight=FontWeight.ExtraBold,color=Ink);Text("$reason 때라 한 번만 물어요.",fontSize=16.sp,lineHeight=25.sp,color=Ink)}
 if(prompt.state=="pending"){SLButton("괜찮아요",{onAnswer("okay")},enabled=!busy,color=Ok,large=true);SLButton("도움이 필요해요",{onAnswer("help_needed")},enabled=!busy,color=Danger)}
 else SmallNote("응답이 없어서 안전 미확인으로 남았어요.",amber=true)
 NoteText("답하지 않으면 사고가 아니라 '안전 미확인'으로 기록돼요.",center=true)
}

// 3-2 응답 완료
@Composable fun AckSheet(call:WorkerCall?,onDone:()->Unit){
 val closed=call?.status=="closed"
 SLPill("사람이 확인",fact=true)
 Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){
  DoneMark();Text("괜찮다고 기록했어요",fontSize=26.sp,fontWeight=FontWeight.ExtraBold,color=Ink)
  Text(if(closed)"폰이 흔들린 일로 보고 오탐으로 남겼어요.\n사진은 필요 없어요." else "본인 응답을 남겼어요.\n이미 시작한 동료 호출과 현장 대응은 계속돼요.",fontSize=16.sp,lineHeight=25.sp,color=Muted,textAlign=TextAlign.Center)
 }
 FlatCard(listOf("충격 감지" to clockText(call?.createdAt),"내 응답" to clockText(call?.respondedAt),"기록" to if(closed)"오탐" else "대응 계속"))
 SLButton("작업으로 돌아가기",onDone)
}

// 3-3 ~ 3-7 긴급 대응. 상태마다 화면을 하나만 보여준다.
@Composable fun CallSheet(c:WorkerCall,s:WorkerState,now:Long,busy:Boolean,actions:WorkerActions,onDial:()->Unit,onFailed:()->Unit,onRespond:(String,String?)->Unit){
 var photo by rememberSaveable(c.id){mutableStateOf<String?>(null)}
 OnCameraResult(actions,"scene",c.id){photo=it.path}
 when{
  c.status=="closed"->CallClosed(c,s)
  c.isSubject(s.uid)->CallingPeers(c,s,now,busy,onDial,actions.phone,onFailed,{actions.record(c.id)},onRespond)
  c.acceptedBy!=null&&c.acceptedBy!=s.uid->{ScreenHead("다른 동료가\n확인 중이에요","본인의 안전을 먼저 확보해 주세요.");FlatCard(listOf("작업자" to c.subjectName,"위치" to c.zone))}
  c.acceptedBy==null->IncomingCall(c,s,now,busy,actions,onFailed,onRespond)
  c.arrivedAt==null->GoingToCall(c,s,busy,actions,onRespond)
  c.observation==null->ArrivedAtCall(c,busy,onDial,onRespond)
  else->WrapUpCall(c,busy,photo,actions,onRespond)
 }
}
@Composable private fun CallingPeers(c:WorkerCall,s:WorkerState,now:Long,busy:Boolean,onDial:()->Unit,onPhone:(String)->Unit,onFailed:()->Unit,onRecord:()->Unit,onRespond:(String,String?)->Unit){
 SLPill("안전 미확인",amber=true,estimate=true)
 // 촬영 중임을 알리고 바로 끌 수 있어야 하므로 맨 위에 둔다.
 if(SituationRecorder.active){SLPill("영상 녹화 중 · 소리 없음",danger=true);GhostButton("녹화 끄기",{SituationRecorder.stop()})}
 if(SafetyAlarm.beaconOn)GhostButton("사이렌·손전등 끄기",{SafetyAlarm.stop()})
 ScreenHead("동료를 부르고 있어요","안전 동료와 반장님께 알렸어요. 움직일 수 있으면 119를 누르세요.")
 FlatBox{
  if(s.preview){CallRow(s.buddyName,"안전 동료","체험");HorizontalDivider(color=Line);CallRow("반장님","현장 책임자 · 동시 통보","체험");HorizontalDivider(color=Line);val wait=((c.deadline+30_000-now+999)/1000).coerceAtLeast(0);CallRow("같은 구역 동료",c.zone,if(wait>0)"${wait}초 뒤" else "체험")}
  else if(c.recipients.isEmpty())StepRow("호출 대상을 확인하고 있어요"){SLPill("대기",neutral=true)}
  else c.recipients.forEachIndexed{i,recipient->if(i>0)HorizontalDivider(color=Line);CallRow(recipient.name,recipientRole(recipient.role),recipientStatus(recipient))}
 }
 SituationVideo(c.id)
 if(s.preview)NoteText("체험 모드라 실제로 전송되지 않아요.")
 if(c.acceptedBy!=null)SmallNote("동료가 확인하러 오고 있어요.")
 if(c.serverStatus=="unanswered"){SmallNote("수락한 사람이 없어요. 주변 동료나 반장에게 직접 연락하세요.",amber=true);SLButton("동료에게 다시 요청",{onRespond("retry",null)},enabled=!busy,secondary=true)}
 SLButton("119 전화",onDial,color=Danger,large=true,icon="phone")
 SLButton("괜찮아요, 제가 응답할게요",{onRespond("safe",null)},enabled=!busy,secondary=true)
 if(c.workerResponse=="okay")GhostButton("도움이 필요해요",{onRespond("help",null);onRecord()},enabled=!busy)
 s.foremanPhone?.let{phone->SLButton("반장님께 전화",{onPhone(phone)},secondary=true,icon="phone")}
 if(s.preview)GhostButton("체험: 호출 실패 화면 보기",onFailed)
}
@Composable private fun CallRow(name:String,role:String,status:String){Row(Modifier.fillMaxWidth().padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){Column(Modifier.weight(1f)){Text(name,fontSize=16.sp,fontWeight=FontWeight.ExtraBold,color=Ink);Text(role,fontSize=14.sp,color=Muted)};SLPill(status,neutral=status!="수락")}}
private fun recipientRole(role:String)=when(role){"buddy"->"안전 동료";"foreman"->"현장 책임자";else->"같은 구역 동료"}
private fun recipientStatus(r:CallRecipient)=when{r.declinedAt!=null->"참여 어려움";r.acceptedAt!=null->"수락";r.openedAt!=null->"열람";r.receivedAt!=null->"알림 도착";r.delivery=="sent"->"발송 · 수신 미확인";r.delivery=="unavailable"->"발송 불가";r.delivery=="failed"->"발송 실패";r.delivery=="cancelled"->"발송 취소";else->"발송 대기"}

@Composable private fun IncomingCall(c:WorkerCall,s:WorkerState,now:Long,busy:Boolean,actions:WorkerActions,onFailed:()->Unit,onRespond:(String,String?)->Unit){
 val invited=s.preview||c.recipients.any{it.id==s.uid&&it.declinedAt==null};val context=LocalContext.current
 DisposableEffect(c.id){SafetyAlarm.start(context,10_000,"${c.subjectName} 님이 응답하지 않아요. 내 안전을 먼저 확보하세요.");onDispose{SafetyAlarm.stop()}}
 Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){SLPill(if(s.preview)"체험 호출" else "긴급 호출",danger=true);if(c.deadline>now)SLPill("${c.remainingSeconds(now)}초 안에 응답",amber=true)}
 ScreenHead("${c.subjectName} 님이\n응답하지 않아요")
 FlatBox{
  DetailPad("위치",c.zone+(c.accuracy?.let{" · 오차 약 ${it.toInt()}m"}?:""));HorizontalDivider(color=Line)
  DetailPad("충격 감지",clockText(c.createdAt.takeIf{it>0}));HorizontalDivider(color=Line)
  StepRow("기록"){SLPill("시스템이 추정",amber=true,estimate=true)}
 }
 if(c.latitude==null&&!s.preview)SmallNote("위치를 확인할 수 없어요. 구역 정보와 직접 연락으로 확인해 주세요.",amber=true)
 if(c.latitude!=null&&c.longitude!=null)SLButton("지도에서 위치 보기",{actions.map(c.latitude,c.longitude)},secondary=true,icon="pin")
 SLCard(color=PaleAmber){Text("내 안전을 먼저 확보하세요",fontSize=17.sp,fontWeight=FontWeight.ExtraBold,color=Ink);Text("무리하게 접근하지 마세요. 위험하면 119에 먼저 연락하세요.",fontSize=14.sp,lineHeight=22.sp,color=WarnInk)}
 if(c.serverStatus=="unanswered")SmallNote("수락한 사람이 없어요. 주변 동료나 반장에게 직접 연락하세요. 이미 초대된 동료는 계속 수락할 수 있어요.",amber=true)
 SLButton("확인하러 갈게요",{onRespond("accepted",null)},enabled=!busy&&invited,large=true)
 SLButton("지금 갈 수 없어요",{onRespond("declined",null);onFailed()},enabled=!busy&&invited,secondary=true)
}
@Composable private fun GoingToCall(c:WorkerCall,s:WorkerState,busy:Boolean,actions:WorkerActions,onRespond:(String,String?)->Unit){
 SLPill("수락했어요 ${clockText(c.acceptedAt)}")
 ScreenHead("${c.subjectName} 님에게\n가는 중이에요")
 if(c.latitude!=null&&c.longitude!=null){IncidentDirections(c,actions.locate);SLButton("지도에서 위치 보기",{actions.map(c.latitude,c.longitude)},secondary=true,icon="pin")}
 else if(s.preview)SiteSketch(c.subjectName)
 else SmallNote("위치를 확인할 수 없어요. 구역 정보와 직접 연락으로 확인해 주세요.",amber=true)
 FlatCard(listOf("위치" to c.zone,"책임자" to if(s.preview)"체험 · 실제 전송 안 됨" else c.observationNotice?:"알림 전달 확인 중"))
 SLButton("도착했어요",{onRespond("arrived",null)},enabled=!busy,large=true)
 s.foremanPhone?.let{phone->SLButton("반장님께 전화",{actions.phone(phone)},secondary=true,icon="phone")}
}
@Composable private fun ArrivedAtCall(c:WorkerCall,busy:Boolean,onDial:()->Unit,onRespond:(String,String?)->Unit){
 var condition by rememberSaveable(c.id){mutableStateOf("")}
 SLButton("119 전화",onDial,color=Danger,large=true,icon="phone")
 ScreenHead("${c.subjectName} 님의\n상태를 확인해요")
 listOf("unconscious" to "의식이 없어요","immobile" to "움직일 수 없어요","conscious" to "의식은 있어요","appears_safe" to "괜찮아 보여요").forEach{(value,label)->RadioRow(label,condition==value){condition=value}}
 SLButton("반장님께 상태 알리기",{onRespond("observe:$condition",null)},enabled=!busy&&condition.isNotEmpty())
 NoteText("지금은 사진을 찍지 않아도 돼요. 상태 확인과 119가 먼저예요.")
}
@Composable private fun WrapUpCall(c:WorkerCall,busy:Boolean,photo:String?,actions:WorkerActions,onRespond:(String,String?)->Unit){
 var reason by rememberSaveable(c.id){mutableStateOf("onsite_action_completed")}
 SLPill("사람이 확인",fact=true)
 ScreenHead("대응을 마무리해요")
 FlatCard(listOf("도착" to clockText(c.arrivedAt),"119 전화 화면 열림" to (c.dialTimes.lastOrNull()?.let(::clockText)?:"기록 없음"),"반장님 알림" to (c.observationNotice?:"전달 미확인")))
 listOf("onsite_action_completed" to "현장 조치 완료","no_help_needed" to "도움 불필요 확인","handoff_confirmed" to "구조대·반장에게 인계").forEach{(value,label)->RadioRow(label,reason==value){reason=value}}
 SLCard{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text("현장 사진",fontSize=17.sp,fontWeight=FontWeight.ExtraBold,color=Ink);SLPill("선택",neutral=true)};PhotoCapture(photo,"사고 지점을 찍어요","사람이 아니라 사고 지점과 설비를 찍어요."){actions.capture("scene",c.id)};NoteText("상황이 안정된 뒤에만 찍어요.")}
 NoteText("119 전화 화면을 연 기록이에요. 실제로 통화가 연결됐는지는 앱이 알 수 없어요.")
 SLButton(if(photo==null)"사진 없이 마무리" else "마무리",{onRespond("finish:$reason",photo)},enabled=!busy,icon="check")
}
@Composable private fun CallClosed(c:WorkerCall,s:WorkerState){
 if(!c.detailLoaded){SmallNote("종료된 요청의 세부 내용을 불러오고 있어요.");return}
 SLPill("안전 확인 완료",fact=true)
 ScreenHead("${c.subjectName} 님\n대응을 마쳤어요")
 FlatCard(buildList{
  add("위치" to c.zone);add("발생 시각" to clockText(c.createdAt.takeIf{it>0}))
  c.arrivedAt?.let{add("도착 시각" to clockText(it))}
  add("마무리" to when(c.resolution){"worker_confirmed_okay"->"본인이 괜찮다고 응답";"no_help_needed"->"도움 불필요 확인";"handoff_confirmed"->"구조대·반장에게 인계";"onsite_action_completed"->"현장 조치 완료";else->"종료"})
  c.observation?.let{add("직접 확인한 상태" to (mapOf("unconscious" to "의식 없음","immobile" to "움직이기 어려움","conscious" to "의식 있음","appears_safe" to "안전해 보임")[it]?:it))}
 })
 c.recipients.forEach{DetailRow(it.name,recipientStatus(it))}
 if(c.isSubject(s.uid))SituationVideo(c.id)
 if(c.photoId==null&&c.photo==null)SmallNote("현장 사진 없음 · 사진은 선택 항목") else c.photo?.let{PhotoPreview(it)}
 if(s.preview)NoteText("이 기기의 체험 기록이에요. 실제 사고나 신고를 입증하지 않아요.")
}

// 3-8 호출 실패
@Composable fun CallFailedSheet(foremanPhone:String?,busy:Boolean,onDial:()->Unit,onPhone:(String)->Unit,onRetry:()->Unit){
 ScreenHead("호출이 전달됐는지\n알 수 없어요")
 SLCard(color=DangerSoft){Text("이런 경우예요",fontSize=17.sp,fontWeight=FontWeight.ExtraBold,color=Ink);Text("· 서버에 연결하지 못했어요\n· 수락한 동료가 아직 없어요",fontSize=14.sp,lineHeight=22.sp,color=Ink)}
 SLCard{Text("직접 연락해 주세요",fontSize=17.sp,fontWeight=FontWeight.ExtraBold,color=Ink);Text("1. 가까이 있다면 크게 불러요\n2. 반장님께 전화해요\n3. 위험하면 바로 119예요",fontSize=14.sp,lineHeight=22.sp,color=Ink)}
 SLButton("119 전화",onDial,color=Danger,large=true,icon="phone")
 foremanPhone?.let{phone->SLButton("반장님께 전화",{onPhone(phone)},secondary=true,icon="phone")}
 GhostButton("다시 시도",onRetry,enabled=!busy)
}

// 4-3 위험 상세
@Composable fun RiskDetailSheet(risk:WorkerRisk,c:WorkerController,actions:WorkerActions){
 var after by rememberSaveable(risk.id){mutableStateOf<String?>(null)}
 OnCameraResult(actions,"risk_after",risk.id){after=it.path}
 SLPill(when(risk.status){"verified"->"재확인 완료";"resolved"->"재확인 대기";else->"미조치"},amber=risk.status=="open",brand=risk.status=="resolved")
 ScreenHead(risk.title)
 NoteText(listOfNotNull(risk.category,risk.zone,risk.fixedAt?.let{"조치까지 ${((it-risk.time)/60000).coerceAtLeast(0)}분 걸렸어요"}).joinToString(" · "))
 if(risk.note.isNotBlank())Text(risk.note,fontSize=15.sp,lineHeight=23.sp,color=Ink)
 FlatBox{
  TimelineRow(clockText(risk.time),"신고"){risk.photo?.let{PhotoPreview(it)}?:NoteText("등록된 사진 없음")}
  risk.fixedAt?.let{TimelineRow(clockText(it),"조치 완료"+(risk.fixerName?.let{name->" · $name"}?:"")){risk.afterPhoto?.let{photo->PhotoPreview(photo)}}}
  if(risk.status=="verified")TimelineRow(clockText(risk.verifiedAt),"재확인 완료"+(risk.verifierName?.let{" · $it"}?:""))
  if(risk.status=="resolved")TimelineRow("대기","다른 동료의 재확인","신고한 사람과 조치한 사람은 확인할 수 없어요",pending=true)
 }
 if(risk.status=="open"){PhotoCapture(after,"조치 후 사진 찍기","고친 곳이 잘 보이게 찍어 주세요."){actions.capture("risk_after",risk.id)};SLButton("조치 완료 기록",{after?.let{c.fixRisk(risk.id,it)}},enabled=!c.busy&&after!=null)}
 if(risk.status=="resolved"){
  if(c.state.preview)GhostButton("체험: 동료가 재확인한 모습 보기",{c.simulateRecheck(risk.id)},enabled=!c.busy)
  else SLButton("재확인하기",{c.recheckRisk(risk.id)},enabled=!c.busy&&risk.canRecheck)
 }
}

// 5-1 퇴근
@Composable fun CheckoutSheet(s:WorkerState,now:Long,busy:Boolean,onCheckout:()->Unit){
 val shift=s.currentShift
 ScreenHead("오늘도\n수고했어요")
 if(shift!=null)SLCard(color=BrandSoft){
  Text("${clockText(shift.start)} ~ ${clockText(now)}",fontSize=14.sp,fontWeight=FontWeight.Bold,color=Muted)
  Text(durationText(shift.start,now),fontSize=30.sp,fontWeight=FontWeight.ExtraBold,color=Ink)
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text("예상 일당",fontSize=14.sp,color=Muted);Text(if(shift.wageKnown)"${moneyText(shift.wage)}원" else "미입력",fontSize=22.sp,fontWeight=FontWeight.ExtraBold,color=Ink)}
  Text("실제 지급액과 다를 수 있어요.",fontSize=14.sp,color=Muted)
 }
 SLCard{
  Text("오늘 한 일",fontSize=17.sp,fontWeight=FontWeight.ExtraBold,color=Ink)
  Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){if(s.checkCount==3)SLPill("3가지 확인",fact=true) else SLPill("3가지 확인 ${s.checkCount}/3",neutral=true);if(s.peerChecked)SLPill("서로 찍기",fact=true) else SLPill("서로 찍기 안 함",neutral=true)}
 }
 NoteText("퇴근하면 움직임 감시도 꺼져요.")
 SLButton("퇴근하기",onCheckout,enabled=!busy)
}

// 5-4 기록 내보내기
@Composable fun ExportSheet(s:WorkerState,previewExport:(String,String,String,String?)->Unit,share:(String,String)->Unit,more:()->Unit,onReport:(String)->Unit){
 var from by remember{mutableStateOf(java.time.YearMonth.now(koreaZone).atDay(1).toString())};var to by remember{mutableStateOf(todayKey())};var format by remember{mutableStateOf("csv")};var period by remember{mutableIntStateOf(0)}
 ScreenHead("내 기록을\n내보내요")
 if(s.preview)SmallNote("이 기기에 저장한 체험 기록을 내보내요.")
 Text("기간",fontSize=14.sp,fontWeight=FontWeight.Bold,color=Muted)
 PickRow(listOf("이번 달","지난달","직접 고르기"),period){period=it;if(it<2){val month=java.time.YearMonth.now(koreaZone).minusMonths(it.toLong());from=month.atDay(1).toString();to=if(it==0)todayKey() else month.atEndOfMonth().toString()}}
 if(period==2){SLField("시작 날짜",from,{from=it},"YYYY-MM-DD");SLField("종료 날짜",to,{to=it},"YYYY-MM-DD")}
 Text("형식",fontSize=14.sp,fontWeight=FontWeight.Bold,color=Muted)
 RadioRow("엑셀용 파일 (CSV)",format=="csv"){format="csv"}
 RadioRow("PDF",format=="pdf"){format="pdf"}
 SLCard(color=BrandSoft){Text("이렇게 쓸 수 있어요",fontSize=16.sp,fontWeight=FontWeight.ExtraBold,color=Ink);Text("고용보험 근로내역과 비교해서 빠진 날을 찾고, 사업주에게 정정을 요청할 때 근거로 쓸 수 있어요.",fontSize=14.sp,lineHeight=22.sp,color=Ink)}
 val valid=runCatching{val start=java.time.LocalDate.parse(from);val end=java.time.LocalDate.parse(to);!end.isBefore(start)&&java.time.temporal.ChronoUnit.DAYS.between(start,end)<=365}.getOrDefault(false)
 SLButton("내보내기",{if(s.preview)previewExport(from,to,format,null)else share("/reports/work?from=$from&to=$to&format=$format",format)},enabled=valid,icon="download")
 if(s.preview){
  val month=java.time.YearMonth.now(koreaZone).toString();val summary=monthlySummary(s,month)
  SectionHeading("월간 근로일·안전확인 집계")
  FlatCard(listOf("근로일" to "${summary.workDays}일","작업 전 확인" to "${summary.checklists}회","동료 교차 확인" to "${summary.peerChecks}회","위험 신고" to "${summary.risks}건","안전 확인 요청" to "${summary.safetyCalls}건"))
  NoteText("안전 확인 요청은 사고 건수가 아니에요.")
  SLButton("월간 집계 PDF",{previewExport(month,month,"pdf","monthly")},secondary=true,icon="download")
 }
 SectionHeading("사고 경위 보고서")
 val mine=s.calls.filter{it.isSubject(s.uid)&&(s.preview||it.id in s.reportCallIds)}
 if(mine.isEmpty())NoteText("현재 조회한 달의 본인 사고 기록이 없어요.")
 mine.forEach{call->MenuRow("records","${call.subjectName} · ${clockText(call.createdAt)}",callStatus(call,s.uid)){onReport(call.id)}}
 if(s.nextIncidents!=null)SLButton("사고 기록 더 보기",more,secondary=true)
}

// 5-5 사고 경위 보고서. 연결 모드는 서버 원본을 쓰는 IncidentReportSheet가 맡는다.
@Composable fun PreviewReportSheet(c:WorkerCall,s:WorkerState,onPdf:()->Unit){
 ScreenHead("사고 경위 보고서","체험 기록이에요. 실제 사고나 신고를 입증하지 않아요.")
 FlatCard(listOf("작업자" to "${c.subjectName} · ${s.job}","현장" to s.site,"사고 의심 시각" to clockText(c.createdAt.takeIf{it>0}),"자료 구분" to "체험 기록"))
 TimelineLegend()
 FlatBox{
  s.peerEvidence.firstOrNull()?.let{TimelineRow(clockText(it.checkedAt),"보호구 교차 확인","${s.buddyName} 님과")}
  if(c.createdAt>0)TimelineRow(clockText(c.createdAt),"강한 충격 감지",impactDetail(c),estimate=true)
  if(c.stage=="peer"&&c.respondedAt==null)TimelineRow(clockText(c.deadline),"본인 무응답 · 동료 호출","시스템 기록",estimate=true)
  c.respondedAt?.let{TimelineRow(clockText(it),"본인 응답","사람이 확인")}
  c.acceptedAt?.let{TimelineRow(clockText(it),"동료 수락","사람이 확인")}
  c.arrivedAt?.let{TimelineRow(clockText(it),"현장 도착","사람이 확인")}
  c.dialTimes.lastOrNull()?.let{TimelineRow(clockText(it),"119 전화 화면 열림","통화 연결 여부는 별도 확인 필요")}
 }
 c.photo?.let{PhotoPreview(it)}
 SituationVideo(c.id)
 Surface13("본 보고서는 시스템에 수집된 사실과 사용자 입력을 구분하여 정리한 자료이며, 법률상 의무 이행 여부 또는 사고 원인에 관한 판단을 대신하지 않습니다.")
 NoteText("원본 사건 ID ${c.id.take(8)} · 정정 이력 없음")
 SLButton("PDF 만들기",onPdf,icon="download")
}
@Composable private fun Surface13(text:String){Box(Modifier.fillMaxWidth().background(Color(0xFFEEF2F7),RoundedCornerShape(18.dp)).padding(horizontal=14.dp,vertical=12.dp)){Text(text,fontSize=13.sp,lineHeight=20.sp,color=Muted)}}

// 6-2 기록과 개인정보
@Composable fun PrivacySheet(preview:Boolean){
 ScreenHead("기록은\n내 것이에요")
 FlatBox{listOf("근로 기록" to "출퇴근, 근무 시간, 예상 일당","확인 기록과 사진" to "동료와 서로 찍은 사진, 위험 신고","위치" to "출근 확인과 긴급 상황에서만 써요","움직임 센서" to "이상 신호 전후의 짧은 구간만 저장해요","마이크" to "말로 답하고 신고할 때만 듣고, 녹음은 저장하지 않아요","상황 영상" to "도움을 요청하면 소리 없이 30초 찍어 이 폰에만 저장해요","사진 속 얼굴" to "폰 안에서 자동으로 가리고 밖으로 보내지 않아요").forEachIndexed{i,(title,detail)->if(i>0)HorizontalDivider(color=Line);Column(Modifier.padding(vertical=12.dp)){Text(title,fontSize=16.sp,fontWeight=FontWeight.ExtraBold,color=Ink);Text(detail,fontSize=14.sp,lineHeight=22.sp,color=Muted)}}}
 SLCard(color=OkSoft){Text("내가 할 수 있는 일",fontSize=16.sp,fontWeight=FontWeight.ExtraBold,color=Ink);Text("· 언제든 내 기록을 보고 내보낼 수 있어요\n· 사업주는 내 기록을 지울 수 없어요\n· 위험 신고는 이름을 숨길 수 있어요",fontSize=14.sp,lineHeight=22.sp,color=Ink)}
 if(preview)SmallNote("체험 모드의 기록과 사진은 이 기기에만 저장돼요. 실제 현장에 전송되지 않습니다.")
 NoteText("119 버튼은 전화 화면을 열어요. 실제 통화 연결이나 신고 접수를 증명하지 않아요.")
}

// 6-3 이용 안내
@Composable fun GuideSheet(){
 ScreenHead("안전 감지\n이용 안내")
 FlatBox{listOf("1. 폰을 몸에 지니고 계세요","2. 작업을 시작할 때 감시를 켜요","3. 충격과 움직임 감소는 위험의 '추정'이에요. 모든 사고를 알아내지는 못해요","4. 알림이 오면 먼저 내 안전을 확보하세요").forEachIndexed{i,text->if(i>0)HorizontalDivider(color=Line);Text(text,Modifier.padding(vertical=12.dp),fontSize=16.sp,lineHeight=24.sp,color=Ink)}}
 SLCard(color=DangerSoft){Text("실제로 위험하다면",fontSize=17.sp,fontWeight=FontWeight.ExtraBold,color=Ink);Text("앱에서 확인하기보다 119에 먼저 연락하세요. 앱은 119에 대신 신고하지 않아요.",fontSize=14.sp,lineHeight=22.sp,color=Ink)}
 NoteText("감시 중에는 배터리를 사용해요. 잠금·절전 동작은 기기마다 다를 수 있어요.")
}
