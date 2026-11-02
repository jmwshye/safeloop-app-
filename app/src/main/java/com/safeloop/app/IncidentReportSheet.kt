package com.safeloop.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.CancellationException

// 5-5. Display the same evidence used by the server's PDF.
@Composable
fun IncidentReportSheet(id: String, controller: WorkerController, share: (String, String) -> Unit) {
    var report by remember(id) { mutableStateOf<Map<String, Any?>?>(null) }
    var photo by remember(id) { mutableStateOf<String?>(null) }
    var failure by remember(id) { mutableStateOf<String?>(null) }
    var attempt by remember(id) { mutableIntStateOf(0) }
    val site = controller.state.siteId
    LaunchedEffect(id, site, attempt) {
        failure = null
        try {
            val data = controller.incidentReport(id)
            report = data
            val scene = (data["incident"] as? Map<*, *>)?.get("scenePhotoId") as? String
            photo = scene?.let { controller.reportPhoto(it) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure = e.localizedMessage ?: "보고서를 불러오지 못했어요." }
    }
    failure?.let {
        SmallNote(it, amber = true)
        SLButton("다시 시도", { attempt++ }, secondary = true)
    }
    val data = report
    if (data == null) {
        if (failure == null) SmallNote("서버 원본을 불러오고 있어요.")
        return
    }
    val incident = data["incident"] as? Map<*, *> ?: return
    DetailRow("작업자", listOfNotNull((incident["worker"] as? Map<*, *>)?.get("name"), incident["occupation"]).joinToString(" · "))
    DetailRow("현장", incident["siteName"]?.toString().orEmpty())
    DetailRow("발생 시각", incident["createdAt"]?.toString()?.let { java.time.Instant.parse(it).atZone(koreaZone).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) } ?: "미확인")
    val gaps = data["dataGaps"] as? List<*> ?: emptyList<Any>()
    SmallNote("데이터 누락 · " + gaps.joinToString(" / ").ifEmpty { "없음" }, amber = gaps.isNotEmpty())
    (incident["peakG"] as? Number)?.let { DetailRow("최대 충격 · 시스템 추정", "${it.toDouble()} G") }
    val ppe = incident["ppeEvidence"] as? Map<*, *>
    for (check in (ppe?.get("checks") as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()) {
        val from = (check["photographer"] as? Map<*, *>)?.get("name") ?: "촬영자 미확인"
        val to = (check["subject"] as? Map<*, *>)?.get("name") ?: "대상 미확인"
        val evidence = when (check["evidence"]) {
            "photo" -> "사진 기록"
            "statement" -> "사진 없는 진술"
            "missing_check" -> "확인 기록 없음"
            "mismatch" -> "불일치"
            else -> "증거 미확인"
        }
        DetailRow("$from → $to", evidence)
    }
    for (recipient in (incident["recipients"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()) {
        Text(recipient["name"]?.toString().orEmpty(),fontWeight=FontWeight.Bold)
        for ((field,label) in listOf("sentAt" to "발송","receivedAt" to "단말 수신","acceptedAt" to "수락")) {
            parseTime(recipient[field])?.let { DetailRow(label, clockText(it)) }
        }
    }
    SectionHeading("시각별 원본 기록")
    val events = (data["events"] as? List<*>)?.filterIsInstance<Map<*, *>>().orEmpty()
    for (event in events) {
        val time = parseTime(event["serverReceivedAt"]) ?: continue
        EventRow(WorkerEvent(event["id"].toString(), eventTitle(event["type"].toString()), "서버 수신 기록", time, event["source"] == "system"), false)
    }
    photo?.let { Text("현장 사진", fontWeight = FontWeight.Bold); PhotoPreview(it) }
    for (record in (data["corrections"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()) {
        DetailRow("정정 이력", record["note"]?.toString().orEmpty())
    }
    DetailRow("원본 사건 ID", id)
    SmallNote(data["disclaimer"]?.toString().orEmpty())
    SLButton("PDF 만들기", { share("/sites/$site/reports/incidents/$id?format=pdf", "pdf") }, icon = "download")
}
