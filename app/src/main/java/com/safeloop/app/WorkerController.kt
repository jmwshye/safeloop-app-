package com.safeloop.app
import android.app.*
import android.content.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

class WorkerController(private val app:Application):AndroidViewModel(app) {
 private val storage=WorkerStorage(app)
 private val monitoringPrefs=app.getSharedPreferences("safeloop",Context.MODE_PRIVATE)
 var state by mutableStateOf(storage.load());private set
 var now by mutableStateOf(System.currentTimeMillis());private set
 var busy by mutableStateOf(false);private set
 var feedback by mutableStateOf<String?>(null)
 var openCallId by mutableStateOf<String?>(null)
 init { viewModelScope.launch { while(true){ now=System.currentTimeMillis();delay(1000) } } }
 private fun update(next:WorkerState){state=next;if(next.preview)storage.save(next)}
 private fun event(title:String,detail:String,estimate:Boolean=false){update(state.copy(events=listOf(WorkerEvent(UUID.randomUUID().toString(),title,detail,estimate=estimate))+state.events))}
 private fun action(block:suspend()->Unit){if(busy)return;busy=true;viewModelScope.launch{try{block()}catch(e:Exception){feedback=e.localizedMessage?:"연결을 확인하고 다시 시도해 주세요."}finally{busy=false}}}
 fun saveWorkInfo(name:String,zone:String,wage:Int){require(wage in 0..10000000);update(state.copy(name=name.ifBlank{"김민수"},zone=zone.ifBlank{"2층 · 설비 작업"},wage=wage));feedback="내 작업 정보를 저장했어요."}
 fun checkIn(location:Map<String,Any?>?=null)=action { update(state.attend());event("출근 기록","${state.zone} · 예상 일당 ${moneyText(state.wage)}원");feedback="출근을 기록했어요. 오늘도 안전하게 작업하세요." }
 fun checkOut()=action { update(state.checkout());event("퇴근 기록","오늘의 근로 기록을 마무리했어요.");Unit;feedback="오늘도 수고하셨어요. 근로 기록을 확인해 보세요." }
 fun resetPreview(){Unit;update(WorkerState.preview());feedback="체험 기록을 초기화했어요."}
 fun emergency(){event("119 전화 화면 열기","전화 화면 실행 요청 · 통화 연결 여부는 확인하지 않습니다.")}
 fun saveChecks(values:List<String>)=action {
  require(values.size==3&&values.none{it.isBlank()}){"세 항목의 확인 상태를 모두 선택해 주세요."}
  if(state.preview){update(state.copy(checks=values));event("작업 전 확인",if(values.all{it=="확인"})"3개 항목 확인 완료" else "개선 필요·모름 항목이 있어요. 책임자에게 확인해 주세요.")}
  feedback="작업 전 확인을 저장했어요."
 }
 fun checkPeer(observed:String,localPhoto:String?)=action {
  if(state.preview){update(state.copy(peerChecked=observed=="착용"));event("동료 교차 확인",if(observed=="착용")"${state.buddyName} · ${if(localPhoto==null)"버튼 확인" else "사진 확인"}" else "${state.buddyName} · 개선 필요")}
  feedback="동료 확인을 저장했어요."
 }

}
