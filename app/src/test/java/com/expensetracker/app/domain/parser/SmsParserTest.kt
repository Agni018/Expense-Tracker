package com.expensetracker.app.domain.parser

import com.expensetracker.app.IST
import com.expensetracker.app.at
import com.expensetracker.app.dateOf
import com.expensetracker.app.domain.model.DefaultCategories
import com.expensetracker.app.domain.model.Direction
import com.expensetracker.app.domain.model.PaymentMethod
import com.expensetracker.app.domain.model.TxnStatus
import com.expensetracker.app.domain.model.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.Instant

/** All messages are synthetic; account numbers and references are made up. */
class SmsParserTest {

    private val parser = SmsParser(IST)
    private val received = at(2026, 11, 4, 10, 30)

    private fun txn(sender: String, body: String, receivedAt: Long = received): ParsedTransaction {
        val result = parser.parse(sender, body, receivedAt)
        if (result !is MessageParseResult.Transaction) fail("Expected a transaction but got $result for: $body")
        return (result as MessageParseResult.Transaction).txn
    }

    private fun ignored(sender: String, body: String): IgnoreReason {
        val result = parser.parse(sender, body, received)
        if (result !is MessageParseResult.Ignored) fail("Expected the message to be ignored but got $result")
        return (result as MessageParseResult.Ignored).reason
    }

    @Test
    fun `spec example debit via UPI`() {
        val t = txn("VM-HDFCBK-S", "Rs 450 debited from A/c XX4521 through UPI to SWIGGY on 04-11-2026. Ref 629384751102.")
        assertEquals(45000L, t.amountMinor)
        assertEquals(Direction.DEBIT, t.direction)
        assertEquals(TxnType.EXPENSE, t.type)
        assertEquals("Swiggy", t.merchant)
        assertEquals(PaymentMethod.UPI, t.paymentMethod)
        assertEquals("4521", t.accountLast4)
        assertEquals("629384751102", t.reference)
        assertEquals(TxnStatus.COMPLETED, t.status)
        assertEquals(LocalDate.of(2026, 11, 4), dateOf(t.timestamp))
        assertEquals("HDFC Bank", t.institution)
    }

    @Test
    fun `hdfc multi line UPI template`() {
        val t = txn(
            "AX-HDFCBK",
            "Sent Rs.450.00\nFrom HDFC Bank A/C *4521\nTo SWIGGY\nOn 04/11/26\nRef 629384751102\nNot You?\nCall 18002586161/SMS BLOCK UPI to 7308080808",
        )
        assertEquals(45000L, t.amountMinor)
        assertEquals("Swiggy", t.merchant)
        assertEquals("4521", t.accountLast4)
        assertEquals("629384751102", t.reference)
        assertTrue(t.notes.any { it.contains("template") })
    }

    @Test
    fun `credit from a person via UPI`() {
        val t = txn(
            "JD-HDFCBK",
            "Money Received - INR 5,000.00 in your HDFC Bank A/c xx4521 on 04-11-26 by A/c linked to VPA rahul.sharma@okaxis (UPI Ref No 629384751103).",
        )
        assertEquals(500000L, t.amountMinor)
        assertEquals(Direction.CREDIT, t.direction)
        assertEquals(TxnType.INCOME, t.type)
        assertEquals("Rahul Sharma", t.merchant)
        assertTrue(t.counterpartyIsPerson)
        assertEquals("629384751103", t.reference)
    }

    @Test
    fun `sbi upi debit without currency symbol`() {
        val t = txn("JD-SBIUPI", "Dear UPI user A/C X4521 debited by 250.0 on date 04Nov26 trf to ZOMATO Refno 629384751104. If not u? call 1800111109. -SBI")
        assertEquals(25000L, t.amountMinor)
        assertEquals("Zomato", t.merchant)
        assertEquals("4521", t.accountLast4)
        assertEquals("629384751104", t.reference)
        assertEquals("SBI", t.institution)
    }

    @Test
    fun `icici payee credited format`() {
        val t = txn("AD-ICICIB", "ICICI Bank Acct XX521 debited for Rs 1,299.00 on 04-Nov-26; Netflix credited. UPI:629384751105. Call 18002662 for dispute. SMS BLOCK 521 to 9215676766")
        assertEquals(129900L, t.amountMinor)
        assertEquals(Direction.DEBIT, t.direction)
        assertEquals("Netflix", t.merchant)
        assertEquals("521", t.accountLast4)
    }

    @Test
    fun `card spend with time in body`() {
        val t = txn("VM-HDFCBK", "Spent Rs.2,340.50 On HDFC Bank Card 1234 At AMAZON On 2026-11-04:10:15:12 Bal Rs.45000 Not You? Call 18002586161")
        assertEquals(234050L, t.amountMinor)
        assertEquals("Amazon", t.merchant)
        assertEquals("1234", t.accountLast4)
        val time = Instant.ofEpochMilli(t.timestamp).atZone(IST).toLocalTime()
        assertEquals(LocalTime.of(10, 30), time) // delivered the same day, so delivery time is kept
    }

