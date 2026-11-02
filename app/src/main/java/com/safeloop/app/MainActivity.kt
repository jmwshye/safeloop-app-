package com.safeloop.app

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.LocationManager
import android.location.Location
import androidx.core.location.LocationManagerCompat
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import android.media.ExifInterface
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
 private var photoCallback:((String?)->Unit)?=null
 private var photoFile:File?=null
 private var processingPhoto=false
 private var photoGuide="scene"
 private var photoIncident:String?=null
 private var afterPermissions:(()->Unit)?=null
 private var locationPending=false
 private var locationVersion=0
 private var checkingOnly=false
 private var responderLocation:((Location?)->Unit)?=null
 private val setupPermissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){ afterPermissions?.invoke();afterPermissions=null }
 private val ppeCamera=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){ result ->
  val intent=result.data
  if(result.resultCode==RESULT_OK && intent?.getBooleanExtra("dial119",false)==true) dial(photoIncident)
  if(result.resultCode==RESULT_OK && intent?.getBooleanExtra("mismatch",false)==true)controller.peerMismatchRequested=true
  finishCamera(result.resultCode==RESULT_OK && intent?.data!=null)
 }
 private var afterNotifications:(()->Unit)?=null
 private val notificationPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){granted->val action=afterNotifications;afterNotifications=null;if(granted)action?.invoke()else controller.feedback="알림을 허용하면 본인 확인과 동료 요청을 받을 수 있어요. 내 정보에서 알림 설정을 확인해 주세요."}
 private val cameraPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){granted->if(granted)launchCamera()else{photoCallback=null;controller.feedback="현장 사진을 찍으려면 카메라 권한을 허용해 주세요."}}
 private val locationPermission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){result->if(result.values.any{it})getLocation()else useLocation(null)}
 private fun finishCamera(ok:Boolean){
  val file=photoFile;photoFile=null;photoIncident=null
  if(!ok||file==null){file?.delete();photoCallback?.invoke(null);photoCallback=null;return}
  processingPhoto=true
  lifecycleScope.launch{val path=runCatching{withContext(Dispatchers.IO){preparePhoto(file)}}.getOrElse{file.delete();controller.feedback="사진을 준비하지 못했어요. 다시 촬영해 주세요.";null};val callback=photoCallback;photoCallback=null;processingPhoto=false;callback?.invoke(path)}
 }
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState)
  WindowCompat.setDecorFitsSystemWindows(window,false)
  WindowCompat.getInsetsController(window,window.decorView).apply{isAppearanceLightStatusBars=true;isAppearanceLightNavigationBars=true}
  controller=ViewModelProvider(this)[WorkerController::class.java]
  handleIntent(intent)
  setContent{WorkerTheme{WorkerApp(controller,WorkerActions(dial={dial()},camera={cb->capture(cb,"risk_before")},incidentCamera={id,cb->capture(cb,"scene",id)},fixCamera={cb->capture(cb,"risk_after")},attend=::attend,monitor={enabled->if(enabled)notifications{controller.setMonitoring(true)}else controller.setMonitoring(false)},export=::exportRecords,notificationSettings=::notificationSettings,notifications=::notifications,shareReport=::shareReport,map=::openMap,phone=::callPhone,dialIncident={dial(it)},checkLocation={requestLocation(true)},locate={callback->if(!locationPending){responderLocation=callback;requestLocation(true)}else controller.feedback="위치 확인 중이에요."},peerCamera={cb->capture(cb,"peer_check")},onboardingPermissions=::requestSetupPermissions))}}
 }
 override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);handleIntent(intent)}
 override fun onResume(){super.onResume();if(::controller.isInitialized)controller.refreshMonitoring()}
 private fun handleIntent(intent:Intent){
  val matches=controller.acceptsNotification(intent.getStringExtra("siteId"))
  if(intent.getBooleanExtra("dial119",false)){intent.removeExtra("dial119");dial(intent.getStringExtra("callId"),matches);return}
  if(matches)intent.getStringExtra("notificationType")?.let{controller.openNotice(it,intent.getStringExtra("entityId"))}
  intent.getStringExtra("callId")?.let{if(matches)controller.openCall(it)else controller.feedback="현재 연결한 계정·현장의 알림이 아니에요."}
  if(intent.getBooleanExtra("alert",false)&&intent.getStringExtra("callId")==null&&!controller.state.preview&&matches)controller.requestedRoute="call-failed"
 }

 private fun notifications(action:()->Unit){
  val manager=getSystemService(NotificationManager::class.java)
  if(manager.getNotificationChannel("alerts")?.importance==NotificationManager.IMPORTANCE_NONE){controller.feedback="내 정보의 알림 설정에서 ‘안전 확인 요청’을 허용해 주세요.";return}
  if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){afterNotifications=action;notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)}
  else if(!NotificationManagerCompat.from(this).areNotificationsEnabled())controller.feedback="앱 알림이 차단되어 있어요. 내 정보에서 알림을 허용해 주세요."
  else action()
 }
 private fun requestSetupPermissions(done:()->Unit){
  val permissions=mutableListOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.CAMERA)
  if(Build.VERSION.SDK_INT>=33)permissions+=Manifest.permission.POST_NOTIFICATIONS
  afterPermissions=done;setupPermissions.launch(permissions.toTypedArray())
 }
 private fun capture(callback:(String?)->Unit,guide:String,incident:String?=null){if(photoCallback!=null||processingPhoto){controller.feedback="사진을 준비하고 있어요. 잠시 기다려 주세요.";return};photoGuide=guide;photoIncident=incident;photoCallback=callback;if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)cameraPermission.launch(Manifest.permission.CAMERA)else launchCamera()}
 private fun launchCamera(){try{val dir=File(filesDir,"captures").apply{mkdirs()};photoFile=File(dir,"${UUID.randomUUID()}.jpg");val uri=FileProvider.getUriForFile(this,"com.safeloop.app.files",photoFile!!);ppeCamera.launch(Intent(this,PpeCameraActivity::class.java).setData(uri).putExtra("guide",photoGuide).putExtra("previewMode",controller.state.preview))}catch(e:Exception){photoFile?.delete();photoFile=null;photoCallback=null;controller.feedback="카메라를 열 수 없어요. 기기 카메라 설정을 확인해 주세요."}}
 private fun preparePhoto(file:File):String{
  val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(file.path,bounds)
  val sample=generateSequence(1){it*2}.first{bounds.outWidth/it<=1600&&bounds.outHeight/it<=1600}
  val original=BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply{inSampleSize=sample})?:error("Invalid photograph")
  val orientation=runCatching{ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)}.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
  val degrees=when(orientation){ExifInterface.ORIENTATION_ROTATE_90->90f;ExifInterface.ORIENTATION_ROTATE_180->180f;ExifInterface.ORIENTATION_ROTATE_270->270f;else->0f}
  val bitmap=if(degrees==0f)original else Bitmap.createBitmap(original,0,0,original.width,original.height,Matrix().apply{postRotate(degrees)},true)
  file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,85,it)};if(bitmap!==original)bitmap.recycle();original.recycle();return file.path
 }
 private fun attend()=requestLocation(false)
 private fun requestLocation(checkOnly:Boolean){
  if(controller.state.preview){if(!checkOnly)controller.checkIn();return}
  if(locationPending){controller.feedback="위치 확인 중이에요.";return}
  locationPending=true;locationVersion=controller.sessionVersion;checkingOnly=checkOnly
  if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED &&
     ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED)
      locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))
  else getLocation()
 }
 private fun useLocation(location:Location?){
  locationPending=false
  if(locationVersion!=controller.sessionVersion){responderLocation=null;return}
  responderLocation?.let{callback->responderLocation=null;callback(location);return}
  val fix=location?.let{mapOf("latitude" to it.latitude,"longitude" to it.longitude,"accuracyMeters" to it.accuracy.toDouble(),"capturedAt" to java.time.Instant.ofEpochMilli(it.time).toString())}
  if(checkingOnly)controller.verifyLocation(fix)else controller.checkIn(fix)
 }
 @android.annotation.SuppressLint("MissingPermission")
 private fun getLocation(){
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
