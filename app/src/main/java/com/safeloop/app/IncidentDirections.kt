package com.safeloop.app

import android.location.Location
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

// 3-5. Relative bearing on the device; querying this screen never uploads the responder's position.
@Composable
fun IncidentDirections(call: WorkerCall, locate: ((Location?) -> Unit) -> Unit) {
    var here by remember(call.id) { mutableStateOf<Location?>(null) }
    var attempted by remember(call.id) { mutableStateOf(false) }
    SLButton("내 위치에서 거리 확인", { locate { here = it; attempted = true } }, secondary = true)
    val position = here
    if (position == null) {
        if (attempted) SmallNote("내 위치를 확인하지 못했어요. 동료의 작업 구역을 확인해 주세요.", amber = true)
        return
    }
    val distance = FloatArray(2)
    Location.distanceBetween(position.latitude, position.longitude, call.latitude ?: return, call.longitude ?: return, distance)
    DetailRow("직선 거리", "약 ${distance[0].toInt()}m")
    Text("상대 위치 · 위쪽이 북쪽 · 파랑은 내 위치, 빨강은 동료 위치")
    Canvas(Modifier.fillMaxWidth().height(128.dp).background(BrandSoft)) {
        val angle = Math.toRadians(distance[1].toDouble())
        val radius = if (distance[0] < 1) 0f else size.minDimension * .35f
        val target = Offset(center.x + sin(angle).toFloat() * radius, center.y - cos(angle).toFloat() * radius)
        drawLine(Muted, center, target, 2.dp.toPx())
        drawCircle(Brand, 9.dp.toPx(), center)
        drawCircle(Danger, 6.dp.toPx(), target)
    }
    SmallNote("기기 위치 오차가 있어요. 안전한 접근 경로를 현장에서 확인하세요.")
}