    @Test
    fun `credit card spend is a credit card payment method`() {
        val t = txn("AD-ICICIT", "INR 1,250.00 spent using ICICI Bank Card XX1234 on 04-Nov-26 on AMAZON. Avl Limit: INR 45,000.00. If not you, call 1800 2662/SMS BLOCK 1234 to 9215676766.")
        assertEquals(125000L, t.amountMinor)
        assertEquals(PaymentMethod.CREDIT_CARD, t.paymentMethod)
        assertTrue(t.isCreditCard)
        assertNull("Card limit is not an account balance", t.balanceMinor)
        assertEquals("Amazon", t.merchant)
    }

    @Test
    fun `atm withdrawal is not spending`() {
        val t = txn("BZ-SBIINB", "Dear Customer, Rs.2000 withdrawn at ATM S1AW000123 from A/cX4521 on 04Nov26 Transaction Number 1234. Available Balance Rs.10000. If not withdrawn by you, call 1800111109 -SBI")
        assertEquals(200000L, t.amountMinor)
        assertEquals(TxnType.CASH_WITHDRAWAL, t.type)
        assertEquals(PaymentMethod.ATM, t.paymentMethod)
        assertEquals(1000000L, t.balanceMinor)
        assertNull(t.counterpartyAccountLast4)
    }

    @Test
    fun `failed transaction is recorded as failed`() {
        val t = txn("VM-HDFCBK", "Your UPI transaction of Rs.500.00 to swiggy@icici failed. Amount if debited will be reversed in 3-5 working days.")
        assertEquals(TxnStatus.FAILED, t.status)
        assertEquals(50000L, t.amountMinor)
        assertEquals(TxnType.EXPENSE, t.type)
    }

    @Test
    fun `declined card transaction`() {
        val t = txn("AD-ICICIT", "Your transaction of Rs 1,500.00 on ICICI Bank Credit Card XX1234 at MYNTRA was declined due to insufficient limit.")
        assertEquals(TxnStatus.DECLINED, t.status)
        assertEquals("Myntra", t.merchant)
    }

    @Test
    fun `pending payment`() {
        val t = txn("VM-AXISBK", "Your payment of Rs 300 to Uber is pending. We will update you shortly.")
        assertEquals(TxnStatus.PENDING, t.status)
        assertEquals("Uber", t.merchant)
    }

    @Test
    fun `refund is a credit refund`() {
        val t = txn("VM-HDFCBK", "Refund of Rs 450.00 from SWIGGY has been credited to your A/c XX4521 on 05-11-26.")
        assertEquals(TxnType.REFUND, t.type)
        assertEquals(Direction.CREDIT, t.direction)
        assertEquals("Swiggy", t.merchant)
    }

    @Test
    fun `refund initiated notice is not a credit yet`() {
        assertEquals(IgnoreReason.MANDATE_OR_UPCOMING, ignored("VM-AMAZON", "Refund of Rs 450 for your order has been initiated and will reflect in 5-7 days."))
    }

    @Test
    fun `reversal of debit`() {
        val t = txn("VM-SBIINB", "Rs 499.00 debited from your A/c X4521 on 02Nov26 has been reversed to your account on 04Nov26.")
        assertEquals(TxnType.REFUND, t.type)
        assertEquals(Direction.CREDIT, t.direction)
    }

    @Test
    fun `otp is ignored`() {
        assertEquals(IgnoreReason.OTP, ignored("AD-HDFCBK", "123456 is your OTP for txn of Rs 450.00 at AMAZON. Do not share it with anyone."))
    }

    @Test
    fun `promotional message is ignored`() {
        assertEquals(IgnoreReason.PROMOTIONAL, ignored("AD-PAYTM-P", "Get flat Rs 500 cashback on your first UPI payment! Pay now. T&C apply"))
        assertEquals(IgnoreReason.PROMOTIONAL, ignored("VM-HDFCBK", "Congratulations! You are pre-approved for a personal loan of Rs 5,00,000. Apply now."))
    }

    @Test
    fun `balance alert updates balance only`() {
        val result = parser.parse("VM-SBIINB", "Your A/c XX4521 balance as on 04-Nov-26 is Rs 25,000.00", received)
        assertTrue(result is MessageParseResult.BalanceAlert)
        result as MessageParseResult.BalanceAlert
        assertEquals(2500000L, result.balanceMinor)
        assertEquals("4521", result.accountLast4)
    }

    @Test
    fun `mandates and upcoming debits are ignored`() {
        assertEquals(IgnoreReason.MANDATE_OR_UPCOMING, ignored("VM-HDFCBK", "Your e-mandate for Rs 649.00 towards Netflix has been successfully registered."))
        assertEquals(IgnoreReason.MANDATE_OR_UPCOMING, ignored("VM-HDFCBK", "Rs 499 will be debited from your a/c XX4521 on 10-11-26 towards Spotify via UPI AutoPay"))
    }

    @Test
    fun `autopay debit that happened is a transaction`() {
        val t = txn("VM-HDFCBK", "Rs 649.00 debited from A/c XX4521 for Netflix via UPI AutoPay mandate. Ref 629384751199")
        assertEquals(64900L, t.amountMinor)
        assertEquals("Netflix", t.merchant)
    }

