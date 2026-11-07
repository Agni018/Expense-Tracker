package com.expensetracker.app.domain.engine

import com.expensetracker.app.IST
import com.expensetracker.app.at
import com.expensetracker.app.domain.model.DateRange
import com.expensetracker.app.domain.model.DefaultCategories
import com.expensetracker.app.domain.model.Direction
import com.expensetracker.app.domain.model.RecurringFrequency
import com.expensetracker.app.domain.model.RuleMatchType
import com.expensetracker.app.domain.model.RuleOrigin
import com.expensetracker.app.domain.model.SourceType
import com.expensetracker.app.domain.model.TxnStatus
import com.expensetracker.app.domain.model.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

private fun entry(
    id: Long,
    amount: Long,
    type: TxnType,
    direction: Direction = if (type == TxnType.INCOME || type == TxnType.REFUND) Direction.CREDIT else Direction.DEBIT,
    status: TxnStatus = TxnStatus.COMPLETED,
    category: Long? = null,
    merchant: String? = null,
    time: Long = at(2026, 11, 3),
    splits: List<SplitPart> = emptyList(),
) = LedgerEntry(id, amount, direction, type, status, category, null, merchant, time, splits = splits)

class FinanceCalculatorTest {

    @Test
    fun `accuracy rules for income and spending`() {
        val entries = listOf(
            entry(1, 100_000_00, TxnType.INCOME),
            entry(2, 300_00, TxnType.EXPENSE, category = 1),
            entry(3, 200_00, TxnType.EXPENSE, category = 2),
            entry(4, 50_00, TxnType.REFUND, category = 1),
            entry(5, 500_00, TxnType.TRANSFER),
            entry(6, 250_00, TxnType.CREDIT_CARD_PAYMENT),
            entry(7, 100_00, TxnType.CASH_WITHDRAWAL),
            entry(8, 999_00, TxnType.EXPENSE, status = TxnStatus.FAILED),
            entry(9, 10_000_00, TxnType.INVESTMENT),
            entry(10, 50_000_00, TxnType.LOAN, direction = Direction.CREDIT),
            entry(11, 120_00, TxnType.EXPENSE, status = TxnStatus.PENDING),
        )
        val s = FinanceCalculator.summarize(entries)
        assertEquals(100_000_00L, s.income)
        assertEquals(500_00L, s.expenses)
        assertEquals(50_00L, s.refunds)
        assertEquals(450_00L, s.netSpend)
        assertEquals(100_000_00L - 450_00L, s.savings)
        assertEquals(250_00L, s.cardBillPayments)
        assertEquals(100_00L, s.cashWithdrawals)
        assertEquals(10_000_00L, s.investments)
        assertEquals(50_000_00L, s.loanProceeds)
        assertEquals(1, s.failedCount)
        assertEquals(1, s.pendingCount)
    }

    @Test
    fun `category spend nets refunds and respects splits`() {
        val entries = listOf(
            entry(1, 1000_00, TxnType.EXPENSE, category = 1, splits = listOf(SplitPart(2, 300_00), SplitPart(3, 200_00))),
            entry(2, 100_00, TxnType.REFUND, category = 1),
            entry(3, 50_00, TxnType.EXPENSE, category = 3, status = TxnStatus.DECLINED),
        )
        val byCat = FinanceCalculator.spendByCategory(entries)
        assertEquals(400_00L, byCat[1])
        assertEquals(300_00L, byCat[2])
        assertEquals(200_00L, byCat[3])
    }

    @Test
    fun `daily spend covers every day`() {
        val range = DateRange(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 5))
        val daily = FinanceCalculator.dailySpend(listOf(entry(1, 100_00, TxnType.EXPENSE, time = at(2026, 11, 3))), range, IST)
        assertEquals(5, daily.size)
        assertEquals(100_00L, daily[2].second)
        assertEquals(0L, daily[0].second)
    }
}

class BudgetCalculatorTest {
    @Test
    fun `utilisation projection and rollover`() {
        val budget = BudgetSpec(1, 5, 10_000_00, rollover = true)
        val s = BudgetCalculator.status(budget, YearMonth.of(2026, 11), LocalDate.of(2026, 11, 15), 6_000_00, 7_000_00)
        assertEquals(3_000_00L, s.carriedOverMinor)
        assertEquals(13_000_00L, s.effectiveLimitMinor)
        assertEquals(12_000_00L, s.projectedMinor)
        assertNull("6,000 of a 13,000 limit is under 50%", s.thresholdReached)
        assertTrue(!s.willExceed)

        val tight = BudgetCalculator.status(budget.copy(rollover = false), YearMonth.of(2026, 11), LocalDate.of(2026, 11, 15), 8_500_00, 0)
        assertEquals(80, tight.thresholdReached)
        assertTrue(tight.willExceed)
        val over = BudgetCalculator.status(budget.copy(rollover = false), YearMonth.of(2026, 11), LocalDate.of(2026, 11, 15), 10_500_00, 0)
        assertTrue(over.isExceeded)
        assertEquals(100, over.thresholdReached)
    }

