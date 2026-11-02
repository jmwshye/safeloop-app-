package com.safeloop.app

import android.app.*
import android.content.*
import android.graphics.BitmapFactory
import androidx.compose.runtime.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.io.File
import java.time.Instant
import java.time.YearMonth
import java.util.UUID

/** Connected records come from NestJS. Preview records stay on this device. */
class WorkerController(private val app:Application):AndroidViewModel(app) {
 private val storage=WorkerStorage(app)
 private val session=app.getSharedPreferences("worker-session",Context.MODE_PRIVATE)
 private val monitoringPrefs=app.getSharedPreferences("safeloop",Context.MODE_PRIVATE)
 private val repository by lazy{Repository(app)}
 private val refreshMutex=Mutex()
 private var polling:Job?=null
 private var generation=0
 private var recordsMonth=YearMonth.now(koreaZone).toString()
 private var timelineDay=todayKey()
 private var openRiskId:String?=null
 private var riskPages=1
 private var timelinePages=1
 private var incidentPages=1
 var onboarding by mutableStateOf<String?>(if(session.getBoolean("hasStarted",false)||session.getBoolean("connected",false))null else "welcome");private set
 var choices by mutableStateOf<List<SiteChoice>>(emptyList());private set
 private val photoCache=mutableMapOf<String,String>()
 private val requestIds=mutableMapOf<String,String>()

