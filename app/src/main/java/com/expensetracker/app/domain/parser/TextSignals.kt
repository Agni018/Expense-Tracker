package com.expensetracker.app.domain.parser

import com.expensetracker.app.domain.model.DefaultCategories
import com.expensetracker.app.domain.model.Direction
import com.expensetracker.app.domain.model.PaymentMethod
import com.expensetracker.app.domain.model.TxnStatus
import com.expensetracker.app.domain.model.TxnType

/**
 * Shared extraction primitives for transaction text (SMS, notifications, emails, narrations).
 * Every function is pure so it can be unit tested on the JVM.
 */
object TextSignals {

    // ---------- normalisation ----------

    fun flatten(body: String): String = body
        .replace('\u00A0', ' ')
        .replace("\r", "")
        .replace(Regex("\\s*\\n+\\s*"), " | ")
        .replace(Regex("[\\t ]{2,}"), " ")
        .replace(Regex("(?i)\\b(mr|mrs|ms|dr|shri|smt|m/s)\\.\\s*"), "$1 ")
        .trim()

    private val futurePhraseRe = Regex(
        "(?i)\\b(will|shall|would|to) be (credited|debited|reversed|refunded|deducted|processed|charged|paid|auto[- ]?debited)\\b" +
            "|\\bif (already )?(paid|debited|done)\\b|\\bignore if (already )?(paid|done)\\b|\\bif not (paid|done)\\b" +
            "|\\bif (the )?(txn|transaction|payment) (has |is )?(failed|declined)\\b" +
            "|\\breceived (your|a|the) (request|application|complaint|query|order|enquiry|cheque)\\b" +
            "|\\bnot (done|initiated|made) by you\\b",
    )

    /** Removes conditional and future phrases so they are not mistaken for completed events. */
    fun stripNonEvents(lower: String): String = futurePhraseRe.replace(lower, " ")

    // ---------- amounts ----------

    data class AmountMatch(val minor: Long, val range: IntRange, val isBalance: Boolean)

    private val amountRe = Regex("(?i)(?:rs\\.?|inr|₹|rupees)\\s*[:.]?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)(?![0-9])")
    private val bareAmountRe = Regex("(?i)\\b(?:debited|credited|withdrawn|deducted)\\s+(?:by|for|with|of)\\s+([0-9][0-9,]*(?:\\.[0-9]{1,2})?)(?![0-9])")
    private val balanceContextRe = Regex(
        "(?i)(bal|balance|avl|avbl|available|limit|lmt|outstanding|total due|min(imum)?\\.? (amt|amount)? ?due|amount due|due amt|cashback (of|upto|up to)|upto|up to|worth|credit line|o/s)(\\s+(is|of|now|as on\\s+\\S+))?\\W*$",
    )

    fun findAmounts(text: String): List<AmountMatch> {
        val matches = ArrayList<AmountMatch>()
        var lastEnd = 0
        val all = (amountRe.findAll(text).map { it to it.groups[1]!! } + bareAmountRe.findAll(text).map { it to it.groups[1]!! })
            .sortedBy { it.first.range.first }
            .distinctBy { it.second.range.first }
        for ((m, g) in all) {
            val minor = Amounts.toMinor(g.value) ?: continue
            val windowStart = maxOf(lastEnd, m.range.first - 28)
            val before = text.substring(windowStart, m.range.first)
            matches += AmountMatch(minor, m.range, balanceContextRe.containsMatchIn(before.trimEnd(' ', ':', '-', '.')))
            lastEnd = m.range.last + 1
        }
        return matches
    }

    // ---------- direction, status, type ----------

    val strongDebitRe = Regex(
        "(?i)\\b(debited|debit of|spent|withdrawn|deducted|charged|sent|paid|purchased?|used for|used at|using your|transferred from|dr\\.? from|withdrawal of|txn done|payment made|auto[- ]?debited|debited by|debited for)\\b",
    )
    val strongCreditRe = Regex(
        "(?i)\\b(credited|received|deposited|refunded|reversed|reversal|credited back|added to your|added to wallet|cr\\.? to your|has been added)\\b",
    )
    private val payeeCreditRe = Regex("(?i)^credited to (?!(your|ur|you|a/c|ac\\b|acct|account|card|the a/c|wallet))")
    val weakTxnRe = Regex("(?i)\\b(txn of|transaction of|payment of|purchase of|trf to|transfer of|txn|transaction|payment)\\b")

