package com.safeloop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 폰 없이 화면을 그려 PNG로 남긴다. 결과는 app/build/outputs/roborazzi, CI에서는 Screen-captures로 받는다. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h1500dp-xhdpi")
class ScreenCaptureTest {
    @get:Rule val rule = createComposeRule()
    private val font = FontFamily(Font(File("../backend/assets/fonts/NotoSansKR.ttf")))
    private val actions = WorkerActions(dial = {}, capture = { _, _ -> }, attend = {}, monitor = {}, export = { _, _, _, _ -> }, notificationSettings = {}, notifications = {})
    private val base = WorkerState.preview()
    private val working = base.attend()
    private val done = working.copy(checks = listOf("확인", "확인", "확인"), peerChecked = true, peerStatus = "photo_confirmed", monitoring = true,
        peerEvidence = listOf(PeerEvidence(working.uid, "b", "checked", 1000, "p"), PeerEvidence("preview-buddy", "a", "checked", 1000)))
    private val incoming = WorkerCall("c1", subject = "preview-buddy", subjectName = "박철수", zone = "2층 동측", stage = "peer", createdAt = System.currentTimeMillis(), deadline = System.currentTimeMillis() + 15_000)
    private val own = WorkerCall("c2", subject = working.uid, subjectName = working.name, stage = "peer", createdAt = System.currentTimeMillis(), peakG = 3.4, dropMeters = 2.1)
    private val events = listOf(WorkerEvent("3", "동료 수락", "박철수"), WorkerEvent("2", "이상 움직임 감지", "센서 추정 신호", estimate = true), WorkerEvent("1", "출근 기록", "2층 · 설비 작업"))
        .foldRight(emptyList<WorkerEvent>()) { entry, chain -> listOf(entry.copy(hash = eventHash(chain.firstOrNull()?.hash.orEmpty(), entry))) + chain }

    private fun shot(name: String, content: @Composable ColumnScope.() -> Unit) {
        rule.setContent {
            WorkerTheme {
                ProvideTextStyle(TextStyle(fontFamily = font)) {
                    Column(Modifier.fillMaxSize().background(CanvasColor).verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
                }
            }
        }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun today1BeforeWork() = shot("1-1-today-before") { HomePage(base) }
    @Test fun today2Tasks() = shot("1-2-today-tasks") { HomePage(working) }
    @Test fun today3Working() = shot("2-1-working") { HomePage(done) }
    @Test fun today4MonitorOff() = shot("2-1-monitor-off") { HomePage(done.copy(monitoring = false)) }
    @Test fun today5HighRisk() = shot("2-1-high-risk") { HomePage(done.copy(highRiskTask = "scaffold", highRiskStartedAt = 1000)) }
    @Test fun today6NotCarried() = shot("2-1-not-carried") { HomePage(done.copy(carried = false, deviceState = "not_carried")) }
    @Test fun today7After() = shot("today-after") { HomePage(working.checkout().copy(events = events)) }
    @Test fun checklist() = shot("1-3-checklist") { ChecklistSheet(working.copy(checks = listOf("확인", "모름", "")), false) {} }
    @Test fun peerStart() = shot("1-4-peer") { PeerSheet(working, false, actions, {}, {}) { _, _ -> } }
    @Test fun peerMismatch() = shot("1-6-mismatch") { PeerSheet(working.copy(peerStatus = "mismatch"), false, actions, {}, {}) { _, _ -> } }
    @Test fun peerDone() = shot("1-7-done") { PeerSheet(done, false, actions, {}, {}) { _, _ -> } }
    @Test fun highRisk() = shot("2-2-high-risk") { HighRiskSheet(working, false) {} }
    @Test fun checkIn() = shot("2-3-check-in") { PromptSheet(WorkPrompt("p", "break_return", 1000, "pending"), false) {} }
    @Test fun ack() = shot("3-2-ack") { AckSheet(own.copy(status = "closed", respondedAt = 2000)) {} }
    @Test fun calling() = shot("3-3-calling") { CallSheet(own, working, System.currentTimeMillis(), false, actions, {}, {}) { _, _ -> } }
    @Test fun incomingCall() = shot("3-4-incoming") { CallSheet(incoming, working, System.currentTimeMillis(), false, actions, {}, {}) { _, _ -> } }
    @Test fun going() = shot("3-5-going") { CallSheet(incoming.accept(working.uid).copy(acceptedAt = 1000), working, 0, false, actions, {}, {}) { _, _ -> } }
    @Test fun arrived() = shot("3-6-arrived") { CallSheet(incoming.accept(working.uid).copy(arrivedAt = 1000), working, 0, false, actions, {}, {}) { _, _ -> } }
    @Test fun wrapUp() = shot("3-7-wrap") { CallSheet(incoming.accept(working.uid).copy(arrivedAt = 1000, observation = "conscious"), working, 0, false, actions, {}, {}) { _, _ -> } }
    @Test fun failed() = shot("3-8-failed") { CallFailedSheet("010", false, {}, {}) {} }
    @Test fun checkout() = shot("5-1-checkout") { CheckoutSheet(done, System.currentTimeMillis() + 3_600_000, false) {} }
    @Test fun export() = shot("5-4-export") { ExportSheet(done.copy(events = events, calls = listOf(own)), { _, _, _, _ -> }, { _, _ -> }, {}) {} }
    @Test fun report() = shot("5-5-report") { PreviewReportSheet(own, done) {} }
    @Test fun privacy() = shot("6-2-privacy") { PrivacySheet(true) }
    @Test fun guide() = shot("6-3-guide") { GuideSheet() }
    @Test fun riskList() = shot("4-2-risks") { ReportPage(base, false, actions, { _, _, _, _, _, _, _ -> }, {}) }
    @Test fun records() = shot("5-3-records") { RecordsPage(done, {}, {}) }
    @Test fun timeline() = shot("5-2-timeline") { RecordsPage(done.copy(events = events), {}, {}, openTimeline = true) }
    @Test fun profile() = shot("6-1-profile") { ProfilePage(base, {}, {}) }
    @Test fun temperature() = shot("6-4-temperature") { TemperatureSheet(safetyTemperature(done.work, events + listOf(WorkerEvent("c", "작업 전 확인", ""), WorkerEvent("p", "동료 교차 확인", ""))), true) }
}
