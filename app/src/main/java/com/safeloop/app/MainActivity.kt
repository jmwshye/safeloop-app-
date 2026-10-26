package com.safeloop.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {

    private lateinit var sensorDetector: SensorDetector
    private val db by lazy { FirebaseFirestore.getInstance() }
    private var countDownTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // UI 및 센서 상태 변수
        var currentGState by mutableDoubleStateOf(1.00)
        var linearGState by mutableDoubleStateOf(0.00)
        var sensorStatusText by mutableStateOf("대기 중")
        var isCheckedIn by mutableStateOf(false)
        var checkInTimeString by mutableStateOf("--:--")
        var isHighRiskMode by mutableStateOf(false)

        // 안전점검 3항목 체크 상태
        var check1FallZone by mutableStateOf(false)
        var check2FloorOpening by mutableStateOf(false)
        var check3PpeHelmet by mutableStateOf(false)

        // 비상 경보(15초 카운트다운) 상태
        var alertActive by mutableStateOf(false)
        var remainingSeconds by mutableIntStateOf(15)
        var lastDetectedShockG by mutableDoubleStateOf(0.0)
        var lastServerLog by mutableStateOf("서버 연결 준비 완료 (Spark 0원 방어 모드)")

        fun uploadEventToFirestore(eventType: String, factType: String, shockVal: Double) {
            val evtId = "evt_" + UUID.randomUUID().toString().take(8)
            val now = System.currentTimeMillis()
            val (prevH, currH) = HashChainEngine.generateChainedHash(
                evtId, "user_park_01", eventType, shockVal, now
            )

            val event = SafetyEvent(
                eventId = evtId,
                incidentGroupId = "inc_20261026_01",
                eventType = eventType,
                factOrEstimate = factType,
                shockG = shockVal,
                deviceTimestamp = now,
                prevEventHash = prevH,
                currentEventHash = currH
            )

            db.collection("safety_events")
                .document(evtId)
                .set(event)
                .addOnSuccessListener {
                    lastServerLog = "[전송성공] $eventType (${shockVal}G) | Hash: " + currH.take(12) + "..."
                }
                .addOnFailureListener { e ->
                    lastServerLog = "[오프라인 큐 보관] 네트워크 복구 시 자동 재전송 (${e.message})"
                }
        }

        fun triggerShockAlert(shockG: Double) {
            if (alertActive) return
            alertActive = true
            lastDetectedShockG = shockG
            remainingSeconds = 15

            uploadEventToFirestore("SHOCK_DETECTED", "SYSTEM_ESTIMATE", shockG)

            countDownTimer?.cancel()
            countDownTimer = object : CountDownTimer(15000, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    remainingSeconds = (millisUntilFinished / 1000).toInt() + 1
                }
                override fun onFinish() {
                    alertActive = false
                    uploadEventToFirestore("SELF_NO_RESPONSE", "SYSTEM_ESTIMATE", shockG)
                    uploadEventToFirestore("PEER_CALL_SENT", "SYSTEM_ESTIMATE", shockG)
                    Toast.makeText(this@MainActivity, "무응답: 안전동료(정민철) 및 반장에게 긴급 호출 발송됨!", Toast.LENGTH_LONG).show()
                }
            }.start()
        }

        sensorDetector = SensorDetector(
            context = this,
            onTelemetryUpdate = { totalG, linG, status ->
                currentGState = totalG
                linearGState = linG
                sensorStatusText = status
            },
            onShockAndInactivityDetected = { peakG ->
                triggerShockAlert(peakG)
            }
        )
        sensorDetector.start()

        setContent {
            SafeLoopCommercialApp(
                isCheckedIn = isCheckedIn,
                checkInTime = checkInTimeString,
                isHighRiskMode = isHighRiskMode,
                check1 = check1FallZone,
                check2 = check2FloorOpening,
                check3 = check3PpeHelmet,
                onCheck1Change = { check1FallZone = it },
                onCheck2Change = { check2FloorOpening = it },
                onCheck3Change = { check3PpeHelmet = it },
                onCompleteCheckIn = {
                    val sdf = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
                    checkInTimeString = sdf.format(Date())
                    isCheckedIn = true
                    uploadEventToFirestore("CHECK_IN", "HUMAN_FACT", 1.0)
                    Toast.makeText(this, "출근 및 작업 전 안전점검이 서버에 기록되었습니다.", Toast.LENGTH_SHORT).show()
                },
                onToggleHighRisk = { enabled ->
                    isHighRiskMode = enabled
                    if (enabled) {
                        uploadEventToFirestore("HIGH_RISK_START", "HUMAN_FACT", 1.0)
                    }
                },
                alertActive = alertActive,
                remainingSec = remainingSeconds,
                detectedG = lastDetectedShockG,
                onImOkayClick = {
                    countDownTimer?.cancel()
                    alertActive = false
                    uploadEventToFirestore("SELF_OK_FALSE_ALARM", "HUMAN_FACT", lastDetectedShockG)
                },
                onCall119Click = {
                    uploadEventToFirestore("EMERGENCY_119_CALLED", "HUMAN_FACT", lastDetectedShockG)
                    Toast.makeText(this, "현장 위치(2층 설비구역) 및 사고 경위 로그가 서버에 긴급 고정되었습니다.", Toast.LENGTH_LONG).show()
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:119"))
                    startActivity(intent)
                },
                currentG = currentGState,
                linearG = linearGState,
                sensorState = sensorStatusText,
                lastLog = lastServerLog,
                onSimulateShockClick = {
                    triggerShockAlert(3.45)
                }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
        sensorDetector.stop()
    }
}

