package com.safeloop.app
import android.app.*
import android.content.*
import android.hardware.*
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
class SafetyService:Service(),SensorEventListener {
 private lateinit var sensors:SensorManager
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
 private val detector=MotionDetector()
 private val gravity=DoubleArray(3)
 private var raw=false
 private var settlingUntil=0L
 private var wakeLock:PowerManager.WakeLock?=null
 override fun onBind(intent:Intent?)=null
 override fun onCreate(){
  super.onCreate();val nm=getSystemService(NotificationManager::class.java)
  nm.createNotificationChannel(NotificationChannel("monitor","안전 감시 상태",NotificationManager.IMPORTANCE_LOW))
  nm.createNotificationChannel(NotificationChannel("alerts","안전 확인 요청",NotificationManager.IMPORTANCE_HIGH).apply{enableVibration(true)})
  val open=PendingIntent.getActivity(this,1,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  val stop=PendingIntent.getService(this,2,Intent(this,SafetyService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE)
  val n=NotificationCompat.Builder(this,"monitor").setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("안전고리 · 움직임 감시 중").setContentText("휴대폰을 몸에 휴대하세요.").setOngoing(true).setContentIntent(open).addAction(0,"감시 종료",stop).build()
  if(Build.VERSION.SDK_INT>=34)startForeground(101,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)else startForeground(101,n)
  sensors=getSystemService(SENSOR_SERVICE) as SensorManager
  val sensor=sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?:sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
  if(sensor==null){notifySelf(null,"가속도 센서가 없어 감시를 시작할 수 없습니다.");stopSelf();return}
  raw=sensor.type==Sensor.TYPE_ACCELEROMETER;settlingUntil=SystemClock.elapsedRealtime()+2000
  if(!sensors.registerListener(this,sensor,20000)){notifySelf(null,"센서 등록 실패 · 감시를 시작할 수 없습니다.");stopSelf();return}
  wakeLock=(getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"SafeLoop:MotionMonitoring").apply{acquire()}
  getSharedPreferences("safeloop",MODE_PRIVATE).edit().putBoolean("monitoring",true).apply()
 }
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
  if(intent?.action=="STOP"){if(getSharedPreferences("safeloop",MODE_PRIVATE).getBoolean("previewMonitoring",false))stopSelf()else scope.launch{runCatching{Repository(this@SafetyService).command("monitoring",mapOf("enabled" to false))};stopSelf()}}
  return START_NOT_STICKY
 }
 override fun onSensorChanged(e:SensorEvent){
  val now=SystemClock.elapsedRealtime()
  val v=DoubleArray(3){i->val value=e.values[i].toDouble();if(raw){gravity[i]=0.8*gravity[i]+0.2*value;value-gravity[i]}else value}
  if(now<settlingUntil)return
  if(detector.sample(now,v[0],v[1],v[2])){
   val prefs=getSharedPreferences("safeloop",MODE_PRIVATE)
   val localId=java.util.UUID.randomUUID().toString()
   prefs.edit().putString("localAlert",localId).remove("localCallId").apply()
   if(prefs.getBoolean("previewMonitoring",false)){
    prefs.edit().putLong("previewSignalAt",System.currentTimeMillis()).putString("previewSignalId",localId).apply()
    notifySelf(null,"휴대폰 움직임 변화가 감지되었습니다. 15초 안에 확인해 주세요. · 체험 모드",localId)
    scope.launch{delay(15000);if(prefs.getString("ackAlert",null)!=localId)notifySelf(null,"체험: 본인 무응답으로 동료 확인 대기 단계입니다. 다른 사람에게 실제 전송하지 않습니다.",localId)}
    return
   }
   notifySelf(null,"움직임 변화가 감지되었습니다. 괜찮으세요? 서버 연결 중…",localId)
   scope.launch{
    val result=runCatching{withTimeout(10000){Repository(this@SafetyService).command("signal")}}
    val call=result.getOrNull()?.get("callId") as? String
    if(call!=null){
     if(prefs.getString("ackAlert",null)==localId){Repository(this@SafetyService).queue("call_response",mapOf("callId" to call,"response" to "safe"))}
     else{prefs.edit().putString("localCallId",call).apply();notifySelf(call,if(result.getOrNull()?.get("stage")=="peer")"동료의 현장 확인이 진행 중입니다." else "15초 안에 안전 상태를 확인해 주세요.",localId)}
    }else if(prefs.getString("ackAlert",null)!=localId)notifySelf(null,"서버 연결 실패 · 동료 호출 여부를 확인할 수 없습니다. 직접 연락하세요.",localId)
   }
  }
 }
 private fun notifySelf(call:String?,body:String,localId:String?=null){
  val i=Intent(this,MainActivity::class.java).putExtra("alert",true).putExtra("callId",call).putExtra("localAlert",localId)
  val open=PendingIntent.getActivity(this,3,i,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  val dial=PendingIntent.getActivity(this,119,Intent(Intent.ACTION_DIAL,android.net.Uri.parse("tel:119")),PendingIntent.FLAG_IMMUTABLE)
  getSystemService(NotificationManager::class.java).notify(103,NotificationCompat.Builder(this,"alerts").setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle("안전 확인이 필요합니다").setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(open).addAction(0,"상태 확인",open).addAction(0,"119 전화 화면",dial).setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_ALARM).setAutoCancel(true).build())
 }
 override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int){}
 override fun onDestroy(){if(::sensors.isInitialized)sensors.unregisterListener(this);wakeLock?.let{if(it.isHeld)it.release()};getSharedPreferences("safeloop",MODE_PRIVATE).edit().putBoolean("monitoring",false).apply();scope.cancel();super.onDestroy()}
}
