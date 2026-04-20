package com.stealthcalc.auth

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
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
import com.stealthcalc.core.di.EncryptedPrefs
import com.stealthcalc.core.logging.AppLogger
import com.stealthcalc.vault.data.VaultRepository
import com.stealthcalc.vault.service.FileEncryptionService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IntruderSelfieManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @EncryptedPrefs private val prefs: SharedPreferences,
    private val encryptionService: FileEncryptionService,
    private val vaultRepository: VaultRepository,
) {
    companion object {
        const val KEY_INTRUDER_SELFIE_ENABLED = "intruder_selfie_enabled"
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    val isEnabled: Boolean
        get() = prefs.getBoolean(KEY_INTRUDER_SELFIE_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_INTRUDER_SELFIE_ENABLED, enabled).apply()
    }

    fun maybeCaptureIntruder() {
        if (!isEnabled) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED) return
        scope.launch { captureAndSave() }
    }

    private fun captureAndSave() {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val frontId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        } ?: return

        val thread = HandlerThread("IntruderSelfie").apply { start() }
        val handler = Handler(thread.looper)
        val latch = CountDownLatch(1)
        var capturedBitmap: Bitmap? = null

        val imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 1)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            image.close()
            capturedBitmap = BitmapFactory.decodeStream(ByteArrayInputStream(bytes))
            latch.countDown()
        }, handler)

        val stateCallback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                runCatching {
                    camera.createCaptureSession(
                        listOf(imageReader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                    addTarget(imageReader.surface)
                                }.build()
                                session.capture(request, null, handler)
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) { latch.countDown() }
                        },
                        handler
                    )
                }.onFailure { latch.countDown() }
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close(); latch.countDown() }
            override fun onError(camera: CameraDevice, error: Int) { camera.close(); latch.countDown() }
        }

        runCatching {
            cameraManager.openCamera(frontId, stateCallback, handler)
            latch.await(8, TimeUnit.SECONDS)

            val bitmap = capturedBitmap ?: return@runCatching
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val vaultFile = encryptionService.encryptBitmap(bitmap, "Intruder_$ts.jpg")
            vaultRepository.saveFile(vaultFile)
            AppLogger.log(context, "[auth]", "Intruder selfie saved: ${vaultFile.id}")
        }.onFailure { e ->
            AppLogger.log(context, "[auth]", "Intruder selfie failed: ${e.message}")
        }

        runCatching { imageReader.close() }
        thread.quitSafely()
    }
}