    @Test
    fun `overall budget uses net spend`() {
        val entries = listOf(entry(1, 500_00, TxnType.EXPENSE, category = 1), entry(2, 100_00, TxnType.REFUND, category = 1), entry(3, 999_00, TxnType.TRANSFER))
        assertEquals(400_00L, BudgetCalculator.spentFor(null, entries))
        assertEquals(400_00L, BudgetCalculator.spentFor(1, entries))
    }
}

class DuplicateDetectorTest {
    private fun rec(
        amount: Long = 450_00,
        time: Long = at(2026, 11, 4, 10, 0),
        account: String? = "4521",
        ref: String? = null,
        merchant: String? = "Swiggy",
        source: SourceType = SourceType.SMS,
        body: String? = null,
        known: Boolean = true,
    ) = DuplicateDetector.Record(amount, Direction.DEBIT, time, account, ref, merchant, setOf(source), setOfNotNull(body), known)

    @Test
    fun `same reference merges across sources`() {
        val d = DuplicateDetector.evaluate(rec(ref = "629384751102", source = SourceType.STATEMENT_CSV, time = at(2026, 11, 5, 12, 0)), rec(ref = "629384751102"))
        assertEquals(DuplicateDetector.Verdict.DUPLICATE, d.verdict)
        assertTrue(d.reasons.first().contains("reference"))
    }

    @Test
    fun `sms and notification at the same time merge`() {
        val d = DuplicateDetector.evaluate(rec(source = SourceType.NOTIFICATION, account = null, time = at(2026, 11, 4, 10, 1)), rec())
        assertEquals(DuplicateDetector.Verdict.DUPLICATE, d.verdict)
    }

    @Test
    fun `different upi references are separate payments`() {
        val d = DuplicateDetector.evaluate(rec(ref = "629384751102"), rec(ref = "629384751103", time = at(2026, 11, 4, 10, 1)))
        assertEquals(DuplicateDetector.Verdict.DISTINCT, d.verdict)
    }

    @Test
    fun `amount alone never merges`() {
        val d = DuplicateDetector.evaluate(
            rec(merchant = "Zomato", source = SourceType.EMAIL, time = at(2026, 11, 4, 11, 0)),
            rec(merchant = "Swiggy"),
        )
        assertTrue(d.verdict != DuplicateDetector.Verdict.DUPLICATE)
    }

    @Test
    fun `sms and statement row next day merge`() {
        val d = DuplicateDetector.evaluate(rec(source = SourceType.STATEMENT_PDF, merchant = "Swiggy", time = at(2026, 11, 5, 12, 0)), rec())
        assertEquals(DuplicateDetector.Verdict.DUPLICATE, d.verdict)
    }

    @Test
    fun `different accounts never merge`() {
        val d = DuplicateDetector.evaluate(rec(account = "1111", source = SourceType.NOTIFICATION), rec(account = "4521"))
        assertEquals(DuplicateDetector.Verdict.DISTINCT, d.verdict)
    }

    @Test
    fun `identical message delivered twice`() {
        val d = DuplicateDetector.evaluate(rec(body = "h1", time = at(2026, 11, 4, 10, 2)), rec(body = "h1"))
        assertEquals(DuplicateDetector.Verdict.DUPLICATE, d.verdict)
    }

    @Test
    fun `two similar sms without references need review`() {
        val d = DuplicateDetector.evaluate(rec(time = at(2026, 11, 4, 10, 1), body = "a"), rec(body = "b"))
        assertEquals(DuplicateDetector.Verdict.POSSIBLE, d.verdict)
    }
}

class CategorizerTest {
    private val ids = DefaultCategories.all.mapIndexed { i, c -> c.name to (i + 1).toLong() }.toMap()
    private fun id(name: String) = ids.getValue(name)

    @Test
    fun `known merchants keywords and people`() {
        val c = Categorizer(ids, emptyList())
        assertEquals(id(DefaultCategories.FOOD), c.categorize("Swiggy", null, TxnType.EXPENSE, Direction.DEBIT).categoryId)
        assertEquals(id(DefaultCategories.FUEL), c.categorize("Sri Ganesh Petrol Pump", null, TxnType.EXPENSE, Direction.DEBIT).categoryId)
        assertEquals(id(DefaultCategories.HEALTH), c.categorize("Apollo Pharmacy", null, TxnType.EXPENSE, Direction.DEBIT).categoryId)
        assertEquals(id(DefaultCategories.TRAVEL), c.categorize("IRCTC", null, TxnType.EXPENSE, Direction.DEBIT).categoryId)
        assertEquals(id(DefaultCategories.PERSONAL_TRANSFERS), c.categorize("Rahul Sharma", null, TxnType.EXPENSE, Direction.DEBIT, isPerson = true).categoryId)
        assertEquals(id(DefaultCategories.MERCHANT_PAYMENTS), c.categorize("Xyz Unknown Co", null, TxnType.EXPENSE, Direction.DEBIT).categoryId)
        assertEquals(id(DefaultCategories.CC_BILL), c.categorize(null, null, TxnType.CREDIT_CARD_PAYMENT, Direction.DEBIT).categoryId)
        assertEquals(id(DefaultCategories.SALARY), c.categorize("Acme Corp", "NEFT salary", TxnType.INCOME, Direction.CREDIT).categoryId)
    }

