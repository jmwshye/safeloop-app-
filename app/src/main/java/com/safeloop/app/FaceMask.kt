package com.safeloop.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector

/** 사진 속 얼굴을 폰 안에서 찾아 모자이크한다. 사진과 얼굴 정보를 밖으로 보내지 않는다. */
internal object FaceMask {
    private const val MODEL = "blaze_face_short_range.tflite"

    /** 가린 뒤의 사진과 가린 얼굴 수를 돌려준다. 실패하면 호출한 쪽에서 원본을 쓴다. */
    fun cover(context: Context, source: Bitmap, keepChin: Boolean): Pair<Bitmap, Int> {
        val options = FaceDetector.FaceDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
            .setRunningMode(RunningMode.IMAGE).setMinDetectionConfidence(0.5f).build()
        val input = if (source.config == Bitmap.Config.ARGB_8888) source else source.copy(Bitmap.Config.ARGB_8888, false)
        val faces = FaceDetector.createFromOptions(context, options).use { detector ->
            detector.detect(BitmapImageBuilder(input).build()).detections().map { it.boundingBox() }
        }
        if (faces.isEmpty()) return source to 0
        val result = input.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        faces.forEach { face ->
            val box = faceBox(face.left, face.top, face.right, face.bottom, result.width, result.height, keepChin) ?: return@forEach
            val (x, y, width, height) = box
            val region = Bitmap.createBitmap(result, x, y, width, height)
            val small = Bitmap.createScaledBitmap(region, (width / 16).coerceAtLeast(1), (height / 16).coerceAtLeast(1), false)
            val blocks = Bitmap.createScaledBitmap(small, width, height, false)
            canvas.drawBitmap(blocks, x.toFloat(), y.toFloat(), null)
            listOf(region, small, blocks).distinct().forEach(Bitmap::recycle)
        }
        return result to faces.size
    }
}

/** 얼굴 상자를 조금 넓혀 사진 안으로 자른다. 보호구 사진은 턱끈이 보이도록 아래쪽을 남긴다. */
internal fun faceBox(left: Float, top: Float, right: Float, bottom: Float, width: Int, height: Int, keepChin: Boolean): List<Int>? {
    val marginX = (right - left) * .15f
    val marginY = (bottom - top) * .15f
    val x0 = (left - marginX).toInt().coerceIn(0, width)
    val y0 = (top - marginY).toInt().coerceIn(0, height)
    val x1 = (right + marginX).toInt().coerceIn(0, width)
    val fullBottom = (bottom + marginY).toInt().coerceIn(0, height)
    val y1 = if (keepChin) (top + (bottom - top) * .68f).toInt().coerceIn(0, height) else fullBottom
    return if (x1 > x0 && y1 > y0) listOf(x0, y0, x1 - x0, y1 - y0) else null
}
