package com.safeloop.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
                    Box(Modifier.fillMaxWidth().height(150.dp).background(BrandSoft, RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
                        BrandRings(Modifier.size(width = 150.dp, height = 96.dp))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("안전고리", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
                        Text("내 근로와 안전을, 내가 기록해요.", fontSize = 18.sp, color = Muted)
                    }
                    SLCard {
                        Text("출근 한 번으로 근로 기록이 남아요", fontSize = 16.sp, color = Ink)
                        HorizontalDivider(color = Line)
                        Text("동료와 서로 확인하고 사진으로 남겨요", fontSize = 16.sp, color = Ink)
                        HorizontalDivider(color = Line)
                        Text("쓰러지면 동료와 반장님께 알려요", fontSize = 16.sp, color = Ink)
                    }
                    SLButton("시작하기", { controller.beginOnboarding("signup") })
                    SLButton("체험 먼저 해보기", controller::usePreview, secondary = true)
                    GhostButton("이미 계정이 있어요 · 로그인", { controller.beginOnboarding("login") })
                }
                "signup", "login" -> key(stage) {
                    val signup = stage == "signup"
                    var name by rememberSaveable { mutableStateOf("") }
                    var email by rememberSaveable { mutableStateOf("") }
                    var password by remember { mutableStateOf("") }
                    PageHeading(if (signup) "가입 1 / 4" else "로그인", if (signup) "계정을 만들어요" else "다시 만나서 반가워요", "내 근로 기록의 주인은 나예요. 기록은 이 계정에 남아요.")
                    if (controller.serverDown) {
                        SmallNote("지금은 서버에 연결할 수 없어요. 체험 모드로 이어서 볼 수 있어요. 기록은 이 폰에만 저장돼요.", amber = true)
                        SLButton("체험 모드로 계속", { controller.continueOffline(name) }, enabled = !busy, secondary = true)
                        if (BuildConfig.DEBUG) {
                            var server by rememberSaveable { mutableStateOf("") }
                            SLField("서버 주소를 받았다면", server, { server = it }, "https://")
                            SLButton("서버 주소 저장", { controller.changeServer(server) }, enabled = !busy && server.isNotBlank(), secondary = true)
                        }
                    }
                    if (signup) SLField("이름", name, { name = it })
                    SLField("이메일", email, { email = it }, "name@example.com")
                    OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), label = { Text(if (signup) "비밀번호 · 12~20자" else "비밀번호") })
                    SLButton(if (signup) "다음" else "로그인", { controller.authenticate(email, password, name, signup) },
                        enabled = !busy && email.isNotBlank() && password.isNotBlank() && (!signup || (password.length in 12..20 && name.isNotBlank())))
                    GhostButton(if (signup) "이미 계정이 있어요" else "처음 이용해요", { controller.beginOnboarding(if (signup) "login" else "signup") })
                }
                "sites" -> {
                    PageHeading("현장", "근무할 현장을 선택해요", "참여한 현장은 코드를 다시 입력할 필요가 없어요.")
                    controller.choices.forEach { site -> SLButton(site.name, { controller.chooseSite(site.id) }, enabled = !busy) }
                    if (controller.choices.isEmpty()) SLButton("참여한 현장 다시 확인", controller::reloadSites, enabled = !busy, secondary = true)
                    SLButton("현장 코드로 참여", { controller.beginOnboarding("code") }, enabled = !busy, secondary = true)
                }
                // 0-3 현장 코드
                "code" -> {
                    var code by rememberSaveable { mutableStateOf("") }
                    val site = controller.pendingSite
                    PageHeading("가입 2 / 4", "현장 코드를 입력해요", "반장님께 받은 코드를 입력하면 이 현장에 참여해요.")
                    if (site == null) {
                        SLField("현장 코드", code, { code = it.uppercase() })
                        SLButton("현장 확인", { controller.lookup(code) }, enabled = code.isNotBlank() && !busy)
                    } else {
                        SLCard(color = OkSoft) {
                            SLPill("현장을 찾았어요")
                            Text(site.name, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
                            Text("현장 책임자 " + site.foreman.ifBlank { "미등록" }, fontSize = 14.sp, color = Muted)
                        }
                        NoteText("현장에 참여해도 사업주가 내 기록을 지우거나 고칠 수 없어요.")
                        SLButton("이 현장으로 참여", controller::joinPending, enabled = !busy)
                        SLButton("다른 코드 입력", controller::cancelSiteConfirmation, enabled = !busy, secondary = true)
                    }
                    GhostButton("코드가 없어요 · 체험으로 보기", controller::usePreview, enabled = !busy)
                }
                // 0-4 내 작업 정보
                "profile" -> {
                    val jobs = listOf("형틀 목공", "철근", "설비", "전기", "미장·조적", "기타")
                    var job by rememberSaveable { mutableStateOf(controller.state.job) }
                    var other by rememberSaveable { mutableStateOf(controller.state.job.isNotBlank() && controller.state.job !in jobs) }
                    var zone by rememberSaveable { mutableStateOf(controller.state.zone) }
                    var wage by rememberSaveable { mutableStateOf("") }
                    PageHeading("가입 3 / 4", "내 작업 정보를 알려 주세요", controller.state.site)
                    Text("직종", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Muted)
                    PickRow(jobs, if (other) jobs.lastIndex else jobs.indexOf(job)) { other = it == jobs.lastIndex; job = if (other) "" else jobs[it] }
                    if (other) SLField("직종 직접 입력", job, { job = it })
                    SLField("작업 구역", zone, { zone = it }, "예: 2층 슬래브")
                    SLField("하루 예상 일당 · 선택", wage, { wage = it.filter(Char::isDigit).take(8) }, "예: 230000")
                    NoteText("예상 일당은 내 기록용이에요. 실제 지급액과 다를 수 있어요.")
                    SLButton("저장", { controller.finishProfile(job, zone, wage.toIntOrNull()) },
                        enabled = !busy && job.isNotBlank() && zone.isNotBlank() && (wage.isBlank() || (wage.toIntOrNull() ?: -1) in 0..10000000))
                }
                // 0-5 권한 안내
                "permissions" -> {
                    PageHeading("가입 4 / 4", "이 권한이 필요해요", "필요한 때만 쓰고, 이유를 먼저 알려 드려요.")
                    FlatBox {
                        listOf(Triple("알림", "긴급 호출을 놓치지 않으려고요", true), Triple("움직임 감지", "충격을 감지하고, 켜져 있음을 알림으로 보여요", true),
                            Triple("위치", "출근 확인과 긴급 상황에서만 써요", false), Triple("카메라", "동료·위험 요소 촬영. 얼굴은 자동으로 가려요", false), Triple("마이크", "말로 도움을 요청할 때만 들어요. 녹음은 저장하지 않아요", false)).forEachIndexed { i, (name, why, needed) ->
                            if (i > 0) HorizontalDivider(color = Line)
                            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Column(Modifier.weight(1f)) { Text(name, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Ink); Text(why, fontSize = 14.sp, lineHeight = 21.sp, color = Muted) }
                                if (needed) SLPill("필요해요", brand = true) else SLPill("선택", neutral = true)
                            }
                        }
                    }
                    NoteText("허용하지 않아도 근로 기록은 쓸 수 있어요. 일부 기능은 제한돼요.")
                    SLButton("권한 허용하고 시작", actions.onboardingPermissions)
                    GhostButton("나중에 할게요", controller::finishOnboarding)
                }
            }
        }
        EmergencyStrip(actions.dial)
    }
}
