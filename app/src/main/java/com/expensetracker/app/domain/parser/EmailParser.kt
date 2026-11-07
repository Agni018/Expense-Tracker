package com.expensetracker.app.domain.parser

import com.expensetracker.app.domain.model.DefaultCategories
import com.expensetracker.app.domain.model.Direction
import com.expensetracker.app.domain.model.PaymentMethod
import com.expensetracker.app.domain.model.TxnStatus
import com.expensetracker.app.domain.model.TxnType
import java.time.ZoneId

/**
 * Extracts transactions from bank alert emails and merchant receipts. Receipts usually
 * duplicate a bank alert; the ingestor's duplicate detection merges them into one record.
 */
class EmailParser(zone: ZoneId = ZoneId.systemDefault()) {

    private val sms = SmsParser(zone)

    data class Email(val from: String, val subject: String, val body: String, val receivedAt: Long)

    private val bankDomainRe = Regex(
        "(?i)@([a-z0-9.-]*\\.)?(hdfcbank|icicibank|sbi|sbicard|axisbank|kotak|yesbank|indusind|idfcfirstbank|federalbank|pnb|bankofbaroda|canarabank|unionbankofindia|rblbank|aubank|americanexpress|sc\\.com|hsbc|citi|dbs|onecard|paytmbank|airtelbank)\\.",
    )
    private val marketingRe = Regex("(?i)newsletter|marketing|offers?@|promo|deals@|news@|mailer@|campaign")
    private val promoSubjectRe = Regex("(?i)% off|\\bsale\\b|\\boffer|\\bdeals?\\b|cashback up ?to|\\bwin\\b|exclusive|limited time|just for you|recommended")
    private val statementRe = Regex("(?i)\\b(e-?statement|account statement|card statement|statement of account|monthly statement)\\b")
    private val cancelledRe = Regex("(?i)order (has been |was )?cancell?ed|cancellation confirmed")
    private val totalLabels = listOf(
        "grand total", "order total", "total paid", "amount paid", "total amount paid", "net amount paid", "total amount",
        "bill total", "net payable", "total payable", "amount charged", "you paid", "paid amount", "payment amount", "total",
    )
    private val totalRe = Regex(
        "(?i)(" + totalLabels.joinToString("|") { label -> label.split(' ').joinToString("\\s+") { Regex.escape(it) } } +
            ")\\s*(?:\\(incl[^)]*\\))?\\s*[:\\-]?\\s*(?:rs\\.?|inr|₹)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)",
    )
    private val orderIdRe = Regex("(?i)\\border\\s*(?:id|no\\.?|number|#)\\s*[:#]?\\s*([A-Z0-9][A-Z0-9-]{4,30})")

    fun parse(email: Email): MessageParseResult {
        val from = email.from
        val subject = email.subject
        val body = HtmlText.toText(email.body).take(6000)
        val subjectLower = subject.lowercase()
        val bodyLower = body.lowercase()

        if (statementRe.containsMatchIn(subjectLower)) {
            return MessageParseResult.Ignored(IgnoreReason.BILL_OR_STATEMENT, "Statement email – import the attached file from Statement import")
        }
        val paidWords = Regex("(?i)\\b(paid|debited|credited|charged|spent|received|refund(ed)?)\\b").containsMatchIn(bodyLower)
        if ((marketingRe.containsMatchIn(from) || promoSubjectRe.containsMatchIn(subjectLower)) && !paidWords) {
            return MessageParseResult.Ignored(IgnoreReason.PROMOTIONAL)
        }
        if (cancelledRe.containsMatchIn(subjectLower) || cancelledRe.containsMatchIn(bodyLower.take(400))) {
            return MessageParseResult.Ignored(IgnoreReason.NOT_FINANCIAL, "Order cancelled")
        }

        val isBank = bankDomainRe.containsMatchIn(from)
        val alertLike = Regex("(?i)(debited|credited|spent|withdrawn)[^.]{0,80}(a/c|acct|account|card)|(a/c|acct|account|card)[^.]{0,80}(debited|credited)").containsMatchIn(bodyLower)
        if (isBank || alertLike) {
            val institution = BankDirectory.institutionFromText(from) ?: BankDirectory.institutionFromText(subject)
            val result = sms.parseText("$subject | ${body.take(1500)}", email.receivedAt, institution, promotionalHeader = false, parserName = "Email")
            if (result is MessageParseResult.Transaction || isBank) return result
        }
        return parseReceipt(email, body, subjectLower, bodyLower)
    }

