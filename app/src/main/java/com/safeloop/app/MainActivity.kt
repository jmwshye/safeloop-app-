package com.safeloop.app

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.location.Location
import androidx.core.location.LocationManagerCompat
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MainActivity:ComponentActivity(){
 private lateinit var controller:WorkerController
 private lateinit var captures:WorkerCapture
 private var setupScope:String?=null
 private var locationRequest:LocationRequest?=null
 private var resolvingLocation=false
 private var responderLocation:((Location?)->Unit)?=null
 private val setupPermissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){ if(setupScope==controller.captureScope && controller.onboarding=="permissions")controller.finishOnboarding();setupScope=null }
 private val ppeCamera=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){ result ->
  val data=result.data
  if(captures.scope!=controller.captureScope){captures.cancel();return@registerForActivityResult}
  val ok=result.resultCode==RESULT_OK
  if(ok&&data?.getBooleanExtra("dial119",false)==true)dial(captures.subject?.takeIf{captures.guide=="scene"})
  val mismatch=ok&&data?.getBooleanExtra("mismatch",false)==true&&captures.guide=="peer_check"
  if(mismatch || (ok && data?.data!=null))captures.finish(mismatch) else captures.cancel()
 }
 private var notificationAction:NotificationAction?=null
 private var notificationScope:String?=null
 private val notificationPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){granted->val action=notificationAction;notificationAction=null;if(granted)action?.takeIf{notificationScope==controller.captureScope}?.let(::performNotificationAction)else controller.feedback="알림을 허용하면 본인 확인과 동료 요청을 받을 수 있어요. 내 정보에서 알림 설정을 확인해 주세요."}
 private val cameraPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){granted->if(granted&&captures.scope==controller.captureScope)launchCamera()else{captures.cancel();controller.feedback="현장 사진을 찍으려면 카메라 권한을 허용해 주세요."}}
 private val locationPermission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){result->if(result.values.any{it})getLocation()else useLocation(null)}
 override fun onSaveInstanceState(outState:Bundle) {
  super.onSaveInstanceState(outState)
  outState.putString("locationPurpose",locationRequest?.purpose?.name)
  outState.putString("locationScope",locationRequest?.scope)
  outState.putBoolean("resolvingLocation",resolvingLocation)
  outState.putString("setupScope",setupScope)
  outState.putString("notificationScope",notificationScope)
  outState.putString("notificationAction",notificationAction?.name)
 }
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState)
  WindowCompat.setDecorFitsSystemWindows(window,false)
  WindowCompat.getInsetsController(window,window.decorView).apply{isAppearanceLightStatusBars=true;isAppearanceLightNavigationBars=true}
  controller=ViewModelProvider(this)[WorkerController::class.java]
  captures=ViewModelProvider(this)[WorkerCapture::class.java]
  setupScope=savedInstanceState?.getString("setupScope")
  notificationScope=savedInstanceState?.getString("notificationScope")
  notificationAction=NotificationAction.entries.find{it.name==savedInstanceState?.getString("notificationAction")}
  locationRequest=LocationRequest.restore(savedInstanceState?.getString("locationPurpose"),savedInstanceState?.getString("locationScope"))
  if(locationRequest!=null&&savedInstanceState?.getBoolean("resolvingLocation")==true)getLocation()
  if(savedInstanceState==null)handleIntent(intent)
  setContent{
   val photo=captures.result
   val scope=controller.captureScope
   val state=controller.state
   androidx.compose.runtime.LaunchedEffect(photo,scope,state.buddyAttendanceId,state.lastSyncedAt!=null){
    if(photo!=null && photo.scope!=scope)captures.discardResult()
    else if(photo?.guide=="peer_check"&&!state.preview&&state.lastSyncedAt!=null&&photo.subject!=state.buddyAttendanceId){
     captures.discardResult()
     controller.feedback="동료가 바뀌어 이전 동료의 촬영 결과를 폐기했어요."
    }
   }
   androidx.compose.runtime.LaunchedEffect(captures.failure){captures.failure?.let{controller.feedback=it}}
   WorkerTheme{
    androidx.compose.runtime.key(scope){WorkerApp(controller,WorkerActions(
     dial={dial()},capture=::capture,cameraResult=photo?.takeIf{it.scope==scope},consumeCamera=captures::consume,
     attend={requestLocation(LocationPurpose.CHECK_IN)},monitor={enabled->if(enabled)notifications(NotificationAction.MONITOR)else controller.setMonitoring(false)},
     export=::exportRecords,notificationSettings=::notificationSettings,notifications=::notifications,shareReport=::shareReport,map=::openMap,phone=::callPhone,dialIncident={dial(it)},
     checkLocation={requestLocation(LocationPurpose.CHECK_ONLY)},locate={callback->if(locationRequest==null){responderLocation=callback;requestLocation(LocationPurpose.RESPONDER)}else controller.feedback="위치 확인 중이에요."},
     onboardingPermissions=::requestSetupPermissions))}}
  }
 }
 override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);handleIntent(intent)}
 override fun onResume(){super.onResume();if(::controller.isInitialized)controller.refreshMonitoring()}
 private fun handleIntent(intent:Intent){
  val matches=controller.acceptsNotification(intent.getStringExtra("siteId"),intent.getStringExtra("recipientId"))
  if(intent.getBooleanExtra("dial119",false)){intent.removeExtra("dial119");dial(intent.getStringExtra("callId"),matches);return}
  if(matches)intent.getStringExtra("notificationType")?.let{controller.openNotice(it,intent.getStringExtra("entityId"))}
  intent.getStringExtra("callId")?.let{if(matches)controller.openCall(it)else controller.feedback="현재 연결한 계정·현장의 알림이 아니에요."}
  if(intent.getBooleanExtra("alert",false)&&intent.getStringExtra("callId")==null&&!controller.state.preview&&matches)controller.requestedRoute="call-failed"
 }

 private fun performNotificationAction(action:NotificationAction) {
  when(action) {
   NotificationAction.MONITOR->controller.setMonitoring(true)
   NotificationAction.DEMO_SELF->controller.simulateSelf()
   NotificationAction.DEMO_PEER->controller.simulateIncoming()
  }
 }
 private fun notifications(action:NotificationAction){
  val manager=getSystemService(NotificationManager::class.java)
  if(manager.getNotificationChannel("alerts")?.importance==NotificationManager.IMPORTANCE_NONE){controller.feedback="내 정보의 알림 설정에서 ‘안전 확인 요청’을 허용해 주세요.";return}
  if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){if(notificationAction!=null)return;notificationAction=action;notificationScope=controller.captureScope;notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)}
  else if(!NotificationManagerCompat.from(this).areNotificationsEnabled())controller.feedback="앱 알림이 차단되어 있어요. 내 정보에서 알림을 허용해 주세요."
  else performNotificationAction(action)
 }
 private fun requestSetupPermissions(){
  val permissions=mutableListOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.CAMERA)
  if(Build.VERSION.SDK_INT>=33)permissions+=Manifest.permission.POST_NOTIFICATIONS
  setupScope=controller.captureScope;setupPermissions.launch(permissions.toTypedArray())
 }
 private fun capture(guide:String, subject:String) {
  if(!captures.begin(controller.captureScope,guide,subject)){controller.feedback="사진을 준비하고 있어요. 잠시 기다려 주세요.";return}
  if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)cameraPermission.launch(Manifest.permission.CAMERA) else launchCamera()
 }
 private fun launchCamera(){
  val file=captures.file?:return
  try{
   val uri=FileProvider.getUriForFile(this,"com.safeloop.app.files",file)
   ppeCamera.launch(Intent(this,PpeCameraActivity::class.java).setData(uri).putExtra("guide",captures.guide).putExtra("previewMode",controller.state.preview))
  }catch(e:Exception){captures.cancel();controller.feedback="카메라를 열 수 없어요. 기기 카메라 설정을 확인해 주세요."}
 }
 private fun requestLocation(purpose:LocationPurpose){
  if(controller.state.preview){if(purpose==LocationPurpose.CHECK_IN)controller.checkIn();return}
  if(locationRequest!=null){controller.feedback="위치 확인 중이에요.";return}
  locationRequest=LocationRequest(purpose,controller.captureScope)
  if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED &&
     ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED)
      locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))
  else getLocation()
 }
 private fun useLocation(location:Location?){
  val purpose=locationRequest?.forScope(controller.captureScope)
  locationRequest=null
  resolvingLocation=false
  val callback=responderLocation
  responderLocation=null
  if(purpose==null)return
  val fix=location?.let{mapOf("latitude" to it.latitude,"longitude" to it.longitude,"accuracyMeters" to it.accuracy.toDouble(),"capturedAt" to java.time.Instant.ofEpochMilli(it.time).toString())}
  when(purpose) {
   LocationPurpose.CHECK_IN -> controller.checkIn(fix)
   LocationPurpose.CHECK_ONLY -> controller.verifyLocation(fix)
   LocationPurpose.RESPONDER -> if(callback!=null)callback(location) else controller.feedback="거리 확인을 다시 눌러 주세요."
  }
 }
 @android.annotation.SuppressLint("MissingPermission")
 private fun getLocation(){
  if(locationRequest==null || resolvingLocation)return
  resolvingLocation=true
  lifecycleScope.launch {
   val manager=getSystemService(LOCATION_SERVICE) as LocationManager
   val fix=try {
    val providers=manager.getProviders(true)
    val fresh=providers.mapNotNull{manager.getLastKnownLocation(it)}.filter{it.hasAccuracy()&&System.currentTimeMillis()-it.time in 0L..30_000L}.minByOrNull{it.accuracy}
    val provider=listOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER).firstOrNull{it in providers}
    fresh?:provider?.let{withTimeoutOrNull(5000L){suspendCancellableCoroutine<Location?>{cont->
     val cancel=CancellationSignal();cont.invokeOnCancellation{cancel.cancel()}
     LocationManagerCompat.getCurrentLocation(manager,it,cancel,ContextCompat.getMainExecutor(this@MainActivity)){loc->if(cont.isActive)cont.resume(loc)}
    }}}
   }catch(_:SecurityException){null}catch(_:IllegalArgumentException){null}
   useLocation(fix?.takeIf{it.hasAccuracy()&&it.accuracy.isFinite()&&it.accuracy>=0})
  }
 }
 private fun dial(incidentId:String?=null,record:Boolean=true){runCatching{startActivity(Intent(Intent.ACTION_DIAL,Uri.parse("tel:119")))}.onSuccess{if(record)controller.emergency(incidentId)}.onFailure{controller.feedback="전화 앱을 열 수 없습니다. 다른 전화로 119에 연락해 주세요."}}
 private fun notificationSettings(){runCatching{startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,packageName))}.onFailure{controller.feedback="기기 설정에서 안전고리의 알림을 허용해 주세요."}}
 private fun exportRecords(from:String,to:String,format:String,callId:String?){
  if(!controller.state.preview||format !in listOf("pdf","csv"))return
  val state=controller.state
  lifecycleScope.launch{
   try{
    val file=withContext(Dispatchers.IO){createPreviewExport(cacheDir,state,from,to,format,callId)}
    val uri=FileProvider.getUriForFile(this@MainActivity,"com.safeloop.app.files",file)
    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(if(format=="pdf")"application/pdf" else "text/csv").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"체험 기록 내보내기"))
   }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){controller.feedback="체험 기록을 내보내지 못했어요."}
  }
 }
 private fun callPhone(number:String){runCatching{startActivity(Intent(Intent.ACTION_DIAL,Uri.parse("tel:"+Uri.encode(number))))}.onFailure{controller.feedback="전화 앱을 열 수 없어요."}}
 private fun openMap(latitude:Double,longitude:Double){runCatching{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude")))}.onFailure{controller.feedback="지도 앱을 열 수 없어요. 위치: $latitude, $longitude"}}
 private fun shareReport(path:String,format:String){
  if(format !in listOf("pdf","csv"))return
  lifecycleScope.launch{
   try{val version=controller.sessionVersion;val bytes=controller.report(path);if(version!=controller.sessionVersion||controller.state.preview)return@launch;val file=withContext(Dispatchers.IO){File(cacheDir,"nest-media").apply{mkdirs()}.let{File(it,"safeloop-${UUID.randomUUID()}.$format")}.apply{writeBytes(bytes)}}
    if(version!=controller.sessionVersion||controller.state.preview){file.delete();return@launch}
    val uri=FileProvider.getUriForFile(this@MainActivity,"com.safeloop.app.files",file)
    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(if(format=="pdf")"application/pdf" else "text/csv").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"서버 기록 내보내기"))
   }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){controller.feedback=e.localizedMessage?:"보고서를 내보내지 못했어요."}
  }
 }

}
