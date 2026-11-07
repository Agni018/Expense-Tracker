package com.expensetracker.app.domain.statement

import com.expensetracker.app.domain.model.Direction
import com.expensetracker.app.domain.parser.Amounts
import com.expensetracker.app.domain.parser.DateParser
import com.expensetracker.app.domain.parser.TextSignals
import java.time.LocalDate

/**
 * Parses statements from extracted PDF text or OCR lines. Column positions are lost in text
 * extraction, so the debit/credit side is recovered from the running balance where possible.
 */
object TextStatementParser {

    fun parse(lines: List<String>, reference: LocalDate = LocalDate.now()): StatementParseResult {
        val clean = lines.map { it.replace(' ', ' ').replace(Regex("[\\t ]+"), " ").trim() }
        PhonePeStatementParser.tryParse(clean, reference)?.let { return it }
        GooglePayActivityParser.tryParse(clean, reference)?.let { return it }
        return generic(clean, reference)
    }

    private val moneyRe = Regex("(?<![\\w/.:-])(?:₹|rs\\.?|inr)?\\s?((?:\\d{1,3}(?:,\\d{2,3})+|\\d+)\\.\\d{2})(?:\\s?(cr|dr)\\b)?(?![\\d/]|\\.\\d)", RegexOption.IGNORE_CASE)
    private val skipLineRe = Regex(
        "(?i)^(page \\d|statement of|account (no|number|summary)|customer id|ifsc|branch|date\\s+(narration|description|particulars|transaction)|txn date|opening balance|closing balance|total|grand total|\\*+|-+|generated on|this is a computer)",
    )
    private val openingRe = Regex("(?i)(opening|brought forward|b/f)[^\\d]{0,30}((?:\\d{1,3}(?:,\\d{2,3})+|\\d+)\\.\\d{2})")
    private val creditWordsRe = Regex("(?i)\\bby transfer|neft cr|imps cr|/cr/|\\bcredit\\b|refund|reversal|interest|salary|cash dep|payment received|thank you|\\bcr\\b|deposit")
    private val debitWordsRe = Regex("(?i)\\bto transfer|/dr/|\\bdebit\\b|\\bpos\\b|\\batm\\b|\\bwdl\\b|ach d|nach|\\bbil/|\\bdr\\b|purchase|withdrawal")

    private data class Pending(
        val line: Int,
        val date: LocalDate,
        val time: java.time.LocalTime?,
        var description: String,
        val amounts: List<Pair<Long, Direction?>>,
        val raw: String,
    )

