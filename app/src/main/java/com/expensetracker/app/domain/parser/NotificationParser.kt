package com.expensetracker.app.domain.parser

import com.expensetracker.app.domain.model.Direction
import com.expensetracker.app.domain.model.PaymentMethod
import com.expensetracker.app.domain.model.TxnType
import java.time.ZoneId

/**
 * Reads payment notifications from UPI and banking apps. Payment apps do not expose their
 * history, so this only sees what the app itself posts to the notification shade.
 */
class NotificationParser(zone: ZoneId = ZoneId.systemDefault()) {

    private val sms = SmsParser(zone)

    enum class AppKind { UPI, BANK, SMS }

    data class App(val name: String, val kind: AppKind)

    fun appFor(packageName: String): App? = apps[packageName]

    fun parse(packageName: String, title: String?, text: String?, postedAt: Long): MessageParseResult {
        val app = apps[packageName] ?: return MessageParseResult.Ignored(IgnoreReason.NOT_FINANCIAL, "App not monitored")
        val combined = TextSignals.flatten(listOfNotNull(title, text).filter { it.isNotBlank() }.joinToString(" | "))
        if (combined.isBlank()) return MessageParseResult.Ignored(IgnoreReason.NO_AMOUNT)
        return when (app.kind) {
            AppKind.SMS -> sms.parse(title, text.orEmpty(), postedAt)
            AppKind.BANK -> sms.parseText(combined, postedAt, app.name, promotionalHeader = false, parserName = "Notification")
            AppKind.UPI -> parseUpiApp(app, combined, postedAt)
        }
    }

    private fun parseUpiApp(app: App, combined: String, postedAt: Long): MessageParseResult {
        val lower = combined.lowercase()
        if (TextSignals.isRequest(lower) || Regex("(?i)\\b(reminder|due|request(ed)?|collect)\\b").containsMatchIn(lower)) {
            return MessageParseResult.Ignored(IgnoreReason.PAYMENT_REQUEST)
        }
        if (Regex("(?i)\\b(get|win|won|earn|scratch|offer|upto|up to|claim|unlock)\\b").containsMatchIn(lower) &&
            !Regex("(?i)\\b(paid|received|sent|debited|credited)\\b").containsMatchIn(lower)
        ) {
            return MessageParseResult.Ignored(IgnoreReason.PROMOTIONAL)
        }
        val status = TextSignals.status(lower)
        for ((re, direction) in upiPatterns) {
            val m = re.find(combined) ?: continue
            val amountGroup = if (re === paidYouRe) 2 else 1
            val nameGroup = if (re === paidYouRe) 1 else 2
            val amount = Amounts.toMinor(m.groupValues[amountGroup]) ?: continue
            if (amount == 0L) continue
            val rawName = MerchantNormalizer.clean(m.groupValues[nameGroup])
            val vpa = TextSignals.findVpa(combined)
            val normalized = MerchantNormalizer.normalize(rawName, vpa)
            val cleaned = TextSignals.stripNonEvents(lower)
            val classification = TextSignals.classify(cleaned, direction, isCreditCard = false)
            val accounts = TextSignals.accounts(combined)
            var type = classification.type
            normalized?.known?.type?.let { if (type == TxnType.EXPENSE || type == TxnType.INCOME) type = it }
            val txn = ParsedTransaction(
                amountMinor = amount,
                direction = classification.direction,
                type = type,
                status = status,
                timestamp = postedAt,
                merchant = normalized?.name,
                merchantRaw = rawName,
                counterpartyIsPerson = normalized?.isPerson == true,
                accountLast4 = accounts.own,
                institution = null,
                paymentMethod = PaymentMethod.UPI,
                reference = TextSignals.reference(combined),
                upiId = vpa,
                categoryHint = classification.categoryHint,
                description = combined.take(300),
                confidence = if (normalized != null) 0.72f else 0.55f,
                parser = "${app.name} notification",
                notes = listOf("Read from ${app.name} notification") + classification.notes,
            )
            return MessageParseResult.Transaction(txn)
        }
        // Some payment apps also post bank-style text.
        return sms.parseText(combined, postedAt, null, promotionalHeader = false, parserName = "${app.name} notification")
    }

