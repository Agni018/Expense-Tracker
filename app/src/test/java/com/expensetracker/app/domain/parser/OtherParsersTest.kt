package com.expensetracker.app.domain.parser

import com.expensetracker.app.IST
import com.expensetracker.app.at
import com.expensetracker.app.core.Money
import com.expensetracker.app.domain.model.Direction
import com.expensetracker.app.domain.model.PaymentMethod
import com.expensetracker.app.domain.model.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MerchantNormalizerTest {

    @Test
    fun `swiggy variants map to Swiggy`() {
        listOf("SWIGGYBANGALORE", "SWIGGY UPI", "SWIGGY ONLINE", "swiggy@icici", "Razorpay*Swiggy").forEach {
            assertEquals(it, "Swiggy", MerchantNormalizer.normalize(it)?.name)
        }
    }

    @Test
    fun `instamart is groceries not food`() {
        val n = MerchantNormalizer.normalize("SWIGGY INSTAMART")
        assertEquals("Swiggy Instamart", n?.name)
        assertEquals("Groceries", n?.known?.category)
    }

    @Test
    fun `short aliases need a whole word`() {
        assertEquals("Ola", MerchantNormalizer.normalize("OLA CABS")?.name)
        assertTrue(MerchantNormalizer.normalize("MOTOROLA SERVICE CENTRE")?.name != "Ola")
    }

    @Test
    fun `phone number vpa is a person`() {
        val n = MerchantNormalizer.normalize(null, "9876543210@ybl")!!
        assertTrue(n.isPerson)
        assertEquals("UPI 98xxxxx210", n.name)
    }

    @Test
    fun `qr vpa is a merchant`() {
        val n = MerchantNormalizer.normalize(null, "paytmqr2810050501011abcd@paytm")!!
        assertEquals("Paytm QR merchant", n.name)
        assertTrue(!n.isPerson)
    }

    @Test
    fun `human name vs business`() {
        assertTrue(MerchantNormalizer.normalize("Mr RAHUL SHARMA")!!.isPerson)
        assertTrue(!MerchantNormalizer.normalize("SRI BALAJI MEDICALS")!!.isPerson)
        assertEquals("Sri Balaji Medicals", MerchantNormalizer.normalize("SRI BALAJI MEDICALS PVT LTD")!!.name)
    }
}

class DateAndMoneyTest {
    private val ref = LocalDate.of(2026, 11, 6)

    @Test
    fun `common indian date formats`() {
        val expected = LocalDate.of(2026, 11, 4)
        listOf("04Nov26", "04-Nov-2026", "04 November 2026", "Nov 04, 2026", "2026-11-04", "04/11/26", "04-11-2026", "4.11.2026").forEach {
            assertEquals(it, expected, DateParser.findInText("on $it", ref)?.date)
        }
    }

    @Test
    fun `month first only when detected`() {
        assertEquals(LocalDate.of(2026, 4, 11), DateParser.parseDate("04/11/2026", dayFirst = false, reference = ref))
        assertEquals(LocalDate.of(2026, 11, 4), DateParser.parseDate("04/11/2026", reference = ref))
    }

    @Test
    fun `words are not dates and amounts are not times`() {
        assertNull(DateParser.findInText("Rs 1 Market Road", ref))
        assertNull(DateParser.findTime("Rs 12.50 paid"))
    }

    @Test
    fun `invalid dates are rejected`() {
        assertNull(DateParser.parseDate("31/02/2026", reference = ref))
        assertNull(DateParser.parseDate("not a date", reference = ref))
    }

    @Test
    fun `indian money formatting`() {
        assertEquals("₹1,25,000.50", Money.format(12500050))
        assertEquals("₹450", Money.format(45000))
        assertEquals("₹1,00,00,000", Money.format(1_00_00_000_00))
        assertEquals("-₹99.05", Money.format(-9905))
        assertEquals("₹12.5K", Money.compact(1_250_000))
        assertEquals("₹3.4L", Money.compact(34_000_000))
        assertEquals(125050L, Money.parseToMinor("1,250.50"))
        assertNull(Money.parseToMinor("abc"))
    }