    private fun generic(lines: List<String>, reference: LocalDate): StatementParseResult {
        val fullText = lines.joinToString("\n").lowercase()
        val isCc = Regex("credit card").containsMatchIn(fullText) &&
            Regex("minimum (amount )?due|payment due date|total amount due|credit limit").containsMatchIn(fullText)
        val dayFirst = detectDayFirst(lines)
        val opening: Long? = openingRe.find(lines.joinToString(" "))?.groupValues?.get(2)?.let { Amounts.toMinor(it) }

        val pending = ArrayList<Pending>()
        val failed = ArrayList<FailedLine>()
        var skipped = 0
        var current: Pending? = null

        lines.forEachIndexed { i, line ->
            if (line.isEmpty()) return@forEachIndexed
            if (skipLineRe.containsMatchIn(line)) {
                skipped++
                current = null
                return@forEachIndexed
            }
            val found = DateParser.findInText(line, reference, dayFirst, earliest = LocalDate.of(1990, 1, 1), latest = reference.plusDays(31))
            val startsWithDate = found != null && found.range.first <= 2
            val money = moneyRe.findAll(line).toList()
            if (startsWithDate) {
                var rest = line.substring(found!!.range.last + 1)
                // Drop a second (value) date directly after the transaction date.
                DateParser.findInText(rest, reference, dayFirst, earliest = LocalDate.of(1990, 1, 1), latest = reference.plusDays(31))
                    ?.takeIf { it.range.first <= 2 }?.let { rest = rest.substring(it.range.last + 1) }
                val restMoney = moneyRe.findAll(rest).toList()
                val descEnd = restMoney.firstOrNull()?.range?.first ?: rest.length
                val description = rest.substring(0, descEnd)
                    .replace(Regex("^[\\s:,-]*(\\d{1,2}:\\d{2}(:\\d{2})?\\s*(am|pm)?)?", RegexOption.IGNORE_CASE), "")
                    .trim()
                val amounts = restMoney.mapNotNull { m ->
                    Amounts.toMinor(m.groupValues[1])?.let { it to m.groupValues[2].lowercase().takeIf { s -> s.isNotEmpty() }?.let { s -> if (s == "cr") Direction.CREDIT else Direction.DEBIT } }
                }
                current = Pending(i + 1, found.date, found.time, description, amounts, line).also { pending += it }
            } else if (current != null) {
                val c = current!!
                if (money.isNotEmpty() && c.amounts.isEmpty()) {
                    // Amounts printed on the line after the narration.
                    val amounts = money.mapNotNull { m -> Amounts.toMinor(m.groupValues[1])?.let { it to m.groupValues[2].lowercase().takeIf { s -> s.isNotEmpty() }?.let { s -> if (s == "cr") Direction.CREDIT else Direction.DEBIT } } }
                    val replaced = c.copy(description = (c.description + " " + line.substring(0, money.first().range.first)).trim(), amounts = amounts)
                    pending[pending.size - 1] = replaced
                    current = replaced
                } else if (money.isEmpty() && line.length < 120 && c.description.length < 240) {
                    c.description = (c.description + " " + line).trim()
                } else {
                    current = null
                }
            }
        }

        val rows = ArrayList<StatementRow>()
        pending.forEachIndexed { idx, p ->
            if (p.amounts.isEmpty()) {
                failed += FailedLine(p.line, p.raw.take(200), "No amount found on this line")
                return@forEachIndexed
            }
            val hasBalance = !isCc && p.amounts.size >= 2
            val balance = if (hasBalance) p.amounts.last().first else null
            val candidates = if (hasBalance) p.amounts.dropLast(1) else p.amounts
            val txn = candidates.lastOrNull { it.first > 0 } ?: candidates.last()
            var direction = txn.second
            var certain = direction != null
            if (direction == null && balance != null) {
                val prevBalance = pending.getOrNull(idx - 1)?.takeIf { it.amounts.size >= 2 }?.amounts?.last()?.first ?: opening.takeIf { idx == 0 }
                val nextBalance = pending.getOrNull(idx + 1)?.takeIf { it.amounts.size >= 2 }?.amounts?.last()?.first
                direction = when {
                    prevBalance != null && prevBalance - txn.first == balance -> Direction.DEBIT
                    prevBalance != null && prevBalance + txn.first == balance -> Direction.CREDIT
                    // Newest-first statements: the next line is chronologically earlier.
                    nextBalance != null && nextBalance - txn.first == balance -> Direction.DEBIT
                    nextBalance != null && nextBalance + txn.first == balance -> Direction.CREDIT
                    else -> null
                }
                certain = direction != null
            }
            if (direction == null) {
                direction = when {
                    creditWordsRe.containsMatchIn(p.description) && !debitWordsRe.containsMatchIn(p.description) -> Direction.CREDIT
                    debitWordsRe.containsMatchIn(p.description) -> Direction.DEBIT
                    else -> Direction.DEBIT
                }
                certain = isCc && direction == Direction.DEBIT && !creditWordsRe.containsMatchIn(p.description)
            }
            if (txn.first == 0L) {
                skipped++
                return@forEachIndexed
            }
            rows += StatementRow(
                lineNumber = p.line,
                date = p.date,
                time = p.time,
                description = p.description.ifBlank { "Statement entry" },
                amountMinor = txn.first,
                direction = direction,
                directionCertain = certain,
                balanceMinor = balance,
                reference = TextSignals.reference(p.description),
                raw = p.raw.take(400),
            )
        }
        return StatementParseResult(
            rows = rows,
            failed = failed,
            format = if (isCc) "Credit card statement" else "Bank statement",
            isCreditCardStatement = isCc,
            skippedLines = skipped,
        )
    }

    private fun detectDayFirst(lines: List<String>): Boolean {
        var dayFirst = 0
        var monthFirst = 0
        val re = Regex("^(\\d{1,2})[-/.](\\d{1,2})[-/.]\\d{2,4}")
        lines.forEach { line ->
            val m = re.find(line) ?: return@forEach
            if (m.groupValues[1].toInt() > 12) dayFirst++
            if (m.groupValues[2].toInt() > 12) monthFirst++
        }
        return !(monthFirst > 0 && dayFirst == 0)
    }
}

