package com.safeloop.app
import android.app.*
import android.content.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

class WorkerController(private val app:Application):AndroidViewModel(app) {
 private val storage=WorkerStorage(app)
 private val monitoringPrefs=app.getSharedPreferences("safeloop",Context.MODE_PRIVATE)
 var state by mutableStateOf(storage.load());private set
 var now by mutableStateOf(System.currentTimeMillis());private set
 var busy by mutableStateOf(false);private set
 var feedback by mutableStateOf<String?>(null)
 var openCallId by mutableStateOf<String?>(null)
 private val preferencesListener=SharedPreferences.OnSharedPreferenceChangeListener{_,key->
  if(key=="monitoring")state=state.copy(monitoring=monitoringPrefs.getBoolean("monitoring",false))
  if(key=="previewSignalId"&&state.preview)readPreviewSensor()
 }
 init {
  monitoringPrefs.registerOnSharedPreferenceChangeListener(preferencesListener)
  refreshMonitoring();readPreviewSensor()
  viewModelScope.launch {while(true){now=System.currentTimeMillis();if(state.preview){
   val expired=state.calls.filter{it.stage=="self"&&it.status=="pending"&&now>=it.deadline}
   if(expired.isNotEmpty()){val ids=expired.map{it.id};update(state.copy(calls=state.calls.map{if(it.id in ids)it.copy(stage="peer")else it}));event("본인 응답 시간 종료","같은 구역 동료 확인 대기로 전환 · 체험 기록",true);notifyPreview("안전 확인이 필요해요","체험: 본인 무응답으로 동료 확인 대기 단계가 되었습니다.",expired.first().id)}
  };delay(1000)}}
 }
 private fun update(next:WorkerState){state=next;if(next.preview)storage.save(next)}
 private fun event(title:String,detail:String,estimate:Boolean=false){update(state.copy(events=listOf(WorkerEvent(UUID.randomUUID().toString(),title,detail,estimate=estimate))+state.events))}
 private fun action(block:suspend()->Unit){if(busy)return;busy=true;viewModelScope.launch{try{block()}catch(e:Exception){feedback=e.localizedMessage?:"연결을 확인하고 다시 시도해 주세요."}finally{busy=false}}}
 fun saveWorkInfo(name:String,zone:String,wage:Int){require(wage in 0..10000000);update(state.copy(name=name.ifBlank{"김민수"},zone=zone.ifBlank{"2층 · 설비 작업"},wage=wage));feedback="내 작업 정보를 저장했어요."}
 fun checkIn(location:Map<String,Any?>?=null)=action { update(state.attend());event("출근 기록","${state.zone} · 예상 일당 ${moneyText(state.wage)}원");feedback="출근을 기록했어요. 오늘도 안전하게 작업하세요." }
 fun checkOut()=action { update(state.checkout());event("퇴근 기록","오늘의 근로 기록을 마무리했어요.");app.stopService(Intent(app,SafetyService::class.java));feedback="오늘도 수고하셨어요. 근로 기록을 확인해 보세요." }
 fun resetPreview(){app.stopService(Intent(app,SafetyService::class.java));update(WorkerState.preview());feedback="체험 기록을 초기화했어요."}
 fun emergency(){event("119 전화 화면 열기","전화 화면 실행 요청 · 통화 연결 여부는 확인하지 않습니다.")}
 fun saveChecks(values:List<String>)=action {
  require(values.size==3&&values.none{it.isBlank()}){"세 항목의 확인 상태를 모두 선택해 주세요."}
  if(state.preview){update(state.copy(checks=values));event("작업 전 확인",if(values.all{it=="확인"})"3개 항목 확인 완료" else "개선 필요·모름 항목이 있어요. 책임자에게 확인해 주세요.")}
  feedback="작업 전 확인을 저장했어요."
 }
 fun checkPeer(observed:String,localPhoto:String?)=action {
  if(state.preview){update(state.copy(peerChecked=observed=="착용"));event("동료 교차 확인",if(observed=="착용")"${state.buddyName} · ${if(localPhoto==null)"버튼 확인" else "사진 확인"}" else "${state.buddyName} · 개선 필요")}
  feedback="동료 확인을 저장했어요."
 }

 fun submitRisk(title:String,zone:String,category:String,note:String,anonymous:Boolean,localPhoto:String?,finished:()->Unit)=action {
  require(title.isNotBlank()&&zone.isNotBlank()){"위험 내용과 위치를 입력해 주세요."};require(localPhoto!=null){"현장 사진을 촬영해 주세요."}
  if(state.preview){val risk=WorkerRisk(UUID.randomUUID().toString(),title,zone,note,category,photo=localPhoto,anonymous=anonymous);update(state.copy(risks=listOf(risk)+state.risks));event("위험 요소 신고","$title · $zone")}
  feedback=if(state.preview)"체험 기록에 위험 신고를 저장했어요." else "위험 신고를 현장에 전달했어요.";finished()
 }

