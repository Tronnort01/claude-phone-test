package com.stealthcalc.recorder.ui

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fake phone lock screen shown while recording is active.
 * Mimics a real Android lock screen with clock, date, and PIN pad.
 *
 * - Entering the SECRET PIN unlocks back to the recorder control screen.
 * - Any WRONG PIN shows a brief shake + "Wrong PIN" then resets — the
 *   screen stays "locked" indefinitely. To an observer, the phone is
 *   just sitting on its lock screen.
 * - The secret PIN is the same one used to unlock the stealth app from
 *   the calculator.
 */
@Composable
fun FakeLockScreen(
    secretPin: String,
    onUnlock: () -> Unit
) {
    // Swallow the system Back press — the only way out of the fake lock
    // screen is to enter the correct PIN. Without this, pressing Back
    // would dismiss the composable and expose the real recorder UI,
    // defeating the cover.
    BackHandler(enabled = true) { /* intentionally no-op */ }

    // Keep the device screen on while the fake lock is shown so the
    // display doesn't auto-sleep and interrupt the underlying recording.
    // Also hide the status and navigation bars (immersive sticky) so the
    // Pixel home-swipe indicator is gone and there's no visual affordance
    // to leave the fake lock.
    //
    // We intentionally do NOT call Activity.startLockTask() here: on
    // devices where the user has enabled "App pinning" in Settings →
    // Security the call triggers a system toast ("Screen pinned — touch
    // and hold Back and Overview to unpin") every time recording starts.
    // That toast blows the cover. Immersive sticky + BackHandler are the
    // full suite now; home-gesture dismiss is accepted as a trade-off.
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val insetsController = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        insetsController?.apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    var enteredPin by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    var currentTime by remember { mutableStateOf(formatTime()) }
    var currentDate by remember { mutableStateOf(formatDate()) }

    val shakeOffset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // Update clock every second
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = formatTime()
            currentDate = formatDate()
            delay(1000)
        }
    }

    // Auto-clear error after showing
    LaunchedEffect(showError) {
        if (showError) {
            delay(1500)
            showError = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 80.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Clock
            Text(
                text = currentTime,
                color = Color.White,
                fontSize = 72.sp,
                fontWeight = FontWeight.Thin,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center,
            )

            Text(
                text = currentDate,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.weight(1f))

            // Lock icon
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // PIN dots
            Row(
                modifier = Modifier
                    .offset { IntOffset(shakeOffset.value.toInt(), 0) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Show up to 8 dot positions
                val maxDots = maxOf(secretPin.length, 4)
                for (i in 0 until maxDots) {
                    val filled = i < enteredPin.length
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(
                                if (showError) Color(0xFFEF5350)
                                else if (filled) Color.White
                                else Color.White.copy(alpha = 0.25f)
                            )
                    )
                }
            }

            // Error text
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = if (showError) "Wrong PIN" else "Enter PIN",
                color = if (showError) Color(0xFFEF5350) else Color.White.copy(alpha = 0.5f),
                fontSize = 14.sp,
            )

            Spacer(modifier = Modifier.height(32.dp))

            // PIN keypad
            PinKeypad(
                onDigit = { digit ->
                    if (enteredPin.length < 16 && !showError) {
                        enteredPin += digit
                        // Check PIN when length matches secret
                        if (enteredPin.length >= secretPin.length) {
                            if (enteredPin == secretPin) {
                                onUnlock()
                            } else {
                                // Wrong PIN — shake and reset
                                showError = true
                                scope.launch {
                                    for (i in 0..5) {
                                        shakeOffset.animateTo(
                                            if (i % 2 == 0) 16f else -16f,
                                            animationSpec = tween(50)
                                        )
                                    }
                                    shakeOffset.animateTo(0f, animationSpec = tween(50))
                                    delay(800)
                                    enteredPin = ""
                                }
                            }
                        }
                    }
                },
                onBackspace = {
                    if (enteredPin.isNotEmpty() && !showError) {
                        enteredPin = enteredPin.dropLast(1)
                    }
                },
                modifier = Modifier.padding(bottom = 48.dp)
            )
        }
    }
}

@Composable
private fun PinKeypad(
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", "⌫"),
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        for (row in rows) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (key in row) {
                    if (key.isEmpty()) {
                        // Empty spacer
                        Box(modifier = Modifier.size(72.dp))
                    } else if (key == "⌫") {
                        // Backspace
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                    onClick = onBackspace
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Backspace,
                                contentDescription = "Delete",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    } else {
                        // Digit button
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.08f))
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                    onClick = { onDigit(key) }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = key,
                                color = Color.White,
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Light,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Pure-black screen cover for stealth recording. Tap anywhere to briefly
 * reveal a numeric PIN pad; entering the correct PIN returns to the recorder.
 * After 3 seconds of inactivity the pad fades back to black.
 */
@Composable
fun BlackScreenLock(
    secretPin: String,
    onUnlock: () -> Unit,
) {
    BackHandler(enabled = true) { /* intentionally no-op */ }

    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val insetsController = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        insetsController?.apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    var showPad by remember { mutableStateOf(false) }
    var enteredPin by remember { mutableStateOf("") }
    var showWrong by remember { mutableStateOf(false) }
    val padAlpha = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // Auto-hide pad after 3 seconds of no input
    LaunchedEffect(showPad) {
        if (showPad) {
            padAlpha.animateTo(1f, tween(300))
            kotlinx.coroutines.delay(3000)
            padAlpha.animateTo(0f, tween(500))
            showPad = false
            enteredPin = ""
        }
    }

    Box(
        modifier = androidx.compose.ui.Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                if (!showPad) { showPad = true }
            },
        contentAlignment = Alignment.Center
    ) {
        if (showPad || padAlpha.value > 0f) {
            Column(
                modifier = androidx.compose.ui.Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = padAlpha.value)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // PIN dots
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    repeat(6) { i ->
                        Box(
                            modifier = androidx.compose.ui.Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(if (i < enteredPin.length) Color.White else Color.Gray.copy(alpha = 0.4f))
                        )
                    }
                }
                Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))
                if (showWrong) {
                    Text("Wrong PIN", color = androidx.compose.material3.MaterialTheme.colorScheme.error, fontSize = 14.sp)
                }
                Spacer(modifier = androidx.compose.ui.Modifier.height(24.dp))
                // Numpad
                val keys = listOf(
                    listOf("1","2","3"),
                    listOf("4","5","6"),
                    listOf("7","8","9"),
                    listOf("","0","⌫"),
                )
                keys.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        row.forEach { key ->
                            Box(
                                modifier = androidx.compose.ui.Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .clickable(enabled = key.isNotEmpty()) {
                                        when (key) {
                                            "⌫" -> { enteredPin = enteredPin.dropLast(1); showWrong = false }
                                            else -> {
                                                enteredPin += key
                                                if (enteredPin == secretPin) {
                                                    onUnlock()
                                                } else if (enteredPin.length >= secretPin.length) {
                                                    showWrong = true
                                                    scope.launch {
                                                        kotlinx.coroutines.delay(1000)
                                                        showWrong = false
                                                        enteredPin = ""
                                                    }
                                                }
                                            }
                                        }
                                        // Reset the 3-second timer on any tap
                                        showPad = false
                                        showPad = true
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(key, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Light)
                            }
                        }
                    }
                    Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))
                }
            }
        }
    }
}

private fun formatTime(): String {
    return SimpleDateFormat("h:mm", Locale.getDefault()).format(Date())
}

private fun formatDate(): String {
    return SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
}