    private val refundRe = Regex("(?i)\\b(refund(ed)?|revers(ed|al)|credited back|reverted|chargeback)\\b")
    private val refundNoticeRe = Regex("(?i)\\brefund\\b[^|.]{0,60}\\b(initiated|requested|being processed|has been processed and|within \\d)")
    private val failedRe = Regex("(?i)\\b(failed|failure|unsuccessful|could not be (processed|completed)|not successful|was not completed|txn (has been )?cancell?ed|transaction (has been )?cancell?ed)\\b")
    private val declinedRe = Regex("(?i)\\b(declined|rejected|insufficient (funds|balance))\\b")
    private val pendingRe = Regex("(?i)\\b(pending|under process|in process|is being processed|awaiting confirmation)\\b")

    private val ccPaymentRe = Regex(
        "(?i)(credit card|card)\\s*(bill\\s*)?payment|payment (of [^|]{1,30} )?(has been |is )?received (on|towards|for|against) (your )?[^|]{0,40}card" +
            "|towards (your )?[^|]{0,30}credit card|\\bcc (bill )?payment|card dues|creditcard payment|\\bcred\\b|cred club|billpay[^|]{0,20}card|payment received[^|]{0,30}card",
    )
    private val loanRe = Regex("(?i)loan[^|]{0,40}(disburs|sanctioned and credited)|disbursement of (your )?loan|loan amount[^|]{0,30}(credited|disbursed)")
    private val atmRe = Regex("(?i)\\batm\\b|cash withdrawal|cash wdl|\\batw\\b|\\bnwd\\b|withdrawn at|\\bcash w/d")
    private val investRe = Regex("(?i)\\bsip\\b|mutual fund|\\bmf\\b|systematic investment|\\biccl\\b|indian clearing|nse clearing|bse star|zerodha|groww|upstox|\\bdemat\\b|\\bnps\\b|\\bppf\\b|recurring deposit|\\brd (installment|instalment)|fixed deposit|\\bfd (booked|created|opened)")
    private val selfRe = Regex("(?i)self transfer|\\bto self\\b|own account|\\bself a/?c")
    private val cashDepositRe = Regex("(?i)cash deposit|cash dep\\b|\\bcdm\\b|cash credited")
    private val salaryRe = Regex("(?i)\\bsalary\\b|\\bsal\\b|payroll|\\bsal cr|wages|stipend")
    private val otherIncomeRe = Regex("(?i)interest|\\bint\\.? (cr|pd)|dividend|cashback|cash back|\\breward|bonus")
    private val emiRe = Regex("(?i)\\bemi\\b|loan (repayment|installment|instalment)|\\bloan a/?c")
    private val reimbursementRe = Regex("(?i)reimburse")

    fun direction(cleanedLower: String): Direction? {
        val debit = strongDebitRe.find(cleanedLower)?.range?.first
        val credit = strongCreditRe.findAll(cleanedLower)
            .firstOrNull { !payeeCreditRe.containsMatchIn(cleanedLower.substring(it.range.first)) }
            ?.range?.first
        return when {
            debit != null && credit != null -> if (debit <= credit) Direction.DEBIT else Direction.CREDIT
            debit != null -> Direction.DEBIT
            credit != null -> Direction.CREDIT
            else -> null
        }
    }

    fun hasStrongVerb(cleanedLower: String) = strongDebitRe.containsMatchIn(cleanedLower) || strongCreditRe.containsMatchIn(cleanedLower)

    fun isRefund(cleanedLower: String) = refundRe.containsMatchIn(cleanedLower)
    fun isRefundNotice(cleanedLower: String) = refundNoticeRe.containsMatchIn(cleanedLower) && !Regex("(?i)\\b(credited|added|reversed to)\\b").containsMatchIn(cleanedLower)

    fun status(cleanedLower: String): TxnStatus = when {
        declinedRe.containsMatchIn(cleanedLower) -> TxnStatus.DECLINED
        failedRe.containsMatchIn(cleanedLower) -> TxnStatus.FAILED
        pendingRe.containsMatchIn(cleanedLower) -> TxnStatus.PENDING
        else -> TxnStatus.COMPLETED
    }

    data class Classification(val type: TxnType, val direction: Direction, val categoryHint: String?, val notes: List<String>)

