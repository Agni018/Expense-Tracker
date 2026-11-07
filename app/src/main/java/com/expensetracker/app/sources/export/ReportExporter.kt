package com.expensetracker.app.sources.export

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.expensetracker.app.core.Money
import com.expensetracker.app.data.db.AppDatabase
import com.expensetracker.app.data.db.TransactionEntity
import com.expensetracker.app.data.repo.TransactionRepository
import com.expensetracker.app.domain.engine.FinanceCalculator
import com.expensetracker.app.domain.engine.SplitPart
import com.expensetracker.app.domain.model.DateRange
import com.expensetracker.app.domain.model.Direction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReportExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val transactions: TransactionRepository,
    private val zone: ZoneId,
) {
    private val dateFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH)
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    suspend fun exportCsv(uri: Uri, range: DateRange): Int = withContext(Dispatchers.IO) {
        val txns = transactions.observeRange(range).first().sortedBy { it.timestamp }
        val categories = db.categoryDao().all().associateBy { it.id }
        val accounts = db.accountDao().all().associateBy { it.id }
        fun esc(s: String?) = s.orEmpty().let { if (it.any { c -> c == ',' || c == '"' || c == '\n' }) "\"" + it.replace("\"", "\"\"") + "\"" else it }
        val sb = StringBuilder("Date,Time,Type,Direction,Status,Amount,Merchant,Category,Account,Payment method,Reference,Notes,Tags,Source\n")
        txns.forEach { t ->
            val at = Instant.ofEpochMilli(t.timestamp).atZone(zone)
            sb.append(
                listOf(
                    at.format(dateFmt), at.format(timeFmt), t.type.label, t.direction.name, t.status.label, Money.formatPlain(t.amountMinor),
                    esc(t.merchant), esc(t.categoryId?.let { categories[it]?.name }), esc(t.accountId?.let { accounts[it]?.name }),
                    t.paymentMethod.label, esc(t.reference), esc(t.notes), esc(t.tags), t.sourceType.label,
                ).joinToString(","),
            ).append('\n')
        }
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(sb.toString().toByteArray()) }
            ?: throw IllegalStateException("Could not write the file")
        txns.size
    }

    /** A4 PDF: summary, category and merchant breakdowns, then the transaction list. */
    suspend fun exportPdf(uri: Uri, range: DateRange, title: String): Int = withContext(Dispatchers.IO) {
        val txns = transactions.observeRange(range).first().sortedBy { it.timestamp }
        val ledger = txns.map { it.toLedger(transactions.splits(it.id).map { s -> SplitPart(s.categoryId, s.amountMinor) }) }
        val summary = FinanceCalculator.summarize(ledger)
        val categories = db.categoryDao().all().associateBy { it.id }
        val byCategory = FinanceCalculator.spendByCategory(ledger).entries.sortedByDescending { it.value }
        val merchants = FinanceCalculator.spendByMerchant(ledger).take(10)

        val doc = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val margin = 40f
        var pageNumber = 0
        lateinit var page: PdfDocument.Page
        var y = 0f
        val text = Paint().apply { color = Color.rgb(30, 30, 40); textSize = 10f; isAntiAlias = true }
        val muted = Paint(text).apply { color = Color.rgb(110, 110, 125) }
        val bold = Paint(text).apply { typeface = Typeface.DEFAULT_BOLD }
        val heading = Paint(bold).apply { textSize = 14f }
        val bar = Paint().apply { color = Color.rgb(79, 70, 229) }
        val barBg = Paint().apply { color = Color.rgb(230, 230, 240) }

        fun newPage() {
            if (pageNumber > 0) doc.finishPage(page)
            pageNumber++
            page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            y = margin
            page.canvas.drawText("Page $pageNumber", pageWidth - margin - 40, pageHeight - 20f, muted)
        }
        fun ensure(space: Float) { if (y + space > pageHeight - margin) newPage() }
        fun line(label: String, value: String, paint: Paint = text) {
            ensure(16f)
            page.canvas.drawText(label, margin, y, paint)
            page.canvas.drawText(value, pageWidth - margin - paint.measureText(value), y, paint)
            y += 16f
        }

        newPage()
        page.canvas.drawText(title, margin, y + 8, Paint(heading).apply { textSize = 20f })
        y += 30f
        page.canvas.drawText("${range.start.format(dateFmt)} – ${range.endInclusive.format(dateFmt)}", margin, y, muted)
        y += 28f
        page.canvas.drawText("Summary", margin, y, heading); y += 20f
        line("Income", Money.format(summary.income))
        line("Spending (net of refunds)", Money.format(summary.netSpend))
        line("Savings", Money.format(summary.savings), bold)
        summary.savingsRatePct?.let { line("Savings rate", "${it.toInt()}%") }
        line("Refunds", Money.format(summary.refunds), muted)
        line("Investments", Money.format(summary.investments), muted)
        line("Self transfers (excluded)", Money.format(summary.transfers), muted)
        line("Card bill payments (excluded)", Money.format(summary.cardBillPayments), muted)
        line("Cash withdrawals (excluded)", Money.format(summary.cashWithdrawals), muted)
        line("Transactions counted", summary.countedTransactions.toString(), muted)
        y += 14f

        ensure(40f)
        page.canvas.drawText("Spending by category", margin, y, heading); y += 20f
        val max = byCategory.firstOrNull()?.value ?: 1L
        byCategory.forEach { (id, amount) ->
            ensure(20f)
            val name = id?.let { categories[it]?.name } ?: "Uncategorised"
            page.canvas.drawText(name, margin, y, text)
            val value = "${Money.format(amount)} (${Money.percent(amount, summary.netSpend).toInt()}%)"
            page.canvas.drawText(value, pageWidth - margin - text.measureText(value), y, text)
            val left = margin + 170f
            val right = pageWidth - margin - 120f
            page.canvas.drawRect(left, y - 8f, right, y - 2f, barBg)
            page.canvas.drawRect(left, y - 8f, left + (right - left) * amount / max, y - 2f, bar)
            y += 18f
        }
        y += 14f
        if (merchants.isNotEmpty()) {
            ensure(40f)
            page.canvas.drawText("Top merchants", margin, y, heading); y += 20f
            merchants.forEach { (m, amount) -> line(m, Money.format(amount)) }
            y += 14f
        }

        ensure(40f)
        page.canvas.drawText("Transactions", margin, y, heading); y += 20f
        txns.forEach { t: TransactionEntity ->
            ensure(14f)
            val at = Instant.ofEpochMilli(t.timestamp).atZone(zone)
            page.canvas.drawText(at.format(dateFmt), margin, y, muted)
            page.canvas.drawText((t.merchant ?: t.type.label).take(34), margin + 70f, y, text)
            page.canvas.drawText((t.categoryId?.let { categories[it]?.name } ?: "").take(22), margin + 280f, y, muted)
            val amount = (if (t.direction == Direction.DEBIT) "-" else "+") + Money.format(t.amountMinor)
            page.canvas.drawText(amount, pageWidth - margin - text.measureText(amount), y, text)
            y += 14f
        }
        doc.finishPage(page)
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { doc.writeTo(it) } ?: throw IllegalStateException("Could not write the file")
        } finally {
            doc.close()
        }
        txns.size
    }
}
