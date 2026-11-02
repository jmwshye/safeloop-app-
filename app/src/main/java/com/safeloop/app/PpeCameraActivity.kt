package com.safeloop.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

class PpeCameraActivity : ComponentActivity(), SurfaceHolder.Callback {
    private val handler = Handler(Looper.getMainLooper())
    private val manager by lazy { getSystemService(CameraManager::class.java) }
    private lateinit var preview: SurfaceView
    private lateinit var frame: FrameLayout
    private lateinit var message: TextView
    private lateinit var shutter: Button
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSize: Size? = null
    private var sensorDegrees = 0
    private var frontFacing = false
    private var generation = 0
    private var opening = false
    private var resumed = false
    private var saving = false
    private val isPpe get() = (intent.getStringExtra("guide") ?: "peer_check") == "peer_check"

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (intent.data?.authority != "$packageName.files") { finish(); return }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(17, 24, 30))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { action() }
        }
        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        toolbar.addView(button("닫기") { finish() })
        toolbar.addView(TextView(this).apply {
            val title = if (isPpe) "동료 보호구 촬영" else "현장 사진 촬영"
            text = if (intent.getBooleanExtra("previewMode", false)) "체험 모드 · $title" else title
            setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(button("119") { returnAction("dial119") })
        root.addView(toolbar)
        frame = FrameLayout(this)
        preview = SurfaceView(this)
        frame.addView(preview, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        if (isPpe) frame.addView(EquipmentGuide(this), FrameLayout.LayoutParams(-1, -1))
        frame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitPreview() }
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        message = TextView(this).apply {
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8)); textSize = 16f
            text = if (isPpe) "보호구가 안내선 안에 보이게 맞춰주세요. 얼굴은 나오지 않아도 돼요."
                else "사람 대신 현장과 위험 요소가 보이게 맞춰주세요."
        }
        root.addView(message)
        shutter = button("사진 찍기") { takePhoto() }.apply { isEnabled = false }
        root.addView(shutter)
        if (isPpe) root.addView(button("내가 말한 것과 달라 보여요") { returnAction("mismatch") })
        preview.holder.addCallback(this)
        setContentView(root)
    }

    private fun returnAction(key: String) {
        setResult(Activity.RESULT_OK, Intent().putExtra(key, true))
        finish()
    }

    override fun onResume() { super.onResume(); resumed = true; openCamera() }
    override fun onPause() { resumed = false; closeCamera(); super.onPause() }
    override fun surfaceCreated(holder: SurfaceHolder) = openCamera()
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
    override fun surfaceDestroyed(holder: SurfaceHolder) = closeCamera()

    private fun closeCamera() {
        generation++
        opening = false
        session?.close(); session = null
        camera?.close(); camera = null
        reader?.close(); reader = null
        if (::shutter.isInitialized) shutter.isEnabled = false
    }

    private fun cameraUnavailable() {
        closeCamera()
        message.text = "카메라를 사용할 수 없어요. 카메라 권한과 다른 앱의 사용 여부를 확인하고 다시 열어주세요."
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (!resumed || !::preview.isInitialized || !preview.holder.surface.isValid || opening || camera != null || saving) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraUnavailable(); return
        }
        val ticket = ++generation
        opening = true
        try {
            val ids = manager.cameraIdList
            val id = ids.firstOrNull { manager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK }
                ?: ids.first()
            val info = manager.getCameraCharacteristics(id)
            val streams = requireNotNull(info[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP])
            val jpegSizes = streams.getOutputSizes(ImageFormat.JPEG)
            val photo = jpegSizes.filter { it.width <= 2048 && it.height <= 2048 }.maxByOrNull { it.width.toLong() * it.height }
                ?: jpegSizes.minBy { it.width.toLong() * it.height }
            val sizes = streams.getOutputSizes(SurfaceHolder::class.java)
            val suitable = sizes.filter { it.width <= 1920 && it.height <= 1080 }.ifEmpty { sizes.toList() }
            previewSize = suitable.minWith(compareBy<Size> { abs(it.width.toDouble() / it.height - photo.width.toDouble() / photo.height) }
                .thenBy { abs(it.width - 1280) })
            sensorDegrees = info[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0
            frontFacing = info[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_FRONT
            previewSize?.let { preview.holder.setFixedSize(it.width, it.height) }
            fitPreview()
            reader = ImageReader.newInstance(photo.width, photo.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ source -> if (generation == ticket) saveImage(source) }, handler)
            }
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (generation != ticket) { device.close(); return }
                    opening = false; camera = device
                    startPreview(device, ticket)
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); if (generation == ticket) cameraUnavailable() }
                override fun onError(device: CameraDevice, error: Int) { onDisconnected(device) }
            }, handler)
        } catch (_: Exception) { cameraUnavailable() }
    }

    @Suppress("DEPRECATION")
    private fun startPreview(device: CameraDevice, ticket: Int) {
        val surface = preview.holder.surface
        try {
            val live = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface) }
            device.createCaptureSession(listOf(surface, requireNotNull(reader).surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(active: CameraCaptureSession) {
                    if (generation != ticket) { active.close(); return }
                    session = active
                    try {
                        active.setRepeatingRequest(live.build(), null, handler)
                        shutter.isEnabled = true
                    } catch (_: Exception) { cameraUnavailable() }
                }
                override fun onConfigureFailed(active: CameraCaptureSession) { active.close(); if (generation == ticket) cameraUnavailable() }
            }, handler)
        } catch (_: Exception) { cameraUnavailable() }
    }

    @Suppress("DEPRECATION")
    private fun imageRotation() = (sensorDegrees + (if (frontFacing) 1 else -1) * windowManager.defaultDisplay.rotation * 90 + 360) % 360

    private fun fitPreview() {
        val size = previewSize ?: return
        if (frame.width == 0 || frame.height == 0) return
        val sideways = imageRotation() % 180 != 0
        val w = if (sideways) size.height else size.width
        val h = if (sideways) size.width else size.height
        val scale = maxOf(frame.width.toFloat() / w, frame.height.toFloat() / h)
        val params = preview.layoutParams as FrameLayout.LayoutParams
        val width = (w * scale).roundToInt()
        val height = (h * scale).roundToInt()
        if (params.width != width || params.height != height) {
            params.width = width; params.height = height
            preview.layoutParams = params
        }
    }

    private fun takePhoto() {
        val device = camera ?: return
        val active = session ?: return
        val destination = reader?.surface ?: return
        shutter.isEnabled = false
        try {
            val capture = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(destination); set(CaptureRequest.JPEG_ORIENTATION, imageRotation())
            }
            active.capture(capture.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    if (session == this@PpeCameraActivity.session) {
                        shutter.isEnabled = true; message.text = "사진을 찍지 못했어요. 다시 찍어주세요."
                    }
                }
            }, handler)
        } catch (_: Exception) { cameraUnavailable() }
    }

    private fun saveImage(source: ImageReader) {
        val photo = source.acquireNextImage()?.use { image ->
            val buffer = image.planes[0].buffer
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } ?: return
        val destination = intent.data ?: return
        saving = true
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching { requireNotNull(contentResolver.openOutputStream(destination)).use { it.write(photo) } }.isSuccess
            }
            if (isFinishing) return@launch
            if (success) {
                setResult(Activity.RESULT_OK, Intent().setData(destination))
                finish()
            } else {
                saving = false; shutter.isEnabled = session != null
                message.text = "사진을 저장하지 못했어요. 저장 공간을 확인하고 다시 찍어주세요."
                openCamera()
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}

private class EquipmentGuide(context: Context) : View(context) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2
        setShadowLayer(3f, 0f, 1f, Color.BLACK)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
        textSize = resources.displayMetrics.scaledDensity * 16
        setShadowLayer(3f, 0f, 1f, Color.BLACK)
    }
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val pad = resources.displayMetrics.density * 8
        for ((top, bottom, text) in listOf(Triple(.12f, .39f, "안전모"), Triple(.48f, .84f, "안전대 · 보호구"))) {
            val box = RectF(.18f * w, top * h, .82f * w, bottom * h)
            canvas.drawRoundRect(box, pad, pad, line)
            canvas.drawText(text, w / 2, box.top + label.textSize + pad, label)
        }
    }
}
