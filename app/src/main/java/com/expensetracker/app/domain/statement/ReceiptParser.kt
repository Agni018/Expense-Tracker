package com.expensetracker.app.domain.statement

import com.expensetracker.app.domain.parser.Amounts
import com.expensetracker.app.domain.parser.DateParser
import com.expensetracker.app.domain.parser.MerchantDirectory
import com.expensetracker.app.domain.parser.MerchantNormalizer
import java.time.LocalDate

/** Extracts merchant, total and date from OCR text of a shop receipt or bill. */
object ReceiptParser {

    data class Receipt(val merchant: String?, val amountMinor: Long?, val date: LocalDate?, val amountLabel: String?)

    private val amountRe = Regex("(?<![\\d.])((?:\\d{1,3}(?:,\\d{2,3})+|\\d+)(?:\\.\\d{1,2})?)(?![\\d%])")
    private val totalLabels = listOf(
        "grand total", "net amount", "net payable", "amount payable", "total payable", "bill amount", "total amount",
        "net total", "amount due", "to pay", "total due", "amount paid", "total",
    )
    private val excludedRe = Regex("(?i)change|tendered|cash received|saved|saving|discount|you save|round ?off|qty|quantity|items?\\b|mrp|rate|gstin|phone|mob|tel|cgst|sgst|igst|tax")
    private val headerJunkRe = Regex("(?i)tax invoice|invoice|receipt|\\bbill\\b|gst|phone|tel|mob|date|cash memo|welcome|thank|order|table|kot|www|http|@|customer|token|counter|cashier")

    fun parse(lines: List<String>, reference: LocalDate = LocalDate.now()): Receipt {
        val clean = lines.map { it.trim() }.filter { it.isNotEmpty() }
        var amount: Long? = null
        var label: String? = null
        loop@ for (wanted in totalLabels) {
            for ((i, line) in clean.withIndex()) {
                val lower = line.lowercase()
                if (!lower.contains(wanted) || (wanted == "total" && Regex("(?i)sub ?total|total (qty|items)").containsMatchIn(lower))) continue
                val onLine = amountsIn(line.substring(lower.indexOf(wanted) + wanted.length))
                val value = onLine.lastOrNull() ?: clean.getOrNull(i + 1)?.let { amountsIn(it).lastOrNull() }
                if (value != null && value > 0) {
                    amount = value
                    label = wanted
                    break@loop
                }
            }
        }
        if (amount == null) {
            amount = clean.filterNot { excludedRe.containsMatchIn(it) }
                .flatMap { line -> amountsIn(line).filter { line.contains('.') } }
                .maxOrNull()
        }
        val known = clean.firstNotNullOfOrNull { MerchantDirectory.match(it) }
        val merchant = known?.name ?: clean.take(6).firstOrNull { line ->
            line.count(Char::isLetter) >= 3 && !headerJunkRe.containsMatchIn(line) && line.count(Char::isDigit) < line.length / 3
        }?.let { MerchantNormalizer.titleCase(MerchantNormalizer.clean(it)) }
        val date = DateParser.findInText(clean.joinToString(" | "), reference)?.date
        return Receipt(merchant, amount, date, label)
    }

    private fun amountsIn(text: String): List<Long> =
        amountRe.findAll(text.replace("₹", " ").replace(Regex("(?i)rs\\.?"), " "))
            .mapNotNull { Amounts.toMinor(it.groupValues[1]) }
            .filter { it in 100L..1_000_000_000L }
            .toList()
}

/** Groups OCR text boxes into visual rows so table columns line up again. */
object OcrLayout {
    data class Box(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val centerY: Int get() = (top + bottom) / 2
        val height: Int get() = bottom - top
    }

    fun toLines(boxes: List<Box>): List<String> {
        if (boxes.isEmpty()) return emptyList()
        val sorted = boxes.sortedBy { it.centerY }
        val rows = ArrayList<MutableList<Box>>()
        for (box in sorted) {
            val row = rows.lastOrNull()
            val reference = row?.let { r -> r.sumOf { it.centerY } / r.size }
            val tolerance = maxOf(6, (row?.maxOf { it.height } ?: box.height) / 2)
            if (row != null && reference != null && kotlin.math.abs(box.centerY - reference) <= tolerance) row += box else rows += mutableListOf(box)
        }
        return rows.map { r -> r.sortedBy { it.left }.joinToString(" ") { it.text } }
    }
}
