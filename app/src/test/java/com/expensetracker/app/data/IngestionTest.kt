package com.expensetracker.app.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.expensetracker.app.IST
import com.expensetracker.app.at
import com.expensetracker.app.core.Hashing
import com.expensetracker.app.data.db.AppDatabase
import com.expensetracker.app.data.ingest.TransactionIngestor
import com.expensetracker.app.data.ingest.TransactionIngestor.Outcome
import com.expensetracker.app.data.repo.CategoryRepository
import com.expensetracker.app.data.repo.TransactionRepository
import com.expensetracker.app.domain.engine.FinanceCalculator
import com.expensetracker.app.domain.model.DefaultCategories
import com.expensetracker.app.domain.model.Direction
import com.expensetracker.app.domain.model.RuleOrigin
import com.expensetracker.app.domain.model.SourceType
import com.expensetracker.app.domain.model.TxnStatus
import com.expensetracker.app.domain.model.TxnType
import com.expensetracker.app.domain.parser.MessageParseResult
import com.expensetracker.app.domain.parser.NotificationParser
import com.expensetracker.app.domain.parser.SmsParser
import com.expensetracker.app.domain.statement.CsvReader
import com.expensetracker.app.domain.statement.NarrationParser
import com.expensetracker.app.domain.statement.TabularStatementParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Runs the real ingestion pipeline against an in-memory Room database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IngestionTest {

    private lateinit var db: AppDatabase
    private lateinit var ingestor: TransactionIngestor
    private lateinit var repo: TransactionRepository
    private val sms = SmsParser(IST)
    private val notifications = NotificationParser(IST)

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        CategoryRepository(db).seedDefaultsIfEmpty()
        ingestor = TransactionIngestor(db)
        repo = TransactionRepository(db, IST)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun sms(sender: String, body: String, time: Long): Outcome {
        val r = sms.parse(sender, body, time) as MessageParseResult.Transaction
        return ingestor.ingest(TransactionIngestor.SourceItem(SourceType.SMS, Hashing.sha256(sender, body, time), sender, body, time), r.txn)
    }

    private suspend fun all() = db.transactionDao().all()
    private suspend fun categoryId(name: String) = db.categoryDao().all().first { it.name == name }.id

    private val swiggy = "Rs 450 debited from A/c XX4521 through UPI to SWIGGY on 04-11-2026. Ref 629384751102."

    @Test
    fun `rescanning the same message is idempotent`() = runBlocking {
        assertTrue(sms("VM-HDFCBK", swiggy, at(2026, 11, 4, 10, 0)) is Outcome.Created)
        assertEquals(Outcome.AlreadyProcessed, sms("VM-HDFCBK", swiggy, at(2026, 11, 4, 10, 0)))
        assertEquals(1, all().size)
    }

    @Test
    fun `same alert delivered twice is merged`() = runBlocking {
        sms("VM-HDFCBK", swiggy, at(2026, 11, 4, 10, 0))
        assertTrue(sms("AX-HDFCBK", swiggy, at(2026, 11, 4, 10, 1)) is Outcome.Merged)
        val txns = all()
        assertEquals(1, txns.size)
        assertEquals(2, db.sourceRecordDao().forTransaction(txns[0].id).size)
    }

    @Test
    fun `account is detected and category assigned`() = runBlocking {
        sms("VM-HDFCBK", swiggy, at(2026, 11, 4, 10, 0))
        val t = all().single()
        val account = db.accountDao().get(t.accountId!!)!!
        assertEquals("HDFC Bank ••4521", account.name)
        assertEquals(categoryId(DefaultCategories.FOOD), t.categoryId)
        assertEquals("Swiggy", t.merchant)
    }

    @Test
    fun `sms and payment app notification become one transaction`() = runBlocking {
        val time = at(2026, 11, 4, 10, 0)
        sms("VM-HDFCBK", swiggy, time)
        val n = notifications.parse("com.google.android.apps.nbu.paisa.user", "Payment successful", "₹450 paid to Swiggy", time + 30_000) as MessageParseResult.Transaction
        val outcome = ingestor.ingest(TransactionIngestor.SourceItem(SourceType.NOTIFICATION, "n1", "Google Pay", "₹450 paid to Swiggy", time + 30_000), n.txn)
        assertTrue(outcome.toString(), outcome is Outcome.Merged)
        assertEquals(1, all().size)
    }

    @Test
    fun `two real payments with different references stay separate`() = runBlocking {
        sms("VM-HDFCBK", swiggy, at(2026, 11, 4, 10, 0))
        sms("VM-HDFCBK", swiggy.replace("629384751102", "629384751199"), at(2026, 11, 4, 10, 1))
        assertEquals(2, all().size)
    }

    @Test
    fun `statement row reconciles with the sms`() = runBlocking {
        sms("VM-HDFCBK", swiggy, at(2026, 11, 4, 10, 0))
        val account = db.accountDao().all().single()
        val csv = "Date,Narration,Chq./Ref.No.,Withdrawal Amt.,Deposit Amt.,Closing Balance\n" +
            "04/11/26,UPI-SWIGGY-SWIGGY@ICICI-ICIC0DC0099-629384751102-UPI,0000629384751102,450.00,,\"24,550.00\"\n" +
            "05/11/26,UPI-ZOMATO-ZOMATO@HDFC-HDFC0001-629384751300-UPI,0000629384751300,300.00,,\"24,250.00\"\n"
        val result = TabularStatementParser.parse(CsvReader.parse(csv), reference = LocalDate.of(2026, 11, 6))
        result.rows.forEachIndexed { i, row ->
            val parsed = NarrationParser.toParsed(row, false, account.last4, account.institution, IST, "CSV")
            ingestor.ingest(TransactionIngestor.SourceItem(SourceType.STATEMENT_CSV, "row$i", "statement.csv", row.raw, parsed.timestamp, accountId = account.id), parsed)
        }
        val txns = all()
        assertEquals("Swiggy merged, Zomato added", 2, txns.size)
        val swiggyTxn = txns.first { it.merchant == "Swiggy" }
        assertEquals(setOf(SourceType.SMS, SourceType.STATEMENT_CSV), db.sourceRecordDao().forTransaction(swiggyTxn.id).map { it.sourceType }.toSet())
    }

    @Test
    fun `refund is linked to the original and nets out`() = runBlocking {
        sms("VM-HDFCBK", swiggy, at(2026, 11, 4, 10, 0))
        sms("VM-HDFCBK", "Refund of Rs 450.00 from SWIGGY has been credited to your A/c XX4521 on 05-11-26.", at(2026, 11, 5, 9, 0))
        val txns = all()
        val refund = txns.first { it.type == TxnType.REFUND }
        val original = txns.first { it.type == TxnType.EXPENSE }
        assertEquals(original.id, refund.linkedTxnId)
        assertEquals(original.categoryId, refund.categoryId)
        assertEquals(0L, FinanceCalculator.summarize(txns.map { it.toLedger() }).netSpend)
    }

    @Test
    fun `reversal of a failed payment is not counted`() = runBlocking {
        sms("VM-HDFCBK", "Your UPI transaction of Rs.500.00 to swiggy@icici failed. Amount if debited will be reversed in 3-5 working days.", at(2026, 11, 4, 10, 0))
        sms("VM-HDFCBK", "Rs 500.00 debited from your A/c XX4521 for Swiggy has been reversed to your account on 05-11-26.", at(2026, 11, 5, 10, 0))
        val txns = all()
        assertEquals(TxnStatus.REVERSED, txns.first { it.type == TxnType.REFUND }.status)
        val s = FinanceCalculator.summarize(txns.map { it.toLedger() })
        assertEquals(0L, s.expenses)
        assertEquals(0L, s.refunds)
    }

    @Test
    fun `transfer to an own account is a self transfer`() = runBlocking {
        sms("VM-ICICIB", "Rs 100.00 debited from A/c XX9876 to VPA chai@ybl. Ref 629384751400", at(2026, 11, 3, 9, 0))
        sms("VM-HDFCBK", "Rs 10,000.00 debited from A/c XX4521 and credited to A/c XX9876 via IMPS. Ref 629384751106", at(2026, 11, 4, 10, 0))
        val transfer = all().first { it.amountMinor == 1_000_000L }
        assertEquals(TxnType.TRANSFER, transfer.type)
        assertEquals(categoryId(DefaultCategories.TRANSFERS), transfer.categoryId)
    }

    @Test
    fun `matching debit and credit on own accounts are paired as transfer`() = runBlocking {
        sms("VM-ICICIB", "Rs 100.00 debited from A/c XX9876 to VPA chai@ybl. Ref 629384751400", at(2026, 11, 3, 9, 0))
        sms("VM-HDFCBK", "Rs 5,000.00 debited from A/c XX4521 via IMPS to RAHUL SHARMA on 04-11-26. Ref 629384751500", at(2026, 11, 4, 10, 0))
        sms("VM-ICICIB", "Dear Customer, Acct XX9876 is credited with Rs 5000.00 on 04-Nov-26 from RAHUL SHARMA. IMPS Ref 629384751501.", at(2026, 11, 4, 10, 5))
        val pair = all().filter { it.amountMinor == 500_000L }
        assertEquals(2, pair.size)
        assertTrue(pair.all { it.type == TxnType.TRANSFER && it.needsReview })
        assertEquals(0L, FinanceCalculator.summarize(pair.map { it.toLedger() }).netSpend)
    }

    @Test
    fun `deleted transactions are not re-imported`() = runBlocking {
        sms("VM-HDFCBK", swiggy, at(2026, 11, 4, 10, 0))
        repo.delete(all().single().id)
        assertEquals(Outcome.AlreadyProcessed, sms("VM-HDFCBK", swiggy, at(2026, 11, 4, 10, 0)))
        assertTrue(all().isEmpty())
    }

    @Test
    fun `category corrections are learned`() = runBlocking {
        val groceries = categoryId(DefaultCategories.GROCERIES)
        sms("VM-HDFCBK", "Rs 250 debited from A/c XX4521 to VPA ramesh.store@ybl on 01-11-26. Ref 629384751601", at(2026, 11, 1, 10, 0))
        sms("VM-HDFCBK", "Rs 180 debited from A/c XX4521 to VPA ramesh.store@ybl on 02-11-26. Ref 629384751602", at(2026, 11, 2, 10, 0))
        all().forEach { repo.setCategory(it.id, groceries, applyToMerchant = false, remember = false) }
        assertTrue(db.ruleDao().all().any { it.origin == RuleOrigin.LEARNED && it.categoryId == groceries })
        sms("VM-HDFCBK", "Rs 90 debited from A/c XX4521 to VPA ramesh.store@ybl on 03-11-26. Ref 629384751603", at(2026, 11, 3, 10, 0))
        assertEquals(groceries, all().first { it.amountMinor == 9000L }.categoryId)
    }

    @Test
    fun `merged source can be separated again`() = runBlocking {
        sms("VM-HDFCBK", swiggy, at(2026, 11, 4, 10, 0))
        sms("AX-HDFCBK", swiggy, at(2026, 11, 4, 10, 1))
        val merged = db.sourceRecordDao().forTransaction(all().single().id).first { it.outcome.name == "MERGED" }
        assertNotNull(repo.separateSource(merged.id))
        assertEquals(2, all().size)
    }

    @Test
    fun `possible duplicates are flagged and can be merged`() = runBlocking {
        val first = "Rs 120.00 debited from A/c XX4521 at CHAI POINT on 04-11-26"
        sms("VM-HDFCBK", first, at(2026, 11, 4, 10, 0))
        val second = sms("VM-HDFCBK", "Rs.120.00 spent at CHAI POINT using A/c XX4521", at(2026, 11, 4, 10, 1))
        assertTrue(second.toString(), second is Outcome.FlaggedDuplicate)
        second as Outcome.FlaggedDuplicate
        repo.mergeInto(second.transactionId, second.duplicateOf)
        assertEquals(1, all().size)
        assertEquals(Direction.DEBIT, all().single().direction)
    }
}
