package com.expensetracker.app.domain.parser

import com.expensetracker.app.domain.model.DefaultCategories as C
import com.expensetracker.app.domain.model.TxnType

data class KnownMerchant(
    val name: String,
    val category: String,
    val aliases: List<String>,
    val type: TxnType? = null,
    val isSubscription: Boolean = false,
)

/**
 * Offline merchant dictionary for Indian payments. Aliases are matched against the
 * lower-cased, punctuation-free text ("SWIGGY BANGALORE" -> "swiggybangalore"); aliases of
 * four characters or fewer must match a whole word so "ola" does not match "motorola".
 */
object MerchantDirectory {

    private fun m(name: String, category: String, vararg aliases: String) =
        KnownMerchant(name, category, aliases.toList())

    private fun sub(name: String, vararg aliases: String) =
        KnownMerchant(name, C.SUBSCRIPTIONS, aliases.toList(), isSubscription = true)

    private fun typed(name: String, category: String, type: TxnType, vararg aliases: String) =
        KnownMerchant(name, category, aliases.toList(), type = type)

    val merchants: List<KnownMerchant> = listOf(
        // Food delivery & dining
        m("Swiggy Instamart", C.GROCERIES, "swiggyinstamart", "instamart"),
        m("Swiggy", C.FOOD, "swiggy", "bundltechnologies"),
        m("Zomato", C.FOOD, "zomato", "zomatoltd", "zomatomedia"),
        m("EatSure", C.FOOD, "eatsure", "rebelfoods", "faasos", "behrouz"),
        m("Domino's", C.FOOD, "dominos", "jubilantfoodworks"),
        m("Pizza Hut", C.FOOD, "pizzahut"),
        m("McDonald's", C.FOOD, "mcdonalds", "mcdonald", "hardcastle", "westlife"),
        m("KFC", C.FOOD, "kfc"),
        m("Burger King", C.FOOD, "burgerking"),
        m("Subway", C.FOOD, "subway"),
        m("Starbucks", C.FOOD, "starbucks", "tatastarbucks"),
        m("Cafe Coffee Day", C.FOOD, "cafecoffeeday", "ccd", "coffeeday"),
        m("Chaayos", C.FOOD, "chaayos"),
        m("Third Wave Coffee", C.FOOD, "thirdwave"),
        m("Blue Tokai", C.FOOD, "bluetokai"),
        m("Haldiram's", C.FOOD, "haldiram"),
        m("Barbeque Nation", C.FOOD, "barbequenation", "barbeque"),
        m("Theobroma", C.FOOD, "theobroma"),
        m("Baskin Robbins", C.FOOD, "baskinrobbins"),
        m("Wow! Momo", C.FOOD, "wowmomo"),
        m("Box8", C.FOOD, "box8"),
        m("EatClub", C.FOOD, "eatclub"),
        // Groceries & quick commerce
        m("Blinkit", C.GROCERIES, "blinkit", "grofers"),
        m("Zepto", C.GROCERIES, "zepto", "kiranakart"),
        m("BigBasket", C.GROCERIES, "bigbasket", "bbnow", "bbdaily", "supermarketgrocery", "innovativeretail"),
        m("DMart", C.GROCERIES, "dmart", "avenuesupermarts", "avenuesupermart"),
        m("JioMart", C.GROCERIES, "jiomart"),
        m("Reliance Fresh", C.GROCERIES, "reliancefresh", "reliancesmart", "smartbazaar", "smartpoint"),
        m("More Supermarket", C.GROCERIES, "moresupermarket", "moreretail", "morestore"),
        m("Spencer's", C.GROCERIES, "spencers"),
        m("Nature's Basket", C.GROCERIES, "naturesbasket"),
        m("Ratnadeep", C.GROCERIES, "ratnadeep"),
        m("Star Bazaar", C.GROCERIES, "starbazaar"),
        m("Lulu Hypermarket", C.GROCERIES, "luluhyper", "lulumall"),
        m("Country Delight", C.GROCERIES, "countrydelight"),
        m("Milkbasket", C.GROCERIES, "milkbasket"),
        m("Licious", C.GROCERIES, "licious", "delightfulgourmet"),
        m("FreshToHome", C.GROCERIES, "freshtohome"),
        m("Dunzo", C.GROCERIES, "dunzo"),
        // Shopping
        sub("Amazon Prime", "amazonprime", "primevideo", "primemembership"),
        m("Amazon", C.SHOPPING, "amazon", "amzn", "amazonpay", "amazonseller"),
        m("Flipkart", C.SHOPPING, "flipkart", "fkrt", "flipkartinternet", "shopsy"),
        m("Myntra", C.SHOPPING, "myntra"),
        m("Ajio", C.SHOPPING, "ajio"),
        m("Meesho", C.SHOPPING, "meesho", "fashnear"),
        m("Nykaa", C.SHOPPING, "nykaa", "fsnecommerce"),
        m("Tata CLiQ", C.SHOPPING, "tatacliq"),
        m("Snapdeal", C.SHOPPING, "snapdeal"),
        m("FirstCry", C.SHOPPING, "firstcry", "brainbees"),
        m("Lenskart", C.SHOPPING, "lenskart"),
        m("Decathlon", C.SHOPPING, "decathlon"),
        m("Croma", C.SHOPPING, "croma", "infinitiretail"),
        m("Reliance Digital", C.SHOPPING, "reliancedigital"),
        m("Vijay Sales", C.SHOPPING, "vijaysales"),
        m("IKEA", C.SHOPPING, "ikea"),
        m("Pepperfry", C.SHOPPING, "pepperfry"),
        m("Westside", C.SHOPPING, "westside", "trentlimited"),
        m("Max Fashion", C.SHOPPING, "maxfashion", "maxretail"),
        m("Lifestyle", C.SHOPPING, "lifestyleinternational", "lifestylestores"),
        m("Pantaloons", C.SHOPPING, "pantaloons"),
        m("Shoppers Stop", C.SHOPPING, "shoppersstop"),
        m("Zara", C.SHOPPING, "zara", "inditex"),
        m("H&M", C.SHOPPING, "hennesmauritz", "handm"),
        m("Uniqlo", C.SHOPPING, "uniqlo"),
        m("Tanishq", C.SHOPPING, "tanishq", "titancompany"),
        m("CaratLane", C.SHOPPING, "caratlane"),
        m("Purplle", C.SHOPPING, "purplle"),
        m("Mamaearth", C.SHOPPING, "mamaearth", "honasa"),
        m("Apple Store", C.SHOPPING, "appleindia", "applestore"),
        // Transport
        m("Uber", C.TRANSPORT, "uber", "uberindia", "ubertrip", "uberrides"),
        m("Ola", C.TRANSPORT, "olacabs", "ola", "anitechnologies", "olamoney"),
        m("Rapido", C.TRANSPORT, "rapido", "roppen"),
        m("Namma Yatri", C.TRANSPORT, "nammayatri"),
        m("BluSmart", C.TRANSPORT, "blusmart"),
        m("Metro", C.TRANSPORT, "dmrc", "delhimetro", "bmrcl", "nammametro", "mumbaimetro", "mmrcl", "mmopl", "hmrl", "cmrl", "chennaimetro", "kochimetro", "kmrl"),
        m("Yulu", C.TRANSPORT, "yulu"),
        m("State Transport Bus", C.TRANSPORT, "ksrtc", "msrtc", "tsrtc", "apsrtc", "gsrtc", "upsrtc", "rsrtc"),
        // Fuel, tolls
        m("Indian Oil", C.FUEL, "indianoil", "iocl", "indianoilcorp"),
        m("HP Petrol", C.FUEL, "hpcl", "hindustanpetroleum", "hppetrol"),
        m("Bharat Petroleum", C.FUEL, "bpcl", "bharatpetroleum"),
        m("Shell", C.FUEL, "shellindia", "shellpetrol", "shell"),
        m("Nayara Energy", C.FUEL, "nayara"),
        m("Jio-bp", C.FUEL, "jiobp", "reliancebp"),
        m("FASTag", C.TOLLS, "fastag", "netc", "ihmcl", "tollplaza"),
        // Travel
        m("IRCTC", C.TRAVEL, "irctc", "railticket"),
        m("MakeMyTrip", C.TRAVEL, "makemytrip", "mmt"),
        m("Goibibo", C.TRAVEL, "goibibo", "ibibo"),
        m("Cleartrip", C.TRAVEL, "cleartrip"),
        m("Yatra", C.TRAVEL, "yatraonline", "yatra"),
        m("ixigo", C.TRAVEL, "ixigo", "lebtravel"),
        m("EaseMyTrip", C.TRAVEL, "easemytrip"),
        m("ConfirmTkt", C.TRAVEL, "confirmtkt"),
        m("RedBus", C.TRAVEL, "redbus"),
        m("AbhiBus", C.TRAVEL, "abhibus"),
        m("IndiGo", C.TRAVEL, "indigo", "interglobe", "goindigo"),
        m("Air India", C.TRAVEL, "airindia", "airindiaexpress"),
        m("Akasa Air", C.TRAVEL, "akasa"),
        m("SpiceJet", C.TRAVEL, "spicejet"),
        m("Booking.com", C.TRAVEL, "bookingcom"),
        m("Agoda", C.TRAVEL, "agoda"),
        m("Airbnb", C.TRAVEL, "airbnb"),
        m("OYO", C.TRAVEL, "oyo", "oyorooms", "oravel"),
        m("Zoomcar", C.TRAVEL, "zoomcar"),
        // Utilities & bills
        m("Electricity Bill", C.ELECTRICITY, "bescom", "msedcl", "mahadiscom", "mahavitaran", "tatapower", "adanielectricity", "bsesrajdhani", "bsesyamuna", "bses", "tangedco", "tneb", "kseb", "uppcl", "cesc", "tsspdcl", "tgspdcl", "apspdcl", "apepdcl", "torrentpower", "wbsedcl", "pspcl", "dhbvn", "uhbvn", "jvvnl", "avvnl", "jdvvnl", "mppkvvcl", "mpmkvvcl", "cspdcl", "hescom", "gescom", "mescom", "chescom", "tpddl", "ndpl", "nbpdcl", "sbpdcl", "jbvnl", "msedc"),
        m("Gas Bill", C.UTILITIES, "mahanagargas", "indraprasthagas", "igl", "adanigas", "adanitotalgas", "gujaratgas", "gailgas", "hpgas", "indane", "bharatgas"),
        m("Water Bill", C.UTILITIES, "bwssb", "djb", "delhijalboard", "hmwssb", "waterboard"),
        m("JioFiber", C.INTERNET, "jiofiber"),
        m("Airtel Broadband", C.INTERNET, "airtelbroadband", "airtelxstreamfiber", "airtelfiber"),
        m("ACT Fibernet", C.INTERNET, "actfibernet", "actcorp", "atriaconvergence"),
        m("Hathway", C.INTERNET, "hathway"),
        m("Excitel", C.INTERNET, "excitel"),
        m("Tata Play Fiber", C.INTERNET, "tataplayfiber"),
        m("Jio", C.MOBILE, "jio", "reliancejio", "jioprepaid", "jiopostpaid"),
        m("Airtel", C.MOBILE, "airtel", "bhartiairtel", "airtelprepaid", "airtelpostpaid"),
        m("Vi", C.MOBILE, "vodafoneidea", "vodafone", "myvi", "idea"),
        m("BSNL", C.MOBILE, "bsnl", "mtnl"),
        m("Tata Play", C.ENTERTAINMENT, "tataplay", "tatasky"),
        m("Dish TV", C.ENTERTAINMENT, "dishtv", "d2h", "videocond2h"),
        // Subscriptions
        sub("Netflix", "netflix"),
        sub("Spotify", "spotify"),
        sub("JioHotstar", "hotstar", "disneyplus", "jiohotstar", "jiocinema", "novidigital", "staranddisney"),
        sub("SonyLIV", "sonyliv", "sonypictures"),
        sub("ZEE5", "zee5"),
        sub("YouTube Premium", "youtubepremium", "youtube", "googleyoutube"),
        sub("Google Play", "googleplay", "playstore", "googleservices"),
        sub("Google One", "googleone", "googlestorage"),
        sub("Apple Services", "applecombill", "itunes", "appleservices", "icloud", "applemusic"),
        sub("ChatGPT", "openai", "chatgpt"),
        sub("Claude", "anthropic", "claudeai"),
        sub("Microsoft 365", "microsoft", "msbill", "xbox"),
        sub("Adobe", "adobe"),
        sub("LinkedIn Premium", "linkedin"),
        sub("Audible", "audible"),
        sub("JioSaavn", "jiosaavn", "saavn"),
        sub("Gaana", "gaana"),
        sub("Wynk", "wynk"),
        sub("Duolingo", "duolingo"),
        sub("Discovery+", "discoveryplus"),
        sub("SunNXT", "sunnxt"),
        // Entertainment
        m("BookMyShow", C.ENTERTAINMENT, "bookmyshow", "bigtreeentertainment"),
        m("PVR INOX", C.ENTERTAINMENT, "pvr", "inox", "pvrinox", "pvrcinemas"),
        m("Cinepolis", C.ENTERTAINMENT, "cinepolis"),
        m("District", C.ENTERTAINMENT, "districtapp", "paytminsider", "insiderin"),
        m("Steam", C.ENTERTAINMENT, "steampowered", "steamgames"),
        m("PlayStation", C.ENTERTAINMENT, "playstation", "sonyinteractive"),
        // Healthcare
        m("Apollo", C.HEALTH, "apollo", "apollopharmacy", "apollo247", "apollohospital"),
        m("PharmEasy", C.HEALTH, "pharmeasy", "axelia"),
        m("Tata 1mg", C.HEALTH, "1mg", "tata1mg"),
        m("Netmeds", C.HEALTH, "netmeds"),
        m("MedPlus", C.HEALTH, "medplus", "optival"),
        m("Practo", C.HEALTH, "practo"),
        m("Wellness Forever", C.HEALTH, "wellnessforever"),
        m("Truemeds", C.HEALTH, "truemeds"),
        m("HealthKart", C.HEALTH, "healthkart"),
        m("Diagnostics Lab", C.HEALTH, "thyrocare", "lalpathlabs", "drlal", "metropolis", "redcliffe", "healthians"),
        m("Hospital", C.HEALTH, "manipalhospital", "fortis", "maxhealthcare", "narayanahealth", "asterdm", "medanta"),
        // Education
        m("Udemy", C.EDUCATION, "udemy"),
        m("Coursera", C.EDUCATION, "coursera"),
        m("BYJU'S", C.EDUCATION, "byjus", "thinkandlearn"),
        m("Unacademy", C.EDUCATION, "unacademy", "sortinghat"),
        m("Physics Wallah", C.EDUCATION, "physicswallah"),
        m("upGrad", C.EDUCATION, "upgradedu", "upgradeducation"),
        m("Scaler", C.EDUCATION, "scaler", "interviewbit"),
        // Insurance
        m("LIC", C.INSURANCE, "licofindia", "lifeinsurancecorp", "lic"),
        m("HDFC Life", C.INSURANCE, "hdfclife", "hdfcstandardlife"),
        m("ICICI Prudential", C.INSURANCE, "icicipru", "iciciprudential"),
        m("ICICI Lombard", C.INSURANCE, "icicilombard"),
        m("SBI Life", C.INSURANCE, "sbilife"),
        m("Max Life", C.INSURANCE, "maxlife", "axismaxlife"),
        m("Tata AIA", C.INSURANCE, "tataaia"),
        m("Tata AIG", C.INSURANCE, "tataaig"),
        m("Bajaj Allianz", C.INSURANCE, "bajajallianz"),
        m("Star Health", C.INSURANCE, "starhealth"),
        m("Niva Bupa", C.INSURANCE, "nivabupa", "maxbupa"),
        m("Care Health", C.INSURANCE, "careinsurance", "carehealth"),
        m("HDFC ERGO", C.INSURANCE, "hdfcergo"),
        m("Acko", C.INSURANCE, "acko"),
        m("Digit Insurance", C.INSURANCE, "godigit", "digitinsurance"),
        m("Policybazaar", C.INSURANCE, "policybazaar", "pbfintech"),
        // Investments
        typed("Zerodha", C.INVESTMENTS, TxnType.INVESTMENT, "zerodha"),
        typed("Groww", C.INVESTMENTS, TxnType.INVESTMENT, "groww", "nextbillion"),
        typed("Upstox", C.INVESTMENTS, TxnType.INVESTMENT, "upstox", "rksv"),
        typed("Angel One", C.INVESTMENTS, TxnType.INVESTMENT, "angelone", "angelbroking"),
        typed("Kuvera", C.INVESTMENTS, TxnType.INVESTMENT, "kuvera"),
        typed("Paytm Money", C.INVESTMENTS, TxnType.INVESTMENT, "paytmmoney"),
        typed("ET Money", C.INVESTMENTS, TxnType.INVESTMENT, "etmoney"),
        typed("Smallcase", C.INVESTMENTS, TxnType.INVESTMENT, "smallcase"),
        typed("Mutual Fund SIP", C.INVESTMENTS, TxnType.INVESTMENT, "iccl", "indianclearingcorp", "indianclearing", "nseclearing", "bsestarmf", "camsonline", "kfintech", "mfutilities", "mutualfund", "mutual fund"),
        typed("NPS", C.INVESTMENTS, TxnType.INVESTMENT, "npstrust", "nps", "protean"),
        typed("PPF", C.INVESTMENTS, TxnType.INVESTMENT, "ppf"),
        // Loans & card bills
        m("Bajaj Finance", C.EMI, "bajajfinance", "bajajfinserv"),
        m("Home Credit", C.EMI, "homecredit"),
        m("HDB Financial", C.EMI, "hdbfinancial", "hdbfs"),
        m("Tata Capital", C.EMI, "tatacapital"),
        m("Muthoot Finance", C.EMI, "muthoot"),
        m("KreditBee", C.EMI, "kreditbee"),
        m("Navi", C.EMI, "navi", "navifinserv"),
        m("LazyPay", C.EMI, "lazypay"),
        m("Simpl", C.EMI, "getsimpl", "simplpay"),
        typed("CRED", C.CC_BILL, TxnType.CREDIT_CARD_PAYMENT, "cred", "credclub", "dreamplug"),
        // Rent, personal care, services
        m("NoBroker", C.RENT, "nobroker"),
        m("Urban Company", C.PERSONAL_CARE, "urbancompany", "urbanclap"),
        m("Cult.fit", C.PERSONAL_CARE, "cultfit", "curefit"),
        m("Naturals Salon", C.PERSONAL_CARE, "naturalssalon"),
        m("Lakme Salon", C.PERSONAL_CARE, "lakmesalon"),
    )

    /** Payment gateways that sit in front of the real merchant; never a merchant themselves. */
    val aggregators = setOf(
        "razorpay", "billdesk", "payu", "cashfree", "ccavenue", "paytm", "phonepe", "gpay", "googlepay",
        "bharatpe", "pinelabs", "ezetap", "mswipe", "juspay", "airpay", "instamojo", "easebuzz", "worldline",
        "paypal", "stripe", "upi", "vpa", "bhim", "mobikwik", "freecharge",
    )

    private val index: List<Pair<String, KnownMerchant>> =
        merchants.flatMap { merchant -> merchant.aliases.map { compact(it) to merchant } }
            .sortedByDescending { it.first.length }

    fun match(text: String?): KnownMerchant? {
        if (text.isNullOrBlank()) return null
        val compactText = compact(text)
        val tokens = tokens(text)
        for ((alias, merchant) in index) {
            if (alias.length <= 4) {
                if (alias in tokens) return merchant
            } else if (compactText.contains(alias)) {
                return merchant
            }
        }
        return null
    }

    fun byName(name: String): KnownMerchant? = merchants.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun compact(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    fun tokens(s: String): Set<String> = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()
}