    fun classify(cleanedLower: String, direction: Direction, isCreditCard: Boolean): Classification {
        val notes = ArrayList<String>()
        var dir = direction
        val type = when {
            isRefund(cleanedLower) && (dir == Direction.CREDIT || strongCreditRe.containsMatchIn(cleanedLower)) -> {
                dir = Direction.CREDIT
                notes += "Refund or reversal wording"
                TxnType.REFUND
            }
            ccPaymentRe.containsMatchIn(cleanedLower) -> {
                notes += "Credit card bill payment (not counted as spending)"
                TxnType.CREDIT_CARD_PAYMENT
            }
            dir == Direction.CREDIT && loanRe.containsMatchIn(cleanedLower) -> {
                notes += "Loan disbursal (not income)"
                TxnType.LOAN
            }
            dir == Direction.DEBIT && atmRe.containsMatchIn(cleanedLower) && !isCreditCard -> {
                notes += "Cash withdrawal (not counted as spending)"
                TxnType.CASH_WITHDRAWAL
            }
            investRe.containsMatchIn(cleanedLower) -> {
                notes += "Investment"
                TxnType.INVESTMENT
            }
            selfRe.containsMatchIn(cleanedLower) -> {
                notes += "Self transfer wording"
                TxnType.TRANSFER
            }
            dir == Direction.CREDIT && cashDepositRe.containsMatchIn(cleanedLower) -> {
                notes += "Cash deposit into own account"
                TxnType.TRANSFER
            }
            dir == Direction.CREDIT && reimbursementRe.containsMatchIn(cleanedLower) -> TxnType.REIMBURSEMENT
            dir == Direction.DEBIT -> TxnType.EXPENSE
            else -> TxnType.INCOME
        }
        val hint = when {
            type == TxnType.INCOME && salaryRe.containsMatchIn(cleanedLower) -> DefaultCategories.SALARY
            type == TxnType.INCOME && otherIncomeRe.containsMatchIn(cleanedLower) -> DefaultCategories.OTHER_INCOME
            type == TxnType.EXPENSE && emiRe.containsMatchIn(cleanedLower) -> DefaultCategories.EMI
            else -> null
        }
        return Classification(type, dir, hint, notes)
    }

    // ---------- payment method ----------

    private val vpaRe = Regex("([a-zA-Z0-9.\\-_]{2,}@[a-zA-Z][a-zA-Z0-9]{1,})(?![a-zA-Z0-9.@])")

    fun findVpa(text: String): String? = vpaRe.findAll(text)
        .map { it.groupValues[1] }
        .firstOrNull { !it.contains("..") && it.substringAfter('@').length <= 15 }

    fun isCreditCard(lower: String): Boolean =
        Regex("(?i)credit card|\\bcc\\b|avl\\.? lmt|available limit|avail(able)? credit limit|\\bavl limit|credit limit").containsMatchIn(lower)

    fun paymentMethod(lower: String, isCreditCard: Boolean, hasVpa: Boolean): PaymentMethod = when {
        Regex("\\batm\\b|cash withdrawal|\\batw\\b|\\bnwd\\b").containsMatchIn(lower) && !isCreditCard -> PaymentMethod.ATM
        isCreditCard -> PaymentMethod.CREDIT_CARD
        hasVpa || Regex("\\bupi\\b|upi/|upi-|upi:|bhim|\\bvpa\\b").containsMatchIn(lower) -> PaymentMethod.UPI
        Regex("\\bimps\\b").containsMatchIn(lower) -> PaymentMethod.IMPS
        Regex("\\bneft\\b").containsMatchIn(lower) -> PaymentMethod.NEFT
        Regex("\\brtgs\\b").containsMatchIn(lower) -> PaymentMethod.RTGS
        Regex("debit card|\\bdc\\b|\\bpos\\b|\\bcard\\b").containsMatchIn(lower) -> PaymentMethod.DEBIT_CARD
        Regex("wallet|paytm balance|amazon pay balance|mobikwik|freecharge").containsMatchIn(lower) -> PaymentMethod.WALLET
        Regex("nach|\\becs\\b|mandate|autopay|auto pay|auto[- ]debit|standing instruction|\\bsi\\b").containsMatchIn(lower) -> PaymentMethod.AUTO_DEBIT
        Regex("net ?banking|internet banking|\\bib\\b|billpay|bill pay|\\bbil/").containsMatchIn(lower) -> PaymentMethod.NET_BANKING
        Regex("cheque|\\bchq\\b|\\bclg\\b").containsMatchIn(lower) -> PaymentMethod.CHEQUE
        Regex("\\bcash\\b").containsMatchIn(lower) -> PaymentMethod.CASH
        else -> PaymentMethod.OTHER
    }

    // ---------- accounts and references ----------

    private val maskedAccountRe = Regex(
        "(?i)(?<![a-z])(a/c|a/c\\.|acct|ac|account|card|bank|cc)\\.?\\s*(?:no\\.?|number|num)?\\s*[:\\-]?\\s*\\d{0,6}[x*#•.]+\\s*-?(\\d{3,6})(?!\\d)",
    )
    private val endingAccountRe = Regex("(?i)(card|a/c|account|acct)[^.|]{0,25}?ending\\s*(?:with|in)?\\s*[:\\-]?\\s*[x*]*(\\d{3,6})(?!\\d)")
    private val plainCardRe = Regex("(?i)\\bcard\\s+(?:no\\.?\\s*)?(\\d{4})(?!\\d)")

