package com.stealthcalc.calculator.viewmodel

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import com.stealthcalc.auth.IntruderSelfieManager
import com.stealthcalc.auth.SecretCodeManager
import com.stealthcalc.auth.WipeManager
import com.stealthcalc.calculator.engine.CalcEngine
import com.stealthcalc.core.di.EncryptedPrefs
import com.stealthcalc.settings.viewmodel.SettingsViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class CalculatorState(
    val expression: String = "",
    val displayValue: String = "0",
    val history: String = "",
    val isResultShown: Boolean = false,
    val isScientificMode: Boolean = false,
)

sealed class CalcAction {
    data class Digit(val digit: String) : CalcAction()
    data class Operator(val op: String) : CalcAction()
    data class Function(val func: String) : CalcAction()
    data object Equals : CalcAction()
    data object Clear : CalcAction()
    data object ClearEntry : CalcAction()
    data object Backspace : CalcAction()
    data object Decimal : CalcAction()
    data object Negate : CalcAction()
    data object Percent : CalcAction()
    data object ToggleScientific : CalcAction()
    data object OpenParen : CalcAction()
    data object CloseParen : CalcAction()
}

@HiltViewModel
class CalculatorViewModel @Inject constructor(
    private val secretCodeManager: SecretCodeManager,
    private val intruderSelfieManager: IntruderSelfieManager,
    private val wipeManager: WipeManager,
    @EncryptedPrefs private val prefs: SharedPreferences,
) : ViewModel() {

    private val engine = CalcEngine()

    private val _state = MutableStateFlow(CalculatorState())
    val state: StateFlow<CalculatorState> = _state.asStateFlow()

    // Tracks the sequence of digits entered for secret code detection
    private val inputBuffer = StringBuilder()

    fun onAction(action: CalcAction): SecretCodeResult {
        when (action) {
            is CalcAction.Digit -> appendDigit(action.digit)
            is CalcAction.Operator -> appendOperator(action.op)
            is CalcAction.Function -> appendFunction(action.func)
            CalcAction.Equals -> return evaluate()
            CalcAction.Clear -> clear()
            CalcAction.ClearEntry -> clearEntry()
            CalcAction.Backspace -> backspace()
            CalcAction.Decimal -> appendDecimal()
            CalcAction.Negate -> negate()
            CalcAction.Percent -> appendPercent()
            CalcAction.ToggleScientific -> toggleScientific()
            CalcAction.OpenParen -> appendChar("(")
            CalcAction.CloseParen -> appendChar(")")
        }
        return SecretCodeResult.None
    }

    private fun appendDigit(digit: String) {
        inputBuffer.append(digit)

        _state.update { current ->
            if (current.isResultShown) {
                // Start fresh after showing a result
                CalculatorState(
                    expression = digit,
                    displayValue = digit,
                )
            } else {
                val newExpr = current.expression + digit
                current.copy(
                    expression = newExpr,
                    displayValue = extractDisplayNumber(newExpr),
                )
            }
        }
    }

    private fun appendOperator(op: String) {
        inputBuffer.clear()
        _state.update { current ->
            val expr = if (current.isResultShown) {
                current.displayValue + op
            } else {
                current.expression + op
            }
            current.copy(
                expression = expr,
                displayValue = current.displayValue,
                isResultShown = false,
            )
        }
    }

    private fun appendFunction(func: String) {
        inputBuffer.clear()
        _state.update { current ->
            val expr = if (current.isResultShown) {
                "$func("
            } else {
                current.expression + "$func("
            }
            current.copy(
                expression = expr,
                displayValue = "$func(",
                isResultShown = false,
            )
        }
    }

    private fun evaluate(): SecretCodeResult {
        val currentExpr = _state.value.expression
        if (currentExpr.isBlank()) return SecretCodeResult.None

        // Check for secret code: the expression is just digits (the code) and user pressed =
        val codeCandidate = inputBuffer.toString()
        if (codeCandidate.isNotEmpty()) {
            when (val result = secretCodeManager.validateCode(codeCandidate)) {
                is SecretCodeManager.ValidationResult.Valid -> {
                    performCalculation()
                    val code = codeCandidate
                    inputBuffer.clear()
                    return SecretCodeResult.Unlocked(code)
                }
                is SecretCodeManager.ValidationResult.DecoyValid -> {
                    performCalculation()
                    val code = codeCandidate
                    inputBuffer.clear()
                    if (prefs.getBoolean(SettingsViewModel.KEY_DECOY_WIPE_ENABLED, false)) {
                        wipeManager.wipeAll()
                    }
                    return SecretCodeResult.DecoyUnlocked(code)
                }
                is SecretCodeManager.ValidationResult.NotSetup -> {
                    // Defensive guard: if setup is actually complete but the
                    // manager still returned NotSetup (e.g. corrupted prefs),
                    // treat the input as a wrong code instead of re-triggering
                    // the setup flow — which would otherwise grant access.
                    performCalculation()
                    inputBuffer.clear()
                    return if (secretCodeManager.isSetupComplete) {
                        SecretCodeResult.None
                    } else {
                        SecretCodeResult.NeedsSetup(codeCandidate)
                    }
                }
                is SecretCodeManager.ValidationResult.LockedOut -> {
                    // Just show normal calc result, no hint of lockout
                    performCalculation()
                    inputBuffer.clear()
                    return SecretCodeResult.None
                }
                is SecretCodeManager.ValidationResult.Invalid -> {
                    // Wrong code — trigger intruder selfie if enabled
                    intruderSelfieManager.maybeCaptureIntruder()
                    // Auto-wipe if enabled and threshold reached
                    if (wipeManager.isAutoWipeEnabled &&
                        secretCodeManager.getFailedAttempts() >= wipeManager.autoWipeThreshold) {
                        wipeManager.wipeAll()
                    }
                    performCalculation()
                    inputBuffer.clear()
                    return SecretCodeResult.None
                }
            }
        }

        performCalculation()
        inputBuffer.clear()
        return SecretCodeResult.None
    }

    private fun performCalculation() {
        val currentExpr = _state.value.expression
        val result = engine.evaluate(currentExpr)
        result.onSuccess { value ->
            val formatted = engine.formatResult(value)
            _state.update { current ->
                current.copy(
                    displayValue = formatted,
                    history = currentExpr,
                    expression = formatted,
                    isResultShown = true,
                )
            }
        }.onFailure {
            _state.update { current ->
                current.copy(
                    displayValue = "Error",
                    isResultShown = true,
                )
            }
        }
    }

    private fun clear() {
        inputBuffer.clear()
        _state.value = CalculatorState()
    }

    private fun clearEntry() {
        inputBuffer.clear()
        _state.update { it.copy(displayValue = "0", expression = "") }
    }

    private fun backspace() {
        if (inputBuffer.isNotEmpty()) inputBuffer.deleteCharAt(inputBuffer.length - 1)
        _state.update { current ->
            if (current.isResultShown || current.expression.isEmpty()) {
                current
            } else {
                val newExpr = current.expression.dropLast(1)
                current.copy(
                    expression = newExpr,
                    displayValue = if (newExpr.isEmpty()) "0" else extractDisplayNumber(newExpr),
                )
            }
        }
    }

    private fun appendDecimal() {
        _state.update { current ->
            val expr = if (current.isResultShown) "0." else current.expression + "."
            current.copy(
                expression = expr,
                displayValue = extractDisplayNumber(expr),
                isResultShown = false,
            )
        }
    }

    private fun negate() {
        _state.update { current ->
            if (current.displayValue == "0") return@update current
            val negated = if (current.displayValue.startsWith("-")) {
                current.displayValue.drop(1)
            } else {
                "-${current.displayValue}"
            }
            current.copy(
                displayValue = negated,
                expression = negated,
            )
        }
    }

    private fun appendPercent() {
        _state.update { current ->
            current.copy(expression = current.expression + "%")
        }
    }

    private fun appendChar(char: String) {
        _state.update { current ->
            val expr = if (current.isResultShown) char else current.expression + char
            current.copy(expression = expr, isResultShown = false)
        }
    }

    private fun toggleScientific() {
        _state.update { it.copy(isScientificMode = !it.isScientificMode) }
    }

    private fun extractDisplayNumber(expr: String): String {
        // Extract the last number from the expression for display
        val lastNum = StringBuilder()
        for (c in expr.reversed()) {
            if (c.isDigit() || c == '.' || (c == '-' && lastNum.isNotEmpty())) {
                lastNum.append(c)
            } else {
                break
            }
        }
        val result = lastNum.reverse().toString()
        return result.ifEmpty { "0" }
    }
}

sealed class SecretCodeResult {
    data object None : SecretCodeResult()
    data class Unlocked(val enteredCode: String) : SecretCodeResult()
    data class DecoyUnlocked(val enteredCode: String) : SecretCodeResult()
    data class NeedsSetup(val candidateCode: String) : SecretCodeResult()
}
