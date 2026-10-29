package com.safeloop.app

fun workCsv(state:WorkerState):String {
 fun safe(value:String):String {val clean=if(value.firstOrNull() in listOf('=','+','-','@','\t','\r'))"'$value" else value;return "\""+clean.replace("\"","\"\"")+"\""}
 val rows=listOf(listOf("근로일","이름","현장","작업 구역","출근","퇴근","예상 일당","자료 구분"))+state.work.sortedByDescending{it.day}.map{listOf(it.day,state.name,state.site,state.zone,clockText(it.start),clockText(it.end),it.wage.toString(),if(state.preview)"체험 기록" else "서버 기록")}
 return "\uFEFF"+rows.joinToString("\r\n"){row->row.joinToString(","){safe(it)}}
}
