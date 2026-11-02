package com.safeloop.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

// 0-1 ~ 0-5. Returning workers can reuse an existing membership.
@Composable
fun WorkerOnboarding(stage: String, controller: WorkerController, actions: WorkerActions) {
    val busy = controller.busy
    fun back() {
        when (stage) {
            "code" -> if (controller.pendingSite != null) controller.cancelSiteConfirmation() else controller.beginOnboarding("sites")
            "login", "signup" -> controller.beginOnboarding("welcome")
            "permissions" -> controller.finishOnboarding()
            else -> controller.beginOnboarding("welcome")
        }
    }
    BackHandler(stage != "welcome", onBack = ::back)
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (stage != "welcome") TextButton(onClick = ::back, enabled = !busy) { Text("뒤로") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            controller.feedback?.let { SmallNote(it, amber = true) }
            when (stage) {
                "welcome" -> {
                    PageHeading("안전고리", "내 근로와 안전을,\n내가 기록해요.", "출퇴근과 동료 확인을 기록하고, 이상 신호가 생기면 도움을 요청해요.")
                    SLButton("시작하기", { controller.beginOnboarding("signup") })
                    SLButton("체험 먼저 해보기", controller::usePreview, secondary = true)
                    SLButton("이미 계정이 있어요 · 로그인", { controller.beginOnboarding("login") }, secondary = true)
                }
                "signup", "login" -> key(stage) {
                    val signup = stage == "signup"
                    var name by rememberSaveable { mutableStateOf("") }
                    var email by rememberSaveable { mutableStateOf("") }
                    var password by remember { mutableStateOf("") }
                    PageHeading("계정", if (signup) "계정을 만들어요" else "다시 만나서 반가워요", "근로·안전 기록은 이 계정에 남아요.")
                    if (signup) SLField("이름", name, { name = it })
                    SLField("이메일", email, { email = it }, "name@example.com")
                    OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), label = { Text(if (signup) "비밀번호 · 12~20자" else "비밀번호") })
                    SLButton(if (signup) "다음" else "로그인", { controller.authenticate(email, password, name, signup) },
                        enabled = !busy && email.isNotBlank() && password.isNotBlank() && (!signup || (password.length in 12..20 && name.isNotBlank())))
                    SLButton(if (signup) "이미 계정이 있어요" else "처음 이용해요", { controller.beginOnboarding(if (signup) "login" else "signup") }, secondary = true)
                }
                "sites" -> {
                    PageHeading("현장", "근무할 현장을 선택해요", "참여한 현장은 코드를 다시 입력할 필요가 없어요.")
                    controller.choices.forEach { site -> SLButton(site.name, { controller.chooseSite(site.id) }, enabled = !busy) }
                    if (controller.choices.isEmpty()) SLButton("참여한 현장 다시 확인", controller::reloadSites, enabled = !busy, secondary = true)
                    SLButton("현장 코드로 참여", { controller.beginOnboarding("code") }, enabled = !busy, secondary = true)
                }
                "code" -> {
                    var code by rememberSaveable { mutableStateOf("") }
                    val site = controller.pendingSite
                    PageHeading("현장 연결", "현장 코드를 입력해요", "반장님께 받은 코드를 확인해 주세요.")
                    if (site == null) {
                        SLField("현장 코드", code, { code = it.uppercase() })
                        SLButton("현장 확인", { controller.lookup(code) }, enabled = code.isNotBlank() && !busy)
                    } else {
                        DetailRow("현장", site.name)
                        DetailRow("책임자", site.foreman.ifBlank { "미등록" })
                        SLButton("이 현장으로 참여", controller::joinPending, enabled = !busy)
                        SLButton("다른 코드 입력", controller::cancelSiteConfirmation, enabled = !busy, secondary = true)
                    }
                    SLButton("코드가 없어요 · 체험으로 보기", controller::usePreview, enabled = !busy, secondary = true)
                }
                "profile" -> {
                    var job by rememberSaveable { mutableStateOf(controller.state.job) }
                    var zone by rememberSaveable { mutableStateOf(controller.state.zone) }
                    var wage by rememberSaveable { mutableStateOf("") }
                    PageHeading("내 작업 정보", "어떤 일을 하세요?", controller.state.site)
                    listOf(listOf("형틀 목공", "철근", "설비"), listOf("전기", "미장·조적", "기타")).forEach { jobs ->
                        ChoiceTabs(jobs, jobs.indexOf(job)) { job = jobs[it] }
                    }
                    SLField("직종", job, { job = it })
                    SLField("작업 구역", zone, { zone = it })
                    SLField("하루 예상 일당 · 선택", wage, { wage = it.filter(Char::isDigit).take(8) })
                    SmallNote("예상 일당은 내 기록용이에요. 실제 지급액과 다를 수 있어요.")
                    SLButton("저장", { controller.finishProfile(job, zone, wage.toIntOrNull()) },
                        enabled = !busy && job.isNotBlank() && zone.isNotBlank() && (wage.isBlank() || (wage.toIntOrNull()?:-1) in 0..10000000))
                }
                "permissions" -> {
                    PageHeading("권한 안내", "필요할 때만 사용해요", "허용하지 않아도 출퇴근 기록을 남길 수 있어요.")
                    DetailRow("알림", "잠금 화면에서 안전 요청 확인")
                    DetailRow("움직임 센서", "직접 감시를 켠 작업 시간")
                    DetailRow("위치 · 선택", "출근 확인과 긴급 상황")
                    DetailRow("카메라 · 선택", "동료 보호구와 위험 요소 촬영")
                    SLButton("권한 허용하고 시작", { actions.onboardingPermissions(controller::finishOnboarding) })
                    SLButton("나중에 할게요", controller::finishOnboarding, secondary = true)
                }
            }
        }
        EmergencyStrip(actions.dial)
    }
}
