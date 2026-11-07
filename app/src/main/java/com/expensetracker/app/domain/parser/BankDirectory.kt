package com.expensetracker.app.domain.parser

import com.expensetracker.app.domain.model.Direction

/** Sender header and bank-name recognition for Indian SMS (TRAI headers like "VM-HDFCBK-S"). */
object BankDirectory {

    data class SenderInfo(
        val raw: String?,
        val code: String?,
        val institution: String?,
        val isPersonalNumber: Boolean,
        val isPromotionalHeader: Boolean,
    )

    private val senderCodes: List<Pair<Regex, String>> = listOf(
        "HDFCBK|HDFCBN|HDFCCC|HDFCB" to "HDFC Bank",
        "SBICRD|SBICARD|SBICAR" to "SBI Card",
        "SBIUPI|SBIINB|SBIBNK|CBSSBI|SBMSMS|ATMSBI|SBIPSG|SBIOTP|SBYONO|SBI" to "SBI",
        "ICICIB|ICICIT|ICICIC|ICICI" to "ICICI Bank",
        "AXISBK|AXISCC|AXISB|AXIS" to "Axis Bank",
        "KOTAKB|KOTAK|KMBL" to "Kotak Bank",
        "PNBSMS|PNBBNK|PNB" to "PNB",
        "BOBTXN|BOBSMS|BARODA|BOBCRD" to "Bank of Baroda",
        "CANBNK|CANARA" to "Canara Bank",
        "UNIONB|UBINBK|UBOI" to "Union Bank",
        "IDFCFB|IDFCBK|IDFC" to "IDFC FIRST Bank",
        "YESBNK|YESBK|YESBNK" to "Yes Bank",
        "INDUSB|INDUSIND|IBLBNK" to "IndusInd Bank",
        "FEDBNK|FEDERAL|FEDFIB|FEDJPR|JUPITR" to "Federal Bank",
        "AUBANK|AUSFBL" to "AU Bank",
        "RBLBNK|RBLCRD|RBLCC" to "RBL Bank",
        "IDBIBK|IDBI" to "IDBI Bank",
        "BOIIND|BOISMS|BOIBNK" to "Bank of India",
        "CENTBK|CBOI" to "Central Bank",
        "IOBCHN|IOBBNK|IOB" to "Indian Overseas Bank",
        "INDBNK|INDIAN" to "Indian Bank",
        "UCOBNK|UCO" to "UCO Bank",
        "PAYTMB|PYTMBK|PAYTM" to "Paytm",
        "AIRBNK|AIRTEL" to "Airtel Payments Bank",
        "AMEXIN|AMEX" to "American Express",
        "ONECRD|ONECARD" to "OneCard",
        "SLICEIT|SLICE" to "slice",
        "DBSBNK|DBSIND" to "DBS Bank",
        "SCBANK|STANCH|SCBNK" to "Standard Chartered",
        "HSBCIN|HSBC" to "HSBC",
        "CITIBK|CITI" to "Citi",
        "BANDHN|BANDHAN" to "Bandhan Bank",
        "SIBSMS|SIBANK" to "South Indian Bank",
        "KVBANK|KVB" to "Karur Vysya Bank",
        "CUBANK|CUB" to "City Union Bank",
        "EQUITS|ESFBNK" to "Equitas Bank",
        "UJJIVN" to "Ujjivan Bank",
        "MAHABK|MAHB" to "Bank of Maharashtra",
        "PSBANK" to "Punjab & Sind Bank",
        "JKBANK" to "J&K Bank",
        "KARBNK|KBLBNK" to "Karnataka Bank",
        "TMBANK|TMBL" to "TMB",
        "DCBBNK" to "DCB Bank",
        "NSDLPB|JIOPBK|FINOPB|IPPBNK" to "Payments Bank",
    ).map { (codes, name) -> Regex("^(?:$codes)") to name }