    @Test
    fun `bill reminders are ignored`() {
        assertEquals(IgnoreReason.BILL_OR_STATEMENT, ignored("VM-HDFCBK", "Your HDFC Bank Credit Card statement is generated. Total due Rs 15,000; Min due Rs 750; due date 20-11-26."))
    }

    @Test
    fun `payment request is ignored`() {
        assertEquals(IgnoreReason.PAYMENT_REQUEST, ignored("VM-PAYTMB", "Rahul has requested Rs 200 from you via UPI. Approve in the app."))
    }

    @Test
    fun `transfer between accounts exposes counterparty account`() {
        val t = txn("VM-HDFCBK", "Rs 10,000.00 debited from A/c XX4521 and credited to A/c XX9876 via IMPS. Ref 629384751106")
        assertEquals(Direction.DEBIT, t.direction)
        assertEquals("4521", t.accountLast4)
        assertEquals("9876", t.counterpartyAccountLast4)
        assertEquals(PaymentMethod.IMPS, t.paymentMethod)
    }

    @Test
    fun `credit card bill payment is not spending`() {
        val t = txn("VM-HDFCBK", "Payment of Rs 15,000.00 has been received on your HDFC Bank Credit Card ending 1234. Thank you.")
        assertEquals(TxnType.CREDIT_CARD_PAYMENT, t.type)
        assertEquals(Direction.CREDIT, t.direction)
        assertEquals("1234", t.accountLast4)
    }

    @Test
    fun `bank side of a card bill payment`() {
        val t = txn("VM-HDFCBK", "Rs 15,000.00 debited from A/c XX4521 towards HDFC Credit Card payment via BillPay on 04-11-26.")
        assertEquals(TxnType.CREDIT_CARD_PAYMENT, t.type)
        assertEquals(Direction.DEBIT, t.direction)
    }

    @Test
    fun `salary credit`() {
        val t = txn("VM-HDFCBK", "Rs 85,000.00 credited to your A/c XX4521 on 01-11-26 by NEFT from ACME TECHNOLOGIES PVT LTD - SALARY OCT 2026")
        assertEquals(TxnType.INCOME, t.type)
        assertEquals(DefaultCategories.SALARY, t.categoryHint)
        assertEquals(PaymentMethod.NEFT, t.paymentMethod)
        assertEquals("Acme Technologies", t.merchant)
    }

    @Test
    fun `sender headers without hyphen are recognised`() {
        assertEquals("HDFC Bank", txn("VMHDFCBK", "Rs 450 debited from A/c XX4521 through UPI to SWIGGY. Ref 629384751102.").institution)
    }

    @Test
    fun `personal numbers are ignored`() {
        assertEquals(IgnoreReason.PERSONAL_SENDER, ignored("+919876543210", "I paid Rs 500 to you, please check"))
    }

    @Test
    fun `unknown bank generic format`() {
        val t = txn("XY-NEWBNK", "Dear Customer, INR 750.00 has been debited from your account no. XXXXXXXX7788 towards BESCOM ELECTRICITY on 03-11-2026.")
        assertEquals(75000L, t.amountMinor)
        assertEquals("7788", t.accountLast4)
        assertEquals("Electricity Bill", t.merchant)
        assertEquals(LocalDate.of(2026, 11, 3), dateOf(t.timestamp))
    }

    @Test
    fun `late alert uses date written in the message`() {
        val t = txn("VM-HDFCBK", "Rs 120.00 debited from A/c XX4521 on 04-11-26 to VPA chai.point@ybl. Ref 629384751110", receivedAt = at(2026, 11, 5, 0, 5))
        assertEquals(LocalDate.of(2026, 11, 4), dateOf(t.timestamp))
    }

    @Test
    fun `mentions an amount without a transaction`() {
        val result = parser.parse("VM-HDFCBK", "Your FD of Rs 50,000 matures on 10-12-26. Visit branch for renewal.", received)
        assertTrue(result is MessageParseResult.Ignored)
    }

    @Test
    fun `wallet payment`() {
        val t = txn("VM-PAYTMB", "Paid Rs.450 to Swiggy from Paytm Wallet. Updated Balance: Paytm Wallet- Rs 100. Txn ID: 26110412345")
        assertEquals(45000L, t.amountMinor)
        assertEquals("Swiggy", t.merchant)
        assertEquals(PaymentMethod.WALLET, t.paymentMethod)
    }

    @Test
    fun `fuel and toll payments`() {
        val fuel = txn("VM-ICICIB", "Rs 2,000.00 spent on ICICI Bank Card XX1234 at INDIAN OIL PETROL PUMP on 04-Nov-26.")
        assertEquals("Indian Oil", fuel.merchant)
        val toll = txn("VM-ICICIB", "Rs 95.00 debited from your FASTag wallet for toll at Electronic City Toll Plaza on 04-11-26.")
        assertEquals(TxnType.EXPENSE, toll.type)
        assertEquals(9500L, toll.amountMinor)
    }
}
