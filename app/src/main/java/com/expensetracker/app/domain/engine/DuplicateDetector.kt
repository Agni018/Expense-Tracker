package com.expensetracker.app.domain.engine

import com.expensetracker.app.domain.model.Direction
import com.expensetracker.app.domain.model.SourceType
import com.expensetracker.app.domain.parser.MerchantDirectory
import kotlin.math.abs

/**
 * Decides whether an incoming record describes a transaction that is already stored.
 * Amount alone never decides: a reference match, or amount + time window + agreeing account
 * or merchant, is required. Every decision carries human-readable reasons.
 */
object DuplicateDetector {

    data class Record(
        val amountMinor: Long,
        val direction: Direction,
        val timestamp: Long,
        val accountLast4: String?,
        val reference: String?,
        val merchant: String?,
        val sourceTypes: Set<SourceType>,
        /** Hashes of normalised message bodies already linked to the record. */
        val bodyHashes: Set<String> = emptySet(),
        val knownMerchant: Boolean = false,
    )

    enum class Verdict { DUPLICATE, POSSIBLE, DISTINCT }

    data class Decision(val verdict: Verdict, val score: Int, val reasons: List<String>)

    private const val MINUTE = 60_000L
    private const val DAY = 24 * 60 * MINUTE

    fun evaluate(incoming: Record, existing: Record): Decision {
        val reasons = ArrayList<String>()
        if (incoming.amountMinor != existing.amountMinor) return Decision(Verdict.DISTINCT, 0, listOf("Different amount"))
        if (incoming.direction != existing.direction) return Decision(Verdict.DISTINCT, 0, listOf("Opposite direction"))

        val timeDiff = abs(incoming.timestamp - existing.timestamp)
        val sharedBody = incoming.bodyHashes.any { it in existing.bodyHashes }
        if (sharedBody && timeDiff <= 10 * MINUTE) {
            return Decision(Verdict.DUPLICATE, 100, listOf("Identical message received twice"))
        }

        val refA = incoming.reference
        val refB = existing.reference
        var refConflict = false
        if (refA != null && refB != null) {
            val same = refA == refB || (minOf(refA.length, refB.length) >= 8 && (refA.endsWith(refB) || refB.endsWith(refA)))
            if (same && timeDiff <= 4 * DAY) {
                return Decision(Verdict.DUPLICATE, 100, listOf("Same reference number $refA"))
            }
            if (refA.length == 12 && refB.length == 12 && refA.all(Char::isDigit) && refB.all(Char::isDigit)) {
                return Decision(Verdict.DISTINCT, 0, listOf("Different UPI reference numbers"))
            }
            refConflict = true
        }

        if (incoming.accountLast4 != null && existing.accountLast4 != null && incoming.accountLast4 != existing.accountLast4) {
            return Decision(Verdict.DISTINCT, 0, listOf("Different accounts"))
        }

        val crossSource = incoming.sourceTypes.none { it in existing.sourceTypes }
        val statementInvolved = incoming.sourceTypes.any { it.isStatement } || existing.sourceTypes.any { it.isStatement }
        val emailInvolved = SourceType.EMAIL in incoming.sourceTypes || SourceType.EMAIL in existing.sourceTypes ||
            SourceType.RECEIPT_OCR in incoming.sourceTypes || SourceType.RECEIPT_OCR in existing.sourceTypes
        val window = when {
            statementInvolved -> 2 * DAY + 12 * 60 * MINUTE
            emailInvolved -> 12 * 60 * MINUTE
            crossSource -> 30 * MINUTE
            else -> 3 * MINUTE
        }
        if (timeDiff > window) return Decision(Verdict.DISTINCT, 0, listOf("Too far apart in time"))

        var score = 50
        reasons += "Same amount and direction"
        reasons += if (statementInvolved) "Within ${timeDiff / DAY} day(s)" else "Within ${maxOf(1, timeDiff / MINUTE)} minute(s)"
        if (crossSource && timeDiff <= 5 * MINUTE) {
            score += 15
            reasons += "Reported by two sources at the same time"
        }
        if (statementInvolved && timeDiff <= DAY) score += 10
        if (incoming.accountLast4 != null && incoming.accountLast4 == existing.accountLast4) {
            score += 20
            reasons += "Same account ••${incoming.accountLast4}"
        }
        val merchantMatch = merchantsMatch(incoming.merchant, existing.merchant)
        when {
            merchantMatch -> {
                score += 25
                reasons += "Same merchant"
            }
            incoming.merchant != null && existing.merchant != null && incoming.knownMerchant && existing.knownMerchant -> {
                score -= 40
                reasons += "Different merchants"
            }
        }
        if (refConflict) {
            score -= 30
            reasons += "Reference numbers differ"
        }

        val verdict = if (crossSource) {
            when {
                score >= 60 -> Verdict.DUPLICATE
                score >= 40 -> Verdict.POSSIBLE
                else -> Verdict.DISTINCT
            }
        } else {
            // Two separate alerts from the same channel need a reference match to merge automatically.
            if (score >= 70) Verdict.POSSIBLE else Verdict.DISTINCT
        }
        return Decision(verdict, score, reasons)
    }

    fun merchantsMatch(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        val ka = MerchantDirectory.compact(a)
        val kb = MerchantDirectory.compact(b)
        if (ka.isEmpty() || kb.isEmpty()) return false
        if (ka == kb) return true
        if (minOf(ka.length, kb.length) >= 4 && (ka.contains(kb) || kb.contains(ka))) return true
        val ta = MerchantDirectory.tokens(a).filter { it.length > 2 }.toSet()
        val tb = MerchantDirectory.tokens(b).filter { it.length > 2 }.toSet()
        if (ta.isEmpty() || tb.isEmpty()) return false
        val jaccard = ta.intersect(tb).size.toDouble() / ta.union(tb).size
        return jaccard >= 0.5
    }
}
