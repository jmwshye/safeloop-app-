package com.safeloop.app

import android.app.Application
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import androidx.compose.runtime.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID

class WorkerController(private val app:Application):AndroidViewModel(app) {
 private val storage=WorkerStorage(app)
 private val session=app.getSharedPreferences("worker-session",Context.MODE_PRIVATE)
 private val monitoringPrefs=app.getSharedPreferences("safeloop",Context.MODE_PRIVATE)
 private val registrations=mutableListOf<ListenerRegistration>()
 private var selfCalls=emptyList<WorkerCall>()
 private var peerCalls=emptyList<WorkerCall>()
 private val repository by lazy{Repository(app)}
 var state by mutableStateOf(storage.load());private set
 var now by mutableStateOf(System.currentTimeMillis());private set
 var busy by mutableStateOf(false);private set
 var feedback by mutableStateOf<String?>(null)
 var openCallId by mutableStateOf<String?>(null)
 val firebaseReady get()=FirebaseApp.getApps(app).isNotEmpty()
 private val preferencesListener=SharedPreferences.OnSharedPreferenceChangeListener{_,key->
  if(key=="monitoring")state=state.copy(monitoring=monitoringPrefs.getBoolean("monitoring",false))
  if(key=="previewSignalId"&&state.preview)readPreviewSensor()
 }
 init {
  monitoringPrefs.registerOnSharedPreferenceChangeListener(preferencesListener)
  if(session.getBoolean("connected",false)&&firebaseReady&&repository.auth.currentUser!=null&&repository.siteId.isNotEmpty()){
   state=WorkerState(preview=false,uid=repository.auth.currentUser!!.uid,name=session.getString("name","")?:"작업자",site="현장 정보 불러오는 중",zone=session.getString("zone","")?:"")
   listen();registerToken()
  }
  refreshMonitoring();readPreviewSensor()
  viewModelScope.launch {while(true){now=System.currentTimeMillis();if(state.preview){
   val expired=state.calls.filter{it.stage=="self"&&it.status=="pending"&&now>=it.deadline}
   if(expired.isNotEmpty()){val ids=expired.map{it.id};update(state.copy(calls=state.calls.map{if(it.id in ids)it.copy(stage="peer")else it}));event("본인 응답 시간 종료","같은 구역 동료 확인 대기로 전환 · 체험 기록",true);notifyPreview("안전 확인이 필요해요","체험: 본인 무응답으로 동료 확인 대기 단계가 되었습니다.",expired.first().id)}
  };delay(1000)}}
 }
 private fun update(next:WorkerState){state=next;if(next.preview)storage.save(next)}
 private fun event(title:String,detail:String,estimate:Boolean=false){update(state.copy(events=listOf(WorkerEvent(UUID.randomUUID().toString(),title,detail,estimate=estimate))+state.events))}
 private fun action(block:suspend()->Unit){if(busy)return;busy=true;viewModelScope.launch{try{block()}catch(e:Exception){feedback=e.localizedMessage?:"연결을 확인하고 다시 시도해 주세요."}finally{busy=false}}}
 fun saveWorkInfo(name:String,zone:String,wage:Int){require(wage in 0..10000000);if(!state.preview){feedback="연결 모드의 이름·구역 변경은 현장 책임자에게 요청해 주세요.";return};update(state.copy(name=name.ifBlank{"김민수"},zone=zone.ifBlank{"2층 · 설비 작업"},wage=wage));feedback="내 작업 정보를 저장했어요."}
 fun checkIn(location:Map<String,Any?>?=null)=action {
  if(state.preview){update(state.attend());event("출근 기록","${state.zone} · 예상 일당 ${moneyText(state.wage)}원")}
  else repository.command("attendance",mapOf("wage" to state.wage,"zone" to state.zone,"location" to location))
  feedback="출근을 기록했어요. 오늘도 안전하게 작업하세요."
 }
 fun checkOut()=action {
  if(state.preview){update(state.checkout());event("퇴근 기록","오늘의 근로 기록을 마무리했어요.")}
  else repository.command("checkout")
  app.stopService(Intent(app,SafetyService::class.java));state=state.copy(monitoring=false);feedback="오늘도 수고하셨어요. 근로 기록을 확인해 보세요."
 }
 fun saveChecks(values:List<String>)=action {
  require(values.size==3&&values.none{it.isBlank()}){"세 항목의 확인 상태를 모두 선택해 주세요."}
  if(state.preview){update(state.copy(checks=values));event("작업 전 확인",if(values.all{it=="확인"})"3개 항목 확인 완료" else "개선 필요·모름 항목이 있어요. 책임자에게 확인해 주세요.")}
  else repository.command("self_check",mapOf("values" to values,"claim" to if(values[2]=="확인")"착용" else "미착용"))
  feedback="작업 전 확인을 저장했어요."
 }
 fun checkPeer(observed:String,localPhoto:String?)=action {
  if(state.preview){update(state.copy(peerChecked=observed=="착용"));event("동료 교차 확인",if(observed=="착용")"${state.buddyName} · ${if(localPhoto==null)"버튼 확인" else "사진 확인"}" else "${state.buddyName} · 개선 필요")}
  else {val buddy=buddyUid?:error("지정된 안전 동료가 없습니다.");val path=localPhoto?.let{upload(it)};repository.command("peer_check",mapOf("target" to buddy,"observed" to observed,"photoPath" to path))}
  feedback="동료 확인을 저장했어요."
 }
 fun submitRisk(title:String,zone:String,category:String,note:String,anonymous:Boolean,localPhoto:String?,finished:()->Unit)=action {
  require(title.isNotBlank()&&zone.isNotBlank()){"위험 내용과 위치를 입력해 주세요."};require(localPhoto!=null){"현장 사진을 촬영해 주세요."}
  if(state.preview){val risk=WorkerRisk(UUID.randomUUID().toString(),title,zone,note,category,photo=localPhoto,anonymous=anonymous);update(state.copy(risks=listOf(risk)+state.risks));event("위험 요소 신고","$title · $zone")}
  else {val path=upload(localPhoto);repository.command("risk",mapOf("title" to title,"zone" to zone,"category" to category,"note" to note,"anonymous" to anonymous,"photoPath" to path))}
  feedback=if(state.preview)"체험 기록에 위험 신고를 저장했어요." else "위험 신고를 현장에 전달했어요.";finished()
 }
 fun setCarried(enabled:Boolean)=action {
  if(!enabled){app.stopService(Intent(app,SafetyService::class.java));state=state.copy(monitoring=false)}
  if(!state.preview){repository.command("device",mapOf("enabled" to enabled));if(!enabled)repository.command("monitoring",mapOf("enabled" to false))}
  update(state.copy(carried=enabled));if(state.preview)event("기기 휴대 상태",if(enabled)"휴대폰을 몸에 휴대하고 있어요." else "기기 미휴대 · 움직임 감시 중지")
 }
 fun setMonitoring(enabled:Boolean)=action {
  require(!enabled||state.currentShift!=null){"먼저 출근을 기록해 주세요."};require(!enabled||state.carried){"휴대폰을 몸에 휴대한 뒤 시작해 주세요."}
  monitoringPrefs.edit().putBoolean("previewMonitoring",state.preview).apply()
  if(!state.preview)repository.command("monitoring",mapOf("enabled" to enabled))
  if(enabled)ContextCompat.startForegroundService(app,Intent(app,SafetyService::class.java))else app.stopService(Intent(app,SafetyService::class.java))
  update(state.copy(monitoring=enabled));if(state.preview)event(if(enabled)"움직임 감시 시작" else "움직임 감시 종료","휴대폰 센서 감시 · 체험 모드에서는 다른 사람에게 전송하지 않습니다.")
  feedback=if(enabled)"휴대폰을 몸에 휴대해 주세요. 감시 중에는 배터리를 사용합니다." else "움직임 감시를 종료했어요."
 }
 @Suppress("DEPRECATION") fun refreshMonitoring(){val active=(app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getRunningServices(100).any{it.service.className==SafetyService::class.java.name};state=state.copy(monitoring=active)}
 private fun readPreviewSensor(){if(!state.preview)return;val id=monitoringPrefs.getString("previewSignalId",null)?:return;if(monitoringPrefs.getString("previewProcessed",null)==id)return
  val at=monitoringPrefs.getLong("previewSignalAt",System.currentTimeMillis());update(state.copy(calls=listOf(WorkerCall(id,subject=state.uid,subjectName=state.name,zone=state.zone,deadline=at+15000))+state.calls));event("이상 움직임 감지","휴대폰 센서에서 감지한 추정 신호입니다.",true);monitoringPrefs.edit().putString("previewProcessed",id).apply()
 }
 fun simulateSelf(){if(!state.preview){action{repository.command("signal");feedback="서버 시험 요청을 생성했어요. 무응답 시 실제 동료에게 전달됩니다."};return};if(state.activeCalls.any{it.isSubject(state.uid)}){feedback="진행 중인 본인 확인 요청이 있어요.";return};val call=WorkerCall(UUID.randomUUID().toString(),subject=state.uid,subjectName=state.name,zone=state.zone);update(state.copy(calls=listOf(call)+state.calls));event("이상 움직임 시험 신호","센서 사고 확정이 아닌 체험용 입력입니다.",true);notifyPreview("괜찮으세요?","15초 안에 안전 상태를 확인해 주세요.",call.id)}
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
  else {val path=if(response=="verified")upload(localPhoto?:error("현장 주변 사진이 필요합니다."),callId)else null;repository.command("call_response",mapOf("callId" to callId,"response" to response,"photoPath" to path))}
  if(response=="safe"){monitoringPrefs.edit().putString("ackAlert",monitoringPrefs.getString("localAlert",null)).apply();app.getSystemService(NotificationManager::class.java).cancel(103)}
  app.getSystemService(NotificationManager::class.java).cancel(callId.hashCode())
  feedback=when(response){"verified"->"주변 사진과 안전 확인을 저장했어요.";"accepted"->"본인의 안전을 확보한 뒤 현장을 확인해 주세요.";"arrived"->"작업자 상태와 주변을 확인하고 촬영해 주세요.";else->"괜찮다고 응답했어요."}
 }
 private suspend fun upload(path:String,callId:String?=null):String {val bitmap=BitmapFactory.decodeFile(path)?:error("촬영 사진을 읽을 수 없습니다.");return try{repository.upload(bitmap,callId)}finally{bitmap.recycle()}}
 fun connect(email:String,password:String,name:String,code:String,zone:String,signUp:Boolean,finished:()->Unit)=action {
  require(firebaseReady){"Firebase 설정 파일이 필요합니다. README의 연결 절차를 확인해 주세요."};require(email.isNotBlank()&&password.length>=6&&code.isNotBlank()){"이메일, 비밀번호, 현장 코드를 확인해 주세요."}
  if(signUp)repository.signUp(email,password,name)else repository.signIn(email,password)
  val result=repository.command("join",mapOf("code" to code,"name" to name.ifBlank{"작업자"},"zone" to zone))
  repository.siteId=result["siteId"] as? String?:error("서버의 현장 응답을 확인해 주세요.")
  app.stopService(Intent(app,SafetyService::class.java));session.edit().putBoolean("connected",true).putString("name",name).putString("zone",zone).apply()
  state=WorkerState(preview=false,uid=repository.auth.currentUser!!.uid,name=name,zone=zone,code=code,site="현장 정보 불러오는 중")
  listen();registerToken();feedback="현장에 연결했어요.";finished()
 }
 fun usePreview(){registrations.forEach{it.remove()};registrations.clear();app.stopService(Intent(app,SafetyService::class.java));session.edit().putBoolean("connected",false).apply();if(firebaseReady)repository.auth.signOut();state=storage.load().copy(monitoring=false);feedback="체험 모드로 전환했어요. 서버 데이터는 변경하지 않습니다."}
 fun resetPreview(){if(!state.preview)return;app.stopService(Intent(app,SafetyService::class.java));update(WorkerState.preview());feedback="체험 기록을 초기화했어요."}
 fun emergency(){if(state.preview)event("119 전화 화면 열기","전화 화면 실행 요청 · 통화 연결 여부는 확인하지 않습니다.")else repository.queue("emergency",emptyMap())}
 private var buddyUid:String?=null
 private val loadingPhotos=mutableSetOf<String>()
 fun loadVerificationPhoto(callId:String){
  val call=state.calls.firstOrNull{it.id==callId}?:return
  if(state.preview||call.resolution!="peer_photo"||call.photo!=null)return
  if(!loadingPhotos.add(callId))return
  viewModelScope.launch{try{val bytes=repository.verificationPhoto(callId);val path=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){val dir=java.io.File(app.cacheDir,"verification").apply{mkdirs()};java.io.File(dir,"${UUID.randomUUID()}.jpg").apply{writeBytes(bytes)}.path};if(!state.preview)state=state.copy(calls=state.calls.map{if(it.id==callId)it.copy(photo=path)else it})}catch(e:Exception){feedback="확인 사진을 불러오지 못했어요. 연결 후 상세 화면을 다시 열어 주세요."}finally{loadingPhotos.remove(callId)}}
 }
 private fun listen(){registrations.forEach{it.remove()};registrations.clear();selfCalls=emptyList();peerCalls=emptyList();val siteId=repository.siteId;val uid=state.uid
  fun error(e:Exception?){if(e!=null)feedback="서버 기록 조회 실패: ${e.localizedMessage}"}
  registrations+=repository.db.document("sites/$siteId").addSnapshotListener{snap,e->error(e);snap?.data?.let{state=state.copy(site=it["name"]?.toString()?:state.site,code=it["code"]?.toString()?:state.code)}}
  registrations+=repository.db.collection("sites/$siteId/members").addSnapshotListener{snap,e->error(e);val me=snap?.documents?.find{it.id==uid};val data=me?.data?:return@addSnapshotListener;buddyUid=data["buddy"] as? String;val buddy=snap?.documents?.find{it.id==buddyUid}?.data
   val checks=(data["selfCheck"] as? List<*>)?.map{it.toString()}?.takeIf{it.size==3}?:state.checks
   state=state.copy(name=data["name"]?.toString()?:state.name,zone=data["zone"]?.toString()?:state.zone,carried=data["carried"] as? Boolean?:true,checks=checks,buddyName=buddy?.get("name")?.toString()?:"배정 대기",buddyZone=buddy?.get("zone")?.toString()?:"현장 책임자에게 문의해 주세요.",buddyState=buddy?.get("state")?.toString()?:"동료 미배정",peerChecked=data["lastPeerCheck"]!=null)
  }
  registrations+=repository.db.collection("users/$uid/shifts").whereEqualTo("siteId",siteId).addSnapshotListener{snap,e->error(e);if(snap!=null)state=state.copy(work=snap.documents.mapNotNull{d->val m=d.data?:return@mapNotNull null;val start=parseTime(m["start"])?:return@mapNotNull null;WorkEntry(d.id,m["day"].toString(),start,parseTime(m["end"]),(m["wage"] as? Number)?.toInt()?:0)}.sortedByDescending{it.start})}
  registrations+=repository.db.collection("sites/$siteId/risks").addSnapshotListener{snap,e->error(e);if(snap!=null)state=state.copy(risks=snap.documents.map{d->val m=d.data?:emptyMap();WorkerRisk(d.id,m["title"].toString(),m["zone"].toString(),m["note"]?.toString()?:"",m["category"]?.toString()?:"기타",m["status"].toString(),time=parseTime(m["createdAt"])?:0,anonymous=m["anonymous"]==true)}.sortedByDescending{it.time})}
  fun calls(){state=state.copy(calls=(selfCalls+peerCalls).distinctBy{it.id}.sortedByDescending{it.deadline})}
  registrations+=repository.db.collection("sites/$siteId/calls").whereEqualTo("subject",uid).addSnapshotListener{snap,e->error(e);if(snap!=null){selfCalls=snap.documents.map{mapCall(it.id,it.data?:emptyMap())};calls()}}
  registrations+=repository.db.collection("sites/$siteId/calls").whereArrayContains("recipients",uid).addSnapshotListener{snap,e->error(e);if(snap!=null){peerCalls=snap.documents.map{mapCall(it.id,it.data?:emptyMap())};calls()}}
  registrations+=repository.db.collection("users/$uid/events").whereEqualTo("siteId",siteId).addSnapshotListener{snap,e->error(e);if(snap!=null)state=state.copy(events=snap.documents.map{d->val m=d.data?:emptyMap();val type=m["type"].toString();val detail=m["detail"] as? Map<*,*>;WorkerEvent(d.id,eventTitle(type),detail?.get("message")?.toString()?:"현장에서 저장된 확인 기록",parseTime(m["serverTime"])?:0,m["source"]=="system_estimate")}.sortedByDescending{it.time})}
 }
 private fun mapCall(id:String,m:Map<String,Any>)=WorkerCall(id,m["subject"].toString(),m["subjectName"]?.toString()?:"동료",m["zone"].toString(),m["stage"].toString(),m["status"].toString(),parseTime(m["deadline"])?:0,m["acceptedBy"] as? String,parseTime(m["arrivedAt"]),photo=state.calls.firstOrNull{it.id==id}?.photo,resolution=m["resolution"] as? String,overdue=m["verificationOverdue"]==true)
 private fun eventTitle(type:String)=mapOf("attendance" to "출근 기록","checkout" to "퇴근 기록","self_check" to "작업 전 확인","peer_check" to "동료 교차 확인","sensor" to "이상 움직임 감지","call" to "동료 안전 확인 요청","accepted" to "현장 확인 수락","arrived" to "현장 도착","verified" to "사진 확인 완료","response" to "본인 안전 응답","risk" to "위험 요소 신고")[type]?:"현장 확인 기록"
 private fun registerToken(){viewModelScope.launch{runCatching{repository.command("registerToken",mapOf("token" to FirebaseMessaging.getInstance().token.await()))}.onFailure{feedback="알림 기기 등록에 실패했어요. 연결과 알림 설정을 확인해 주세요."}}}
 private fun notifyPreview(title:String,body:String,callId:String){val nm=app.getSystemService(NotificationManager::class.java);nm.createNotificationChannel(NotificationChannel("alerts","안전 확인 요청",NotificationManager.IMPORTANCE_HIGH).apply{enableVibration(true)});val open=PendingIntent.getActivity(app,callId.hashCode(),Intent(app,MainActivity::class.java).putExtra("callId",callId),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE);runCatching{nm.notify(callId.hashCode(),NotificationCompat.Builder(app,"alerts").setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle("[체험] $title").setContentText(body).setContentIntent(open).setAutoCancel(true).build())}}
 override fun onCleared(){registrations.forEach{it.remove()};monitoringPrefs.unregisterOnSharedPreferenceChangeListener(preferencesListener);super.onCleared()}
}