 fun setMonitoring(enabled:Boolean)=action {
  require(!enabled||state.currentShift!=null){"먼저 출근을 기록해 주세요."};require(!enabled||state.carried){"휴대폰을 몸에 휴대한 뒤 시작해 주세요."}
  monitoringPrefs.edit().putBoolean("previewMonitoring",state.preview).apply()
  if(enabled)ContextCompat.startForegroundService(app,Intent(app,SafetyService::class.java))else app.stopService(Intent(app,SafetyService::class.java))
  update(state.copy(monitoring=enabled));if(state.preview)event(if(enabled)"움직임 감시 시작" else "움직임 감시 종료","휴대폰 센서 감시 · 체험 모드에서는 다른 사람에게 전송하지 않습니다.")
  feedback=if(enabled)"휴대폰을 몸에 휴대해 주세요. 감시 중에는 배터리를 사용합니다." else "움직임 감시를 종료했어요."
 }
 @Suppress("DEPRECATION") fun refreshMonitoring(){val active=(app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getRunningServices(100).any{it.service.className==SafetyService::class.java.name};state=state.copy(monitoring=active)}
 private fun readPreviewSensor(){if(!state.preview)return;val id=monitoringPrefs.getString("previewSignalId",null)?:return;if(monitoringPrefs.getString("previewProcessed",null)==id)return
  val at=monitoringPrefs.getLong("previewSignalAt",System.currentTimeMillis());update(state.copy(calls=listOf(WorkerCall(id,subject=state.uid,subjectName=state.name,zone=state.zone,deadline=at+15000))+state.calls));event("이상 움직임 감지","휴대폰 센서에서 감지한 추정 신호입니다.",true);monitoringPrefs.edit().putString("previewProcessed",id).apply()
 }
 fun simulateSelf(){if(state.activeCalls.any{it.isSubject(state.uid)}){feedback="진행 중인 본인 확인 요청이 있어요.";return};val call=WorkerCall(UUID.randomUUID().toString(),subject=state.uid,subjectName=state.name,zone=state.zone);update(state.copy(calls=listOf(call)+state.calls));event("이상 움직임 시험 신호","센서 사고 확정이 아닌 체험용 입력입니다.",true);notifyPreview("괜찮으세요?","15초 안에 안전 상태를 확인해 주세요.",call.id)}
 fun simulateIncoming(){if(!state.preview)return;val call=WorkerCall(UUID.randomUUID().toString(),subject="preview-buddy",subjectName=state.buddyName,zone=state.buddyZone,stage="peer");update(state.copy(calls=listOf(call)+state.calls));event("동료 안전 확인 요청","${state.buddyName} 님의 상태를 확인해 주세요. · 체험");openCallId=call.id;notifyPreview("동료가 확인을 기다려요","${state.buddyName} · ${state.buddyZone} · 체험 요청",call.id)}
 fun respond(callId:String,response:String,localPhoto:String?=null)=action {
  val call=state.calls.firstOrNull{it.id==callId}?:error("안전 확인 요청을 다시 불러와 주세요.")
  if(state.preview){val next=when(response){
   "safe"->{require(call.isSubject(state.uid)&&call.stage=="self"&&now<call.deadline){"응답 시간이 지났어요. 동료의 현장 확인을 기다려 주세요."};call.copy(status="closed",resolution="self_safe")}
   "accepted"->call.accept(state.uid)
   "arrived"->call.arrive(state.uid)
   "verified"->call.verify(state.uid,localPhoto)
   else->error("지원하지 않는 응답입니다.")
  };update(state.copy(calls=state.calls.map{if(it.id==callId)next else it}));event(when(response){"safe"->"본인 안전 응답";"accepted"->"동료 현장 확인 수락";"arrived"->"현장 도착";else->"사진 안전 확인 완료"},"${call.subjectName} · ${call.zone} · 체험 기록")}
  if(response=="safe"){monitoringPrefs.edit().putString("ackAlert",monitoringPrefs.getString("localAlert",null)).apply();app.getSystemService(NotificationManager::class.java).cancel(103)}
  app.getSystemService(NotificationManager::class.java).cancel(callId.hashCode())
  feedback=when(response){"verified"->"주변 사진과 안전 확인을 저장했어요.";"accepted"->"본인의 안전을 확보한 뒤 현장을 확인해 주세요.";"arrived"->"작업자 상태와 주변을 확인하고 촬영해 주세요.";else->"괜찮다고 응답했어요."}
 }

 private fun notifyPreview(title:String,body:String,callId:String){val nm=app.getSystemService(NotificationManager::class.java);nm.createNotificationChannel(NotificationChannel("alerts","안전 확인 요청",NotificationManager.IMPORTANCE_HIGH).apply{enableVibration(true)});val open=PendingIntent.getActivity(app,callId.hashCode(),Intent(app,MainActivity::class.java).putExtra("callId",callId),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE);runCatching{nm.notify(callId.hashCode(),NotificationCompat.Builder(app,"alerts").setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle("[체험] $title").setContentText(body).setContentIntent(open).setAutoCancel(true).build())}}
 override fun onCleared(){monitoringPrefs.unregisterOnSharedPreferenceChangeListener(preferencesListener);super.onCleared()}
}
