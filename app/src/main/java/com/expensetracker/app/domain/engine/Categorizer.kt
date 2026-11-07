package com.expensetracker.app.domain.engine

import com.expensetracker.app.domain.model.DefaultCategories as C
import com.expensetracker.app.domain.model.Direction
import com.expensetracker.app.domain.model.RuleMatchType
import com.expensetracker.app.domain.model.RuleOrigin
import com.expensetracker.app.domain.model.TxnType
import com.expensetracker.app.domain.parser.MerchantDirectory
import com.expensetracker.app.domain.parser.ParsedTransaction

data class RuleSpec(
    val id: Long,
    val pattern: String,
    val matchType: RuleMatchType,
    val categoryId: Long,
    val typeOverride: TxnType?,
    val origin: RuleOrigin,
)

/**
 * Local, explainable categorisation. Order: user rules, learned rules, fixed categories for
 * non-spending types, parser hints, merchant dictionary, keyword heuristics, fallbacks.
 */
class Categorizer(
    private val categoryIdsByName: Map<String, Long>,
    private val rules: List<RuleSpec>,
) {
    data class Result(
        val categoryId: Long?,
        val typeOverride: TxnType?,
        val confidence: Float,
        val reason: String,
        val ruleId: Long? = null,
    )

    fun categorize(txn: ParsedTransaction): Result =
        categorize(txn.merchant, txn.description ?: txn.merchantRaw, txn.type, txn.direction, txn.categoryHint, txn.counterpartyIsPerson)

    fun categorize(
        merchant: String?,
        description: String?,
        type: TxnType,
        direction: Direction,
        hint: String? = null,
        isPerson: Boolean = false,
    ): Result {
        val merchantKey = merchant?.let { MerchantDirectory.compact(it) }.orEmpty()
        val haystack = MerchantDirectory.compact(listOfNotNull(merchant, description).joinToString(" "))

        fun ruleHit(origin: RuleOrigin): Result? {
            val ordered = rules.filter { it.origin == origin }
                .sortedWith(compareBy<RuleSpec> { if (it.matchType == RuleMatchType.MERCHANT_EXACT) 0 else 1 }.thenByDescending { it.pattern.length })
            for (rule in ordered) {
                val pattern = MerchantDirectory.compact(rule.pattern)
                if (pattern.isEmpty()) continue
                val hit = when (rule.matchType) {
                    RuleMatchType.MERCHANT_EXACT -> merchantKey.isNotEmpty() && merchantKey == pattern
                    RuleMatchType.CONTAINS -> haystack.contains(pattern)
                }
                if (hit) {
                    val label = if (origin == RuleOrigin.USER) "Your rule" else "Learned from your edits"
                    return Result(rule.categoryId, rule.typeOverride, if (origin == RuleOrigin.USER) 1f else 0.92f, "$label: ${rule.matchType.label.lowercase()} \"${rule.pattern}\"", rule.id)
                }
            }
            return null
        }
        ruleHit(RuleOrigin.USER)?.let { return it }
        ruleHit(RuleOrigin.LEARNED)?.let { return it }

        fun byName(name: String, confidence: Float, reason: String) = categoryIdsByName[name]?.let { Result(it, null, confidence, reason) }

        C.forType(type)?.let { fixed -> byName(fixed, 0.95f, "${type.label} category")?.let { return it } }
        if (type == TxnType.REFUND) return byName(C.REFUNDS, 0.8f, "Refund") ?: Result(null, null, 0.3f, "No category")
        hint?.let { byName(it, 0.85f, "Detected from message wording")?.let { r -> return r } }

        MerchantDirectory.match(merchant)?.let { known ->
            byName(known.category, 0.9f, "Known merchant ${known.name}")?.let { return it }
        }

        val text = listOfNotNull(merchant, description).joinToString(" ").lowercase()
        val keywordRules = if (direction == Direction.DEBIT) expenseKeywords else incomeKeywords
        for ((re, category) in keywordRules) {
            if (re.containsMatchIn(text)) byName(category, 0.7f, "Keyword match")?.let { return it }
        }

        // Default categories can be renamed by the user, so every fallback tolerates a missing name.
        val fallback = when {
            isPerson && direction == Direction.DEBIT -> byName(C.PERSONAL_TRANSFERS, 0.75f, "Payment to a person")
            isPerson -> byName(C.TRANSFERS, 0.7f, "Money received from a person")
            direction == Direction.DEBIT && merchant != null -> byName(C.MERCHANT_PAYMENTS, 0.45f, "Unrecognised merchant")
            direction == Direction.DEBIT -> byName(C.MISC, 0.3f, "No merchant information")
            else -> byName(C.OTHER_INCOME, 0.4f, "Unrecognised credit")
        }
        return fallback ?: Result(null, null, 0.3f, "No matching category")
    }

    companion object {
        private fun r(pattern: String) = Regex("(?i)$pattern")

        val expenseKeywords: List<Pair<Regex, String>> = listOf(
            r("\\b(petrol|fuel|filling station|service station|petroleum|fuels|diesel|\\bcng\\b|pump)") to C.FUEL,
            r("fastag|\\btoll\\b|parking|\\bnetc\\b") to C.TOLLS,
            r("electricity|power distribution|discom|vidyut|bijli|electric supply|\\beb bill|power ltd|power corp") to C.ELECTRICITY,
            r("water (board|supply|bill)|gas (bill|agency)|piped gas|\\blpg\\b|cylinder|municipal|property tax|maintenance|society|apartment") to C.UTILITIES,
            r("broadband|fiber|fibre|internet|wifi|wi-fi") to C.INTERNET,
            r("recharge|prepaid|postpaid|mobile bill|\\bdth\\b") to C.MOBILE,
            r("pharma|pharmacy|medical|medicals|chemist|drug|hospital|clinic|diagnostic|pathology|\\blabs?\\b|dental|dentist|doctor|health|medicine|ayurved|nursing") to C.HEALTH,
            r("school|college|university|tuition|academy|institute|coaching|education|exam fee|\\bfees\\b|course") to C.EDUCATION,
            r("insurance|premium|assurance") to C.INSURANCE,
            r("\\bemi\\b|\\bloan\\b|finance ltd|finserv|\\bnbfc\\b") to C.EMI,
            r("\\brent\\b|house rent|\\bpg\\b|hostel|lease") to C.RENT,
            r("railway|\\btrain\\b|flight|airline|airways|travels|tours|holiday|resort|bus ticket|\\birctc\\b") to C.TRAVEL,
            r("metro|\\bcab\\b|taxi|\\bauto\\b|rickshaw|\\bbus\\b|transport") to C.TRANSPORT,
            r("\\bmart\\b|kirana|supermarket|super market|grocery|grocer|provision|general store|vegetable|fruits|dairy|\\bmilk\\b|departmental|hypermarket") to C.GROCERIES,
            r("restaurant|\\bcafe\\b|café|\\bhotel\\b|dhaba|bakery|bakers|sweets|\\bfoods?\\b|kitchen|biryani|pizza|burger|\\btea\\b|chai|coffee|juice|canteen|\\bmess\\b|eatery|\\bbar\\b|\\bpub\\b|brew|\\bdine|tiffin|caterers|momos") to C.FOOD,
            r("fashion|apparel|garments|textile|clothing|footwear|shoes|electronics|jewel|book ?store|stationery|gift|retail|\\bmall\\b|boutique") to C.SHOPPING,
            r("movie|cinema|theatre|multiplex|gaming|\\bgames?\\b|concert|\\bevent") to C.ENTERTAINMENT,
            r("salon|\\bspa\\b|parlou?r|barber|\\bgym\\b|fitness|yoga") to C.PERSONAL_CARE,
            r("subscription|membership|renewal") to C.SUBSCRIPTIONS,
            r("mutual fund|\\bsip\\b|securities|broking|demat|\\bstock") to C.INVESTMENTS,
        )

        val incomeKeywords: List<Pair<Regex, String>> = listOf(
            r("salary|\\bsal\\b|payroll|wages|stipend") to C.SALARY,
            r("interest|dividend|cashback|cash back|reward|bonus") to C.OTHER_INCOME,
            r("refund|reversal") to C.REFUNDS,
            r("reimburse") to C.REIMBURSEMENTS,
        )
    }
}
