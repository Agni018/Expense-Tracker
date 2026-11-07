package com.expensetracker.app.domain.model

data class CategorySeed(val name: String, val emoji: String, val color: Long, val kind: CategoryKind)

/** Default category names are referenced by the parsers, so they live in the domain layer. */
object DefaultCategories {
    const val FOOD = "Food & Dining"
    const val GROCERIES = "Groceries"
    const val SHOPPING = "Shopping"
    const val TRANSPORT = "Transportation"
    const val FUEL = "Fuel"
    const val TOLLS = "Tolls & Parking"
    const val TRAVEL = "Travel"
    const val RENT = "Rent"
    const val UTILITIES = "Utilities"
    const val ELECTRICITY = "Electricity"
    const val INTERNET = "Internet"
    const val MOBILE = "Mobile Recharge"
    const val ENTERTAINMENT = "Entertainment"
    const val HEALTH = "Healthcare & Medicines"
    const val EDUCATION = "Education"
    const val SUBSCRIPTIONS = "Subscriptions"
    const val INSURANCE = "Insurance"
    const val EMI = "EMI & Loans"
    const val PERSONAL_TRANSFERS = "Personal Transfers"
    const val MERCHANT_PAYMENTS = "Merchant Payments"
    const val PERSONAL_CARE = "Personal Care"
    const val MISC = "Miscellaneous"
    const val INVESTMENTS = "Investments"
    const val TRANSFERS = "Transfers"
    const val CASH = "Cash Withdrawal"
    const val CC_BILL = "Credit Card Bill"
    const val SALARY = "Salary"
    const val OTHER_INCOME = "Other Income"
    const val REFUNDS = "Refunds"
    const val REIMBURSEMENTS = "Reimbursements"

    val all: List<CategorySeed> = listOf(
        CategorySeed(FOOD, "🍔", 0xFFF97316, CategoryKind.EXPENSE),
        CategorySeed(GROCERIES, "🛒", 0xFF22C55E, CategoryKind.EXPENSE),
        CategorySeed(SHOPPING, "🛍️", 0xFFEC4899, CategoryKind.EXPENSE),
        CategorySeed(TRANSPORT, "🚕", 0xFFEAB308, CategoryKind.EXPENSE),
        CategorySeed(FUEL, "⛽", 0xFFEF4444, CategoryKind.EXPENSE),
        CategorySeed(TOLLS, "🛣️", 0xFF78716C, CategoryKind.EXPENSE),
        CategorySeed(TRAVEL, "🚆", 0xFF0EA5E9, CategoryKind.EXPENSE),
        CategorySeed(RENT, "🏠", 0xFF8B5CF6, CategoryKind.EXPENSE),
        CategorySeed(UTILITIES, "🚰", 0xFF14B8A6, CategoryKind.EXPENSE),
        CategorySeed(ELECTRICITY, "💡", 0xFFFACC15, CategoryKind.EXPENSE),
        CategorySeed(INTERNET, "🌐", 0xFF6366F1, CategoryKind.EXPENSE),
        CategorySeed(MOBILE, "📱", 0xFF3B82F6, CategoryKind.EXPENSE),
        CategorySeed(ENTERTAINMENT, "🎬", 0xFFD946EF, CategoryKind.EXPENSE),
        CategorySeed(HEALTH, "💊", 0xFFF43F5E, CategoryKind.EXPENSE),
        CategorySeed(EDUCATION, "🎓", 0xFF0891B2, CategoryKind.EXPENSE),
        CategorySeed(SUBSCRIPTIONS, "🔁", 0xFFA855F7, CategoryKind.EXPENSE),
        CategorySeed(INSURANCE, "🛡️", 0xFF64748B, CategoryKind.EXPENSE),
        CategorySeed(EMI, "🏦", 0xFFB45309, CategoryKind.EXPENSE),
        CategorySeed(PERSONAL_TRANSFERS, "🤝", 0xFF06B6D4, CategoryKind.EXPENSE),
        CategorySeed(MERCHANT_PAYMENTS, "🏪", 0xFF84CC16, CategoryKind.EXPENSE),
        CategorySeed(PERSONAL_CARE, "💇", 0xFFFB7185, CategoryKind.EXPENSE),
        CategorySeed(MISC, "📦", 0xFF94A3B8, CategoryKind.EXPENSE),
        CategorySeed(INVESTMENTS, "📈", 0xFF10B981, CategoryKind.NEUTRAL),
        CategorySeed(TRANSFERS, "🔄", 0xFF60A5FA, CategoryKind.NEUTRAL),
        CategorySeed(CASH, "🏧", 0xFFA3A3A3, CategoryKind.NEUTRAL),
        CategorySeed(CC_BILL, "💳", 0xFF7C3AED, CategoryKind.NEUTRAL),
        CategorySeed(SALARY, "💼", 0xFF16A34A, CategoryKind.INCOME),
        CategorySeed(OTHER_INCOME, "💰", 0xFF65A30D, CategoryKind.INCOME),
        CategorySeed(REFUNDS, "↩️", 0xFF0D9488, CategoryKind.INCOME),
        CategorySeed(REIMBURSEMENTS, "🧾", 0xFF2563EB, CategoryKind.INCOME),
    )

    /** Fixed category for types that are never ordinary spending. */
    fun forType(type: TxnType): String? = when (type) {
        TxnType.TRANSFER -> TRANSFERS
        TxnType.CREDIT_CARD_PAYMENT -> CC_BILL
        TxnType.CASH_WITHDRAWAL -> CASH
        TxnType.INVESTMENT -> INVESTMENTS
        TxnType.LOAN -> EMI
        TxnType.REIMBURSEMENT -> REIMBURSEMENTS
        else -> null
    }
}
