package com.stealthcalc.calculator.engine

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Fully functional calculator engine that parses and evaluates mathematical expressions.
 * Supports: +, -, *, /, %, parentheses, and scientific functions.
 * Uses recursive descent parsing for correct operator precedence.
 */
class CalcEngine {

    private val mc = MathContext(15, RoundingMode.HALF_UP)

    fun evaluate(expression: String): Result<BigDecimal> {
        return try {
            val tokens = tokenize(expression)
            val parser = Parser(tokens, mc)
            val result = parser.parseExpression()
            if (parser.hasMore()) {
                Result.failure(IllegalArgumentException("Unexpected token: ${parser.peek()}"))
            } else {
                Result.success(result.stripTrailingZeros())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun formatResult(value: BigDecimal): String {
        val plain = value.toPlainString()
        // If the number is too long, use scientific notation
        if (plain.length > 15) {
            return value.round(MathContext(10, RoundingMode.HALF_UP)).toEngineeringString()
        }
        return plain
    }

    private fun tokenize(expr: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        val s = expr.replace(" ", "")

        while (i < s.length) {
            val c = s[i]
            when {
                c.isDigit() || c == '.' -> {
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                    tokens.add(Token.Number(BigDecimal(s.substring(start, i))))
                }
                c == '+' -> { tokens.add(Token.Plus); i++ }
                c == '-' -> {
                    // Unary minus: at start, after operator, or after open paren
                    val prev = tokens.lastOrNull()
                    if (prev == null || prev is Token.Plus || prev is Token.Minus ||
                        prev is Token.Multiply || prev is Token.Divide || prev is Token.OpenParen ||
                        prev is Token.Percent || prev is Token.Power
                    ) {
                        i++
                        // Parse the number following the minus
                        if (i < s.length && (s[i].isDigit() || s[i] == '.')) {
                            val start = i
                            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                            tokens.add(Token.Number(BigDecimal(s.substring(start, i)).negate()))
                        } else {
                            tokens.add(Token.Number(BigDecimal.ZERO))
                            tokens.add(Token.Minus)
                        }
                    } else {
                        tokens.add(Token.Minus); i++
                    }
                }
                c == '×' || c == '*' -> { tokens.add(Token.Multiply); i++ }
                c == '÷' || c == '/' -> { tokens.add(Token.Divide); i++ }
                c == '%' -> { tokens.add(Token.Percent); i++ }
                c == '^' -> { tokens.add(Token.Power); i++ }
                c == '(' -> { tokens.add(Token.OpenParen); i++ }
                c == ')' -> { tokens.add(Token.CloseParen); i++ }
                c == 'π' -> { tokens.add(Token.Number(BigDecimal(Math.PI, mc))); i++ }
                c == 'e' && (i + 1 >= s.length || !s[i + 1].isLetter()) -> {
                    tokens.add(Token.Number(BigDecimal(Math.E, mc))); i++
                }
                c.isLetter() -> {
                    val start = i
                    while (i < s.length && s[i].isLetter()) i++
                    val name = s.substring(start, i)
                    tokens.add(Token.Function(name))
                }
                else -> i++ // Skip unknown characters
            }
        }
        return tokens
    }

    private class Parser(
        private val tokens: List<Token>,
        private val mc: MathContext
    ) {
        private var pos = 0

        fun hasMore() = pos < tokens.size
        fun peek() = if (hasMore()) tokens[pos] else null

        private fun consume(): Token {
            if (!hasMore()) throw IllegalArgumentException("Unexpected end of expression")
            return tokens[pos++]
        }

        fun parseExpression(): BigDecimal = parseAddSub()

        private fun parseAddSub(): BigDecimal {
            var left = parseMulDiv()
            while (hasMore()) {
                when (peek()) {
                    is Token.Plus -> { consume(); left = left.add(parseMulDiv(), mc) }
                    is Token.Minus -> { consume(); left = left.subtract(parseMulDiv(), mc) }
                    else -> break
                }
            }
            return left
        }

        private fun parseMulDiv(): BigDecimal {
            var left = parsePower()
            while (hasMore()) {
                when (peek()) {
                    is Token.Multiply -> { consume(); left = left.multiply(parsePower(), mc) }
                    is Token.Divide -> {
                        consume()
                        val right = parsePower()
                        if (right.compareTo(BigDecimal.ZERO) == 0) {
                            throw ArithmeticException("Division by zero")
                        }
                        left = left.divide(right, mc)
                    }
                    is Token.Percent -> {
                        consume()
                        left = left.divide(BigDecimal(100), mc)
                    }
                    else -> break
                }
            }
            return left
        }

        private fun parsePower(): BigDecimal {
            var base = parseUnary()
            while (peek() is Token.Power) {
                consume()
                val exp = parseUnary()
                base = BigDecimal(
                    Math.pow(base.toDouble(), exp.toDouble()), mc
                )
            }
            return base
        }

        private fun parseUnary(): BigDecimal = parsePrimary()

        private fun parsePrimary(): BigDecimal {
            val token = peek() ?: throw IllegalArgumentException("Unexpected end of expression")

            return when (token) {
                is Token.Number -> {
                    consume()
                    token.value
                }
                is Token.OpenParen -> {
                    consume() // (
                    val value = parseExpression()
                    if (peek() is Token.CloseParen) consume() // )
                    value
                }
                is Token.Function -> {
                    consume()
                    // Expect open paren
                    if (peek() is Token.OpenParen) consume()
                    val arg = parseExpression()
                    if (peek() is Token.CloseParen) consume()
                    evaluateFunction(token.name, arg)
                }
                else -> throw IllegalArgumentException("Unexpected token: $token")
            }
        }

        private fun evaluateFunction(name: String, arg: BigDecimal): BigDecimal {
            val d = arg.toDouble()
            val result = when (name.lowercase()) {
                "sin" -> Math.sin(d)
                "cos" -> Math.cos(d)
                "tan" -> Math.tan(d)
                "asin" -> Math.asin(d)
                "acos" -> Math.acos(d)
                "atan" -> Math.atan(d)
                "ln" -> Math.log(d)
                "log" -> Math.log10(d)
                "sqrt", "√" -> Math.sqrt(d)
                "abs" -> Math.abs(d)
                "ceil" -> Math.ceil(d)
                "floor" -> Math.floor(d)
                "exp" -> Math.exp(d)
                else -> throw IllegalArgumentException("Unknown function: $name")
            }
            if (result.isNaN() || result.isInfinite()) {
                throw ArithmeticException("Math error")
            }
            return BigDecimal(result, mc)
        }
    }
}

sealed class Token {
    data class Number(val value: BigDecimal) : Token()
    data class Function(val name: String) : Token()
    data object Plus : Token()
    data object Minus : Token()
    data object Multiply : Token()
    data object Divide : Token()
    data object Percent : Token()
    data object Power : Token()
    data object OpenParen : Token()
    data object CloseParen : Token()
}
