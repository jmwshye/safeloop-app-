package com.safeloop.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Preview records stay on this device. Connected records are authoritative only on the server. */
class WorkerStorage(context:Context) {
 private val prefs=context.getSharedPreferences("worker-preview-v2",Context.MODE_PRIVATE)
 fun load():WorkerState=runCatching{
  val text=prefs.getString("state",null)?:return WorkerState.preview()
  val j=JSONObject(text)
  WorkerState(name=j.getString("name"),job=j.optString("job","설비 작업자"),wageKnown=j.optBoolean("wageKnown",true),deviceState=j.optString("deviceState",if(j.optBoolean("carried",true))"carried" else "not_carried"),peerStatus=j.optString("peerStatus","pending"),highRiskTask=j.stringOrNull("highRiskTask"),highRiskStartedAt=j.longOrNull("highRiskStartedAt"),
   emergencyPush=j.optBoolean("emergencyPush",true),sitePush=j.optBoolean("sitePush",true),
   peerEvidence=j.optJSONArray("peerEvidence")?.objects().orEmpty().map{PeerEvidence(it.getString("checker"),it.getString("subject"),it.getString("outcome"),it.getLong("time"),it.stringOrNull("photo"),it.stringOrNull("photo"))},
   prompts=j.optJSONArray("prompts")?.objects().orEmpty().map{WorkPrompt(it.getString("id"),it.getString("kind"),it.getLong("due"),it.getString("state"),it.stringOrNull("response"))},zone=j.getString("zone"),wage=j.getInt("wage"),carried=j.optBoolean("carried",true),monitoring=j.optBoolean("monitoring"),peerChecked=j.optBoolean("peerChecked"),
   checks=j.getJSONArray("checks").strings(),
   work=j.getJSONArray("work").objects().map{WorkEntry(it.getString("id"),it.getString("day"),it.getLong("start"),it.longOrNull("end"),it.getInt("wage"),it.optBoolean("sample"),wageKnown=it.optBoolean("wageKnown",true),site=it.optString("site"),zone=it.optString("zone"),occupation=it.optString("occupation"))},
   risks=j.getJSONArray("risks").objects().map{WorkerRisk(it.getString("id"),it.getString("title"),it.getString("zone"),it.getString("note"),it.getString("category"),it.getString("status"),it.stringOrNull("photo"),it.getLong("time"),it.optBoolean("anonymous",true),afterPhoto=it.stringOrNull("afterPhoto"),fixerName=it.stringOrNull("fixerName"),verifierName=it.stringOrNull("verifierName"),fixedAt=it.longOrNull("fixedAt"),verifiedAt=it.longOrNull("verifiedAt"),canRecheck=it.optBoolean("canRecheck"))},
   events=j.getJSONArray("events").objects().map{WorkerEvent(it.getString("id"),it.getString("title"),it.getString("detail"),it.getLong("time"),it.optBoolean("estimate"))},
   calls=j.getJSONArray("calls").objects().map{WorkerCall(it.getString("id"),it.getString("subject"),it.getString("subjectName"),it.getString("zone"),it.getString("stage"),it.getString("status"),it.getLong("deadline"),it.stringOrNull("acceptedBy"),it.longOrNull("arrivedAt"),it.stringOrNull("photo"),it.stringOrNull("resolution"),serverStatus=it.optString("serverStatus"),observation=it.stringOrNull("observation"),workerResponse=it.stringOrNull("workerResponse"),createdAt=it.optLong("createdAt"),respondedAt=it.longOrNull("respondedAt"))})
 }.getOrElse{WorkerState.preview()}
 fun save(s:WorkerState){if(!s.preview)return
  val j=JSONObject().put("name",s.name).put("job",s.job).put("wageKnown",s.wageKnown).put("peerStatus",s.peerStatus).put("highRiskTask",s.highRiskTask).put("zone",s.zone).put("wage",s.wage).put("carried",s.carried).put("deviceState",s.deviceState).put("monitoring",s.monitoring).put("peerChecked",s.peerChecked).put("checks",JSONArray(s.checks))
  j.put("highRiskStartedAt",s.highRiskStartedAt).put("emergencyPush",s.emergencyPush).put("sitePush",s.sitePush)
  j.put("peerEvidence",JSONArray(s.peerEvidence.map{JSONObject().put("checker",it.checkerId).put("subject",it.subjectAttendanceId).put("outcome",it.outcome).put("time",it.checkedAt).put("photo",it.photo)}))
  j.put("prompts",JSONArray(s.prompts.map{JSONObject().put("id",it.id).put("kind",it.kind).put("due",it.dueAt).put("state",it.state).put("response",it.response)}))
  j.put("work",JSONArray(s.work.map{JSONObject().put("id",it.id).put("day",it.day).put("start",it.start).put("end",it.end).put("wage",it.wage).put("sample",it.sample).put("wageKnown",it.wageKnown).put("site",it.site).put("zone",it.zone).put("occupation",it.occupation)}))
  j.put("risks",JSONArray(s.risks.map{JSONObject().put("id",it.id).put("title",it.title).put("zone",it.zone).put("note",it.note).put("category",it.category).put("status",it.status).put("photo",it.photo).put("time",it.time).put("anonymous",it.anonymous).put("afterPhoto",it.afterPhoto).put("fixerName",it.fixerName).put("verifierName",it.verifierName).put("fixedAt",it.fixedAt).put("verifiedAt",it.verifiedAt).put("canRecheck",it.canRecheck)}))
  j.put("events",JSONArray(s.events.take(200).map{JSONObject().put("id",it.id).put("title",it.title).put("detail",it.detail).put("time",it.time).put("estimate",it.estimate)}))
  j.put("calls",JSONArray(s.calls.take(30).map{JSONObject().put("id",it.id).put("subject",it.subject).put("subjectName",it.subjectName).put("zone",it.zone).put("stage",it.stage).put("status",it.status).put("deadline",it.deadline).put("acceptedBy",it.acceptedBy).put("arrivedAt",it.arrivedAt).put("photo",it.photo).put("resolution",it.resolution).put("serverStatus",it.serverStatus).put("observation",it.observation).put("workerResponse",it.workerResponse).put("createdAt",it.createdAt).put("respondedAt",it.respondedAt)}))
  prefs.edit().putString("state",j.toString()).apply()
 }
 private fun JSONArray.objects()=(0 until length()).map{getJSONObject(it)}
 private fun JSONArray.strings()=(0 until length()).map{getString(it)}
 private fun JSONObject.longOrNull(key:String)=if(isNull(key))null else optLong(key)
 private fun JSONObject.stringOrNull(key:String)=if(isNull(key))null else optString(key).takeIf{it.isNotEmpty()}
}
