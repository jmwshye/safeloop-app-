package com.safeloop.app

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import android.graphics.Bitmap
import java.util.UUID
import androidx.work.*
import org.json.JSONObject

class Repository(private val context: Context) {
 val auth = FirebaseAuth.getInstance()
 val db = FirebaseFirestore.getInstance()
 private val api = FirebaseFunctions.getInstance("asia-northeast3")
 val prefs = context.getSharedPreferences("safeloop",Context.MODE_PRIVATE)
 var siteId: String
  get() = prefs.getString("siteId", "") ?: ""
  set(value) { prefs.edit().putString("siteId",value).apply() }
 suspend fun command(action: String, values: Map<String,Any?> = emptyMap()): Map<String,Any?> {
  @Suppress("UNCHECKED_CAST")
  return api.getHttpsCallable("command").call(mapOf("siteId" to siteId,"deviceTime" to java.time.Instant.now().toString()) + values + mapOf("action" to action)).await().getData() as Map<String,Any?>
 }
 suspend fun signIn(email: String, password: String) { auth.signInWithEmailAndPassword(email,password).await() }
 suspend fun verificationPhoto(callId:String):ByteArray {
  val result=api.getHttpsCallable("viewCallPhoto").call(mapOf("siteId" to siteId,"callId" to callId)).await().getData() as? Map<*,*> ?: error("사진 응답을 확인해 주세요.")
  return android.util.Base64.decode(result["base64"] as? String ?: error("확인 사진이 없습니다."),android.util.Base64.DEFAULT)
 }
 suspend fun signUp(email: String, password: String, name: String) { auth.createUserWithEmailAndPassword(email,password).await(); command("profile",mapOf("name" to name)) }
 suspend fun upload(bitmap: Bitmap, callId:String?=null): String {
  val uid = auth.currentUser?.uid ?: error("로그인 필요")
  val bytes = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.JPEG,85,this) }.toByteArray()
  val path = "photos/$siteId/$uid/${UUID.randomUUID()}.jpg"
  val metadata=com.google.firebase.storage.StorageMetadata.Builder().setContentType("image/jpeg")
  if(callId!=null)metadata.setCustomMetadata("callId",callId)
  FirebaseStorage.getInstance().reference.child(path).putBytes(bytes,metadata.build()).await()
  return path
 }
 fun queue(action:String, values:Map<String,Any?>) {
  val uid = auth.currentUser?.uid ?: return
  val json = JSONObject(values).toString()
  val data = workDataOf("action" to action,"siteId" to siteId,"uid" to uid,"values" to json)
  val work = OneTimeWorkRequestBuilder<CommandWorker>().setInputData(data).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,10,java.util.concurrent.TimeUnit.SECONDS).build()
  WorkManager.getInstance(context).enqueue(work)
 }
}
class CommandWorker(context:Context, params:WorkerParameters):CoroutineWorker(context,params) {
 override suspend fun doWork():Result {
  val repo=Repository(applicationContext)
  if(repo.auth.currentUser?.uid!=inputData.getString("uid"))return Result.failure()
  val action=inputData.getString("action")?:return Result.failure()
  val json=JSONObject(inputData.getString("values")?:"{}")
  val values=json.keys().asSequence().associateWith { if(json.isNull(it))null else json.get(it) }.toMutableMap()
  values["siteId"]=inputData.getString("siteId")?:""
  return try { repo.command(action,values);Result.success() } catch(e:com.google.firebase.functions.FirebaseFunctionsException) {
   if(e.code in listOf(com.google.firebase.functions.FirebaseFunctionsException.Code.UNAVAILABLE,com.google.firebase.functions.FirebaseFunctionsException.Code.DEADLINE_EXCEEDED))Result.retry() else Result.failure()
  } catch(e:Exception){Result.retry()}
 }
}
