package com.safeloop.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Bundle
import androidx.compose.runtime.*
import androidx.core.os.bundleOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class CapturedImage(val scope: String, val guide: String, val subject: String, val path: String?, val mismatch: Boolean)

class WorkerCapture(private val application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val folder get() = File(application.filesDir, "captures")
    private val request get() = saved.get<Bundle>("request")
    val guide get() = request?.getString("guide")
    val subject get() = request?.getString("subject")
    val scope get() = request?.getString("scope")
    val file get() = request?.getString("file")?.let { File(folder, it) }
    var result by mutableStateOf(readResult())
        private set
    var failure by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    init {
        if (request?.getBoolean("processing") == true) prepare()
    }
    fun begin(scope: String, guide: String, subject: String): Boolean {
        if (request != null) return false
        discardResult()
        folder.mkdirs()
        saved["request"] = bundleOf("scope" to scope, "guide" to guide, "subject" to subject, "file" to "${UUID.randomUUID()}.jpg")
        failure = null
        return true
    }
    fun cancel() {
        file?.delete()
        saved.remove<Bundle>("request")
    }
    fun finish(mismatch: Boolean) {
        val active = request ?: return
        if (mismatch) {
            file?.delete()
            publish(active, null, true)
        } else {
            saved["request"] = Bundle(active).apply { putBoolean("processing", true) }
            prepare()
        }
    }
    private fun prepare() {
        val active = request ?: return
        val source = file ?: return
        viewModelScope.launch {
            try {
                val keepChin = active.getString("guide") == "peer_check"
                val (jpeg, faces) = withContext(Dispatchers.IO) { prepareCapture(application, source, keepChin) }
                publish(active, jpeg, false)
                if (faces > 0) notice = "사진 속 얼굴 ${faces}곳을 자동으로 가렸어요."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                cancel()
                failure = "사진을 준비하지 못했어요. 다시 촬영해 주세요."
            }
        }
    }
    private fun publish(active: Bundle, path: String?, mismatch: Boolean) {
        saved["result"] = Bundle(active).apply { putString("path", path); putBoolean("mismatch", mismatch) }
        saved.remove<Bundle>("request")
        result = readResult()
    }
    private fun readResult() = saved.get<Bundle>("result")?.let {
        CapturedImage(it.getString("scope").orEmpty(), it.getString("guide").orEmpty(), it.getString("subject").orEmpty(),
            it.getString("path"), it.getBoolean("mismatch"))
    }
    fun consume() { saved.remove<Bundle>("result"); result = null }
    fun discardResult() { result?.path?.let { File(it).delete() }; consume() }
}

@Composable
fun OnCameraResult(actions: WorkerActions, guide: String, subject: String, receive: (CapturedImage) -> Unit) {
    val photo = actions.cameraResult
    LaunchedEffect(photo, guide, subject) {
        if (photo != null && photo.guide == guide && photo.subject == subject) {
            receive(photo)
            actions.consumeCamera()
        }
    }
}

private fun prepareCapture(context: android.content.Context, file: File, keepChin: Boolean): Pair<String, Int> {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    val sample = generateSequence(1) { it * 2 }.first { bounds.outWidth / it <= 1600 && bounds.outHeight / it <= 1600 }
    val original = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("Invalid photograph")
    val orientation = ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    val degrees = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    val bitmap = if (degrees == 0f) original else Bitmap.createBitmap(original, 0, 0, original.width, original.height, Matrix().apply { postRotate(degrees) }, true)
    // 얼굴을 찾지 못해도 사진 저장은 막지 않는다.
    val (masked, faces) = runCatching { FaceMask.cover(context, bitmap, keepChin) }.getOrDefault(bitmap to 0)
    val temp = File(file.parentFile, file.name + ".tmp")
    try {
        temp.outputStream().use { check(masked.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
        java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    } finally {
        temp.delete()
        if (masked !== bitmap) masked.recycle()
        if (bitmap !== original) bitmap.recycle()
        original.recycle()
    }
    return file.path to faces
}
