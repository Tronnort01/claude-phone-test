package com.stealthcalc.recorder.ui

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

private fun formatTime(): String {
    return SimpleDateFormat("h:mm", Locale.getDefault()).format(Date())
}

private fun formatDate(): String {
    return SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
}