    data class Accounts(val own: String?, val counterparty: String?)

    fun accounts(text: String): Accounts {
        val found = (maskedAccountRe.findAll(text).map { it.range.first to it.groupValues[2] } +
            endingAccountRe.findAll(text).map { it.range.first to it.groupValues[2] } +
            plainCardRe.findAll(text).map { it.range.first to it.groupValues[1] })
            .sortedBy { it.first }
            .map { it.first to it.second.takeLast(4) }
            .distinctBy { it.second }
            .toList()
        val own = found.firstOrNull()?.second
        val counterparty = found.drop(1).firstOrNull { (pos, _) ->
            val before = text.substring(maxOf(0, pos - 25), pos).lowercase()
            before.contains(" to") || before.contains("from") || before.contains("credited") || before.contains("beneficiary")
        }?.second
        return Accounts(own, counterparty)
    }

    private val refRes = listOf(
        Regex("(?i)\\bupi[\\s:/-]*(?:ref(?:erence)?|rrn|txn|transaction)?\\.?\\s*(?:no|id|number)?\\.?\\s*[:\\-]?\\s*(\\d{9,18})(?!\\d)"),
        Regex("(?i)\\b(?:ref(?:erence)?|rrn|utr|txn|transaction|trxn|trans)\\.?\\s*(?:no|id|number|#)?\\.?\\s*(?:is\\s+)?[:\\-]?\\s*([a-z0-9]*\\d[a-z0-9]{5,24})\\b"),
        Regex("(?i)\\bupi/(?:p2[am]|dr|cr)?/?(\\d{9,18})"),
        Regex("(?<![\\d.,])(\\d{12})(?![\\d,]|\\.\\d)"),
    )

    fun reference(text: String): String? {
        for (re in refRes) {
            val value = re.find(text)?.groupValues?.get(1) ?: continue
            return normalizeRef(value) ?: continue
        }
        return null
    }

    fun normalizeRef(ref: String?): String? {
        val r = ref?.uppercase()?.filter { it.isLetterOrDigit() } ?: return null
        return r.takeIf { it.length >= 6 && it.any { c -> c.isDigit() } }
    }

    // ---------- counterparties ----------

    data class Counterparty(val raw: String?, val vpa: String?, val p2p: Boolean, val accountLast4: String?)

    private const val NAME = "[A-Za-z0-9][A-Za-z0-9 &.'_\\-*/@]{1,60}?"
    private val p2mRe = Regex("(?i)upi/(p2m|p2a)/(\\d+)/([^/|]+)")
    private val icicPayeeRe = Regex("(?i);\\s*([^;|]+?)\\s+credited\\b")
    private val infoRe = Regex("(?i)\\binfo[:\\s]+([^|]+?)(?:\\.\\s|\\.$|\\||$)")
    private val atRe = Regex(
        "(?i)\\bat\\s+($NAME)(?=\\s+(?:(?:on|via|using|for|txn|avl|bal|dated|by|from|was|is|has|got|due|declined|failed)\\b|ref)|[.,|;(]|$)",
    )
    private val toRe = Regex(
        "(?i)(?<!due )\\b(?:to|trf to|transferred to|paid to|sent to|towards|payment to)\\s+(?!your\\b|you\\b|ur\\b)($NAME)" +
            "(?=\\s+(?:(?:on|via|using|for|upi|imps|neft|avl|bal|dated|from|is|has|was|thru|through)\\b|ref)|[,|;(]|\\.(?:\\s|$)|$)",
    )
    private val fromRe = Regex(
        "(?i)\\b(?:from|by)\\s+(?!your\\b|a/c\\b|ac\\b|acct\\b|you\\b|upi\\b|neft\\b|imps\\b)($NAME)" +
            "(?=\\s+(?:(?:on|via|upi|imps|neft|avl|bal|dated|to|thru|through|is)\\b|ref)|[,|;(]|\\.(?:\\s|$)|$)",
    )
    private val neftNameRe = Regex("(?i)(?:neft|imps|rtgs)\\s*(?:cr|dr)?[-:\\s]+[a-z]{4}0[a-z0-9]{6}[-:\\s]+([^-|]+?)\\s*[-|]")
    private val forRe = Regex(
        "(?i)\\bfor\\s+(?!rs\\b|inr\\b|₹|your\\b|the\\b|an?\\b|any\\b|txn|transaction|payment|more|details|dispute|queries|assistance|help)($NAME)" +
            "(?=\\s+(?:(?:via|using|on|thru|through|upi|dated)\\b|ref)|[.,|;(]|$)",
    )
    private val onMerchantRe = Regex(
        "(?i)\\bon\\s+(?!\\d|your\\b|you\\b|a/c|card|date|hdfc|icici|sbi|axis|kotak|yes\\b|idfc|indusind)([A-Za-z][A-Za-z0-9 &.'_\\-*/]{1,40}?)(?=[.,|;(]|\\s+(?:avl|bal|ref)|$)",
    )

