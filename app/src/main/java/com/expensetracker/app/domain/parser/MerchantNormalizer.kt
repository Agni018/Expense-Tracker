package com.expensetracker.app.domain.parser

object MerchantNormalizer {

    data class Normalized(
        val name: String,
        val known: KnownMerchant?,
        val isPerson: Boolean,
    ) {
        val key: String get() = key(name)
    }

    private val titlePrefix = Regex("(?i)^(mr|mrs|ms|miss|shri|smt|sri|dr|kumari|m/s)\\.?\\s+")
    private val businessWords = setOf(
        "store", "stores", "mart", "shop", "shoppe", "traders", "trading", "enterprises", "enterprise", "foods", "food",
        "restaurant", "cafe", "hotel", "medical", "medicals", "pharma", "pharmacy", "agency", "agencies", "services",
        "solutions", "technologies", "tech", "pvt", "private", "ltd", "limited", "llp", "india", "kirana", "general",
        "super", "supermarket", "market", "centre", "center", "bakery", "bakers", "sweets", "petroleum", "fuels",
        "motors", "auto", "automobiles", "garage", "hospital", "clinic", "labs", "lab", "diagnostics", "salon", "studio",
        "tea", "coffee", "juice", "dhaba", "kitchen", "corporation", "corp", "company", "co", "industries", "mall",
        "electronics", "mobiles", "telecom", "communications", "fashion", "textiles", "garments", "jewellers", "jewellery",
        "chemist", "chemists", "provision", "provisions", "dairy", "milk", "fruits", "vegetables", "bank", "finance",
        "insurance", "travels", "tours", "cabs", "logistics", "school", "college", "academy", "institute", "society",
        "trust", "foundation", "bazaar", "bazar", "wines", "bar", "restro", "biryani", "caterers", "hub", "point",
        "payments", "merchant", "online", "retail", "wholesale", "distributors", "associates", "infra", "systems",
    )
    private val trailingNoise = Regex(
        "(?i)[\\s,.-]+(pvt\\.?\\s*ltd\\.?|private\\s+limited|limited|ltd\\.?|llp|inc\\.?|india|in|bangalore|bengaluru|mumbai|delhi|new delhi|chennai|hyderabad|pune|kolkata|gurgaon|gurugram|noida|ahmedabad|jaipur|lucknow|kochi|online|upi)$",
    )
    private val qrVpaRe = Regex(
        "(?i)^(paytmqr|paytm-|q\\d{6,}|bharatpe|gpay-|upiqr|yespay|ezetap|pinelabs|mab\\.|mswipe|rzp|razorpay|cf\\.|cashfree|payu|bqr|phonepemerchant|stq|m\\d{8,}|ombk\\.)",
    )
    private val vpaHandles = mapOf(
        "paytm" to "Paytm", "ptys" to "Paytm", "ptyes" to "Paytm", "pthdfc" to "Paytm", "ptaxis" to "Paytm", "ptsbi" to "Paytm",
        "ybl" to "PhonePe", "ibl" to "PhonePe", "axl" to "PhonePe",
        "okaxis" to "Google Pay", "okhdfcbank" to "Google Pay", "okicici" to "Google Pay", "oksbi" to "Google Pay",
        "upi" to "BHIM",
    )

    /** Normalizes a payee/payer name and/or UPI id into a display name. */
    fun normalize(raw: String?, vpa: String? = null, p2pHint: Boolean = false): Normalized? {
        val cleanedRaw = raw?.let { clean(it) }?.takeIf { it.isNotBlank() && !isGeneric(it) }
        MerchantDirectory.match(cleanedRaw)?.let { return Normalized(it.name, it, false) }
        vpa?.let { MerchantDirectory.match(it.substringBefore('@')) }?.let { return Normalized(it.name, it, false) }

        if (cleanedRaw != null && !looksLikeVpa(cleanedRaw)) {
            if (isOnlyAggregator(cleanedRaw)) return Normalized("Online payment (${titleCase(cleanedRaw)})", null, false)
            val person = !hasBusinessWord(cleanedRaw) &&
                (titlePrefix.containsMatchIn(raw!!.trim()) || p2pHint || looksLikeHumanName(cleanedRaw) && vpa?.let { personalVpa(it) } != false)
            // "Sri"/"Mr" are dropped from people's names but kept in shop names ("Sri Balaji Medicals").
            val display = titleCase(if (person) titlePrefix.replace(cleanedRaw, "") else cleanedRaw)
            return Normalized(display, null, person)
        }
        val upi = vpa ?: cleanedRaw?.takeIf { looksLikeVpa(it) } ?: return null
        return fromVpa(upi, p2pHint)
    }

    fun key(name: String): String = MerchantDirectory.compact(name)

