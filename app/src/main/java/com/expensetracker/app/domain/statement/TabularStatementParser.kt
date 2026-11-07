package com.expensetracker.app.domain.statement

import com.expensetracker.app.domain.model.Direction
import com.expensetracker.app.domain.parser.Amounts
import com.expensetracker.app.domain.parser.DateParser
import java.time.LocalDate

/** Parses CSV/XLSX statements: header detection, column mapping and row interpretation. */
object TabularStatementParser {

    private val synonyms: Map<ColumnRole, List<String>> = mapOf(
        ColumnRole.DATE to listOf(
            "date", "txn date", "transaction date", "tran date", "trans date", "posting date", "post date", "txn dt",
            "date time", "transaction date time", "booking date", "value date", "value dt", "date of transaction",
        ),
        ColumnRole.DESCRIPTION to listOf(
            "description", "narration", "particulars", "remarks", "details", "transaction details", "transaction remarks",
            "transaction description", "merchant", "merchant name", "payee", "beneficiary", "info", "notes",
            "transaction particulars", "description narration",
        ),
        ColumnRole.DEBIT to listOf(
            "debit", "withdrawal", "withdrawals", "withdrawal amt", "withdrawal amount", "dr", "debit amount", "debit amt",
            "amount dr", "dr amount", "paid out", "money out", "debits", "withdrawal inr", "debit inr", "withdrawal amt inr",
        ),
        ColumnRole.CREDIT to listOf(
            "credit", "deposit", "deposits", "deposit amt", "deposit amount", "cr", "credit amount", "credit amt", "amount cr",
            "cr amount", "paid in", "money in", "credits", "deposit inr", "credit inr", "deposit amt inr",
        ),
        ColumnRole.AMOUNT to listOf(
            "amount", "transaction amount", "amt", "txn amount", "amount inr", "amount rs", "billing amount",
            "transaction amt", "amount in rs", "amount in inr",
        ),
        ColumnRole.DR_CR to listOf(
            "dr cr", "cr dr", "drcr", "crdr", "type", "transaction type", "debit credit", "txn type", "debit or credit", "dr or cr",
        ),
        ColumnRole.BALANCE to listOf(
            "balance", "closing balance", "running balance", "available balance", "bal", "balance amt", "balance inr",
            "closing bal", "balance amount",
        ),
        ColumnRole.REFERENCE to listOf(
            "ref no", "reference", "reference no", "chq no", "cheque no", "chq ref no", "chq ref number", "ref no cheque no",
            "utr", "utr no", "transaction id", "txn id", "chq no ref no", "ref", "cheque number", "reference number",
        ),
    )

    fun normalizeHeader(cell: String): String =
        cell.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")

    private fun roleOf(header: String): ColumnRole? {
        if (header.isEmpty()) return null
        synonyms.forEach { (role, names) -> if (header in names) return role }
        return when {
            header.contains("withdraw") -> ColumnRole.DEBIT
            header.contains("deposit") -> ColumnRole.CREDIT
            Regex("narration|particular|description|remark|detail").containsMatchIn(header) -> ColumnRole.DESCRIPTION
            header.contains("balance") -> ColumnRole.BALANCE
            header.contains("debit") && !header.contains("credit") -> ColumnRole.DEBIT
            header.contains("credit") && !header.contains("debit") -> ColumnRole.CREDIT
            header.contains("date") -> ColumnRole.DATE
            header.contains("amount") -> ColumnRole.AMOUNT
            Regex("\\bref|chq|cheque|utr").containsMatchIn(header) -> ColumnRole.REFERENCE
            else -> null
        }
    }