    private val textNames: List<Pair<Regex, String>> = listOf(
        "hdfc bank|hdfc" to "HDFC Bank",
        "sbi card|sbicard" to "SBI Card",
        "state bank|\\bsbi\\b" to "SBI",
        "icici" to "ICICI Bank",
        "axis bank|\\baxis\\b" to "Axis Bank",
        "kotak" to "Kotak Bank",
        "punjab national|\\bpnb\\b" to "PNB",
        "bank of baroda|\\bbob\\b" to "Bank of Baroda",
        "canara" to "Canara Bank",
        "union bank" to "Union Bank",
        "idfc" to "IDFC FIRST Bank",
        "yes bank" to "Yes Bank",
        "indusind" to "IndusInd Bank",
        "federal bank" to "Federal Bank",
        "au small finance|au bank" to "AU Bank",
        "rbl bank" to "RBL Bank",
        "idbi" to "IDBI Bank",
        "bank of india" to "Bank of India",
        "paytm payments bank|paytm bank" to "Paytm",
        "airtel payments bank" to "Airtel Payments Bank",
        "american express|amex" to "American Express",
        "standard chartered" to "Standard Chartered",
        "\\bhsbc\\b" to "HSBC",
        "\\bciti\\b|citibank" to "Citi",
        "\\bdbs\\b" to "DBS Bank",
        "onecard" to "OneCard",
    ).map { (re, name) -> Regex("(?i)$re") to name }

    fun sender(raw: String?): SenderInfo {
        if (raw.isNullOrBlank()) return SenderInfo(raw, null, null, isPersonalNumber = false, isPromotionalHeader = false)
        val trimmed = raw.trim()
        if (Regex("^\\+?\\d[\\d\\s-]{8,14}$").matches(trimmed)) {
            return SenderInfo(raw, null, null, isPersonalNumber = true, isPromotionalHeader = false)
        }
        val parts = trimmed.uppercase().split('-', '_', ' ').filter { it.isNotBlank() }
        val code = parts.filter { it.length >= 3 }.maxByOrNull { it.length } ?: parts.firstOrNull()
        val promotional = parts.size >= 2 && parts.last() == "P"
        // Some phones show headers without the hyphen ("VMHDFCBK"); try again without the 2-letter prefix.
        val institution = code?.let { c ->
            senderCodes.firstOrNull { it.first.containsMatchIn(c) }?.second
                ?: c.takeIf { it.length >= 8 }?.drop(2)?.let { d -> senderCodes.firstOrNull { it.first.containsMatchIn(d) }?.second }
        }
        return SenderInfo(raw, code, institution, isPersonalNumber = false, isPromotionalHeader = promotional)
    }

    fun institutionFromText(text: String): String? = textNames.firstOrNull { it.first.containsMatchIn(text) }?.second

    /**
     * Exact templates for the most common alert formats. Group indices: amount, account,
     * counterparty (name or VPA), date, reference; 0 when the template has no such group.
     */
    data class Template(
        val name: String,
        val regex: Regex,
        val direction: Direction,
        val amount: Int,
        val account: Int,
        val counterparty: Int,
        val date: Int,
        val reference: Int,
    )

    private const val AMT = "([0-9][0-9,]*(?:\\.[0-9]{1,2})?)"