    @Test
    fun `statement amount cells`() {
        assertEquals(Amounts.SignedAmount(125000, Direction.DEBIT, false), Amounts.parseStatementAmount("1,250.00 Dr"))
        assertEquals(Amounts.SignedAmount(45000, null, true), Amounts.parseStatementAmount("(450.00)"))
        assertEquals(Amounts.SignedAmount(9900, Direction.CREDIT, false), Amounts.parseStatementAmount("₹ 99.00Cr"))
        assertEquals(Amounts.SignedAmount(12500000, null, true), Amounts.parseStatementAmount("-1,25,000"))
        assertNull(Amounts.parseStatementAmount("N/A"))
        assertNull(Amounts.parseStatementAmount(""))
    }
}

class NotificationParserTest {
    private val parser = NotificationParser(IST)
    private val gpay = "com.google.android.apps.nbu.paisa.user"
    private val phonepe = "com.phonepe.app"

    @Test
    fun `google pay paid notification`() {
        val r = parser.parse(gpay, "Payment successful", "₹450 paid to Swiggy", at(2026, 11, 4))
        assertTrue(r.toString(), r is MessageParseResult.Transaction)
        val t = (r as MessageParseResult.Transaction).txn
        assertEquals(45000L, t.amountMinor)
        assertEquals("Swiggy", t.merchant)
        assertEquals(PaymentMethod.UPI, t.paymentMethod)
        assertEquals(Direction.DEBIT, t.direction)
    }

    @Test
    fun `phonepe received notification`() {
        val r = parser.parse(phonepe, "Received ₹500 from Rahul Sharma", "Credited to your bank account ••4521", at(2026, 11, 4))
        val t = (r as MessageParseResult.Transaction).txn
        assertEquals(Direction.CREDIT, t.direction)
        assertEquals(TxnType.INCOME, t.type)
        assertEquals("Rahul Sharma", t.merchant)
        assertEquals("4521", t.accountLast4)
    }

    @Test
    fun `offers and requests are ignored`() {
        assertTrue(parser.parse(gpay, "Get ₹100 cashback", "On your next bill payment of ₹500 or more", 0) is MessageParseResult.Ignored)
        assertTrue(parser.parse(phonepe, "Payment request", "Rahul requested ₹200", 0) is MessageParseResult.Ignored)
        assertTrue(parser.parse("com.whatsapp", "Rahul", "Paid ₹200 to you", 0) is MessageParseResult.Ignored)
    }
}

class EmailParserTest {
    private val parser = EmailParser(IST)

    @Test
    fun `food delivery receipt`() {
        val r = parser.parse(
            EmailParser.Email(
                from = "Swiggy <noreply@swiggy.in>",
                subject = "Your Swiggy order summary for Order No. 1234567890",
                body = "<html><body><p>Thanks for ordering!</p><table><tr><td>Item total</td><td>₹ 400.00</td></tr>" +
                    "<tr><td>Order Total</td><td>&#8377; 452.00</td></tr></table><p>Paid via UPI</p></body></html>",
                receivedAt = at(2026, 11, 4),
            ),
        )
        val t = (r as MessageParseResult.Transaction).txn
        assertEquals(45200L, t.amountMinor)
        assertEquals("Swiggy", t.merchant)
        assertEquals(PaymentMethod.UPI, t.paymentMethod)
        assertEquals("1234567890", t.orderId)
    }

    @Test
    fun `bank alert email`() {
        val r = parser.parse(
            EmailParser.Email(
                from = "HDFC Bank InstaAlerts <alerts@hdfcbank.net>",
                subject = "You have done a UPI txn. Check details!",
                body = "Dear Customer, Rs.1,250.00 has been debited from account **4521 to VPA amazon@apl on 04-11-26. Your UPI transaction reference number is 629384751107.",
                receivedAt = at(2026, 11, 4),
            ),
        )
        val t = (r as MessageParseResult.Transaction).txn
        assertEquals(125000L, t.amountMinor)
        assertEquals("Amazon", t.merchant)
        assertEquals("4521", t.accountLast4)
        assertEquals("629384751107", t.reference)
    }

    @Test
    fun `marketing and statement emails are ignored`() {
        assertTrue(parser.parse(EmailParser.Email("Myntra <offers@myntra.com>", "Flat 60% off this weekend", "Shop now", 0)) is MessageParseResult.Ignored)
        assertTrue(parser.parse(EmailParser.Email("SBI Card <statements@sbicard.com>", "Your SBI Card monthly statement", "Total due Rs 5000", 0)) is MessageParseResult.Ignored)
    }
}
