package com.expensetracker.app.domain.parser

import com.expensetracker.app.domain.model.Direction
import com.expensetracker.app.domain.model.PaymentMethod
import com.expensetracker.app.domain.model.TxnStatus
import com.expensetracker.app.domain.model.TxnType
import java.time.Instant
import java.time.ZoneId

/**
 * Layered bank-alert parser:
 *  1. reject OTPs, requests, promotions, mandates and bill notices;
 *  2. bank-specific templates;
 *  3. generic extraction of amount, direction, account, counterparty, reference and date;
 *  4. merchant normalisation and transaction classification;
 *  5. confidence scoring (low scores are sent to the review queue by the ingestor).
 */
class SmsParser(private val zone: ZoneId = ZoneId.systemDefault()) {

    fun parse(sender: String?, body: String, receivedAt: Long, allowPersonalSender: Boolean = false): MessageParseResult {
        val senderInfo = BankDirectory.sender(sender)
        if (senderInfo.isPersonalNumber && !allowPersonalSender) {
            return MessageParseResult.Ignored(IgnoreReason.PERSONAL_SENDER)
        }
        return parseText(body, receivedAt, senderInfo.institution, senderInfo.isPromotionalHeader, PARSER_NAME)
    }

    /** Shared by the SMS, notification and email paths. */
    fun parseText(
        body: String,
        receivedAt: Long,
        institutionHint: String?,
        promotionalHeader: Boolean,
        parserName: String,
    ): MessageParseResult {
        val text = TextSignals.flatten(body)
        val lower = text.lowercase()
        val cleaned = TextSignals.stripNonEvents(lower)

        if (TextSignals.isOtp(lower) && !Regex("(?i)\\b(debited|credited|spent|withdrawn)\\b").containsMatchIn(cleaned)) {
            return MessageParseResult.Ignored(IgnoreReason.OTP)
        }
        if (TextSignals.isRequest(lower)) return MessageParseResult.Ignored(IgnoreReason.PAYMENT_REQUEST)
        if (TextSignals.isRefundNotice(cleaned)) {
            return MessageParseResult.Ignored(IgnoreReason.MANDATE_OR_UPCOMING, "Refund initiated but not yet credited")
        }

        val amounts = TextSignals.findAmounts(text)
        val txnAmount = amounts.firstOrNull { !it.isBalance }
        val balance = amounts.firstOrNull { it.isBalance }
        val accounts = TextSignals.accounts(text)
        val institution = institutionHint ?: BankDirectory.institutionFromText(text)
        val strong = TextSignals.hasStrongVerb(cleaned)

        if (!strong) {
            when {
                TextSignals.isMandateOrUpcoming(lower) -> return MessageParseResult.Ignored(IgnoreReason.MANDATE_OR_UPCOMING)
                TextSignals.isBillNotice(lower) -> return MessageParseResult.Ignored(IgnoreReason.BILL_OR_STATEMENT)
                promotionalHeader || TextSignals.isPromotional(lower) -> return MessageParseResult.Ignored(IgnoreReason.PROMOTIONAL)
                TextSignals.isBalanceAlert(lower) && (balance ?: txnAmount) != null ->
                    return MessageParseResult.BalanceAlert(accounts.own, institution, (balance ?: txnAmount)!!.minor)
            }
            // Without a completed-event verb only failed/pending attempts are worth recording.
            val weak = TextSignals.weakTxnRe.containsMatchIn(cleaned) && TextSignals.status(cleaned) != TxnStatus.COMPLETED
            if (!weak) {
                return if (txnAmount == null) MessageParseResult.Ignored(IgnoreReason.NO_AMOUNT)
                else MessageParseResult.Ignored(IgnoreReason.NOT_FINANCIAL, "Mentions an amount but no completed transaction")
            }
        } else if (promotionalHeader && TextSignals.isPromotional(lower)) {
            return MessageParseResult.Ignored(IgnoreReason.PROMOTIONAL)
        }

        if (txnAmount == null) {
            return MessageParseResult.Unparsed("Transaction wording found but no amount", null)
        }
        if (txnAmount.minor == 0L) return MessageParseResult.Ignored(IgnoreReason.NOT_FINANCIAL, "Zero amount")

        val notes = ArrayList<String>()
        var confidence = 0.45f

        // Layer 1: bank templates
        val template = BankDirectory.templates.firstNotNullOfOrNull { t -> t.regex.find(text)?.let { t to it } }
        var direction: Direction? = null
        var amountMinor = txnAmount.minor
        var account = accounts.own
        var counterpartyText: String? = null
        var reference: String? = null
        var dateText: String? = null
        if (template != null) {
            val (t, m) = template
            fun g(i: Int) = if (i == 0) null else m.groupValues.getOrNull(i)?.takeIf { it.isNotBlank() }
            direction = t.direction
            g(t.amount)?.let { Amounts.toMinor(it) }?.let { amountMinor = it }
            g(t.account)?.let { account = it.takeLast(4) }
            counterpartyText = g(t.counterparty)
            reference = TextSignals.normalizeRef(g(t.reference))
            dateText = g(t.date)
            confidence += 0.25f
            notes += "Matched ${t.name} template"
        }

        // Layer 2: generic extraction
        val dir = direction ?: TextSignals.direction(cleaned) ?: Direction.DEBIT.also {
            notes += "Direction unclear, assumed debit"
            confidence -= 0.15f
        }
        if (strong) confidence += 0.1f else confidence -= 0.1f

        val isCreditCard = TextSignals.isCreditCard(lower) || institution?.contains("Card") == true
        val classification = TextSignals.classify(cleaned, dir, isCreditCard)
        notes += classification.notes
        val status = TextSignals.status(cleaned)

        val counterparty = TextSignals.counterparty(text, classification.direction)
        val vpa = counterparty.vpa ?: counterpartyText?.let { TextSignals.findVpa(it) }
        val rawName = counterpartyText?.takeUnless { it.contains('@') } ?: counterparty.raw
        val normalized = MerchantNormalizer.normalize(rawName, vpa, counterparty.p2p)
        if (normalized != null) confidence += 0.1f

        reference = reference ?: TextSignals.reference(text)
        if (reference != null) confidence += 0.05f
        if (account != null) confidence += 0.1f
        if (institution != null) confidence += 0.05f

        val method = TextSignals.paymentMethod(lower, isCreditCard, vpa != null)
        val receivedDate = Instant.ofEpochMilli(receivedAt).atZone(zone).toLocalDate()
        val found = (dateText?.let { DateParser.findInText(it, receivedDate) } ?: DateParser.findInText(text, receivedDate))
        val timestamp = DateParser.resolveTimestamp(found, receivedAt, zone)

        var type = classification.type
        val known = normalized?.known
        if (known?.type != null && type in setOf(TxnType.EXPENSE, TxnType.INCOME)) {
            type = known.type
            notes += "${known.name} is treated as ${known.type.label.lowercase()}"
        }

        val txn = ParsedTransaction(
            amountMinor = amountMinor,
            direction = classification.direction,
            type = type,
            status = status,
            timestamp = timestamp,
            merchant = normalized?.name,
            merchantRaw = rawName ?: vpa,
            counterpartyIsPerson = normalized?.isPerson == true,
            accountLast4 = account,
            institution = institution,
            isCreditCard = isCreditCard,
            paymentMethod = if (method == PaymentMethod.OTHER && isCreditCard) PaymentMethod.CREDIT_CARD else method,
            reference = reference,
            upiId = vpa,
            balanceMinor = balance?.minor?.takeUnless { isCreditCard },
            counterpartyAccountLast4 = counterparty.accountLast4 ?: accounts.counterparty,
            categoryHint = classification.categoryHint,
            description = text.take(300),
            confidence = confidence.coerceIn(0.1f, 0.99f),
            parser = parserName,
            notes = notes,
        )
        return MessageParseResult.Transaction(txn)
    }

    companion object {
        const val PARSER_NAME = "SMS"
    }
}