/** PhonePe "Transaction Statement" PDF export. */
object PhonePeStatementParser {
    private val rowRe = Regex("(?i)^([a-z]{3}\\s+\\d{1,2},\\s+\\d{4})\\s+(.+?)\\s+(DEBIT|CREDIT)\\s+(?:₹|rs\\.?|inr)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*$")
    private val timeRe = Regex("(?i)^(\\d{1,2}:\\d{2}\\s*[ap]m)")
    private val utrRe = Regex("(?i)utr\\s*no\\.?\\s*:?\\s*(\\w+)")
    private val acctRe = Regex("(?i)(?:paid by|credited to|debited from)\\s+[x*]*(\\d{4})")

    fun tryParse(lines: List<String>, reference: LocalDate): StatementParseResult? {
        val matches = lines.count { rowRe.containsMatchIn(it) }
        if (matches == 0 || lines.none { it.contains("Transaction ID", ignoreCase = true) }) return null
        val rows = ArrayList<StatementRow>()
        lines.forEachIndexed { i, line ->
            val m = rowRe.find(line) ?: return@forEachIndexed
            val date = DateParser.findInText(m.groupValues[1], reference)?.date ?: return@forEachIndexed
            val amount = Amounts.toMinor(m.groupValues[4]) ?: return@forEachIndexed
            val following = lines.subList(i + 1, minOf(lines.size, i + 6)).takeWhile { !rowRe.containsMatchIn(it) }
            val time = following.firstNotNullOfOrNull { timeRe.find(it) }?.let { DateParser.findTime(it.value) }
            val utr = following.firstNotNullOfOrNull { utrRe.find(it)?.groupValues?.get(1) }
            val account = following.firstNotNullOfOrNull { acctRe.find(it)?.groupValues?.get(1) }
            val description = m.groupValues[2]
            rows += StatementRow(
                lineNumber = i + 1,
                date = date,
                time = time,
                description = "UPI " + description,
                amountMinor = amount,
                direction = if (m.groupValues[3].equals("CREDIT", true)) Direction.CREDIT else Direction.DEBIT,
                directionCertain = true,
                reference = utr,
                accountHint = account,
                raw = line,
            )
        }
        return StatementParseResult(rows = rows, failed = emptyList(), format = "PhonePe statement")
    }
}

/**
 * Google Pay activity from Google Takeout ("My Activity" HTML converted to text), e.g.
 * "Paid ₹450.00 to Swiggy using Bank Account XXXXXX4521" followed by the date and status.
 */
object GooglePayActivityParser {
    private val rowRe = Regex("(?i)^(paid|sent|received)\\s+₹\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)(?:\\s+(?:to|from)\\s+(.+?))?(?:\\s+using\\s+(.+))?$")

    fun tryParse(lines: List<String>, reference: LocalDate): StatementParseResult? {
        val hits = lines.count { rowRe.matches(it) }
        if (hits < 1 || lines.none { it.contains("Google Pay", true) || it.contains("using Bank Account", true) }) return null
        val rows = ArrayList<StatementRow>()
        val failed = ArrayList<FailedLine>()
        lines.forEachIndexed { i, line ->
            val m = rowRe.matchEntire(line) ?: return@forEachIndexed
            val next = lines.subList(i + 1, minOf(lines.size, i + 5))
            val dated = next.firstNotNullOfOrNull { DateParser.findInText(it, reference) }
            if (dated == null) {
                failed += FailedLine(i + 1, line, "No date found after activity entry")
                return@forEachIndexed
            }
            val statusText = next.joinToString(" ").lowercase()
            if (Regex("\\b(failed|cancelled|declined)\\b").containsMatchIn(statusText) && !statusText.contains("completed")) {
                failed += FailedLine(i + 1, line, "Payment not completed")
                return@forEachIndexed
            }
            val amount = Amounts.toMinor(m.groupValues[2]) ?: return@forEachIndexed
            val incoming = m.groupValues[1].equals("received", true)
            val counterparty = m.groupValues[3].trim()
            val instrument = m.groupValues[4]
            rows += StatementRow(
                lineNumber = i + 1,
                date = dated.date,
                time = dated.time,
                description = "UPI " + counterparty.ifBlank { if (incoming) "Received" else "Payment" },
                amountMinor = amount,
                direction = if (incoming) Direction.CREDIT else Direction.DEBIT,
                directionCertain = true,
                accountHint = Regex("(\\d{4})\\s*$").find(instrument)?.groupValues?.get(1),
                raw = line,
            )
        }
        return StatementParseResult(rows = rows, failed = failed, format = "Google Pay activity")
    }
}
