package com.safeloop.app

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.LocationManager
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
 private var afterNotifications:(()->Unit)?=null
 private val notificationPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){granted->val action=afterNotifications;afterNotifications=null;if(granted)action?.invoke()else controller.feedback="알림을 허용하면 본인 확인과 동료 요청을 받을 수 있어요. 내 정보에서 알림 설정을 확인해 주세요."}
 private val cameraPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){granted->if(granted)launchCamera()else{photoCallback=null;controller.feedback="현장 사진을 찍으려면 카메라 권한을 허용해 주세요."}}
 private val locationPermission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){result->if(result.values.any{it})controller.checkIn(lastLocation())else controller.feedback="현장 출근 확인에 위치 권한이 필요합니다."}
 private val camera=registerForActivityResult(ActivityResultContracts.TakePicture()){ok->
  val file=photoFile;photoFile=null
  if(!ok||file==null){file?.delete();photoCallback?.invoke(null);photoCallback=null;return@registerForActivityResult}
  processingPhoto=true
  lifecycleScope.launch{val path=runCatching{withContext(Dispatchers.IO){preparePhoto(file)}}.getOrElse{file.delete();controller.feedback="사진을 준비하지 못했어요. 다시 촬영해 주세요.";null};val callback=photoCallback;photoCallback=null;processingPhoto=false;callback?.invoke(path)}
 }
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState)
  WindowCompat.setDecorFitsSystemWindows(window,false)
  WindowCompat.getInsetsController(window,window.decorView).apply{isAppearanceLightStatusBars=true;isAppearanceLightNavigationBars=true}
  controller=ViewModelProvider(this)[WorkerController::class.java]
  handleIntent(intent)
  setContent{WorkerTheme{WorkerApp(controller,WorkerActions(dial=::dial,camera=::capture,attend=::attend,monitor={enabled->if(enabled)notifications{controller.setMonitoring(true)}else controller.setMonitoring(false)},export=::exportRecords,notificationSettings=::notificationSettings,notifications=::notifications))}}
 }
 override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);handleIntent(intent)}
 override fun onResume(){super.onResume();if(::controller.isInitialized)controller.refreshMonitoring()}
 private fun handleIntent(intent:Intent){controller.openCallId=intent.getStringExtra("callId");if(intent.getBooleanExtra("alert",false)&&intent.getStringExtra("callId")==null&&!controller.state.preview)controller.feedback="휴대폰 감지 알림입니다. 서버 연결을 확인할 수 없으면 주변 동료에게 직접 연락해 주세요."}
 private fun notifications(action:()->Unit){
  val manager=getSystemService(NotificationManager::class.java)
  if(manager.getNotificationChannel("alerts")?.importance==NotificationManager.IMPORTANCE_NONE){controller.feedback="내 정보의 알림 설정에서 ‘안전 확인 요청’을 허용해 주세요.";return}
  if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){afterNotifications=action;notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)}
  else if(!NotificationManagerCompat.from(this).areNotificationsEnabled())controller.feedback="앱 알림이 차단되어 있어요. 내 정보에서 알림을 허용해 주세요."
  else action()
 }
 private fun capture(callback:(String?)->Unit){if(processingPhoto){controller.feedback="사진을 준비하고 있어요. 잠시 기다려 주세요.";return};photoCallback=callback;if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)cameraPermission.launch(Manifest.permission.CAMERA)else launchCamera()}
 private fun launchCamera(){try{val dir=File(filesDir,"captures").apply{mkdirs()};photoFile=File(dir,"${UUID.randomUUID()}.jpg");val uri=FileProvider.getUriForFile(this,"com.safeloop.app.files",photoFile!!);camera.launch(uri)}catch(e:Exception){photoFile?.delete();photoFile=null;photoCallback=null;controller.feedback="카메라를 열 수 없어요. 기기 카메라 설정을 확인해 주세요."}}
 private fun preparePhoto(file:File):String{
  val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(file.path,bounds)
  val sample=generateSequence(1){it*2}.first{bounds.outWidth/it<=1600&&bounds.outHeight/it<=1600}
  val original=BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply{inSampleSize=sample})?:error("Invalid photograph")
  val orientation=runCatching{ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)}.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
  val degrees=when(orientation){ExifInterface.ORIENTATION_ROTATE_90->90f;ExifInterface.ORIENTATION_ROTATE_180->180f;ExifInterface.ORIENTATION_ROTATE_270->270f;else->0f}
  val bitmap=if(degrees==0f)original else Bitmap.createBitmap(original,0,0,original.width,original.height,Matrix().apply{postRotate(degrees)},true)
  file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,85,it)};if(bitmap!==original)bitmap.recycle();original.recycle();return file.path
 }
 private fun attend(){if(controller.state.preview){controller.checkIn();return};if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED&&ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED)locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))else controller.checkIn(lastLocation())}
 private fun lastLocation():Map<String,Any?>?=runCatching{val manager=getSystemService(LOCATION_SERVICE) as LocationManager;manager.getProviders(true).mapNotNull{manager.getLastKnownLocation(it)}.filter{System.currentTimeMillis()-it.time<120000}.maxByOrNull{it.time}?.let{mapOf("lat" to it.latitude,"lng" to it.longitude,"accuracy" to it.accuracy.toDouble())}}.getOrNull()
 private fun dial(){controller.emergency();runCatching{startActivity(Intent(Intent.ACTION_DIAL,Uri.parse("tel:119")))}.onFailure{controller.feedback="전화 앱을 열 수 없습니다. 다른 전화로 119에 연락해 주세요."}}
 private fun notificationSettings(){runCatching{startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,packageName))}.onFailure{controller.feedback="기기 설정에서 안전고리의 알림을 허용해 주세요."}}
 private fun exportRecords(){runCatching{val dir=File(cacheDir,"exports").apply{mkdirs()};val file=File(dir,"safeloop-work-${todayKey()}.csv");file.writeText(workCsv(controller.state),Charsets.UTF_8);val uri=FileProvider.getUriForFile(this,"com.safeloop.app.files",file);startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"내 근로 기록 내보내기"))}.onFailure{controller.feedback="기록을 내보내지 못했어요. 공유 앱과 기기 저장 공간을 확인해 주세요."}}
}
