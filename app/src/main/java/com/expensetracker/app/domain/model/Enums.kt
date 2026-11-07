package com.expensetracker.app.domain.model

/** Which way money moved relative to the user's own account. */
enum class Direction { DEBIT, CREDIT }

/**
 * Accounting type. Only EXPENSE counts as spending and only INCOME counts as income;
 * everything else is tracked but excluded so totals follow the accuracy rules
 * (self transfers, card bill payments, cash withdrawals, investments, loans).
 */
enum class TxnType(val label: String) {
    EXPENSE("Expense"),
    INCOME("Income"),
    TRANSFER("Self transfer"),
    REFUND("Refund"),
    CREDIT_CARD_PAYMENT("Card bill payment"),
    INVESTMENT("Investment"),
    CASH_WITHDRAWAL("Cash withdrawal"),
    LOAN("Loan proceeds"),
    REIMBURSEMENT("Reimbursement"),
}

enum class TxnStatus(val label: String) {
    COMPLETED("Completed"),
    PENDING("Pending"),
    FAILED("Failed"),
    DECLINED("Declined"),
    REVERSED("Reversed"),
}

enum class PaymentMethod(val label: String) {
    UPI("UPI"),
    DEBIT_CARD("Debit card"),
    CREDIT_CARD("Credit card"),
    NET_BANKING("Net banking"),
    NEFT("NEFT"),
    IMPS("IMPS"),
    RTGS("RTGS"),
    ATM("ATM"),
    CASH("Cash"),
    WALLET("Wallet"),
    AUTO_DEBIT("Auto debit"),
    CHEQUE("Cheque"),
    OTHER("Other"),
}

enum class SourceType(val label: String) {
    SMS("SMS"),
    NOTIFICATION("Notification"),
    EMAIL("Email"),
    STATEMENT_PDF("PDF statement"),
    STATEMENT_CSV("CSV statement"),
    STATEMENT_XLSX("Excel statement"),
    STATEMENT_IMAGE("Scanned statement"),
    STATEMENT_HTML("Activity export"),
    RECEIPT_OCR("Receipt scan"),
    MANUAL("Manual"),
    BACKUP("Backup restore");

    val isStatement: Boolean
        get() = this == STATEMENT_PDF || this == STATEMENT_CSV || this == STATEMENT_XLSX || this == STATEMENT_IMAGE || this == STATEMENT_HTML
}

enum class AccountType(val label: String) {
    BANK("Bank account"),
    CREDIT_CARD("Credit card"),
    CASH("Cash"),
    WALLET("Wallet"),
    UPI("UPI instrument"),
    OTHER("Other"),
}

enum class CategoryKind { EXPENSE, INCOME, NEUTRAL }

enum class RuleMatchType(val label: String) {
    MERCHANT_EXACT("Merchant is"),
    CONTAINS("Text contains"),
}

enum class RuleOrigin { USER, LEARNED }

/** What happened to an individual source record (one SMS, one statement row...). */
enum class SourceOutcome {
    CREATED,
    MERGED,
    POSSIBLE_DUPLICATE,
    UNMERGED,
    UNPARSED,
    DELETED_BY_USER,
}

enum class RecurringFrequency(val label: String, val days: Int) {
    WEEKLY("Weekly", 7),
    MONTHLY("Monthly", 30),
    QUARTERLY("Quarterly", 91),
    YEARLY("Yearly", 365),
}

enum class RecurringStatus { SUGGESTED, CONFIRMED, DISMISSED }

enum class ImportKind(val label: String) {
    SMS_SCAN("SMS scan"),
    EMAIL_SYNC("Email sync"),
    EML_FILE("Email file"),
    STATEMENT_PDF("PDF statement"),
    STATEMENT_CSV("CSV statement"),
    STATEMENT_XLSX("Excel statement"),
    STATEMENT_IMAGE("Scanned statement"),
    GPAY_ACTIVITY("Google Pay activity"),
    BACKUP_RESTORE("Backup restore"),
}

enum class ImportStatus(val label: String) {
    RUNNING("Running"),
    SUCCESS("Success"),
    PARTIAL("Partial"),
    NO_TRANSACTIONS("No transactions found"),
    FAILED("Failed"),
}
