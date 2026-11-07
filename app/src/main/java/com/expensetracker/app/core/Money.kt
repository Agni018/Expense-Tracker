package com.expensetracker.app.core

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/**
 * All amounts in the app are stored as Long minor units (paise) so arithmetic is exact.
 * Formatting is implemented by hand because java.text on the JVM does not support the
 * Indian 2-digit secondary grouping (1,25,000).
 */
object Money {

    fun format(minor: Long, showPaise: Boolean = true, symbol: Boolean = true): String {
        val negative = minor < 0
        val absMinor = abs(minor)
        val rupees = absMinor / 100
        val paise = (absMinor % 100).toInt()
        val grouped = groupIndian(rupees)
        val body = if (showPaise && paise != 0) "$grouped.${paise.toString().padStart(2, '0')}" else grouped
        return buildString {
            if (negative) append('-')
            if (symbol) append('₹')
            append(body)
        }
    }

    /** Always shows two decimals; used in exports and forms. */
    fun formatPlain(minor: Long): String {
        val negative = minor < 0
        val absMinor = abs(minor)
        val text = "${absMinor / 100}.${(absMinor % 100).toString().padStart(2, '0')}"
        return if (negative) "-$text" else text
    }

    fun groupIndian(value: Long): String {
        val s = value.toString()
        if (s.length <= 3) return s
        val last3 = s.takeLast(3)
        var rest = s.dropLast(3)
        val parts = ArrayList<String>()
        while (rest.length > 2) {
            parts.add(0, rest.takeLast(2))
            rest = rest.dropLast(2)
        }
        if (rest.isNotEmpty()) parts.add(0, rest)
        return parts.joinToString(",") + "," + last3
    }

    /** Short form for chart axes and chips: ₹950, ₹12.5K, ₹3.4L, ₹1.2Cr. */
    fun compact(minor: Long): String {
        val rupees = abs(minor) / 100.0
        val sign = if (minor < 0) "-" else ""
        val text = when {
            rupees >= 1_00_00_000 -> trim(rupees / 1_00_00_000) + "Cr"
            rupees >= 1_00_000 -> trim(rupees / 1_00_000) + "L"
            rupees >= 1_000 -> trim(rupees / 1_000) + "K"
            else -> rupees.toLong().toString()
        }
        return "$sign₹$text"
    }

    private fun trim(v: Double): String {
        val rounded = BigDecimal(v).setScale(1, RoundingMode.HALF_UP)
        return rounded.stripTrailingZeros().toPlainString()
    }

    /** Parses user input such as "1,250.50" or "₹ 450" into paise. */
    fun parseToMinor(text: String): Long? {
        val cleaned = text.replace("₹", "").replace(",", "").replace(" ", "").trim()
        if (cleaned.isEmpty()) return null
        val value = cleaned.toBigDecimalOrNull() ?: return null
        if (value.signum() < 0) return null
        return try {
            value.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
        } catch (e: ArithmeticException) {
            null
        }
    }

    fun percent(part: Long, whole: Long): Double = if (whole == 0L) 0.0 else part.toDouble() * 100.0 / whole.toDouble()
}
