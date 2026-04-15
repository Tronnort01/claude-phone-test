package com.stealthcalc.recorder.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.stealthcalc.core.logging.AppLogger
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Round 4 Feature B: SYSTEM_ALERT_WINDOW overlay lock.
 *
 * Renders a fullscreen fake-lock-screen using the WindowManager overlay
 * layer (TYPE_APPLICATION_OVERLAY). Unlike the in-Activity FakeLockScreen
 * composable, this overlay remains visible even after the user home-
 * swipes out of the calculator — the OS may return the home launcher
 * to the foreground, but our overlay sits on top of it. The user's only
 * way out is entering the correct PIN.
 *
 * Intentionally uses plain Android Views (not Compose). ComposeView
 * inside a Service requires ViewTreeLifecycleOwner /
 * ViewTreeViewModelStoreOwner / ViewTreeSavedStateRegistryOwner to be
 * set on the hosting view, which is fragile and adds a lot of wiring.
 * A LinearLayout + GridLayout of Buttons is 100 lines of code and zero
 * lifecycle complexity.
 *
 * Known limits:
 *  - On Android 10+, the system gesture bars take touch priority over
 *    overlays — the user CAN still home-swipe, but the overlay stays
 *    visible on top of whatever app/screen they land on. Functionally
 *    this is the stealth win: their "lock screen" follows them around.
 *  - Starting from Android 12, background apps can't show toast-style
 *    overlays. Calling startService from a foreground-service-running
 *    process (which we always are during a recording) is fine.
 *  - TYPE_APPLICATION_OVERLAY requires SYSTEM_ALERT_WINDOW. The caller
 *    MUST check Settings.canDrawOverlays before starting this service;
 *    we defensively double-check in onStartCommand and stopSelf on
 *    failure so we don't silently consume resources.
 */
@AndroidEntryPoint
class OverlayLockService : Service() {

    companion object {
        const val ACTION_SHOW = "com.stealthcalc.OVERLAY_SHOW"
        const val ACTION_DISMISS = "com.stealthcalc.OVERLAY_DISMISS"
    }