    val templates: List<Template> = listOf(
        Template(
            "HDFC UPI sent",
            Regex("(?i)sent\\s+(?:rs\\.?|inr)\\s*$AMT\\s*\\|?\\s*from\\s+hdfc bank a/?c\\s*[*x]*(\\d{4})\\s*\\|?\\s*to\\s+([^|]+?)\\s*\\|?\\s*on\\s+(\\d{2}/\\d{2}/\\d{2,4})\\s*\\|?\\s*ref\\s+(\\d+)"),
            Direction.DEBIT, 1, 2, 3, 4, 5,
        ),
        Template(
            "HDFC UPI debit",
            Regex("(?i)(?:rs\\.?|inr)\\s*$AMT\\s+(?:has been\\s+)?debited from a/c\\s*[*x]*(\\d{4}) on ([\\d/-]+) to vpa (\\S+?)\\s*\\(upi ref no\\.?\\s*(\\d+)\\)"),
            Direction.DEBIT, 1, 2, 4, 3, 5,
        ),
        Template(
            "HDFC card spend",
            Regex("(?i)spent\\s+(?:rs\\.?|inr)\\s*$AMT\\s+on hdfc bank card\\s*[x*]*(\\d{4})\\s+at\\s+(.+?)\\s+on\\s+(\\d{4}-\\d{2}-\\d{2})"),
            Direction.DEBIT, 1, 2, 3, 4, 0,
        ),
        Template(
            "HDFC UPI received",
            Regex("(?i)money received - inr\\s*$AMT in your hdfc bank a/c\\s*xx(\\d{4}) on ([\\d-]+) by a/c linked to vpa (\\S+) \\(upi ref no\\.?\\s*(\\d+)\\)"),
            Direction.CREDIT, 1, 2, 4, 3, 5,
        ),
        Template(
            "SBI UPI debit",
            Regex("(?i)a/c\\s*x*(\\d{4,5}) debited by\\s*(?:rs\\.?)?\\s*$AMT on date (\\d{1,2}[a-z]{3}\\d{2}) trf to (.+?) refno (\\d+)"),
            Direction.DEBIT, 2, 1, 4, 3, 5,
        ),
        Template(
            "SBI UPI credit",
            Regex("(?i)a/c\\s*x*(\\d{4,5})[- ]?(?:is\\s+)?credited by rs\\.?\\s*$AMT on (\\d{1,2}[a-z]{3}\\d{2})(?:\\s+(?:by|transfer from)\\s+([^(]+?))?\\s*\\(?ref no\\.?\\s*(\\d+)"),
            Direction.CREDIT, 2, 1, 4, 3, 5,
        ),
        Template(
            "ICICI UPI debit",
            Regex("(?i)icici bank acc?t\\s*xx(\\d{3,4}) debited for (?:rs\\.?|inr)\\s*$AMT on ([\\w-]+); (.+?) credited\\.? upi:\\s*(\\d+)"),
            Direction.DEBIT, 2, 1, 4, 3, 5,
        ),
        Template(
            "ICICI card spend",
            Regex("(?i)inr\\s*$AMT spent (?:using|on) icici bank card\\s*xx(\\d{4}) on ([\\w-]+) on (.+?)\\.\\s*avl limit"),
            Direction.DEBIT, 1, 2, 4, 3, 0,
        ),
        Template(
            "Axis UPI debit",
            Regex("(?i)(?:inr|rs\\.?)\\s*$AMT debited\\s*\\|?\\s*a/c no\\.\\s*xx(\\d{4})\\s*\\|?\\s*(\\d{2}-\\d{2}-\\d{2,4})[^|]*\\|?\\s*upi/p2[am]/(\\d+)/([^|]+)"),
            Direction.DEBIT, 1, 2, 5, 3, 4,
        ),
        Template(
            "Kotak UPI sent",
            Regex("(?i)sent rs\\.?\\s*$AMT from kotak bank a/?c\\s*x(\\d{4}) to (\\S+) on ([\\d-]+)\\.?\\s*upi ref:?\\s*(\\d+)"),
            Direction.DEBIT, 1, 2, 3, 4, 5,
        ),
        Template(
            "Kotak UPI received",
            Regex("(?i)received rs\\.?\\s*$AMT in your kotak bank a/?c\\s*x(\\d{4}) from (\\S+) on ([\\d-]+)\\.?\\s*upi ref:?\\s*(\\d+)"),
            Direction.CREDIT, 1, 2, 3, 4, 5,
        ),
    )
}