    fun counterparty(text: String, direction: Direction): Counterparty {
        val vpa = findVpa(text)
        p2mRe.find(text)?.let { m ->
            return Counterparty(m.groupValues[3].trim(), vpa, m.groupValues[1].equals("p2a", true), null)
        }
        val patterns = if (direction == Direction.DEBIT) {
            listOf(atRe, toRe, icicPayeeRe, infoRe, forRe, onMerchantRe)
        } else {
            listOf(fromRe, neftNameRe, infoRe, forRe)
        }
        for (re in patterns) {
            for (m in re.findAll(text)) {
                val candidate = m.groupValues[1].trim()
                val accountInCandidate = Regex("(?i)(a/?c|acct|account)\\s*(?:no\\.?)?\\s*[x*]*(\\d{3,6})").find(candidate)
                if (accountInCandidate != null) {
                    return Counterparty(null, vpa, false, accountInCandidate.groupValues[2].takeLast(4))
                }
                if (isUsableName(candidate)) return Counterparty(candidate, vpa, false, null)
            }
        }
        return Counterparty(null, vpa, false, null)
    }

    private fun isUsableName(candidate: String): Boolean {
        val c = MerchantNormalizer.clean(candidate)
        if (c.length < 2 || c.none { it.isLetter() }) return false
        val lower = c.lowercase()
        if (Regex("^(rs|inr|your|you|the|a/?c|account|card|bank|upi|imps|neft|mobile|date|txn|ref|self|beneficiary|vpa|linked|wallet|balance|atm)\\b").containsMatchIn(lower)) return false
        if (Regex("^(report|dispute|block|call|know|check|avoid|view|reset|change|unsubscribe|customer|stop|register|update|login|visit|download|activate)\\b").containsMatchIn(lower)) return false
        if (Regex("(?i)\\b(rs\\.?|inr)\\s*\\d").containsMatchIn(c)) return false
        if (Regex("^\\d{1,2}[-/ ]").containsMatchIn(lower)) return false
        return true
    }

    // ---------- non-transaction messages ----------

    private val otpRe = Regex("(?i)\\b(otp|one[- ]time password|verification code|security code|passcode|\\bcode is\\b)")
    private val requestRe = Regex("(?i)(has requested|requested (money|payment|rs|inr|₹)|collect request|payment request|request(ing)? (rs|inr|₹)|sent you a request|requests? ₹)")
    private val mandateRe = Regex("(?i)mandate|autopay|auto pay|standing instruction|e-?nach|\\bsi\\b (registered|created|set)|subscription (is )?(set up|created)|will be (auto[- ]?)?debited|scheduled (for|on)|upcoming (payment|debit)")
    private val billRe = Regex("(?i)(bill|statement) (is )?(generated|due|of)|total (amount )?due|min(imum)?\\.? (amt|amount)? ?due|due (date|on|by)|payment (is )?due|outstanding (amt|amount)|is due")
    private val promoRe = Regex(
        "(?i)\\b(offer|cashback (of )?(up ?to)|win\\b|winner|apply now|click|limited period|hurry|congratulations|pre[- ]?approved|eligible for|exclusive|t&c|tnc|discount|coupon|voucher|% off|flat ₹|flat rs|loan (of|up ?to)|get (up ?to|flat|instant)|avail|shop now|use code|lowest price|free\\b|gift|lucky|reward points? worth)",
    )
    private val balanceAlertRe = Regex("(?i)(available|avl|avbl|closing|current|ledger)\\.? ?(bal|balance)|balance (is|as on|in your)")

    fun isOtp(lower: String) = otpRe.containsMatchIn(lower)
    fun isRequest(lower: String) = requestRe.containsMatchIn(lower)
    fun isMandateOrUpcoming(lower: String) = mandateRe.containsMatchIn(lower)
    fun isBillNotice(lower: String) = billRe.containsMatchIn(lower)
    fun isPromotional(lower: String) = promoRe.containsMatchIn(lower)
    fun isBalanceAlert(lower: String) = balanceAlertRe.containsMatchIn(lower)
}
