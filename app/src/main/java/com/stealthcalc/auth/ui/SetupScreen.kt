package com.stealthcalc.auth.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun SetupScreen(
    suggestedCode: String,
    onSetupComplete: (code: String) -> Unit
) {
    var code by remember { mutableStateOf(suggestedCode) }
    var confirmCode by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var step by remember { mutableStateOf(if (suggestedCode.isNotEmpty()) SetupStep.Confirm else SetupStep.EnterCode) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Secret Code Setup",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = when (step) {
                SetupStep.EnterCode -> "Choose a numeric code that you'll type on the calculator keypad followed by = to unlock the hidden app."
                SetupStep.Confirm -> "Confirm your secret code. You typed \"$code\" on the calculator. Enter it again to confirm."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        when (step) {
            SetupStep.EnterCode -> {
                OutlinedTextField(
                    value = code,
                    onValueChange = { newValue ->
                        if (newValue.all { it.isDigit() }) {
                            code = newValue
                            error = null
                        }
                    },
                    label = { Text("Secret Code") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        when {
                            code.length < 4 -> error = "Code must be at least 4 digits"
                            code.length > 16 -> error = "Code must be 16 digits or fewer"
                            else -> step = SetupStep.Confirm
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Next")
                }
            }
            SetupStep.Confirm -> {
                OutlinedTextField(
                    value = confirmCode,
                    onValueChange = { newValue ->
                        if (newValue.all { it.isDigit() }) {
                            confirmCode = newValue
                            error = null
                        }
                    },
                    label = { Text("Confirm Code") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        if (confirmCode == code) {
                            onSetupComplete(code)
                        } else {
                            error = "Codes don't match"
                            confirmCode = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Confirm & Activate")
                }
            }
        }
    }
}

private enum class SetupStep { EnterCode, Confirm }