    @Test
    fun `user rules beat learned rules beat dictionary`() {
        val rules = listOf(
            RuleSpec(1, "Swiggy", RuleMatchType.MERCHANT_EXACT, id(DefaultCategories.GROCERIES), null, RuleOrigin.LEARNED),
            RuleSpec(2, "office lunch", RuleMatchType.CONTAINS, id(DefaultCategories.REIMBURSEMENTS), null, RuleOrigin.USER),
        )
        val c = Categorizer(ids, rules)
        assertEquals(id(DefaultCategories.GROCERIES), c.categorize("Swiggy", null, TxnType.EXPENSE, Direction.DEBIT).categoryId)
        assertEquals(id(DefaultCategories.REIMBURSEMENTS), c.categorize("Swiggy", "office lunch team", TxnType.EXPENSE, Direction.DEBIT).categoryId)
    }

    @Test
    fun `renamed default categories do not crash`() {
        val c = Categorizer(emptyMap(), emptyList())
        assertNull(c.categorize("Unknown", null, TxnType.EXPENSE, Direction.DEBIT).categoryId)
    }
}

class RecurringAndInsightsTest {
    @Test
    fun `monthly subscription detected with next due date`() {
        val entries = listOf(
            entry(1, 649_00, TxnType.EXPENSE, merchant = "Netflix", time = at(2026, 8, 5)),
            entry(2, 649_00, TxnType.EXPENSE, merchant = "Netflix", time = at(2026, 9, 5)),
            entry(3, 649_00, TxnType.EXPENSE, merchant = "Netflix", time = at(2026, 10, 5)),
            entry(4, 799_00, TxnType.EXPENSE, merchant = "Netflix", time = at(2026, 11, 5)),
            entry(5, 300_00, TxnType.EXPENSE, merchant = "Swiggy", time = at(2026, 11, 1)),
            entry(6, 280_00, TxnType.EXPENSE, merchant = "Swiggy", time = at(2026, 11, 3)),
            entry(7, 410_00, TxnType.EXPENSE, merchant = "Swiggy", time = at(2026, 11, 20)),
        )
        val found = RecurringDetector.detect(entries, IST, LocalDate.of(2026, 11, 6))
        assertEquals(1, found.size)
        val netflix = found.single()
        assertEquals(RecurringFrequency.MONTHLY, netflix.frequency)
        assertTrue(netflix.amountChanged)
        assertTrue(netflix.isSubscription)
    }

    @Test
    fun `failed payments never count as recurring payments`() {
        val entries = (8..11).map { m -> entry(m.toLong(), 499_00, TxnType.EXPENSE, merchant = "Spotify", status = TxnStatus.FAILED, time = at(2026, m, 1)) }
        assertTrue(RecurringDetector.detect(entries, IST, LocalDate.of(2026, 11, 6)).isEmpty())
    }

    @Test
    fun `insights explain category change`() {
        val input = InsightsEngine.Input(
            current = listOf(entry(1, 5_000_00, TxnType.EXPENSE, category = 1), entry(2, 10_000_00, TxnType.INCOME)),
            previous = listOf(entry(3, 3_200_00, TxnType.EXPENSE, category = 1, time = at(2026, 10, 3))),
            history = emptyList(),
            range = DateRange.month(YearMonth.of(2026, 11)),
            today = LocalDate.of(2026, 11, 6),
            categoryNames = mapOf(1L to "Food & Dining"),
            budgets = emptyList(),
            upcoming = emptyList(),
        )
        val insights = InsightsEngine.generate(input)
        assertTrue(insights.any { it.message == "You spent ₹1,800 more on Food & Dining than last month." })
        assertTrue(insights.any { it.id == "savings-rate" && it.message == "You saved 50% of your income." })
    }

    @Test
    fun `local query engine answers`() {
        val engine = FinanceQueryEngine(IST, LocalDate.of(2026, 11, 6))
        val entries = listOf(
            entry(1, 450_00, TxnType.EXPENSE, merchant = "Swiggy", category = 1, time = at(2026, 10, 10)),
            entry(2, 300_00, TxnType.EXPENSE, merchant = "Swiggy", category = 1, time = at(2026, 10, 12)),
            entry(3, 1000_00, TxnType.EXPENSE, merchant = "Amazon", category = 2, time = at(2026, 10, 15)),
        )
        val names = mapOf(1L to "Food & Dining", 2L to "Shopping")
        assertEquals(
            "You spent ₹750 on Swiggy last month across 2 transactions.",
            engine.answer("How much did I spend on Swiggy last month?", entries, names, emptyList()).text,
        )
        assertEquals("You spent ₹1,750 last month.", engine.answer("how much did i spend last month", entries, names, emptyList()).text)
    }
}