    private fun parseReceipt(email: Email, body: String, subjectLower: String, bodyLower: String): MessageParseResult {
        val totals = totalRe.findAll(body).mapNotNull { m ->
            val label = m.groupValues[1].lowercase().replace(Regex("\\s+"), " ")
            val amount = Amounts.toMinor(m.groupValues[2]) ?: return@mapNotNull null
            Triple(totalLabels.indexOf(label).let { if (it < 0) totalLabels.size else it }, amount, label)
        }.filter { it.second > 0 }.toList()
        if (totals.isEmpty()) return MessageParseResult.Ignored(IgnoreReason.NOT_FINANCIAL, "No paid total found")
        val best = totals.minBy { it.first }

        val senderName = email.from.substringBefore('<').trim().trim('"')
        val senderDomain = email.from.substringAfter('@', "").substringBefore('>').substringBeforeLast('.')
        val known = MerchantDirectory.match(senderName) ?: MerchantDirectory.match(senderDomain) ?: MerchantDirectory.match(email.subject)
        val merchant = known?.name ?: MerchantNormalizer.clean(senderName).ifBlank { senderDomain }.let { MerchantNormalizer.titleCase(it) }

        val isRefund = Regex("(?i)\\brefund").containsMatchIn(subjectLower)
        val status = when {
            Regex("(?i)\\b(failed|declined|unsuccessful)\\b").containsMatchIn(subjectLower) -> TxnStatus.FAILED
            else -> TxnStatus.COMPLETED
        }
        val method = when {
            Regex("(?i)cash on delivery|\\bcod\\b|pay on delivery").containsMatchIn(bodyLower) -> PaymentMethod.CASH
            Regex("(?i)\\bupi\\b").containsMatchIn(bodyLower) -> PaymentMethod.UPI
            Regex("(?i)credit card").containsMatchIn(bodyLower) -> PaymentMethod.CREDIT_CARD
            Regex("(?i)debit card").containsMatchIn(bodyLower) -> PaymentMethod.DEBIT_CARD
            Regex("(?i)net ?banking").containsMatchIn(bodyLower) -> PaymentMethod.NET_BANKING
            Regex("(?i)wallet|amazon pay balance|paytm balance").containsMatchIn(bodyLower) -> PaymentMethod.WALLET
            else -> PaymentMethod.OTHER
        }
        val subscription = known?.isSubscription == true || Regex("(?i)subscription|renewal|membership|renewed").containsMatchIn(subjectLower)
        val txn = ParsedTransaction(
            amountMinor = best.second,
            direction = if (isRefund) Direction.CREDIT else Direction.DEBIT,
            type = when {
                isRefund -> TxnType.REFUND
                known?.type != null -> known.type
                else -> TxnType.EXPENSE
            },
            status = status,
            timestamp = email.receivedAt,
            merchant = merchant,
            merchantRaw = senderName,
            paymentMethod = method,
            reference = TextSignals.reference(body),
            orderId = (orderIdRe.find(email.subject) ?: orderIdRe.find(body))?.groupValues?.get(1)?.takeIf { it.any(Char::isDigit) },
            categoryHint = if (subscription) DefaultCategories.SUBSCRIPTIONS else null,
            description = email.subject.take(200),
            confidence = if (known != null) 0.68f else 0.5f,
            parser = "Email receipt",
            notes = listOf("Receipt total taken from \"${best.third}\""),
        )
        return MessageParseResult.Transaction(txn)
    }
}

object HtmlText {
    private val entities = mapOf(
        "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'",
        "&#8377;" to "₹", "&#x20b9;" to "₹", "&#x20B9;" to "₹", "&rupee;" to "₹", "&#160;" to " ",
    )

    fun toText(input: String): String {
        if (!input.contains('<')) return decode(input)
        var s = input
        s = s.replace(Regex("(?is)<(style|script|head)[^>]*>.*?</\\1>"), " ")
        s = s.replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</tr>|</li>|</h[1-6]>|</table>"), "\n")
        s = s.replace(Regex("(?i)</t[dh]>"), " ")
        s = s.replace(Regex("(?s)<[^>]+>"), " ")
        s = decode(s)
        return s.lines().map { it.replace(Regex("[ \\t]+"), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun decode(s: String): String {
        var out = s
        entities.forEach { (k, v) -> out = out.replace(k, v) }
        out = Regex("&#(\\d+);").replace(out) { m ->
            m.groupValues[1].toIntOrNull()?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: ""
        }
        return out
    }
}
