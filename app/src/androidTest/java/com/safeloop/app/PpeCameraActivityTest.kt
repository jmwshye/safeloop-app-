package com.safeloop.app

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.widget.Button
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PpeCameraActivityTest {
    private fun openCamera(output: Uri? = null): ActivityScenario<PpeCameraActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, PpeCameraActivity::class.java).apply {
            data = output ?: Uri.parse("content://${context.packageName}.files/captures/guide-test.jpg")
            putExtra("guide", "peer_check")
        }
        return ActivityScenario.launchActivityForResult(intent)
    }

    @Test fun cameraResultReturnsToTheOriginalBuddyAfterSavedStateRestoration() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val saved=SavedStateHandle()
        val first=WorkerCapture(app,saved)
        assertTrue(first.begin("session:site","peer_check","buddy-B"))
        val output=checkNotNull(first.file)
        val bitmap=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888)
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,90,it) }
        bitmap.recycle()
        val restored=WorkerCapture(app,SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        try {
            assertEquals("buddy-B",restored.subject)
            assertEquals(output,restored.file)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { restored.finish(false) }
            val until=SystemClock.elapsedRealtime()+5000
            while (restored.result==null && SystemClock.elapsedRealtime()<until) SystemClock.sleep(25)
            val result=checkNotNull(restored.result)
            assertEquals("session:site",result.scope)
            assertEquals("buddy-B",result.subject)
            assertEquals(output.path,result.path)
            assertTrue(output.length()>0)
        } finally { output.delete() }
    }

    @Test fun mismatchReturnsToPeerCheckWithoutAPhoto() {
        openCamera().use { screen ->
            onView(withText("내가 말한 것과 달라 보여요")).perform(click())
            val result = screen.result
            assertEquals(Activity.RESULT_OK, result.resultCode)
            assertNull(result.resultData?.data)
            assertTrue(result.resultData?.getBooleanExtra("mismatch", false) == true)
        }
    }

    @Test fun emergencyButtonReturnsDialerRequestWithoutAPhoto() {
        openCamera().use { screen ->
            onView(withText("119")).perform(click())
            val result = screen.result
            assertEquals(Activity.RESULT_OK, result.resultCode)
            assertNull(result.resultData?.data)
            assertTrue(result.resultData?.getBooleanExtra("dial119", false) == true)
        }
    }

    @Test fun closeCancelsCapture() {
        openCamera().use { screen ->
            onView(withText("닫기")).perform(click())
            assertEquals(Activity.RESULT_CANCELED, screen.result.resultCode)
        }
    }

    @Test fun shutterSavesADecodableJpegToTheRequestedLocation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val dir = File(context.filesDir, "captures").apply { mkdirs() }
        val output = File.createTempFile("camera-test-", ".jpg", dir)
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", output)
            openCamera(uri).use { screen ->
                val until = SystemClock.elapsedRealtime() + 10000
                var ready = false
                while (!ready && SystemClock.elapsedRealtime() < until) {
                    screen.onActivity { activity ->
                        val views = arrayListOf<View>()
                        activity.window.decorView.findViewsWithText(views, "사진 찍기", View.FIND_VIEWS_WITH_TEXT)
                        ready = views.filterIsInstance<Button>().any { it.isEnabled }
                    }
                    if (!ready) SystemClock.sleep(100)
                }
                assertTrue("카메라 미리보기 준비", ready)
                onView(withText("사진 찍기")).perform(click())
                val finishBy = SystemClock.elapsedRealtime() + 10000
                while (screen.state != Lifecycle.State.DESTROYED && SystemClock.elapsedRealtime() < finishBy) {
                    SystemClock.sleep(100)
                }
                assertEquals(Lifecycle.State.DESTROYED, screen.state)
                val result = screen.result
                assertEquals(Activity.RESULT_OK, result.resultCode)
                assertEquals(uri, result.resultData?.data)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(output.path, bounds)
                assertEquals("image/jpeg", bounds.outMimeType)
                assertTrue(bounds.outWidth > 0 && bounds.outHeight > 0)
            }
        } finally { output.delete() }
    }
}
