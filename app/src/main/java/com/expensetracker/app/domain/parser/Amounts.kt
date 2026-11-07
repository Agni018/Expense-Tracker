package com.expensetracker.app.domain.parser

import com.expensetracker.app.domain.model.Direction
import java.math.RoundingMode

object Amounts {

    /** "1,25,000.50" -> 12500050 paise. */
    fun toMinor(numeric: String): Long? {
        val cleaned = numeric.replace(",", "").replace(" ", "").trim().trimEnd('.')
        if (cleaned.isEmpty() || cleaned.count { it == '.' } > 1) return null
        val value = cleaned.toBigDecimalOrNull() ?: return null
        if (value.signum() < 0) return null
        return try {
            value.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
        } catch (e: ArithmeticException) {
            null
        }
    }

    data class SignedAmount(val minor: Long, val suffixDirection: Direction?, val negative: Boolean)

    private val suffixRe = Regex("(?i)(?<![a-z])\\(?(cr|dr|credit|debit)\\.?\\)?\\s*$")
    private val prefixRe = Regex("(?i)^\\s*(cr|dr)\\.?\\s+")
    private val currencyRe = Regex("(?i)(rs\\.?|inr|₹)")
    private val numericRe = Regex("^[0-9][0-9,]*(\\.[0-9]+)?$")

    /** Parses a statement cell such as "1,250.00 Dr", "(450.00)", "-450", "₹ 99". */
    fun parseStatementAmount(raw: String): SignedAmount? {
        var s = raw.replace(' ', ' ').trim()
        if (s.isEmpty()) return null
        var dir: Direction? = null
        suffixRe.find(s)?.let { m ->
            dir = if (m.groupValues[1].lowercase().startsWith("c")) Direction.CREDIT else Direction.DEBIT
            s = s.substring(0, m.range.first).trim()
        }
        prefixRe.find(s)?.let { m ->
            dir = if (m.groupValues[1].lowercase().startsWith("c")) Direction.CREDIT else Direction.DEBIT
            s = s.substring(m.range.last + 1).trim()
        }
        s = currencyRe.replace(s, "").trim()
        var negative = false
        if (s.startsWith("(") && s.endsWith(")")) {
            negative = true
            s = s.substring(1, s.length - 1).trim()
        }
        if (s.startsWith("-")) {
            negative = true
            s = s.substring(1).trim()
        } else if (s.endsWith("-")) {
            negative = true
            s = s.dropLast(1).trim()
        }
        if (s.startsWith("+")) s = s.substring(1).trim()
        if (!numericRe.matches(s)) return null
        val minor = toMinor(s) ?: return null
        return SignedAmount(minor, dir, negative)
    }
}
