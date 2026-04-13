package com.stealthcalc.recorder.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Fake sign-in screen shown while recording is active.
 * Looks like a generic app login page on a black background.
 * - "Sign In" button triggers a shake animation + "Incorrect password"
 * - Triple-tap top-left corner exits to real recorder UI
 * - All fields are decorative and do nothing real
 */
@Composable
fun FakeSignInScreen(
    onExitToRecorder: () -> Unit
) {
    var fakeEmail by remember { mutableStateOf("") }
    var fakePassword by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    var tapCount by remember { mutableIntStateOf(0) }

    val shakeOffset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // Reset tap count after 2 seconds of no taps
    LaunchedEffect(tapCount) {
        if (tapCount > 0 && tapCount < 3) {
            kotlinx.coroutines.delay(2000)
            tapCount = 0
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
    ) {
        // Hidden exit zone — top-left corner, triple-tap to exit
        Box(
            modifier = Modifier
                .size(80.dp)
                .align(Alignment.TopStart)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {
                    tapCount++
                    if (tapCount >= 3) {
                        tapCount = 0
                        onExitToRecorder()
                    }
                }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp)
                .offset { IntOffset(shakeOffset.value.toInt(), 0) },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Fake app logo
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1A73E8)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Cloud,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Sign in",
                color = Color.White,
                fontSize = 24.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Use your account",
                color = Color(0xFF9AA0A6),
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Fake email field
            OutlinedTextField(
                value = fakeEmail,
                onValueChange = { fakeEmail = it },
                label = { Text("Email or phone", color = Color(0xFF9AA0A6)) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF1A73E8),
                    unfocusedBorderColor = Color(0xFF5F6368),
                    cursorColor = Color(0xFF1A73E8),
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Fake password field
            OutlinedTextField(
                value = fakePassword,
                onValueChange = { fakePassword = it },
                label = { Text("Password", color = Color(0xFF9AA0A6)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF1A73E8),
                    unfocusedBorderColor = Color(0xFF5F6368),
                    cursorColor = Color(0xFF1A73E8),
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // Error message
            if (showError) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Wrong password. Try again or click Forgot password to reset it.",
                    color = Color(0xFFEA4335),
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Forgot password link
            Text(
                text = "Forgot password?",
                color = Color(0xFF1A73E8),
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.Start)
                    .clickable { /* does nothing */ }
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Fake sign-in button
            Button(
                onClick = {
                    showError = true
                    // Shake animation
                    scope.launch {
                        for (i in 0..5) {
                            shakeOffset.animateTo(
                                if (i % 2 == 0) 12f else -12f,
                                animationSpec = tween(50)
                            )
                        }
                        shakeOffset.animateTo(0f, animationSpec = tween(50))
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1A73E8)
                ),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("Sign in", fontSize = 16.sp)
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Create account",
                color = Color(0xFF1A73E8),
                fontSize = 14.sp,
                modifier = Modifier.clickable { /* does nothing */ }
            )
        }
    }
}