    private fun fromVpa(vpa: String, p2pHint: Boolean): Normalized {
        val local = vpa.substringBefore('@').lowercase()
        val handle = vpa.substringAfter('@', "").lowercase()
        if (qrVpaRe.containsMatchIn(local)) {
            val app = vpaHandles[handle] ?: local.takeWhile { it.isLetter() }.replaceFirstChar { it.uppercase() }.ifBlank { "UPI" }
            return Normalized("$app QR merchant", null, false)
        }
        val digits = local.filter { it.isDigit() }
        if (digits.length >= 10 && local.length - digits.length <= 2) {
            return Normalized("UPI ${digits.take(2)}xxxxx${digits.takeLast(3)}", null, true)
        }
        val words = local.replace(Regex("[._\\-]+"), " ").replace(Regex("\\d+"), " ").trim()
        if (words.isBlank()) return Normalized(vpa.lowercase(), null, p2pHint)
        val display = titleCase(words)
        val person = p2pHint || personalVpa(vpa) && !hasBusinessWord(display)
        return Normalized(display, null, person)
    }

    fun clean(raw: String): String {
        var s = raw.replace(' ', ' ').trim()
        s = s.replace(Regex("(?i)^(vpa|upi|to|from|at|by|towards|info)[:\\s-]+"), "")
        // "ACME CORP - SALARY OCT 2026": the payee is the part before a spaced dash.
        s.split(" - ").firstOrNull { part -> part.count(Char::isLetter) >= 2 }?.let { s = it }
        s = s.replace(Regex("[*#|]+"), " ")
        s = s.replace(Regex("\\s{2,}"), " ").trim().trim('.', ',', '-', ':', ';', '(', ')', '/', '\'', '"')
        repeat(2) { s = trailingNoise.replace(s, "").trim() }
        return s.take(48).trim()
    }

    /**
     * In mixed-case text short all-caps words are acronyms ("KFC"); when the whole text is
     * upper case (bank alerts, receipts) only well-known acronyms keep their capitals.
     */
    fun titleCase(s: String): String {
        val allCaps = s.none { it.isLowerCase() }
        return s.split(' ').filter { it.isNotBlank() }.joinToString(" ") { word ->
            val acronym = word.length <= 3 && word.all { it.isUpperCase() || it.isDigit() } && word.any { it.isLetter() } &&
                word.lowercase() !in commonShortWords
            when {
                word.uppercase() in knownAcronyms -> word.uppercase()
                acronym && !allCaps -> word
                else -> word.lowercase().replaceFirstChar { it.titlecase() }
            }
        }
    }

    private val commonShortWords = setOf("the", "and", "for", "pay", "via", "on", "of", "to", "mr", "ms", "dr")
    private val knownAcronyms = setOf(
        "ATM", "UPI", "KFC", "PVR", "LIC", "SBI", "HDFC", "ICICI", "IRCTC", "BSNL", "IOCL", "HPCL", "BPCL", "CCD", "DTH",
        "EMI", "NEFT", "IMPS", "RTGS", "QR", "IT", "LPG", "CNG", "ICT", "IKEA", "OYO", "HP", "BP", "MTR", "GST",
    )

    private fun isGeneric(s: String): Boolean {
        val l = s.lowercase()
        return l in setOf("you", "your", "a/c", "ac", "account", "the", "your a/c", "self", "na", "n/a", "null", "beneficiary", "upi", "imps", "neft")
            || Regex("(?i)^(a/?c|acct|account)\\b").containsMatchIn(l)
            || l.all { it.isDigit() || it == ' ' || it == '-' || it == '/' }
    }

    private fun isOnlyAggregator(s: String): Boolean = MerchantDirectory.compact(s) in MerchantDirectory.aggregators

    private fun looksLikeVpa(s: String) = Regex("^[\\w.\\-]{2,}@[a-zA-Z][\\w]{1,}$").matches(s.trim())

    private fun hasBusinessWord(s: String): Boolean = MerchantDirectory.tokens(s).any { it in businessWords }

    private fun personalVpa(vpa: String): Boolean {
        val handle = vpa.substringAfter('@', "").lowercase()
        return handle.startsWith("ok") || handle in setOf("ybl", "ibl", "axl", "paytm", "apl", "upi", "sbi", "icici", "hdfcbank", "axisbank", "kotak", "yesbank", "fbl", "idfcbank", "jupiteraxis", "slc", "waaxis", "wahdfcbank", "wasbi", "waicici")
    }

    /** Two to four alphabetic words, each 2–15 letters, without business words. */
    fun looksLikeHumanName(s: String): Boolean {
        val words = s.trim().split(Regex("\\s+"))
        if (words.size !in 2..4) return false
        if (!words.all { w -> w.length in 1..15 && w.all { it.isLetter() || it == '.' } }) return false
        if (MerchantDirectory.match(s) != null) return false
        return !hasBusinessWord(s)
    }
}
