package com.safeloop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

private fun degreesText(value: Double) = "%.1f°C".format(Locale.ROOT, value)

/** 온도 막대. 빨강은 긴급에만 쓰므로 파랑에서 초록으로만 바뀐다. */
@Composable private fun TemperatureBar(degrees: Double) {
    Box(Modifier.fillMaxWidth().height(12.dp).background(NeutralFill, RoundedCornerShape(999.dp))) {
        Box(Modifier.fillMaxWidth(temperatureFill(degrees)).fillMaxHeight().background(if (degrees >= 40) Ok else Brand, RoundedCornerShape(999.dp)))
    }
}

/** 6-1 내 정보 프로필 카드 안의 요약 */
@Composable fun TemperatureSummary(t: SafetyTemperature, onOpen: () -> Unit) {
    HorizontalDivider(color = Line)
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("나의 안전 온도", Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Muted)
            Text(degreesText(t.degrees), fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, color = if (t.degrees >= 40) Ok else Brand)
            SLIcon("chevron", Modifier.size(18.dp), Muted)
        }
        TemperatureBar(t.degrees)
        Text(if (t.monthGain > 0) "이번 달 +${degreesText(t.monthGain)}" else "출근하고 안전 확인을 남기면 올라가요", fontSize = 14.sp, color = Muted)
    }
}

/** 나의 안전 온도 상세. 순위나 다른 사람과의 비교는 보여 주지 않는다. */
@Composable fun TemperatureSheet(t: SafetyTemperature, preview: Boolean, today: LocalDate = LocalDate.now(koreaZone)) {
    ScreenHead("나의 안전 온도", "꾸준히 기록하고 서로 확인할수록 올라가요.")
    SLCard(color = BrandSoft) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(degreesText(t.degrees), fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.ExtraBold, color = if (t.degrees >= 40) Ok else Brand)
            if (t.todayGain > 0) SLPill("오늘 +${degreesText(t.todayGain)}", brand = true)
        }
        TemperatureBar(t.degrees)
        Text("처음엔 모두 ${degreesText(START_DEGREES)}에서 시작해요. 이번 달 +${degreesText(t.monthGain)}", fontSize = 14.sp, lineHeight = 22.sp, color = Ink)
    }
    SectionHeading("${YearMonth.from(today).monthValue}월 기록한 날")
    SLCard { MonthDots(t.activeDays, today) }
    SectionHeading("이번 달 남긴 기록")
    FlatCard(TemperatureStep.entries.map { it.label to "${t.monthCounts[it] ?: 0}회" })
    SectionHeading("이렇게 올라가요")
    FlatBox {
        TemperatureStep.entries.forEachIndexed { i, step ->
            if (i > 0) HorizontalDivider(color = Line)
            StepRow(step.label + if (step.perDay > 1) " · 하루 ${step.perDay}번까지" else "") { SLPill("+${degreesText(step.degrees)}", brand = true) }
        }
    }
    NoteText("하루에 최대 +0.6°C까지 올라가요.")
    SLCard(color = OkSoft) {
        Text("평가에 쓰지 않아요", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
        Text("· 나만 볼 수 있어요. 반장·사업주에게 보내지 않아요\n· 사고, 호출, 결근으로 내려가지 않아요\n· '모름', '개선 필요'도 '확인'과 똑같이 올라가요", fontSize = 14.sp, lineHeight = 22.sp, color = Ink)
    }
    if (preview) NoteText("체험 기록으로 계산했어요.")
}

/** 이번 달 달력에 기록한 날만 점으로 표시한다. */
@Composable private fun MonthDots(active: Set<String>, today: LocalDate) {
    val month = YearMonth.from(today)
    val offset = month.atDay(1).dayOfWeek.value % 7
    val cells = ((offset + month.lengthOfMonth() + 6) / 7) * 7
    Row(Modifier.fillMaxWidth()) { listOf("일", "월", "화", "수", "목", "금", "토").forEach { Text(it, Modifier.weight(1f), fontSize = 13.sp, color = Muted, textAlign = TextAlign.Center) } }
    repeat(cells / 7) { week ->
        Row(Modifier.fillMaxWidth()) {
            repeat(7) { dow ->
                val day = week * 7 + dow - offset + 1
                Box(Modifier.weight(1f).height(36.dp), contentAlignment = Alignment.Center) {
                    if (day in 1..month.lengthOfMonth()) {
                        val on = month.atDay(day).toString() in active
                        Box(Modifier.size(30.dp).background(if (on) OkSoft else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
                            Text("$day", fontSize = 13.sp, fontWeight = if (on) FontWeight.ExtraBold else FontWeight.Normal, color = if (on) Ok else Muted)
                        }
                    }
                }
            }
        }
    }
}