 var pendingSite by mutableStateOf<SiteChoice?>(null);private set
 var state by mutableStateOf(storage.load());private set
 var now by mutableStateOf(System.currentTimeMillis());private set
 var busy by mutableStateOf(false);private set
 private val incidentActions=mutableStateMapOf<String,Boolean>()
 fun incidentBusy(id:String)=incidentActions.containsKey(id)
 private var visibleCallId:String?=null
 fun trackCall(id:String?){visibleCallId=id}
 private var incidentRevision=0L
 private fun runIncidentAction(id:String,block:suspend()->Unit){
  if(incidentActions.put(id,true)!=null)return
  viewModelScope.launch {
   try { block() }
   catch(e:CancellationException){throw e}
   catch(e:Exception){handleSessionError(e);feedback=e.localizedMessage?:"긴급 요청의 연결 상태를 확인해 주세요."}
   finally{incidentActions.remove(id)}
  }
 }
 var pendingWrites by mutableIntStateOf(0);private set
 var feedback by mutableStateOf<String?>(null)
 var openCallId by mutableStateOf<String?>(null)
 var requestedRoute by mutableStateOf<String?>(null)
 private val preferencesListener=SharedPreferences.OnSharedPreferenceChangeListener{_,key->
  if(key=="signalFailed"&&!state.preview&&monitoringPrefs.getBoolean("signalFailed",false))requestedRoute="call-failed"
  if(key=="localCallId"&&!state.preview)monitoringPrefs.getString("localCallId",null)?.let{openCall(it)}
  if(key=="monitoring")state=state.copy(monitoring=monitoringPrefs.getBoolean("monitoring",false))
  if(key=="previewSignalId"&&state.preview)readPreviewSensor()
 }
 init {
  viewModelScope.launch {
   repository.queueStatuses().collect { items -> pendingWrites=items.count{!it.state.isFinished&&repository.pendingTag() in it.tags} }
  }
  monitoringPrefs.registerOnSharedPreferenceChangeListener(preferencesListener)
  if(session.getBoolean("connected",false)&&repository.hasSession&&repository.siteId.isNotEmpty()){
   val user=repository.currentUser;state=WorkerState.live(user?.uid.orEmpty(),user?.name?:"작업자").copy(siteId=repository.siteId);if(session.getBoolean("needsProfile",false))onboarding="profile";listen();registerToken()
  }else if(repository.hasSession){val user=checkNotNull(repository.currentUser);state=WorkerState.live(user.uid,user.name);onboarding="sites";reloadSites()}else if(session.getBoolean("connected",false)){onboarding="login";state=WorkerState.live("","작업자").copy(connectionProblem="로그인이 만료됐어요. 내 정보에서 다시 연결해 주세요.")}
  refreshMonitoring();readPreviewSensor()
  viewModelScope.launch{while(isActive){now=System.currentTimeMillis();if(state.preview){
   val expired=state.calls.filter{it.stage=="self"&&it.status=="pending"&&now>=it.deadline}.map{it.id}
   if(expired.isNotEmpty()){update(state.copy(calls=state.calls.map{if(it.id in expired)it.copy(stage="peer")else it}));event("본인 응답 시간 종료","동료 확인 대기 · 체험 기록",true);notifyPreview("안전 확인이 필요해요","체험: 동료 확인 대기 단계입니다.",expired.first())}
   if(state.currentShift!=null)tickPrompts()
  };delay(1000)}}
 }
 private fun tickPrompts(){val next=advancePrompts(state.prompts,now);if(next==state.prompts)return
  val before=state.prompts.associateBy{it.id};update(state.copy(prompts=next))
  next.forEach{prompt->val was=before[prompt.id]?.state
   if(was=="scheduled"&&prompt.state=="pending")notifyPreview("안전하신가요?","휴식 복귀 때라 한 번만 물어요.",prompt.id,openCall=false)
   if(was=="pending"&&prompt.state=="unverified")event("체크인 응답 없음","안전 미확인",true)
  }
 }
 private fun update(next:WorkerState){state=next;if(next.preview)storage.save(next)}
 private fun event(title:String,detail:String,estimate:Boolean=false){check(state.preview);val entry=WorkerEvent(UUID.randomUUID().toString(),title,detail,estimate=estimate);update(state.copy(events=listOf(entry.copy(hash=eventHash(state.events.firstOrNull()?.hash.orEmpty(),entry)))+state.events))}
 var serverDown by mutableStateOf(false)
 private fun action(block:suspend()->Unit){if(busy)return;busy=true;viewModelScope.launch{try{block()}catch(e:CancellationException){throw e}catch(e:Exception){handleSessionError(e);feedback=e.localizedMessage?:"연결을 확인하고 다시 시도해 주세요."}finally{busy=false}}}
 private fun handleSessionError(e:Exception){if(e is ApiException&&e.status==401&&!repository.hasSession&&!state.preview){generation++;polling?.cancel();photoCache.clear();requestIds.clear();pendingSite=null;onboarding="login";app.stopService(Intent(app,SafetyService::class.java));state=WorkerState.live("","작업자").copy(connectionProblem="로그인이 만료됐어요. 내 정보에서 다시 연결해 주세요.")}}
 val captureScope get()=if(state.preview)"preview" else "${repository.sessionId}:${repository.siteId}"
 val sessionVersion get()=generation
 fun acceptsNotification(site:String?,recipient:String?)=if(state.preview)recipient==null&&site.isNullOrBlank() else pushBelongsTo(recipient,site,repository.currentUser?.uid,repository.siteId)
 private fun requestId(key:String)=requestIds.getOrPut(key){UUID.randomUUID().toString()}
 private val sitePath get()="/sites/${repository.siteId}"
 private fun shiftId()=state.currentShift?.id?:error("먼저 출근 기록을 확인해 주세요.")
 private suspend fun changed(){if(!state.preview)try{refresh()}catch(e:CancellationException){throw e}catch(e:Exception){handleSessionError(e);if(repository.hasSession)state=state.copy(connectionProblem="저장은 완료했지만 최신 기록을 불러오지 못했어요. 연결되면 다시 불러옵니다.")}}
 fun saveWorkInfo(name:String,job:String,zone:String,wage:Int?)=action {
  require(job.isNotBlank()&&zone.isNotBlank()&&(wage==null||wage in 0..10000000)){"직종·구역과 예상 일당을 확인해 주세요."}
  if(state.preview)update(state.copy(name=name.ifBlank{"김민수"},job=job,zone=zone,wage=wage?:0,wageKnown=wage!=null))
  else{repository.request("PUT","$sitePath/profile",mapOf("occupation" to job,"zone" to zone,"expectedWage" to wage));changed()}
  feedback="작업 정보를 저장했어요. 다음 출근부터 적용됩니다."
 }
 fun verifyLocation(location:Map<String,Any?>?)=action {
  val status=if(location==null)"unverified" else repository.request("POST","$sitePath/check-location",mapOf("location" to location)).text("locationStatus")
  state=state.copy(beforeLocation=status)
 }
 fun checkIn(location:Map<String,Any?>?=null)=action {
  if(state.preview){update(state.attend().copy(prompts=scheduledPrompts(now)));event("출근 기록",state.zone)}
  else{repository.request("POST","/attendances",mapOf("siteId" to repository.siteId,"location" to location));changed()}
  feedback=if(location==null&&!state.preview)"출근을 저장했어요. 위치는 미확인으로 표시됩니다." else "출근을 저장했어요."
 }
 fun checkOut(onSaved:()->Unit={})=action {
  if(state.preview){update(state.checkout());event("퇴근 기록","오늘 근무 마무리")}
  else{repository.request("PUT","/attendances/${shiftId()}/checkout",emptyMap());changed()}
  app.stopService(Intent(app,SafetyService::class.java));state=state.copy(monitoring=false);feedback="퇴근을 저장했어요.";onSaved()
 }
 fun saveChecks(values:List<String>)=action {
  require(values.size==3&&values.all{it in listOf("확인","개선 필요","모름")}){"세 항목의 답을 모두 선택해 주세요."}
  if(state.preview){update(state.copy(checks=values));event("작업 전 확인",values.joinToString(" · "))}
  else{val fields=listOf("fallHazard","floorAndGuardrail","protectiveEquipment");repository.request("PUT","/attendances/${shiftId()}/safety-check",fields.zip(values.map{when(it){"확인"->"confirmed";"모름"->"unknown";else->"needs_attention"}}).toMap());changed()}
  feedback="작업 전 확인을 저장했어요."
 }
 fun discardCapture(path:String?) {
  if(path==null)return
  val file=File(path)
  if(file.parentFile==File(app.filesDir,"captures")&&file.extension=="jpg")file.delete()
 }
 fun checkPeer(target:String?,observed:String,localPhoto:String?,onSaved:()->Unit={})=action {
  require(observed=="착용"||observed=="미착용")
  val checked=observed=="착용"
  require(checked||localPhoto==null){"불일치 모습은 저장하지 않아요."}
  if(state.preview){update(state.copy(peerEvidence=listOfNotNull(PeerEvidence(state.uid,"preview-buddy-shift",if(checked)"checked" else "mismatch",now,localPhoto,localPhoto),PeerEvidence("preview-buddy","preview-self-shift","checked",now).takeIf{checked}),peerChecked=checked,peerStatus=if(!checked)"mismatch" else if(localPhoto!=null)"photo_confirmed" else "statement_only"));event("동료 교차 확인",if(checked)state.buddyName else "불일치 · 조치 후 다시 확인")}
  else{val originalBuddy=state.verifiedBuddyTarget(target);val image=if(checked&&localPhoto!=null)upload(localPhoto,"ppe")else null;repository.request("PUT","/attendances/$originalBuddy/peer-check",mapOf("outcome" to if(checked)"checked" else "mismatch","photoId" to image));changed()}
  feedback="동료 확인을 저장했어요.";onSaved()
 }
 fun submitRisk(title:String,zone:String,category:String,note:String,anonymous:Boolean,localPhoto:String?,finished:()->Unit)=action {
  require(zone.isNotBlank()){"위치를 입력해 주세요."}
  if(state.preview){val risk=WorkerRisk(UUID.randomUUID().toString(),title.ifBlank{"$category 위험 · $zone"},zone,note,category,photo=localPhoto,anonymous=anonymous);update(state.copy(risks=listOf(risk)+state.risks));event("위험 요소 신고",risk.title)}
  else{val key="risk:$title:$zone:$category:$note:$anonymous:$localPhoto";val body=mutableMapOf<String,Any?>("requestId" to requestId(key),"zone" to zone,"category" to categoryCode(category),"note" to note,"anonymous" to anonymous);if(title.isNotBlank())body["title"]=title;if(localPhoto!=null)body["beforePhotoId"]=upload(localPhoto,"risk_before");repository.request("POST","$sitePath/risks",body);requestIds.remove(key);changed()}
  feedback="위험 신고를 저장했어요.";finished()
 }
 fun fixRisk(id:String,path:String)=action {
  if(state.preview){update(state.copy(risks=state.risks.map{if(it.id==id)it.copy(status="resolved",afterPhoto=path,fixerName=state.name,fixedAt=now,canRecheck=false)else it}));event("위험 조치","다른 동료의 재확인 대기")}
  else{repository.request("PUT","$sitePath/risks/$id/fix",mapOf("afterPhotoId" to upload(path,"risk_after")));changed();loadRiskPhotos(id)}
  feedback="조치 사진을 저장했어요. 다른 동료의 재확인이 필요해요."
 }
 fun recheckRisk(id:String)=action {
  if(state.preview){val risk=state.risks.first{it.id==id};require(risk.fixerName!=state.name){"조치한 사람 외 동료가 확인해 주세요."};update(state.copy(risks=state.risks.map{if(it.id==id)it.copy(status="verified",verifierName=state.name,verifiedAt=now,canRecheck=false)else it}))}
  else{repository.request("PUT","$sitePath/risks/$id/recheck",emptyMap());changed()};feedback="재확인을 저장했어요."
 }
 // 체험 모드에는 다른 동료가 없어서 재확인 장면을 대신 보여준다.
 fun simulateRecheck(id:String)=action {require(state.preview);update(state.copy(risks=state.risks.map{if(it.id==id)it.copy(status="verified",verifierName=state.buddyName,verifiedAt=now)else it}));event("위험 재확인",state.buddyName+" · 체험 예시")}
 fun setCarried(enabled:Boolean)=action {
  if(!enabled){app.stopService(Intent(app,SafetyService::class.java));state=state.copy(monitoring=false)}
  if(state.preview){update(state.copy(carried=enabled,deviceState=if(enabled)"carried" else "not_carried"));event("기기 휴대 상태",if(enabled)"휴대 중" else "미휴대")}
  else{repository.request("PUT","/attendances/${shiftId()}/device-status",mapOf("state" to if(enabled)"carried" else "not_carried","observedAt" to Instant.now().toString()));changed()}
 }
 fun setMonitoring(enabled:Boolean)=action {
  require(!enabled||state.currentShift!=null){"먼저 출근을 기록해 주세요."};require(!enabled||state.carried){"휴대폰을 몸에 휴대한 뒤 시작해 주세요."}
  monitoringPrefs.edit().putBoolean("previewMonitoring",state.preview).putString("attendanceId",state.currentShift?.id).putString("deviceState",state.deviceState).apply()
  if(!state.preview&&enabled)repository.request("PUT","/attendances/${shiftId()}/device-status",mapOf("state" to "carried","observedAt" to Instant.now().toString()))
  if(enabled)ContextCompat.startForegroundService(app,Intent(app,SafetyService::class.java))else app.stopService(Intent(app,SafetyService::class.java))
  update(state.copy(monitoring=enabled));feedback=if(enabled)"감시 중에는 휴대폰을 몸에 휴대해 주세요." else "움직임 감시를 종료했어요."
 }
 fun highRisk(task:String?)=action {
  if(state.preview){require(state.currentShift!=null);val prompt=WorkPrompt(UUID.randomUUID().toString(),if(task==null)"high_risk_end" else "high_risk_start",now,"pending");update(state.copy(highRiskTask=task,highRiskStartedAt=task?.let{now},prompts=state.prompts+prompt));event("고위험 작업",task?.let(::highRiskLabel)?:"종료")}
  else{repository.request("PUT","/attendances/${shiftId()}/high-risk",mapOf("task" to task));changed()};monitoringPrefs.edit().putString("highRiskTask",task).apply()
 }
 fun answerPrompt(id:String,answer:String)=action {
  if(state.preview){
   update(state.copy(prompts=state.prompts.map{if(it.id==id)it.copy(state="answered",response=answer)else it}))
   event("작업 중 상태 응답",if(answer=="okay")"괜찮아요" else "도움이 필요해요")
   if(answer=="help_needed"&&state.activeCalls.none{it.isSubject(state.uid)}){
    val call=WorkerCall(UUID.randomUUID().toString(),subject=state.uid,subjectName=state.name,zone=state.zone,stage="peer",createdAt=now)
    update(state.copy(calls=listOf(call)+state.calls))
   }
  }else{repository.request("PUT","/attendances/${shiftId()}/check-ins/$id",mapOf("response" to answer));changed()}
  if(answer=="help_needed")state.activeCalls.firstOrNull{it.isSubject(state.uid)}?.let{requestedRoute="call:${it.id}"}
 }
 fun notificationPreferences(emergency:Boolean,site:Boolean)=action {
  if(state.preview)update(state.copy(emergencyPush=emergency,sitePush=site))else{repository.request("PUT","/me/notification-preferences",mapOf("emergencyPush" to emergency,"sitePush" to site));changed()}
 }
 @Suppress("DEPRECATION") fun refreshMonitoring(){val active=(app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getRunningServices(100).any{it.service.className==SafetyService::class.java.name};state=state.copy(monitoring=active)}
 private fun readPreviewSensor(){if(!state.preview)return;val id=monitoringPrefs.getString("previewSignalId",null)?:return;if(monitoringPrefs.getString("previewProcessed",null)==id)return;val at=monitoringPrefs.getLong("previewSignalAt",now);update(state.copy(calls=listOf(WorkerCall(id,subject=state.uid,subjectName=state.name,zone=state.zone,deadline=at+15000,createdAt=at))+state.calls));event("이상 움직임 감지","센서 추정 신호",true);monitoringPrefs.edit().putString("previewProcessed",id).apply()}
 fun simulateSelf(){if(!state.preview)return;if(state.activeCalls.any{it.isSubject(state.uid)})return;val call=WorkerCall(UUID.randomUUID().toString(),subject=state.uid,subjectName=state.name,zone=state.zone,createdAt=now);update(state.copy(calls=listOf(call)+state.calls));event("체험 이상 신호","사고 확정 아님",true);notifyPreview("괜찮으세요?","15초 안에 안전 상태를 확인해 주세요.",call.id)}
 fun simulatePrompt(){if(!state.preview)return;val prompt=WorkPrompt(UUID.randomUUID().toString(),"break_return",now,"pending");update(state.copy(prompts=state.prompts+prompt));requestedRoute="prompt:${prompt.id}"}
 fun simulateIncoming(){if(!state.preview)return;val call=WorkerCall(UUID.randomUUID().toString(),subject="preview-buddy",subjectName=state.buddyName,zone=state.buddyZone,stage="peer",createdAt=now);update(state.copy(calls=listOf(call)+state.calls));openCallId=call.id}
 fun respond(callId:String,response:String,localPhoto:String?=null)=runIncidentAction(callId) {
  val call=state.calls.firstOrNull{it.id==callId}?:error("요청 정보를 다시 불러와 주세요.")
  if(state.preview){val next=when{
   response=="safe"->{require(call.isSubject(state.uid));if((call.stage=="self"&&now<call.deadline)||call.acceptedBy==null)call.copy(status="closed",resolution="worker_confirmed_okay",respondedAt=now)else call.copy(workerResponse="okay",respondedAt=now)}
   response=="help"->call.copy(stage="peer")
   response=="accepted"->call.accept(state.uid).copy(acceptedAt=now)
   response=="arrived"->call.arrive(state.uid)
   response=="declined"->call.copy(serverStatus="unanswered")
   response=="retry"->call.copy(serverStatus="calling")
   response.startsWith("observe:")->call.observe(state.uid,response.substringAfter(':'))
   response.startsWith("finish:")->call.verify(state.uid,localPhoto,response.substringAfter(':'))
   else->error("지원하지 않는 응답입니다.")
  };update(state.copy(calls=state.calls.map{if(it.id==callId)next else it}));event(responseLabel(response),call.subjectName)}
  else{val path="/incidents/$callId";val result=when{
   response=="safe"||response=="help"->{val key="$callId:$response";val body=mapOf("requestId" to requestId(key),"response" to if(response=="safe")"okay" else "help_needed")
    val r=try{repository.request("PUT","$path/worker-response",body)}catch(e:java.io.IOException){
     if(e is ApiException&&!e.retryable)throw e
     feedback="연결되지 않아 응답을 전달하지 못했어요. 다시 시도하거나 119에 직접 연락해 주세요."
     return@runIncidentAction
    };requestIds.remove(key);r}
   response=="accepted"->repository.request("PUT","$path/accept",emptyMap())
   response=="arrived"->repository.request("PUT","$path/arrival",emptyMap())
   response=="declined"->repository.request("PUT","$path/decline",emptyMap())
   response=="retry"->repository.request("PUT","$path/retry",emptyMap())
   response.startsWith("observe:")->repository.request("PUT","$path/observation",mapOf("condition" to response.substringAfter(':')))
   response.startsWith("finish:")->{val body=mutableMapOf<String,Any?>("reason" to response.substringAfter(':'));if(localPhoto!=null)body["photoId"]=upload(localPhoto,"incident_scene");repository.request("PUT","$path/resolution",body)}
   else->error("지원하지 않는 응답입니다.")
  };rememberCall(result.obj("incident"));viewModelScope.launch{changed()}}
  if(response=="safe"&&monitoringPrefs.getString("localCallId",null)==callId){monitoringPrefs.edit().putString("ackAlert",monitoringPrefs.getString("localAlert",null)).apply();app.getSystemService(NotificationManager::class.java).cancel(103)}
  if(response=="safe")requestedRoute="ack:$callId"
  if(response=="help")requestedRoute="call:$callId"
  app.getSystemService(NotificationManager::class.java).cancel(callId.hashCode());feedback="응답을 저장했어요."
 }
 private suspend fun upload(path:String,purpose:String):String{val key="$purpose:$path";photoCache[key]?.let{return it};val bitmap=BitmapFactory.decodeFile(path)?:error("촬영 사진을 읽을 수 없어요.");return try{repository.upload(bitmap,purpose).also{photoCache[key]=it}}finally{bitmap.recycle()}}
 fun beginOnboarding(stage:String) {
  if(!state.preview && stage in setOf("code","sites") && state.hasSiteObligation()) {
   feedback="근무나 긴급 대응을 마친 뒤 현장을 바꿀 수 있어요.";return
  }
  onboarding = stage; pendingSite=null; choices=emptyList() }
 fun finishOnboarding() {
  session.edit().putBoolean("hasStarted",true).apply()
  onboarding=null
 }
 fun authenticate(email:String,password:String,name:String,signUp:Boolean)=action {
  serverDown=false
  try{if(signUp) repository.signUp(email.trim(),password,name.trim()) else repository.signIn(email.trim(),password)}
  catch(e:java.io.IOException){if(e is ApiException)throw e;serverDown=true;throw java.io.IOException("서버에 연결할 수 없어요.",e)}
  session.edit().putBoolean("connected",false).apply()
  generation++;polling?.cancel();photoCache.clear();requestIds.clear()
  val user=checkNotNull(repository.currentUser)
  state=WorkerState.live(user.uid,user.name)
  onboarding="sites"
  loadSites()
 }
 private suspend fun loadSites() {
  choices=repository.get("/sites/mine").rows("sites").map{
   SiteChoice(it.text("id"),it.text("name"),it.text("code"),it.text("foremanName"))
  }
  when(choices.size){
   0->onboarding="code"
   1->activateSite(choices.single().id)
   else->onboarding="sites"
  }
 }
 fun reloadSites()=action{loadSites()}
 fun chooseSite(id:String)=action { activateSite(id) }
 fun lookup(code:String)=action {
  val found=repository.request("POST","/sites/lookup",mapOf("code" to code.trim())).obj("site")
  pendingSite=SiteChoice(found.text("id"),found.text("name"),code.trim(),found.text("foremanName"))
 }
 fun joinPending()=action {
  val found=checkNotNull(pendingSite)
  ensureCanSwitch(found.id)
  repository.request("POST","/sites/${found.id}/join",mapOf("code" to found.code))
  activateSite(found.id)
 }
 private suspend fun ensureCanSwitch(id:String){
  val previous=repository.siteId
  if(previous.isBlank()||previous==id||state.preview)return
  val attendance=repository.get("/attendances/today?siteId=$previous").obj("attendance")
  val calls=repository.get("/incidents/active?siteId=$previous").rows("incidents")
  val uid=repository.currentUser?.uid
  require(!(attendance.isNotEmpty()&&attendance["checkedOutAt"]==null)&&calls.none{it["workerId"]==uid||it["responderId"]==uid}){
   "근무나 긴급 대응을 마친 뒤 현장을 바꿀 수 있어요."
  }
 }
 private suspend fun activateSite(id:String) {
  ensureCanSwitch(id)
  val site=repository.get("/sites/mine").rows("sites").first{it.text("id")==id}
  val user=checkNotNull(repository.currentUser)
  app.stopService(Intent(app,SafetyService::class.java));generation++;polling?.cancel()
  photoCache.clear();requestIds.clear();pendingSite=null;repository.siteId=id
  recordsMonth=YearMonth.now(koreaZone).toString();timelineDay=todayKey();riskPages=1;timelinePages=1;incidentPages=1
  state=WorkerState.live(user.uid,user.name).copy(siteId=id,site=site.text("name"),job=site.text("occupation"),zone=site.text("zone"))
  val needsProfile=site.text("occupation").isBlank()||site.text("zone").isBlank()
  session.edit().putBoolean("connected",true).putBoolean("needsProfile",needsProfile).apply()
  onboarding=if(needsProfile)"profile" else if(!session.getBoolean("hasStarted",false))"permissions" else null
  listen();changed();registerToken()
 }
 fun finishProfile(job:String,zone:String,wage:Int?)=action {
  if(state.preview){update(state.copy(job=job.trim(),zone=zone.trim(),wage=wage?:0,wageKnown=wage!=null));onboarding="permissions";return@action}
  repository.request("PUT","$sitePath/profile",mapOf("occupation" to job.trim(),"zone" to zone.trim(),"expectedWage" to wage))
  session.edit().putBoolean("needsProfile",false).apply()
  onboarding="permissions";changed()
 }
 fun cancelSiteConfirmation(){pendingSite=null}
 // 서버에 닿지 않으면 입력한 이름으로 체험 모드를 시작한다. 실제 가입으로 표시하지 않는다.
 fun continueOffline(name:String)=action {serverDown=false;feedback=null;enterPreview();if(name.isNotBlank())update(state.copy(name=name.trim()));onboarding="profile"}
 fun usePreview()=action {enterPreview()}
 private suspend fun enterPreview() {
  generation++;polling?.cancel();app.stopService(Intent(app,SafetyService::class.java))
  try {repository.logout()} finally {
   session.edit().putBoolean("connected",false).putBoolean("needsProfile",false).apply()
   state=storage.load().copy(monitoring=false);pendingSite=null;choices=emptyList()
   photoCache.clear();requestIds.clear();finishOnboarding()
  }
 }
 fun resetPreview(){if(!state.preview)return;app.stopService(Intent(app,SafetyService::class.java));update(WorkerState.preview());feedback="체험 기록을 초기화했어요."}
 fun emergency(incidentId:String?=null){if(state.preview){event("119 전화 화면 열기","실제 통화·접수 여부는 확인하지 않음");incidentId?.let{id->update(state.copy(calls=state.calls.map{if(it.id==id)it.copy(dialTimes=it.dialTimes+now)else it}))};return};if(!repository.hasSession||repository.siteId.isBlank()){feedback="전화 화면을 열었지만 로그인·현장 연결을 확인할 수 없어 기록은 저장하지 못했어요.";return};val activeId=incidentId?.takeUnless{id->state.calls.any{it.id==id&&it.status=="closed"}}
  val path=if(activeId==null)"$sitePath/dial-intents" else "/incidents/$activeId/dial-intent"
  runCatching{repository.queueRequest(if(activeId==null)"POST" else "PUT",path,mapOf("requestId" to UUID.randomUUID().toString(),"openedAt" to Instant.now().toString()))}.onFailure{feedback="전화 화면은 열었지만 기록 저장을 예약하지 못했어요."}}
 fun retrySignal()=action {
  val body=monitoringPrefs.getString("pendingSignal",null)
  val sameSession=monitoringPrefs.getString("signalSession",null)==repository.sessionId&&monitoringPrefs.getString("signalSite",null)==repository.siteId
  if(body!=null&&sameSession){
   val key=org.json.JSONObject(body).getString("requestId")
   val recovered=repository.recoverSignal(key,repository.identity)
   if(recovered!=null){openCall(recovered);requestedRoute="call:$recovered"}
  }else{
   refresh()
   val ongoing=state.activeCalls.firstOrNull{it.isSubject(state.uid)}
   if(ongoing!=null)requestedRoute="call:${ongoing.id}" else feedback="현재 서버에서 진행 중인 호출을 찾지 못했어요. 주변 동료나 반장에게 직접 연락해 주세요."
  }
 }
 fun openNotice(type:String,id:String?){when(type){"risk_action"->if(id!=null){requestedRoute="risk:$id"};"work_prompt"->if(id!=null){refreshNow();requestedRoute="prompt:$id"};"high_risk","high_risk_end"->{refreshNow();requestedRoute="peer"};"safety_review"->feedback="안전점검 검토 요청이에요. 반장은 관리자 화면에서 확인해 주세요."}}

 fun openCall(id:String){
  openCallId=id
  if(state.preview)return
  val openedAt=Instant.now().toString()
  val version=generation
  viewModelScope.launch {
   try {
    val call=repository.get("/incidents/$id").obj("incident")
    if(version!=generation)return@launch
    rememberCall(call)
    if(call["closedAt"]==null&&call.rows("recipients").any{it.text("userId")==state.uid}){
     repository.queueRequest("PUT","/incidents/$id/receipt",mapOf("stage" to "opened","occurredAt" to openedAt))
    }
    loadVerificationPhoto(id)
   }catch(e:CancellationException){throw e}catch(e:Exception){if(version==generation)feedback=e.localizedMessage?:"호출을 열 수 없어요."}
  }
 }
 fun loadVerificationPhoto(callId:String){val call=state.calls.firstOrNull{it.id==callId}?:return;if(state.preview||call.photoId==null||call.photo!=null)return;val version=generation;viewModelScope.launch{try{val path=cachedPhoto(call.photoId);if(version==generation&&!state.preview)state=state.copy(calls=state.calls.map{if(it.id==callId)it.copy(photo=path)else it})}catch(e:CancellationException){throw e}catch(e:Exception){if(version==generation)feedback="사진을 불러오지 못했어요."}}}
 fun loadRiskPhotos(id:String){if(state.preview)return;openRiskId=id;val version=generation;viewModelScope.launch{try{val risk=repository.get("$sitePath/risks/$id").obj("risk");val before=risk.nullText("beforePhotoId")?.let{cachedPhoto(it)};val after=risk.nullText("afterPhotoId")?.let{cachedPhoto(it)};if(version==generation&&openRiskId==id&&!state.preview)state=state.copy(selectedRisk=mapRisk(risk).copy(photo=before,afterPhoto=after))}catch(e:CancellationException){throw e}catch(e:Exception){if(version==generation)feedback="신고 사진을 불러오지 못했어요."}}}
 fun closeRisk(id:String){
  if(openRiskId==id){openRiskId=null;state=state.copy(selectedRisk=null)}
 }
 fun loadPeerPhotos(){if(state.preview)return;val version=generation;viewModelScope.launch{try{val ids=state.peerEvidence.mapNotNull{it.photoId};ids.forEach{cachedPhoto(it)};if(version==generation&&!state.preview)state=state.copy(peerEvidence=state.peerEvidence.map{it.copy(photo=photoCache[it.photoId])})}catch(e:CancellationException){throw e}catch(e:Exception){if(version==generation)feedback="확인 사진을 불러오지 못했어요."}}}
 private suspend fun cachedPhoto(id:String):String{photoCache[id]?.let{return it};val version=generation;val bytes=repository.photo(id);if(version!=generation)throw CancellationException();val file=withContext(Dispatchers.IO){File(app.cacheDir,"nest-media").apply{mkdirs()}.let{File(it,"${UUID.randomUUID()}.jpg")}.apply{writeBytes(bytes)}};if(version!=generation){file.delete();throw CancellationException()};photoCache[id]=file.path;return file.path}

 suspend fun incidentReport(id:String)=repository.get("$sitePath/reports/incidents/$id")
 suspend fun reportPhoto(id:String)=cachedPhoto(id)
 suspend fun report(path:String):ByteArray=repository.report(path)
 fun loadMonth(month:String){val next=YearMonth.parse(month).toString();if(next!=recordsMonth)incidentPages=1;recordsMonth=next;if(!state.preview)action{refresh()}}
 fun loadDay(day:String){if(day!=timelineDay){timelinePages=1;if(!state.preview)state=state.copy(events=emptyList(),nextTimeline=null)};timelineDay=day;if(!state.preview)action{refresh()}}
 fun refreshNow(){if(!state.preview)action{refresh()}}
 fun correctEvent(id:String,note:String)=action{require(note.isNotBlank());if(state.preview)event("정정 요청",note)else{val key="correction:$id:$note";val site=state.events.firstOrNull{it.id==id&&it.canCorrect}?.siteId?.takeIf{it.isNotBlank()}?:error("선택한 안전 기록을 다시 열어 주세요.");repository.request("POST","/sites/$site/timeline/$id/corrections",mapOf("requestId" to requestId(key),"note" to note));requestIds.remove(key);changed()};feedback="원본을 보존하고 정정 요청을 저장했어요."}
 fun loadMoreRisks()=action{if(state.nextRisks!=null){riskPages++;refresh()}}
 fun loadMoreTimeline()=action{if(state.nextTimeline!=null){timelinePages++;refresh()}}
 fun loadMoreIncidents()=action{if(state.nextIncidents!=null){incidentPages++;refresh()}}
 private suspend fun pages(path:String,field:String,nextKey:String,cursorKey:String,count:Int):Map<String,Any?>{val values=mutableListOf<Map<String,Any?>>();var cursor:Number?=null;var result:Map<String,Any?> = emptyMap();repeat(count){result=repository.get(path+(cursor?.let{"&$cursorKey=$it"}?:""));values+=result.rows(field);cursor=result.number(nextKey);if(cursor==null)return result+mapOf(field to values)};return result+mapOf(field to values)}

 private fun listen(){polling?.cancel();polling=viewModelScope.launch{while(isActive&&!state.preview){try{refresh()}catch(e:CancellationException){throw e}catch(e:Exception){handleSessionError(e);if(repository.hasSession)state=state.copy(connectionProblem=e.localizedMessage?:"서버 연결을 확인해 주세요.")};delay(5000)}}}
 private suspend fun refresh()=refreshMutex.withLock {
  if(state.preview)return@withLock
  val version=generation;val siteId=repository.siteId;val uid=state.uid;val selectedMonth=recordsMonth;val selectedDay=timelineDay
  if(siteId.isBlank())return@withLock
  val signedInUser=repository.currentUser ?: throw ApiException(401,"UNAUTHORIZED","다시 로그인해 주세요.")
  val sites=repository.get("/sites/mine").rows("sites")
  val site=sites.firstOrNull{it.text("id")==siteId}?:error("참여한 현장을 찾을 수 없어요. 다시 연결해 주세요.")
  val today=repository.get("/attendances/today?siteId=$siteId").obj("attendance")
  val context=if(today.isNotEmpty())repository.get("/attendances/${today.text("id")}/context")else emptyMap()
  val month=YearMonth.parse(selectedMonth)
  val work=repository.get("/attendances/records?from=${month.atDay(1)}&to=${month.atEndOfMonth()}").rows("attendances").map{row->mapWork(row).copy(site=sites.firstOrNull{it.text("id")==row.text("siteId")}?.text("name").orEmpty())}
  val risks=pages("/sites/$siteId/risks?limit=100","risks","nextOffset","offset",riskPages)
  val detailId=openRiskId
  val detail=detailId?.let{id->
   risks.rows("risks").firstOrNull{it.text("id")==id}
    ?: try{repository.get("/sites/$siteId/risks/$id").obj("risk")}
       catch(e:ApiException){if(e.status==404)null else throw e}
  }
  val recordSite=(if(selectedDay.startsWith(selectedMonth))work.firstOrNull{it.day==selectedDay}?.siteId else {
   repository.get("/attendances/records?from=$selectedDay&to=$selectedDay").rows("attendances").firstOrNull()?.text("siteId")
  })?.takeIf{it.isNotBlank()} ?: siteId
  val timeline=pages("/sites/$recordSite/timeline?scope=self&day=$selectedDay&limit=100","events","nextBefore","before",timelinePages)
  val revision=incidentRevision
  val active=repository.get("/incidents/active?siteId=$siteId").rows("incidents").map(::mapCall)
  val reports=pages("/sites/$siteId/reports/incidents?month=$selectedMonth&limit=100","incidents","nextOffset","offset",incidentPages)
  val prefs=repository.get("/me/notification-preferences")
  val corrections=repository.get("/sites/$recordSite/corrections").rows("corrections")
  if(version!=generation||state.preview||repository.siteId!=siteId||selectedMonth!=recordsMonth||selectedDay!=timelineDay)return@withLock
  val buddy=context.obj("buddy");val checks=today.obj("safetyCheck");val pairs=context.rows("peerChecks").map{PeerEvidence(it.text("checkerUserId"),it.text("attendanceId"),it.text("outcome"),parseTime(it["checkedAt"])?:0,it.nullText("photoId"),photoCache[it.nullText("photoId")])}
  val peerStatus=peerVerificationStatus(today.text("id"),uid,buddy.nullText("userId"),buddy.nullText("attendanceId"),site.text("foremanId"),pairs)
  val reportRows=reports.rows("incidents")
  val summaryCalls=reportRows.map{row->WorkerCall(id=row.text("id"),subject=if(site.text("role")=="worker")uid else "",detailLoaded=false,subjectName=row.text("workerName"),zone=row.text("zone"),stage="peer",status=if(row["closedAt"]!=null)"closed" else "pending",serverStatus=row.text("status"),createdAt=parseTime(row["createdAt"])?:0)}
  val selected=visibleCallId
  val openDetail=selected?.takeUnless{id->active.any{it.id==id}}?.let{id->
   state.calls.firstOrNull{it.id==id&&it.status=="closed"&&it.detailLoaded}
    ?:try{mapCall(repository.get("/incidents/$id").obj("incident"))}
    catch(e:CancellationException){throw e}catch(e:Exception){if(e is ApiException&&e.status==401)throw e; null}
  }
  if(version!=generation||siteId!=repository.siteId||selectedMonth!=recordsMonth||selectedDay!=timelineDay||revision!=incidentRevision)return@withLock
  val allCalls=mergeIncidentResults(active,openDetail?.takeIf{selected==visibleCallId},summaryCalls)
  val currentWork=if(today.isNotEmpty())listOf(mapWork(today).copy(site=site.text("name")))else emptyList()
  state=state.copy(name=signedInUser.name.ifBlank{state.name},siteId=siteId,site=site.text("name"),code=site.text("code"),job=site.text("occupation"),zone=site.text("zone"),wage=site.number("expectedWage")?.toInt()?:0,wageKnown=site["expectedWage"]!=null,
   work=(currentWork+work+state.work.filter{!it.day.startsWith(recordsMonth)}).distinctBy{it.id}.sortedByDescending{it.start},checks=listOf("fallHazard","floorAndGuardrail","protectiveEquipment").map{answerLabel(checks[it])},
   locationStatus=today.text("locationStatus").ifBlank{"unverified"},liveAttendanceId=today.nullText("id"),buddyId=buddy.nullText("userId"),buddyAttendanceId=buddy.nullText("attendanceId"),buddyName=buddy.text("name").ifBlank{"배정 대기"},buddyZone=buddy.nullText("zone")?:"구역 미확인",buddyState=buddy.obj("device").text("state").let{when(it){"carried"->"휴대 중";"not_carried"->"미휴대";else->"연결 미확인"}},
   carried=context.obj("device").text("state")=="carried",deviceState=context.obj("device").text("state").ifBlank{"unverified"},peerChecked=peerStatus in listOf("photo_confirmed","statement_only"),peerStatus=peerStatus,peerEvidence=pairs,highRiskTask=today.nullText("highRiskTask"),highRiskStartedAt=parseTime(today["highRiskStartedAt"]),
   prompts=context.rows("prompts").map{WorkPrompt(it.text("id"),it.text("kind"),parseTime(it["dueAt"])?:0,it.text("state"),it.nullText("response"))},
   risks=risks.rows("risks").map(::mapRisk),selectedRisk=if(openRiskId==detailId)detail?.let(::mapRisk) else state.selectedRisk,riskCounts=mapOf("open" to (risks.obj("counts").number("open")?.toInt()?:0),"resolved" to (risks.obj("counts").number("awaiting_recheck")?.toInt()?:0),"verified" to (risks.obj("counts").number("resolved")?.toInt()?:0)),nextRisks=risks.number("nextOffset")?.toInt(),events=timeline.rows("events").map(::mapEvent),nextTimeline=timeline.number("nextBefore")?.toInt(),calls=allCalls,reportCallIds=summaryCalls.map{it.id}.toSet(),nextIncidents=reports.number("nextOffset")?.toInt(),
   emergencyPush=prefs["emergencyPush"] as? Boolean?:true,sitePush=prefs["sitePush"] as? Boolean?:true,foremanPhone=site.nullText("foremanPhone"),corrections=corrections.map{"${when(it.text("status")){"accepted"->"수락";"declined"->"반려";else->"검토 대기"}} · ${it.text("note")}"+(it.nullText("reviewNote")?.let{review->"\n검토 의견 · $review"}?:"")},connectionProblem=null,lastSyncedAt=System.currentTimeMillis())
  monitoringPrefs.edit().putString("attendanceId",state.currentShift?.id).putString("highRiskTask",state.highRiskTask).putString("deviceState",state.deviceState).apply()
  if(state.currentShift==null&&state.monitoring){app.stopService(Intent(app,SafetyService::class.java));state=state.copy(monitoring=false)}
 }
 private fun mapWork(m:Map<String,Any?>)=WorkEntry(m.text("id"),m.text("workDate"),parseTime(m["checkedInAt"])?:0,parseTime(m["checkedOutAt"]),m.number("expectedWage")?.toInt()?:0,wageKnown=m["expectedWage"]!=null,zone=m.text("zone"),siteId=m.text("siteId"),occupation=m.text("occupation"))
 private fun mapRisk(m:Map<String,Any?>)=WorkerRisk(id=m.text("id"),title=m.text("title"),zone=m.text("zone"),note=m.text("note"),category=categoryLabel(m.text("category")),status=when(m.text("status")){"resolved"->"verified";"awaiting_recheck"->"resolved";else->"open"},photo=photoCache[m.nullText("beforePhotoId")],time=parseTime(m["createdAt"])?:0,anonymous=m["anonymous"]==true,beforePhotoId=m.nullText("beforePhotoId"),afterPhotoId=m.nullText("afterPhotoId"),afterPhoto=photoCache[m.nullText("afterPhotoId")],fixerName=m.nullText("fixerName"),verifierName=m.nullText("verifierName"),fixedAt=parseTime(m["fixedAt"]),verifiedAt=parseTime(m["verifiedAt"]),canRecheck=m["canRecheck"]==true)
 private fun mapEvent(m:Map<String,Any?>)=WorkerEvent(m.text("id"),eventTitle(m.text("type")),eventDetail(m.text("type"),m.obj("details")),parseTime(m["recordedAt"])?:0,m["source"]=="system",m["actorId"]==state.uid||m["subjectId"]==state.uid||(m["type"]=="risk.reported"&&m["actorId"]==null),m.text("siteId"))
 private fun mapCall(m:Map<String,Any?>):WorkerCall{val id=m.text("id");val status=m.text("status");val position=m.obj("location");return WorkerCall(id=id,subject=m.text("workerId"),subjectName=m.obj("worker").text("name").ifBlank{state.name},zone=m.text("zone"),stage=if(status=="awaiting_worker")"self" else "peer",status=when(status){"closed"->"closed";"responding","arrived"->"accepted";else->"pending"},deadline=parseTime(m[if(status=="awaiting_worker")"workerDueAt" else "nextCallAt"])?:0,acceptedBy=m.nullText("responderId"),arrivedAt=parseTime(m["arrivedAt"]),photo=photoCache[m.nullText("photoId")],resolution=m.nullText("resolution"),serverStatus=status,observation=m.nullText("observation"),photoId=m.nullText("photoId"),recipients=m.rows("recipients").map{CallRecipient(it.text("userId"),it.text("name"),it.text("role"),it.text("delivery"),parseTime(it["receivedAt"]),parseTime(it["openedAt"]),parseTime(it["acceptedAt"]),parseTime(it["declinedAt"]))},latitude=position.number("latitude")?.toDouble(),longitude=position.number("longitude")?.toDouble(),accuracy=position.number("accuracyMeters")?.toDouble(),workerResponse=m.nullText("workerResponse"),respondedAt=parseTime(m["workerRespondedAt"]),acceptedAt=parseTime(m["acceptedAt"]),dialTimes=m.rows("dialIntents").mapNotNull{parseTime(it["openedAt"])},observationNotice=m.rows("notices").lastOrNull{it.text("type")=="observation"}?.let{when(it.text("status")){"sent"->"발송 ${clockText(parseTime(it["sentAt"]))} · 단말 수신 미확인";"failed"->"발송 실패";else->"발송 대기"}},createdAt=parseTime(m["createdAt"])?:0)}
 private fun rememberCall(m:Map<String,Any?>){if(m.isNotEmpty()){incidentRevision++;val call=mapCall(m);state=state.copy(calls=listOf(call)+state.calls.filter{it.id!=call.id})}}
 private fun registerToken(){if(FirebaseApp.getApps(app).isEmpty())return;viewModelScope.launch{runCatching{repository.registerDevice(FirebaseMessaging.getInstance().token.await())}.onFailure{feedback="API 연결은 완료했지만 푸시 기기 등록에 실패했어요. 알림 설정을 확인해 주세요."}}}
 private fun notifyPreview(title:String,body:String,callId:String,openCall:Boolean=true){val nm=app.getSystemService(NotificationManager::class.java);nm.createNotificationChannel(NotificationChannel("alerts","안전 확인 요청",NotificationManager.IMPORTANCE_HIGH).apply{enableVibration(true)});val target=Intent(app,MainActivity::class.java).putExtra("alert",true);if(openCall)target.putExtra("callId",callId);val open=PendingIntent.getActivity(app,callId.hashCode(),target,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE);runCatching{nm.notify(callId.hashCode(),NotificationCompat.Builder(app,"alerts").setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle("[체험] $title").setContentText(body).setContentIntent(open).setFullScreenIntent(open,true).setCategory(NotificationCompat.CATEGORY_ALARM).setAutoCancel(true).build())}}
 override fun onCleared(){polling?.cancel();monitoringPrefs.unregisterOnSharedPreferenceChangeListener(preferencesListener);super.onCleared()}
}
@Suppress("UNCHECKED_CAST") private fun Map<String,Any?>.obj(key:String)=(this[key] as? Map<String,Any?>)?:emptyMap()
@Suppress("UNCHECKED_CAST") private fun Map<String,Any?>.rows(key:String)=(this[key] as? List<*>)?.mapNotNull{it as? Map<String,Any?>}?:emptyList()
private fun Map<String,Any?>.text(key:String)=this[key]?.toString().orEmpty()
private fun Map<String,Any?>.nullText(key:String)=this[key]?.toString()?.takeIf{it.isNotBlank()}
private fun Map<String,Any?>.number(key:String)=this[key] as? Number
private fun answerLabel(value:Any?)=when(value){"confirmed"->"확인";"needs_attention"->"개선 필요";"unknown"->"모름";else->""}
private fun categoryCode(label:String)=mapOf("추락" to "fall","넘어짐" to "trip","충돌" to "collision","감전" to "electric")[label]?:"other"
private fun categoryLabel(code:String)=mapOf("fall" to "추락","trip" to "넘어짐","collision" to "충돌","electric" to "감전")[code]?:"기타"
internal fun eventTitle(type:String)=mapOf("incident.responder_unavailable" to "수락자 대응 불가 · 다른 동료 요청","incident.verification_overdue" to "현장 상태 확인 지연 · 반장 확인 요청","incident.worker_unanswered" to "본인 응답 없음","incident.response_delayed" to "동료 수락 지연","attendance.check_in" to "출근","attendance.check_out" to "퇴근","safety_check.saved" to "작업 전 점검","safety_check.corrected" to "안전점검 수정","safety_check.reviewed" to "반장 점검 확인","peer.assigned" to "안전 동료 배정","work.high_risk_started" to "고위험 작업 시작","work.high_risk_ended" to "고위험 작업 종료","work.check_in_answered" to "작업 중 상태 응답","correction.requested" to "정정 요청","correction.accepted" to "정정 수락","correction.declined" to "정정 반려","incident.dialer_opened" to "119 전화 화면 열기","peer.confirmed" to "동료 확인","peer.mismatch" to "보호구 불일치","peer.remediated" to "보호구 재확인","risk.reported" to "위험 신고","risk.fixed" to "위험 조치","risk.rechecked" to "위험 재확인","incident.signal_detected" to "이상 움직임 감지","incident.help_requested" to "도움 요청","incident.contacts_requested" to "동료·반장 호출","incident.zone_requested" to "같은 구역 동료 호출","incident.no_responder" to "수락자 없음","incident.received" to "호출 수신","incident.opened" to "호출 열람","incident.declined" to "현장 확인 거절","incident.worker_safe" to "본인 안전 응답","incident.observation" to "현장 상태 확인","incident.retry" to "동료 다시 호출","risk.fix_requested" to "위험 조치 요청","attendance.site_linked" to "출근 현장 연결","incident.accepted" to "현장 확인 수락","incident.arrived" to "현장 도착","incident.closed" to "대응 완료")[type]?:"안전 기록"

private fun eventDetail(type:String,d:Map<String,Any?>):String=when{
 type.startsWith("safety_check.")->listOf("fallHazard" to "추락 위험","floorAndGuardrail" to "바닥·난간","protectiveEquipment" to "보호구").mapNotNull{(key,label)->answerLabel(d[key]).takeIf{it.isNotBlank()}?.let{"$label $it"}}.joinToString(" · ").ifBlank{"반장이 확인한 점검 기록"}
 type=="attendance.check_in"->when(d.text("locationStatus")){"inside"->"위치 현장 안";"outside"->"위치 현장 밖";else->"위치 미확인"}
 type=="attendance.check_out"->"오늘 근무 마무리"
 type=="peer.assigned"->"안전 동료 배정 기록"
 type=="peer.mismatch"->"보호구 불일치 · 조치 후 다시 확인"
 type.startsWith("peer.")->if(d.nullText("photoId")!=null)"사진과 함께 직접 확인" else "사진 없는 직접 확인 진술"
 type=="risk.reported"->"${categoryLabel(d.text("category"))} · ${d.text("zone") }"
 type=="risk.fixed"->"조치 사진 저장 · 다른 동료의 재확인 대기"
 type=="risk.rechecked"->"조치자·신고자 외 동료가 재확인"
 type.startsWith("correction.")->d.text("note")
 type=="work.check_in_answered"->if(d.text("response")=="okay")"괜찮아요" else "도움이 필요해요"
 type=="work.high_risk_started"->mapOf("scaffold" to "비계","ladder" to "사다리","opening" to "개구부","aerial_lift" to "고소작업대")[d.text("task")]?:"고위험 작업"
 type=="work.high_risk_ended"->"고위험 작업 종료 후 안부 확인"
 type=="incident.signal_detected"->d.number("peakG")?.let{"최대 충격 ${it}G · 사고 확정 아님"}?:"센서 추정 신호 · 사고 확정 아님"
 type=="incident.observation"->mapOf("unconscious" to "의식 없음","immobile" to "움직이기 어려움","conscious" to "의식 있음","appears_safe" to "안전해 보임")[d.text("condition")]?:"직접 본 상태 기록"
 type=="incident.dialer_opened"->"전화 화면 열기 · 실제 통화·신고 접수 증명 아님"
 else->"서버에 저장된 원본 기록"
}
