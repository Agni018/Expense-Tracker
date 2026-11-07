package com.expensetracker.app.domain.parser

import com.expensetracker.app.domain.model.Direction
import com.expensetracker.app.domain.model.PaymentMethod
import com.expensetracker.app.domain.model.TxnStatus
import com.expensetracker.app.domain.model.TxnType

/** Source-independent result of reading one financial message, row or receipt. */
data class ParsedTransaction(
    val amountMinor: Long,
    val direction: Direction,
    val type: TxnType,
    val status: TxnStatus,
    val timestamp: Long,
    val merchant: String? = null,
    val merchantRaw: String? = null,
    val counterpartyIsPerson: Boolean = false,
    val accountLast4: String? = null,
    val institution: String? = null,
    val isCreditCard: Boolean = false,
    val paymentMethod: PaymentMethod = PaymentMethod.OTHER,
    val reference: String? = null,
    val upiId: String? = null,
    val balanceMinor: Long? = null,
    val counterpartyAccountLast4: String? = null,
    val categoryHint: String? = null,
    val description: String? = null,
    val orderId: String? = null,
    val confidence: Float,
    val parser: String,
    /** Human readable notes on why fields were chosen; shown on the source view. */
    val notes: List<String> = emptyList(),
)

enum class IgnoreReason(val label: String) {
    NOT_FINANCIAL("Not a transaction message"),
    OTP("One-time password"),
    PROMOTIONAL("Promotional message"),
    MANDATE_OR_UPCOMING("Mandate or upcoming payment notice"),
    PAYMENT_REQUEST("Payment request, not a payment"),
    PERSONAL_SENDER("Sent from a personal number"),
    BILL_OR_STATEMENT("Bill or statement summary"),
    NO_AMOUNT("No amount found"),
}

sealed interface MessageParseResult {
    data class Transaction(val txn: ParsedTransaction) : MessageParseResult
    data class BalanceAlert(val accountLast4: String?, val institution: String?, val balanceMinor: Long) : MessageParseResult
    data class Ignored(val reason: IgnoreReason, val detail: String = reason.label) : MessageParseResult
    /** Looks financial but no reliable transaction could be extracted; kept for manual review. */
    data class Unparsed(val reason: String, val amountMinor: Long?) : MessageParseResult
}
