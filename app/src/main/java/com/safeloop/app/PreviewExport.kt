package com.safeloop.app

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.File
import java.util.UUID

internal fun previewIncidentLines(call: WorkerCall): List<String> = buildList {
    add("작업자 · ${call.subjectName} / ${call.zone}")
    if (call.createdAt > 0) add("시스템 기록 · ${clockText(call.createdAt)} 감지 또는 체험 호출 생성 · ${impactDetail(call)}")
    call.respondedAt?.let { add("사람이 확인 · ${clockText(it)} 본인 응답") }
    call.arrivedAt?.let { add("사람이 확인 · ${clockText(it)} 현장 도착") }
    call.dialTimes.lastOrNull()?.let { add("기록 · ${clockText(it)} 119 전화 화면 열림. 통화 연결 여부는 별도 확인 필요") }
    add("대응 상태 · ${callStatus(call, call.subject)}")
    add("이 기기의 체험 기록이에요. 실제 사고나 신고를 입증하지 않아요.")
}

internal fun createPreviewExport(directory:File, state:WorkerState, from:String, to:String, format:String, callId:String?):File {
    require(state.preview)
    val parent = File(directory,"exports").apply { mkdirs() }
    val file = File(parent, "safeloop-preview-${UUID.randomUUID()}.$format")
    val filtered = state.copy(work=state.work.filter { it.day in from..to })
    if(format=="csv"){
        file.writeText(workCsv(filtered),Charsets.UTF_8)
        return file
    }
    require(format=="pdf")
    val monthly = callId=="monthly"
    val lines = if(monthly) monthlyLines(monthlySummary(state,from)) else if(callId!=null) previewIncidentLines(state.calls.first { it.id==callId }) else buildList {
        add("기간 · $from ~ $to")
        add("예상 일당은 실제 지급액과 다를 수 있어요.")
        for(record in filtered.work.sortedBy{it.day}){
            add("${record.day}   ${clockText(record.start)} ~ ${clockText(record.end)}   ${if(record.wageKnown) moneyText(record.wage)+"원" else "일당 미입력"}")
        }
        if(filtered.work.isEmpty()) add("선택한 기간에 기록이 없어요.")
    }
    val document=PdfDocument()
    try {
        for((index,chunk) in lines.chunked(26).withIndex()){
            val page=document.startPage(PdfDocument.PageInfo.Builder(595,842,index+1).create())
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{ textSize=19f }
            page.canvas.drawText("안전고리 · "+(if(monthly)"월간 근로일·안전확인 집계" else if(callId==null)"근로 기록" else "사고 경위 보고서")+" · 체험",32f,50f,paint)
            paint.textSize=13f
            chunk.forEachIndexed { row,line -> page.canvas.drawText(line,32f,88f+row*26,paint) }
            document.finishPage(page)
        }
        file.outputStream().use { document.writeTo(it) }
    } finally { document.close() }
    return file
}