    fun detectMapping(rows: List<List<String>>): ColumnMapping {
        var best: ColumnMapping? = null
        var bestScore = 0
        for (r in 0 until minOf(rows.size, 60)) {
            var mapping = ColumnMapping(headerRow = r)
            var score = 0
            rows[r].forEachIndexed { index, cell ->
                val header = normalizeHeader(cell)
                val role = roleOf(header) ?: return@forEachIndexed
                val existing = mapping.indexOf(role)
                // Prefer the transaction date over the value date when both exist.
                if (existing >= 0 && !(role == ColumnRole.DATE && normalizeHeader(rows[r][existing]).startsWith("value"))) return@forEachIndexed
                mapping = mapping.with(role, index)
                score++
            }
            if (mapping.isValid && score > bestScore) {
                best = mapping
                bestScore = score
            }
        }
        val found = best ?: guessWithoutHeader(rows)
        return found.copy(dayFirst = detectDayFirst(rows, found))
    }

    /** Fallback for header-less exports: infer columns from cell contents. */
    private fun guessWithoutHeader(rows: List<List<String>>): ColumnMapping {
        val sample = rows.take(200)
        val width = sample.maxOfOrNull { it.size } ?: return ColumnMapping()
        fun ratio(col: Int, test: (String) -> Boolean): Double {
            val cells = sample.mapNotNull { it.getOrNull(col)?.takeIf { c -> c.isNotBlank() } }
            if (cells.isEmpty()) return 0.0
            return cells.count(test).toDouble() / sample.size
        }
        val dateCol = (0 until width).maxByOrNull { c -> ratio(c) { DateParser.parseDate(it) != null } }?.takeIf { c ->
            ratio(c) { DateParser.parseDate(it) != null } > 0.4
        } ?: return ColumnMapping()
        val numeric = (0 until width).filter { it != dateCol && ratio(it) { cell -> Amounts.parseStatementAmount(cell) != null } > 0.3 }
        val descCol = (0 until width).filter { it != dateCol && it !in numeric }
            .maxByOrNull { c -> sample.sumOf { it.getOrNull(c)?.length ?: 0 } } ?: -1
        return when (numeric.size) {
            0 -> ColumnMapping()
            1 -> ColumnMapping(date = dateCol, description = descCol, amount = numeric[0])
            2 -> ColumnMapping(date = dateCol, description = descCol, amount = numeric[0], balance = numeric[1])
            else -> ColumnMapping(date = dateCol, description = descCol, debit = numeric[numeric.size - 3], credit = numeric[numeric.size - 2], balance = numeric.last())
        }
    }

    private fun detectDayFirst(rows: List<List<String>>, mapping: ColumnMapping): Boolean {
        if (mapping.date < 0) return true
        var dayFirstEvidence = 0
        var monthFirstEvidence = 0
        val re = Regex("^\\s*(\\d{1,2})[-/.](\\d{1,2})[-/.]\\d{2,4}")
        rows.drop(mapping.headerRow + 1).forEach { row ->
            val m = re.find(row.getOrNull(mapping.date) ?: return@forEach) ?: return@forEach
            if (m.groupValues[1].toInt() > 12) dayFirstEvidence++
            if (m.groupValues[2].toInt() > 12) monthFirstEvidence++
        }
        return !(monthFirstEvidence > 0 && dayFirstEvidence == 0)
    }

    private val summaryRe = Regex("(?i)opening balance|closing balance|brought forward|carried forward|\\bb/f\\b|\\bc/f\\b|^total|grand total|statement summary|balance b/f")

