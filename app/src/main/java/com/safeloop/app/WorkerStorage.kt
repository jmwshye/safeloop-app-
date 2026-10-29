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
  WorkerState(name=j.getString("name"),zone=j.getString("zone"),wage=j.getInt("wage"),carried=j.optBoolean("carried",true),monitoring=j.optBoolean("monitoring"),peerChecked=j.optBoolean("peerChecked"),
   checks=j.getJSONArray("checks").strings(),
   work=j.getJSONArray("work").objects().map{WorkEntry(it.getString("id"),it.getString("day"),it.getLong("start"),it.longOrNull("end"),it.getInt("wage"),it.optBoolean("sample"))},
   risks=j.getJSONArray("risks").objects().map{WorkerRisk(it.getString("id"),it.getString("title"),it.getString("zone"),it.getString("note"),it.getString("category"),it.getString("status"),it.stringOrNull("photo"),it.getLong("time"),it.optBoolean("anonymous",true))},
   events=j.getJSONArray("events").objects().map{WorkerEvent(it.getString("id"),it.getString("title"),it.getString("detail"),it.getLong("time"),it.optBoolean("estimate"))},
   calls=j.getJSONArray("calls").objects().map{WorkerCall(it.getString("id"),it.getString("subject"),it.getString("subjectName"),it.getString("zone"),it.getString("stage"),it.getString("status"),it.getLong("deadline"),it.stringOrNull("acceptedBy"),it.longOrNull("arrivedAt"),it.stringOrNull("photo"),it.stringOrNull("resolution"))})
 }.getOrElse{WorkerState.preview()}
 fun save(s:WorkerState){if(!s.preview)return
  val j=JSONObject().put("name",s.name).put("zone",s.zone).put("wage",s.wage).put("carried",s.carried).put("monitoring",s.monitoring).put("peerChecked",s.peerChecked).put("checks",JSONArray(s.checks))
  j.put("work",JSONArray(s.work.map{JSONObject().put("id",it.id).put("day",it.day).put("start",it.start).put("end",it.end).put("wage",it.wage).put("sample",it.sample)}))
  j.put("risks",JSONArray(s.risks.map{JSONObject().put("id",it.id).put("title",it.title).put("zone",it.zone).put("note",it.note).put("category",it.category).put("status",it.status).put("photo",it.photo).put("time",it.time).put("anonymous",it.anonymous)}))
  j.put("events",JSONArray(s.events.take(200).map{JSONObject().put("id",it.id).put("title",it.title).put("detail",it.detail).put("time",it.time).put("estimate",it.estimate)}))
  j.put("calls",JSONArray(s.calls.take(30).map{JSONObject().put("id",it.id).put("subject",it.subject).put("subjectName",it.subjectName).put("zone",it.zone).put("stage",it.stage).put("status",it.status).put("deadline",it.deadline).put("acceptedBy",it.acceptedBy).put("arrivedAt",it.arrivedAt).put("photo",it.photo).put("resolution",it.resolution)}))
  prefs.edit().putString("state",j.toString()).apply()
 }
 private fun JSONArray.objects()=(0 until length()).map{getJSONObject(it)}
 private fun JSONArray.strings()=(0 until length()).map{getString(it)}
 private fun JSONObject.longOrNull(key:String)=if(isNull(key))null else optLong(key)
 private fun JSONObject.stringOrNull(key:String)=if(isNull(key))null else optString(key).takeIf{it.isNotEmpty()}
}
