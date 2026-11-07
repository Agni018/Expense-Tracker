package com.expensetracker.app.domain.statement

import com.expensetracker.app.domain.model.Direction
import java.time.LocalDate
import java.time.LocalTime

data class StatementRow(
    val lineNumber: Int,
    val date: LocalDate,
    val time: LocalTime? = null,
    val description: String,
    val amountMinor: Long,
    val direction: Direction,
    /** False when the debit/credit side had to be guessed; such rows go to review. */
    val directionCertain: Boolean,
    val balanceMinor: Long? = null,
    val reference: String? = null,
    val accountHint: String? = null,
    val raw: String,
)

data class FailedLine(val lineNumber: Int, val text: String, val reason: String)

/** Column indices into a tabular statement; -1 means the column is not present. */
data class ColumnMapping(
    val headerRow: Int = -1,
    val date: Int = -1,
    val description: Int = -1,
    val debit: Int = -1,
    val credit: Int = -1,
    val amount: Int = -1,
    val drCr: Int = -1,
    val balance: Int = -1,
    val reference: Int = -1,
    val dayFirst: Boolean = true,
) {
    val isValid: Boolean get() = date >= 0 && (debit >= 0 || credit >= 0 || amount >= 0)

    fun with(role: ColumnRole, index: Int): ColumnMapping = when (role) {
        ColumnRole.DATE -> copy(date = index)
        ColumnRole.DESCRIPTION -> copy(description = index)
        ColumnRole.DEBIT -> copy(debit = index)
        ColumnRole.CREDIT -> copy(credit = index)
        ColumnRole.AMOUNT -> copy(amount = index)
        ColumnRole.DR_CR -> copy(drCr = index)
        ColumnRole.BALANCE -> copy(balance = index)
        ColumnRole.REFERENCE -> copy(reference = index)
    }

    fun indexOf(role: ColumnRole): Int = when (role) {
        ColumnRole.DATE -> date
        ColumnRole.DESCRIPTION -> description
        ColumnRole.DEBIT -> debit
        ColumnRole.CREDIT -> credit
        ColumnRole.AMOUNT -> amount
        ColumnRole.DR_CR -> drCr
        ColumnRole.BALANCE -> balance
        ColumnRole.REFERENCE -> reference
    }
}

enum class ColumnRole(val label: String) {
    DATE("Date"),
    DESCRIPTION("Description"),
    DEBIT("Debit / Withdrawal"),
    CREDIT("Credit / Deposit"),
    AMOUNT("Single amount"),
    DR_CR("Dr/Cr indicator"),
    BALANCE("Balance"),
    REFERENCE("Reference no."),
}

data class StatementParseResult(
    val rows: List<StatementRow>,
    val failed: List<FailedLine>,
    val format: String,
    val mapping: ColumnMapping? = null,
    val headers: List<String> = emptyList(),
    val isCreditCardStatement: Boolean = false,
    val skippedLines: Int = 0,
    val warnings: List<String> = emptyList(),
)