    fun parse(rows: List<List<String>>, mappingOverride: ColumnMapping? = null, reference: LocalDate = LocalDate.now()): StatementParseResult {
        val mapping = mappingOverride ?: detectMapping(rows)
        val headers = if (mapping.headerRow >= 0) rows.getOrNull(mapping.headerRow).orEmpty() else emptyList()
        if (!mapping.isValid) {
            return StatementParseResult(
                rows = emptyList(),
                failed = listOf(FailedLine(0, "", "Could not identify date and amount columns – map them manually")),
                format = "Spreadsheet",
                mapping = mapping,
                headers = headers,
            )
        }
        val out = ArrayList<StatementRow>()
        val failed = ArrayList<FailedLine>()
        var skipped = 0
        val anyNegative = rows.drop(mapping.headerRow + 1).any { row ->
            mapping.amount >= 0 && Amounts.parseStatementAmount(row.getOrNull(mapping.amount).orEmpty())?.negative == true
        }

        for (index in (mapping.headerRow + 1) until rows.size) {
            val row = rows[index]
            fun cell(i: Int) = if (i >= 0) row.getOrNull(i)?.trim().orEmpty() else ""
            val joined = row.joinToString(" ").trim()
            if (joined.isEmpty()) continue
            val description = cell(mapping.description)
            val date = DateParser.parseDate(cell(mapping.date), mapping.dayFirst, reference)
            val amount = resolveAmount(row, mapping, anyNegative)

            if (date == null) {
                if (amount == null && description.isNotEmpty() && out.isNotEmpty() && out.last().lineNumber >= index - 3) {
                    // Wrapped narration continues on the next row.
                    val last = out.removeAt(out.size - 1)
                    out += last.copy(description = (last.description + " " + description).trim())
                } else if (amount != null && !summaryRe.containsMatchIn(joined)) {
                    failed += FailedLine(index + 1, joined.take(200), "Missing or unreadable date")
                } else {
                    skipped++
                }
                continue
            }
            if (summaryRe.containsMatchIn(description)) {
                skipped++
                continue
            }
            if (amount == null) {
                if ((cell(mapping.debit) + cell(mapping.credit) + cell(mapping.amount)).any { it.isLetterOrDigit() }) {
                    failed += FailedLine(index + 1, joined.take(200), "Unreadable amount")
                } else {
                    skipped++
                }
                continue
            }
            out += StatementRow(
                lineNumber = index + 1,
                date = date,
                time = DateParser.findTime(cell(mapping.date)),
                description = description.ifEmpty { joined.take(120) },
                amountMinor = amount.first,
                direction = amount.second,
                directionCertain = amount.third,
                balanceMinor = Amounts.parseStatementAmount(cell(mapping.balance))?.minor,
                reference = cell(mapping.reference).takeIf { it.any(Char::isDigit) },
                raw = joined.take(400),
            )
        }
        return StatementParseResult(
            rows = out,
            failed = failed,
            format = "Spreadsheet",
            mapping = mapping,
            headers = headers,
            skippedLines = skipped,
        )
    }

    /** Returns amount, direction and whether the direction is certain. */
    private fun resolveAmount(row: List<String>, mapping: ColumnMapping, anyNegative: Boolean): Triple<Long, Direction, Boolean>? {
        fun cell(i: Int) = if (i >= 0) row.getOrNull(i)?.trim().orEmpty() else ""
        val debit = Amounts.parseStatementAmount(cell(mapping.debit))?.takeIf { it.minor > 0 }
        val credit = Amounts.parseStatementAmount(cell(mapping.credit))?.takeIf { it.minor > 0 }
        if (debit != null && credit == null) return Triple(debit.minor, Direction.DEBIT, true)
        if (credit != null && debit == null) return Triple(credit.minor, Direction.CREDIT, true)
        if (debit != null && credit != null) return null
        val amount = Amounts.parseStatementAmount(cell(mapping.amount))?.takeIf { it.minor > 0 } ?: return null
        val indicator = cell(mapping.drCr).lowercase()
        return when {
            indicator.startsWith("d") || indicator == "-" || indicator.contains("debit") || indicator.contains("withdraw") -> Triple(amount.minor, Direction.DEBIT, true)
            indicator.startsWith("c") || indicator == "+" || indicator.contains("credit") || indicator.contains("deposit") -> Triple(amount.minor, Direction.CREDIT, true)
            amount.suffixDirection != null -> Triple(amount.minor, amount.suffixDirection, true)
            amount.negative -> Triple(amount.minor, Direction.DEBIT, true)
            anyNegative -> Triple(amount.minor, Direction.CREDIT, true)
            else -> Triple(amount.minor, Direction.DEBIT, false)
        }
    }
}