    @Inject lateinit var bus: OverlayLockBus

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private var clockUpdater: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                if (overlayView != null) return START_NOT_STICKY
                if (!canShowOverlay()) {
                    AppLogger.log(applicationContext, "overlay", "SYSTEM_ALERT_WINDOW not granted; dropping show request")
                    stopSelf()
                    return START_NOT_STICKY
                }
                showOverlay()
            }
            ACTION_DISMISS -> dismissOverlay()
            else -> { /* ignore */ }
        }
        return START_NOT_STICKY
    }

    private fun canShowOverlay(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(this)
    }

    private fun showOverlay() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: run {
            stopSelf()
            return
        }
        windowManager = wm

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            // Focus + touchable so the PIN keypad works; KEEP_SCREEN_ON
            // so a long wrong-PIN interval doesn't let the device
            // auto-sleep; FULLSCREEN + LAYOUT_IN_SCREEN + LAYOUT_NO_LIMITS
            // so we're truly edge-to-edge over status bar + nav bar.
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val view = buildOverlayView()
        overlayView = view

        runCatching {
            wm.addView(view, params)
            bus.markShown()
        }.onFailure { e ->
            AppLogger.log(
                applicationContext,
                "overlay",
                "addView failed: ${e.javaClass.simpleName}: ${e.message}"
            )
            overlayView = null
            stopSelf()
        }
    }

    private fun dismissOverlay() {
        clockUpdater?.let { uiHandler.removeCallbacks(it) }
        clockUpdater = null
        val view = overlayView
        overlayView = null
        runCatching { view?.let { windowManager?.removeView(it) } }
        bus.markDismissed()
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        clockUpdater?.let { uiHandler.removeCallbacks(it) }
        clockUpdater = null
        val view = overlayView
        overlayView = null
        runCatching { view?.let { windowManager?.removeView(it) } }
        bus.markDismissed()
    }

    // --- UI ---

    private fun buildOverlayView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER_HORIZONTAL
            // Intercept all touches so nothing reaches the app/launcher
            // underneath the overlay. Returning true here plus having
            // FLAG_FOCUSABLE absent means we're still a touch-consuming
            // opaque layer, but keypad descendants get their own clicks.
            setOnTouchListener { _, _ -> false }
        }

        val clock = TextView(this).apply {
            text = formatTime()
            setTextColor(Color.WHITE)
            textSize = 56f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(80), 0, 0)
        }
        root.addView(clock)

        val dateText = TextView(this).apply {
            text = formatDate()
            setTextColor(Color.WHITE)
            alpha = 0.7f
            textSize = 14f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        root.addView(dateText)

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        }
        root.addView(spacer)

        val pinDisplay = TextView(this).apply {
            text = ""
            setTextColor(Color.WHITE)
            textSize = 28f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(16), 0, dp(8))
        }
        root.addView(pinDisplay)

        val statusText = TextView(this).apply {
            text = "Enter PIN"
            setTextColor(Color.WHITE)
            alpha = 0.5f
            textSize = 14f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(24))
        }
        root.addView(statusText)

        val keypad = buildKeypad(onDigit = { digit ->
            val entered = pinDisplay.text.toString() + digit
            if (entered.length > 16) return@buildKeypad
            pinDisplay.text = "•".repeat(entered.length)
            pinDisplay.tag = entered
            val target = bus.secretPin.value
            if (target.isNotEmpty() && entered.length >= target.length) {
                if (entered == target) {
                    // Correct PIN — start an Activity-less dismiss.
                    applicationContext.startService(
                        Intent(applicationContext, OverlayLockService::class.java).setAction(ACTION_DISMISS)
                    )
                } else {
                    statusText.text = "Wrong PIN"
                    statusText.setTextColor(Color.RED)
                    uiHandler.postDelayed({
                        pinDisplay.text = ""
                        pinDisplay.tag = ""
                        statusText.text = "Enter PIN"
                        statusText.setTextColor(Color.WHITE)
                        statusText.alpha = 0.5f
                    }, 1200)
                }
            }
        }, onBackspace = {
            val current = (pinDisplay.tag as? String).orEmpty()
            val trimmed = current.dropLast(1)
            pinDisplay.text = "•".repeat(trimmed.length)
            pinDisplay.tag = trimmed
        })
        root.addView(keypad)

        val bottomSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        }
        root.addView(bottomSpacer)

        clockUpdater = object : Runnable {
            override fun run() {
                clock.text = formatTime()
                dateText.text = formatDate()
                uiHandler.postDelayed(this, 1000L)
            }
        }.also { uiHandler.postDelayed(it, 1000L) }

        return root
    }

    private fun buildKeypad(onDigit: (String) -> Unit, onBackspace: () -> Unit): View {
        val grid = GridLayout(this).apply {
            rowCount = 4
            columnCount = 3
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }

        val keys = arrayOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫")
        for (key in keys) {
            val btn = Button(this).apply {
                text = key
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#22FFFFFF"))
                textSize = 20f
                minHeight = dp(72)
                minWidth = dp(72)
                val lp = GridLayout.LayoutParams().apply {
                    width = dp(72)
                    height = dp(72)
                    setMargins(dp(8), dp(8), dp(8), dp(8))
                }
                layoutParams = lp
                isAllCaps = false
                when (key) {
                    "" -> { isEnabled = false; alpha = 0f }
                    "⌫" -> setOnClickListener { onBackspace() }
                    else -> setOnClickListener { onDigit(key) }
                }
            }
            grid.addView(btn)
        }
        return grid
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun formatTime(): String =
        SimpleDateFormat("h:mm", Locale.getDefault()).format(Date())

    private fun formatDate(): String =
        SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
}