@Composable
fun SafeLoopCommercialApp(
    isCheckedIn: Boolean,
    checkInTime: String,
    isHighRiskMode: Boolean,
    check1: Boolean,
    check2: Boolean,
    check3: Boolean,
    onCheck1Change: (Boolean) -> Unit,
    onCheck2Change: (Boolean) -> Unit,
    onCheck3Change: (Boolean) -> Unit,
    onCompleteCheckIn: () -> Unit,
    onToggleHighRisk: (Boolean) -> Unit,
    alertActive: Boolean,
    remainingSec: Int,
    detectedG: Double,
    onImOkayClick: () -> Unit,
    onCall119Click: () -> Unit,
    currentG: Double,
    linearG: Double,
    sensorState: String,
    lastLog: String,
    onSimulateShockClick: () -> Unit
) {
    var showDevDrawer by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .padding(18.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // 1. 상단 바: 브랜드 및 보호 상태 배지
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("안전고리 SafeLoop", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    Text("소규모 현장 근로·안전 상호증명 시스템", color = Color(0xFF94A3B8), fontSize = 12.sp)
                }
                Surface(
                    color = if (isCheckedIn) Color(0xFF065F46) else Color(0xFF334155),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        text = if (isCheckedIn) "● 근무 및 보호 중" else "○ 출근 대기",
                        color = if (isCheckedIn) Color(0xFF6EE7B7) else Color(0xFFCBD5E1),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            // 2. 작업자 근로 증명 & 안전 동료 매칭 카드 (작업자가 앱을 켜는 핵심 이유!)
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("작업자 및 소속 구역", color = Color(0xFF94A3B8), fontSize = 12.sp)
                            Text("박태규 · 2층 배관설비", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("오늘 예상 일당 (근로증명)", color = Color(0xFF94A3B8), fontSize = 12.sp)
                            Text("180,000 원", color = Color(0xFF38BDF8), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                    HorizontalDivider(color = Color(0xFF334155))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("오늘의 2인 1조 안전 동료: 정민철 (연결됨)", color = Color(0xFFA7F3D0), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("출근시각: $checkInTime", color = Color(0xFFCBD5E1), fontSize = 13.sp)
                    }
                }
            }

            // 3. 비상 경보 발생 시 최우선 노출되는 15초 본인 확인 모달
            if (alertActive) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF7F1D1D)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("강한 충격 및 정지가 감지되었습니다", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("감지 수치: ${detectedG}G (시스템 추정 - 안전 확인 요청)", color = Color(0xFFFCA5A5), fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("${remainingSec}초", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Black)
                        Text("응답이 없으면 안전 동료(정민철)와 반장에게 즉시 알림이 전송됩니다.", color = Color(0xFFFECACA), fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onImOkayClick,
                            modifier = Modifier.fillMaxWidth().height(64.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("괜찮습니다 (본인 정상 확인 - 오탐 취소)", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // 4. 상태별 메인 워크플로우 영역 (출근 전 점검 vs 출근 후 주머니 감시 모드)
            if (!isCheckedIn) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("작업 시작 전 필수 안전 확인 (3항목)", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("세 항목을 모두 확인해야 오늘의 출근 및 근로 증명이 시작됩니다.", color = Color(0xFF94A3B8), fontSize = 12.sp)

                        CheckItemRow("1. 작업 구역 추락·개구부 위험 위치 확인", check1, onCheck1Change)
                        CheckItemRow("2. 바닥 자재 정리 및 안전난간 상태 확인", check2, onCheck2Change)
                        CheckItemRow("3. 본인 안전모·턱끈 및 보호구 착용 완료", check3, onCheck3Change)

                        Spacer(modifier = Modifier.height(4.dp))
                        val allChecked = check1 && check2 && check3
                        Button(
                            onClick = onCompleteCheckIn,
                            enabled = allChecked,
                            modifier = Modifier.fillMaxWidth().height(62.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF16A34A),
                                disabledContainerColor = Color(0xFF334155)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = if (allChecked) "출근 및 작업 전 안전점검 완료 기록" else "위 3개 안전 항목을 먼저 체크해 주세요",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            } else {
                // 출근 완료 후: 작업 중 무조작 주머니 보호 모드 화면 (헌장 17-3절 반영)
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF064E3B)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("작업 중에는 휴대폰을 주머니에 넣어 두세요", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                        Text(
                            "화면이 꺼져 있어도 이상 충격과 무동작이 감지되면 자동으로 안전 동료(정민철)와 현장 책임자에게 구조를 요청합니다.",
                            color = Color(0xFFA7F3D0),
                            fontSize = 13.sp
                        )
                        HorizontalDivider(color = Color(0xFF047857))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("고소·고위험 작업 집중 감시 모드", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Text("비계·사다리·개구부 작업 시 센서 민감도 강화", color = Color(0xFFA7F3D0), fontSize = 11.sp)
                            }
                            Switch(
                                checked = isHighRiskMode,
                                onCheckedChange = onToggleHighRisk
                            )
                        }
                    }
                }
            }

            // 5. 접이식 시연/개발자 전용 텔레메트리 패널 (평소엔 숨겨서 상용 앱 퀄리티 유지!)
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showDevDrawer = !showDevDrawer },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("엔지니어링 시연 및 센서/해시 모니터", color = Color(0xFF94A3B8), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (showDevDrawer) "접기 ▲" else "펼치기 ▼", color = Color(0xFF38BDF8), fontSize = 12.sp)
                    }
                    if (showDevDrawer) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("• 합성 가속도: ${currentG}G | 순수 선형충격(LPF): ${linearG}G", color = Color.White, fontSize = 12.sp)
                        Text("• 엣지 판별 상태: $sensorState", color = Color(0xFF93C5FD), fontSize = 12.sp)
                        Text("• 최근 블록체인 해시 로그: $lastLog", color = Color(0xFFA7F3D0), fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = onSimulateShockClick,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("[시연용] 3.45G 추락 충격 + 무동작 시나리오 강제 트리거", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 6. 최하단 고정: 119 긴급 신고 버튼 (헌장 원칙: 60dp 이상, 모든 단계에서 즉시 실행 보장)
        Button(
            onClick = onCall119Click,
            modifier = Modifier.fillMaxWidth().height(64.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("119 긴급 신고 및 현장 위치·로그 즉시 전송", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        }
    }
}

@Composable
fun CheckItemRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Surface(
        color = if (checked) Color(0xFF064E3B) else Color(0xFF0F172A),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            Spacer(modifier = Modifier.width(6.dp))
            Text(label, color = Color.White, fontSize = 14.sp, fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal)
        }
    }
}