    companion object {
        private const val CUR = "(?:rs\\.?|inr|₹)\\s*"
        private const val AMT = "([0-9][0-9,]*(?:\\.[0-9]{1,2})?)"
        private const val END = "(?=\\s+(?:using|via|from|on|in|with|to your)\\b|\\s*[|.!,]|$)"

        private val paidToRe = Regex("(?i)(?:you\\s+)?(?:have\\s+)?(?:paid|sent)\\s+$CUR$AMT\\s+(?:successfully\\s+)?to\\s+(.+?)$END")
        private val amtPaidToRe = Regex("(?i)$CUR$AMT\\s+(?:paid|sent)\\s+(?:successfully\\s+)?to\\s+(.+?)$END")
        private val paymentToRe = Regex("(?i)payment of $CUR$AMT\\s+to\\s+(.+?)\\s+(?:was\\s+|is\\s+|has been\\s+)?(?:successful|completed|done)")
        private val receivedFromRe = Regex("(?i)(?:you\\s+)?(?:have\\s+)?received\\s+$CUR$AMT\\s+from\\s+(.+?)$END")
        private val amtReceivedFromRe = Regex("(?i)$CUR$AMT\\s+received\\s+from\\s+(.+?)$END")
        private val paidYouRe = Regex("(?i)^(?:[^|]*\\|\\s*)?(.+?)\\s+(?:has\\s+)?(?:paid|sent)\\s+you\\s+$CUR$AMT")

        private val upiPatterns: List<Pair<Regex, Direction>> = listOf(
            paidToRe to Direction.DEBIT,
            amtPaidToRe to Direction.DEBIT,
            paymentToRe to Direction.DEBIT,
            receivedFromRe to Direction.CREDIT,
            amtReceivedFromRe to Direction.CREDIT,
            paidYouRe to Direction.CREDIT,
        )

        val apps: Map<String, App> = mapOf(
            "com.google.android.apps.nbu.paisa.user" to App("Google Pay", AppKind.UPI),
            "com.phonepe.app" to App("PhonePe", AppKind.UPI),
            "net.one97.paytm" to App("Paytm", AppKind.UPI),
            "in.org.npci.upiapp" to App("BHIM", AppKind.UPI),
            "in.amazon.mShop.android.shopping" to App("Amazon Pay", AppKind.UPI),
            "com.dreamplug.androidapp" to App("CRED", AppKind.UPI),
            "com.mobikwik_new" to App("MobiKwik", AppKind.UPI),
            "com.freecharge.android" to App("Freecharge", AppKind.UPI),
            "money.super.payments" to App("super.money", AppKind.UPI),
            "com.naviapp" to App("Navi", AppKind.UPI),
            "money.jupiter" to App("Jupiter", AppKind.BANK),
            "com.snapwork.hdfc" to App("HDFC Bank", AppKind.BANK),
            "com.sbi.lotusintouch" to App("SBI", AppKind.BANK),
            "com.sbi.upi" to App("SBI", AppKind.BANK),
            "com.csam.icici.bank.imobile" to App("ICICI Bank", AppKind.BANK),
            "com.axis.mobile" to App("Axis Bank", AppKind.BANK),
            "com.msf.kbank.mobile" to App("Kotak Bank", AppKind.BANK),
            "com.idfcfirstbank.optimus" to App("IDFC FIRST Bank", AppKind.BANK),
            "com.fedmobile" to App("Federal Bank", AppKind.BANK),
            "com.Version1" to App("PNB", AppKind.BANK),
            "com.bankofbaroda.mconnect" to App("Bank of Baroda", AppKind.BANK),
            "com.canarabank.mobility" to App("Canara Bank", AppKind.BANK),
            "com.google.android.apps.messaging" to App("Messages", AppKind.SMS),
            "com.samsung.android.messaging" to App("Samsung Messages", AppKind.SMS),
            "com.android.mms" to App("Messaging", AppKind.SMS),
            "com.android.messaging" to App("Messaging", AppKind.SMS),
            "com.oneplus.mms" to App("OnePlus Messages", AppKind.SMS),
        )

        val smsAppPackages: Set<String> = apps.filterValues { it.kind == AppKind.SMS }.keys
    }
}
