package com.expensetracker.app.domain.statement

import com.expensetracker.app.domain.model.DefaultCategories
import com.expensetracker.app.domain.model.Direction
import com.expensetracker.app.domain.model.PaymentMethod
import com.expensetracker.app.domain.model.TxnStatus
import com.expensetracker.app.domain.model.TxnType
import com.expensetracker.app.domain.parser.MerchantNormalizer
import com.expensetracker.app.domain.parser.ParsedTransaction
import com.expensetracker.app.domain.parser.TextSignals
import java.time.LocalTime
import java.time.ZoneId

/**
 * Interprets bank statement narrations such as
 *  "UPI/DR/629384751102/SWIGGY/YESB/swiggy@yesbank/Payment",
 *  "UPI-SWIGGY-SWIGGY@ICICI-ICIC0DC0099-629384751102-UPI",
 *  "POS 512345XXXXXX1234 AMAZON PAY IN", "NEFT CR-CITI0000001-ACME CORP-SALARY".
 */
object NarrationParser {

    data class Narration(val name: String?, val vpa: String?, val reference: String?, val p2p: Boolean)

    private val stopWords = setOf(
        "upi", "dr", "cr", "p2m", "p2a", "p2p", "to", "by", "transfer", "trf", "neft", "imps", "rtgs", "pos", "atm", "wdl",
        "atw", "nwd", "ach", "nach", "ecs", "bil", "onl", "ib", "mb", "inb", "billpay", "payment", "pay", "sent", "received",
        "from", "upiintent", "intent", "collect", "mob", "mobile", "bank", "limited", "ltd", "na", "null", "ref", "no",
        "debit", "credit", "inr", "rs", "card", "visa", "mastercard", "rupay", "ecom", "online", "purchase", "int", "chg",
        "charges", "gst", "sms", "fee", "mps", "tpt", "own", "inf", "inft", "ift", "nfs", "cash", "txn", "tran", "trn",
        "paid", "via", "the", "for", "and", "of", "payments", "paytm", "phonepe", "gpay", "googlepay", "upi--", "vps",
        "mmt", "ft", "pca", "pcd", "ipay", "dir", "ach-dr", "achdr", "si", "cms", "neftcr", "neftdr", "impscr", "impsdr",
        "india", "in", "yesb", "sbin", "icic", "hdfc", "utib", "kkbk", "punb", "barb", "cnrb", "ubin", "idfb", "indb",
        "ibkl", "fdrl", "aubl", "ratn", "okaxis", "oksbi", "okhdfcbank", "okicici", "ybl", "ibl", "axl", "remarks", "upi-",
    )
    private val ifscRe = Regex("(?i)^[a-z]{4}0[a-z0-9]{6}$")

    fun parse(description: String): Narration {
        val tokens = description.split(Regex("[/|*:]+|(?<=\\S)-(?=\\S)|\\s-\\s")).map { it.trim() }.filter { it.isNotEmpty() }
        val vpa = TextSignals.findVpa(description)
        val digitTokens = tokens.map { it.filter(Char::isDigit) }.filter { it.length >= 8 }
        val reference = tokens.firstOrNull { it.length == 12 && it.all(Char::isDigit) }
            ?: digitTokens.maxByOrNull { it.length }?.takeIf { it.length in 8..22 }
            ?: TextSignals.reference(description)
        val p2p = tokens.any { it.equals("P2A", true) || it.equals("P2P", true) }
        var name: String? = null
        for (token in tokens) {
            if (token.contains('@') || ifscRe.matches(token)) continue
            val words = token.split(Regex("\\s+")).filter { w ->
                w.length >= 2 && w.lowercase() !in stopWords && w.count(Char::isLetter) >= 2 &&
                    !Regex("(?i)^[x*\\d]{4,}$").matches(w) && w.count(Char::isDigit) <= 2
            }
            val candidate = words.joinToString(" ")
            if (candidate.count(Char::isLetter) >= 3) {
                name = candidate
                break
            }
        }
        return Narration(name, vpa, TextSignals.normalizeRef(reference), p2p)
    }

    /** Turns a statement row into the source-independent transaction shape. */
    fun toParsed(
        row: StatementRow,
        isCreditCardStatement: Boolean,
        accountLast4: String?,
        institution: String?,
        zone: ZoneId,
        parserName: String,
    ): ParsedTransaction {
        val narration = parse(row.description)
        val lower = row.description.lowercase()
        val cleaned = TextSignals.stripNonEvents(lower)
        val notes = ArrayList<String>()
        val classification = TextSignals.classify(cleaned, row.direction, isCreditCardStatement)
        var type = classification.type
        var hint = classification.categoryHint
        if (isCreditCardStatement && row.direction == Direction.CREDIT && type == TxnType.INCOME) {
            if (Regex("(?i)cashback|reward").containsMatchIn(lower)) {
                hint = DefaultCategories.OTHER_INCOME
            } else {
                type = TxnType.CREDIT_CARD_PAYMENT
                notes += "Credit on a card statement treated as bill payment"
            }
        }
        val normalized = MerchantNormalizer.normalize(narration.name, narration.vpa, narration.p2p)
        normalized?.known?.type?.let { if (type == TxnType.EXPENSE || type == TxnType.INCOME) type = it }
        val method = TextSignals.paymentMethod(lower, isCreditCardStatement, narration.vpa != null).let {
            if (it == PaymentMethod.OTHER && isCreditCardStatement) PaymentMethod.CREDIT_CARD else it
        }
        if (!row.directionCertain) notes += "Debit/credit side was inferred; please verify"
        val timestamp = row.date.atTime(row.time ?: LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
        return ParsedTransaction(
            amountMinor = row.amountMinor,
            direction = classification.direction,
            type = type,
            status = TxnStatus.COMPLETED,
            timestamp = timestamp,
            merchant = normalized?.name,
            merchantRaw = narration.name ?: narration.vpa,
            counterpartyIsPerson = normalized?.isPerson == true,
            accountLast4 = row.accountHint ?: accountLast4,
            institution = institution,
            isCreditCard = isCreditCardStatement,
            paymentMethod = method,
            // A 12-digit UPI RRN in the narration matches what SMS alerts carry; the statement's
            // own reference column is often zero-padded.
            reference = narration.reference?.takeIf { it.length == 12 && it.all(Char::isDigit) }
                ?: row.reference?.trimStart('0')?.let { TextSignals.normalizeRef(it) }
                ?: narration.reference,
            upiId = narration.vpa,
            balanceMinor = row.balanceMinor,
            categoryHint = hint,
            description = row.description.take(300),
            confidence = if (row.directionCertain) 0.82f else 0.5f,
            parser = parserName,
            notes = notes + classification.notes,
        )
    }
}
