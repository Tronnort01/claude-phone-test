package com.stealthagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CalculatorScreen(onSecretCode: (String) -> Unit) {
    var display by remember { mutableStateOf("0") }
    var currentInput by remember { mutableStateOf("") }
    var operator by remember { mutableStateOf("") }
    var firstOperand by remember { mutableStateOf(0.0) }

    val bgColor = Color(0xFF1C1C1E)
    val btnColor = Color(0xFF2C2C2E)
    val opColor = Color(0xFFFF9500)
    val textColor = Color.White

    Column(
        modifier = Modifier.fillMaxSize().background(bgColor).padding(8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp),
            contentAlignment = Alignment.BottomEnd,
        ) {
            Text(
                display,
                color = textColor,
                fontSize = 48.sp,
                maxLines = 2,
                textAlign = TextAlign.End,
            )
        }

        val buttons = listOf(
            listOf("C", "±", "%", "÷"),
            listOf("7", "8", "9", "×"),
            listOf("4", "5", "6", "−"),
            listOf("1", "2", "3", "+"),
            listOf("0", ".", "⌫", "="),
        )

        buttons.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                row.forEach { btn ->
                    val isOp = btn in listOf("÷", "×", "−", "+", "=")
                    val color = when {
                        isOp -> opColor
                        btn in listOf("C", "±", "%") -> Color(0xFF636366)
                        else -> btnColor
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(color)
                            .clickable {
                                when (btn) {
                                    "C" -> { display = "0"; currentInput = ""; operator = ""; firstOperand = 0.0 }
                                    "⌫" -> {
                                        currentInput = currentInput.dropLast(1)
                                        display = currentInput.ifEmpty { "0" }
                                    }
                                    "±" -> {
                                        if (currentInput.startsWith("-")) currentInput = currentInput.drop(1)
                                        else if (currentInput.isNotEmpty()) currentInput = "-$currentInput"
                                        display = currentInput.ifEmpty { "0" }
                                    }
                                    "%" -> {
                                        val v = currentInput.toDoubleOrNull() ?: 0.0
                                        val result = v / 100
                                        currentInput = formatResult(result)
                                        display = currentInput
                                    }
                                    "÷", "×", "−", "+" -> {
                                        firstOperand = currentInput.toDoubleOrNull() ?: 0.0
                                        operator = btn
                                        currentInput = ""
                                    }
                                    "=" -> {
                                        if (currentInput.length >= 4 && currentInput.all { it.isDigit() }) {
                                            onSecretCode(currentInput)
                                        }
                                        val second = currentInput.toDoubleOrNull() ?: 0.0
                                        val result = when (operator) {
                                            "+" -> firstOperand + second
                                            "−" -> firstOperand - second
                                            "×" -> firstOperand * second
                                            "÷" -> if (second != 0.0) firstOperand / second else Double.NaN
                                            else -> second
                                        }
                                        display = formatResult(result)
                                        currentInput = display
                                        operator = ""
                                    }
                                    "." -> {
                                        if ("." !in currentInput) {
                                            currentInput += "."
                                            display = currentInput
                                        }
                                    }
                                    else -> {
                                        if (currentInput == "0") currentInput = btn
                                        else currentInput += btn
                                        display = currentInput
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(btn, color = textColor, fontSize = 24.sp)
                    }
                }
            }
        }
    }
}

private fun formatResult(d: Double): String {
    if (d.isNaN() || d.isInfinite()) return "Error"
    return if (d == d.toLong().toDouble()) d.toLong().toString() else "%.6f".format(d).trimEnd('0').trimEnd('.')
}
