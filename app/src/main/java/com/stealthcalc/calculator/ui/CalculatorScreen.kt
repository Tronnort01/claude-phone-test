package com.stealthcalc.calculator.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stealthcalc.calculator.viewmodel.CalcAction
import com.stealthcalc.calculator.viewmodel.CalculatorViewModel
import com.stealthcalc.calculator.viewmodel.SecretCodeResult
import com.stealthcalc.ui.theme.CalcBackground

@Composable
fun CalculatorScreen(
    onSecretCodeResult: (SecretCodeResult) -> Unit,
    onLongPressEquals: (() -> Unit)? = null,
    viewModel: CalculatorViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CalcBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Display takes up remaining space at the top
        CalculatorDisplay(
            displayValue = state.displayValue,
            history = state.history,
            modifier = Modifier.weight(1f)
        )

        // Scientific panel (collapsible)
        AnimatedVisibility(
            visible = state.isScientificMode,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column {
                ScientificPanel(
                    onAction = { action -> viewModel.onAction(action) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // Main keypad
        CalculatorKeypad(
            onAction = { action ->
                val result = viewModel.onAction(action)
                if (result != SecretCodeResult.None) {
                    onSecretCodeResult(result)
                }
            },
            onLongPressEquals = onLongPressEquals,
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}
