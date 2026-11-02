package com.safeloop.app
import android.app.*
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.*
import kotlinx.coroutines.*
class PushService:FirebaseMessagingService(){
 override fun onNewToken(token:String){CoroutineScope(Dispatchers.IO).launch {runCatching {Repository(this@PushService).command("registerToken",mapOf("token" to token))}}}
 override fun onMessageReceived(message:RemoteMessage){
  val site=message.data["siteId"]?:return
  val repo=Repository(this);if(repo.auth.currentUser==null||repo.siteId!=site)return
  val call=message.data["callId"]
  if(call!=null)Repository(this).queue("call_response",mapOf("siteId" to site,"callId" to call,"response" to "received"))
  val nm=getSystemService(NotificationManager::class.java)
  nm.createNotificationChannel(NotificationChannel("alerts","안전 확인 요청",NotificationManager.IMPORTANCE_HIGH))
  val intent=Intent(this,MainActivity::class.java).putExtra("siteId",site).putExtra("callId",call).putExtra("peerAlert",true)
  val pending=PendingIntent.getActivity(this,call?.hashCode()?:4,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  val dial=PendingIntent.getActivity(this,119,Intent(Intent.ACTION_DIAL,android.net.Uri.parse("tel:119")),PendingIntent.FLAG_IMMUTABLE)
  nm.notify(call?.hashCode()?:104,NotificationCompat.Builder(this,"alerts").setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle(message.data["title"]?:"안전 확인 요청").setContentText(message.data["body"]?:"동료의 상태를 확인해 주세요.").setContentIntent(pending).addAction(0,"현장 확인",pending).addAction(0,"119 전화 화면",dial).setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build())
 }
}
