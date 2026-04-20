package com.stealthcalc.calculator.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stealthcalc.calculator.viewmodel.CalcAction
import com.stealthcalc.ui.theme.CalcButtonFunc
import com.stealthcalc.ui.theme.CalcButtonNumber
import com.stealthcalc.ui.theme.CalcButtonOp
import com.stealthcalc.ui.theme.CalcButtonStyle
import com.stealthcalc.ui.theme.CalcTextPrimary

enum class ButtonType { Number, Operator, Function }

data class CalcButton(
    val label: String,
    val type: ButtonType,
    val action: CalcAction,
    val widthWeight: Float = 1f,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CalculatorKeypad(
    onAction: (CalcAction) -> Unit,
    onLongPressEquals: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    val rows = listOf(
        listOf(
            CalcButton("AC", ButtonType.Function, CalcAction.Clear),
            CalcButton("±", ButtonType.Function, CalcAction.Negate),
            CalcButton("%", ButtonType.Function, CalcAction.Percent),
            CalcButton("÷", ButtonType.Operator, CalcAction.Operator("÷")),
        ),
        listOf(
            CalcButton("7", ButtonType.Number, CalcAction.Digit("7")),
            CalcButton("8", ButtonType.Number, CalcAction.Digit("8")),
            CalcButton("9", ButtonType.Number, CalcAction.Digit("9")),
            CalcButton("×", ButtonType.Operator, CalcAction.Operator("×")),
        ),
        listOf(
            CalcButton("4", ButtonType.Number, CalcAction.Digit("4")),
            CalcButton("5", ButtonType.Number, CalcAction.Digit("5")),
            CalcButton("6", ButtonType.Number, CalcAction.Digit("6")),
            CalcButton("-", ButtonType.Operator, CalcAction.Operator("-")),
        ),
        listOf(
            CalcButton("1", ButtonType.Number, CalcAction.Digit("1")),
            CalcButton("2", ButtonType.Number, CalcAction.Digit("2")),
            CalcButton("3", ButtonType.Number, CalcAction.Digit("3")),
            CalcButton("+", ButtonType.Operator, CalcAction.Operator("+")),
        ),
        listOf(
            CalcButton("0", ButtonType.Number, CalcAction.Digit("0"), widthWeight = 2f),
            CalcButton(".", ButtonType.Number, CalcAction.Decimal),
            CalcButton("=", ButtonType.Operator, CalcAction.Equals),
        ),
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (row in rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (button in row) {
                    CalcKeyButton(
                        button = button,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onAction(button.action)
                        },
                        onLongClick = if (button.action == CalcAction.Equals) onLongPressEquals else null,
                        modifier = Modifier.weight(button.widthWeight)
                    )
                }
            }
        }
    }
}

@Composable
fun ScientificPanel(
    onAction: (CalcAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    val rows = listOf(
        listOf(
            CalcButton("(", ButtonType.Function, CalcAction.OpenParen),
            CalcButton(")", ButtonType.Function, CalcAction.CloseParen),
            CalcButton("^", ButtonType.Function, CalcAction.Operator("^")),
            CalcButton("√", ButtonType.Function, CalcAction.Function("sqrt")),
        ),
        listOf(
            CalcButton("sin", ButtonType.Function, CalcAction.Function("sin")),
            CalcButton("cos", ButtonType.Function, CalcAction.Function("cos")),
            CalcButton("tan", ButtonType.Function, CalcAction.Function("tan")),
            CalcButton("π", ButtonType.Function, CalcAction.Digit("π")),
        ),
        listOf(
            CalcButton("ln", ButtonType.Function, CalcAction.Function("ln")),
            CalcButton("log", ButtonType.Function, CalcAction.Function("log")),
            CalcButton("e", ButtonType.Function, CalcAction.Digit("e")),
            CalcButton("!", ButtonType.Function, CalcAction.Function("factorial")),
        ),
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (row in rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (button in row) {
                    CalcKeyButton(
                        button = button,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onAction(button.action)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CalcKeyButton(
    button: CalcButton,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val bgColor = when (button.type) {
        ButtonType.Number -> CalcButtonNumber
        ButtonType.Operator -> CalcButtonOp
        ButtonType.Function -> CalcButtonFunc
    }

    Box(
        modifier = modifier
            .aspectRatio(if (button.widthWeight > 1f) button.widthWeight else 1f)
            .clip(CircleShape)
            .background(bgColor)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = if (button.widthWeight > 1f) Alignment.CenterStart else Alignment.Center
    ) {
        Text(
            text = button.label,
            style = CalcButtonStyle,
            color = CalcTextPrimary,
            textAlign = TextAlign.Center,
            modifier = if (button.widthWeight > 1f) Modifier.padding(start = 32.dp) else Modifier
        )
    }
}
