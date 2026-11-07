package com.expensetracker.app.ui.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expensetracker.app.data.db.AccountEntity
import com.expensetracker.app.data.db.CategoryEntity
import com.expensetracker.app.data.repo.AccountRepository
import com.expensetracker.app.data.repo.CategoryRepository
import com.expensetracker.app.data.repo.TransactionRepository
import com.expensetracker.app.domain.engine.FinanceCalculator
import com.expensetracker.app.domain.engine.FinanceSummary
import com.expensetracker.app.domain.engine.LedgerEntry
import com.expensetracker.app.domain.model.DateRange
import com.expensetracker.app.domain.model.PaymentMethod
import com.expensetracker.app.domain.model.TxnType
import com.expensetracker.app.ui.common.Fmt
import com.expensetracker.app.ui.common.PeriodSelection
import com.expensetracker.app.ui.components.BarGroup
import com.expensetracker.app.ui.components.ChartSlice
import com.expensetracker.app.ui.components.categoryColor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

data class CategoryRow(val category: CategoryEntity?, val amountMinor: Long, val sharePct: Int, val changeMinor: Long)

data class AnalyticsState(
    val loading: Boolean = true,
    val period: PeriodSelection = PeriodSelection(),
    val range: DateRange = PeriodSelection().range(),
    val summary: FinanceSummary = FinanceSummary.EMPTY,
    val previous: FinanceSummary = FinanceSummary.EMPTY,
    val slices: List<ChartSlice> = emptyList(),
    val categoryRows: List<CategoryRow> = emptyList(),
    val months: List<BarGroup> = emptyList(),
    val monthsShown: Int = 6,
    val trend: List<Pair<String, Long>> = emptyList(),
    val merchants: List<Pair<String, Long>> = emptyList(),
    val methods: List<Pair<PaymentMethod, Long>> = emptyList(),
    val accounts: List<Pair<AccountEntity?, Long>> = emptyList(),
    val income: List<Pair<CategoryEntity?, Long>> = emptyList(),
    val weekdays: List<Pair<String, Long>> = emptyList(),
)

@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    transactions: TransactionRepository,
    categories: CategoryRepository,
    accounts: AccountRepository,
    private val zone: ZoneId,
) : ViewModel() {

    private val period = MutableStateFlow(PeriodSelection())
    private val monthsShown = MutableStateFlow(6)

    val state: StateFlow<AnalyticsState> = combine(transactions.observeData(), categories.observeAll(), accounts.observeAll(), period, monthsShown) { data, cats, accs, p, months ->
        compute(data.ledger, cats.associateBy { it.id }, accs.associateBy { it.id }, p, months)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalyticsState())

    fun setPeriod(p: PeriodSelection) { period.value = p }
    fun setMonths(n: Int) { monthsShown.value = n }

    private fun compute(ledger: List<LedgerEntry>, cats: Map<Long, CategoryEntity>, accs: Map<Long, AccountEntity>, p: PeriodSelection, months: Int): AnalyticsState {
        val range = p.range()
        fun within(r: DateRange) = ledger.filter { it.timestamp >= r.startMillis(zone) && it.timestamp < r.endMillisExclusive(zone) }
        val current = within(range)
        val previous = within(range.previous())
        val summary = FinanceCalculator.summarize(current)
        val byCat = FinanceCalculator.spendByCategory(current).entries.sortedByDescending { it.value }
        val prevCat = FinanceCalculator.spendByCategory(previous)
        val total = byCat.sumOf { it.value }.coerceAtLeast(1)
        val rows = byCat.map { (id, v) -> CategoryRow(id?.let { cats[it] }, v, (v * 100 / total).toInt(), v - (prevCat[id] ?: 0)) }
        val slices = rows.map { r -> ChartSlice(r.category?.id, r.category?.name ?: "Uncategorised", r.amountMinor, categoryColor(r.category?.colorArgb), r.category?.emoji ?: "❔") }

        val endMonth = YearMonth.from(range.endInclusive)
        val monthList = (months - 1 downTo 0).map { endMonth.minusMonths(it.toLong()) }
        val monthly = FinanceCalculator.monthly(ledger, monthList, zone).map { BarGroup(Fmt.monthShort(it.month.atDay(1)), it.income, it.netSpend) }

        val trend = if (range.days <= 62) {
            FinanceCalculator.dailySpend(current, range, zone).map { (d, v) -> Fmt.dayShort(d) to v }
        } else {
            val ms = generateSequence(YearMonth.from(range.start)) { it.plusMonths(1) }.takeWhile { !it.isAfter(endMonth) }.toList()
            FinanceCalculator.monthly(current, ms, zone).map { Fmt.monthShort(it.month.atDay(1)) to it.netSpend }
        }

        val expenses = current.filter { FinanceCalculator.counts(it) && it.type == TxnType.EXPENSE }
        val weekdayTotals = expenses.groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).dayOfWeek }.mapValues { (_, l) -> l.sumOf { it.amountMinor } }
        val weekdays = DayOfWeek.entries.map { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) to (weekdayTotals[it] ?: 0L) }

        return AnalyticsState(
            loading = false,
            period = p,
            range = range,
            summary = summary,
            previous = FinanceCalculator.summarize(previous),
            slices = slices,
            categoryRows = rows,
            months = monthly,
            monthsShown = months,
            trend = trend,
            merchants = FinanceCalculator.spendByMerchant(current).take(10),
            methods = FinanceCalculator.spendByMethod(current).entries.sortedByDescending { it.value }.map { it.key to it.value },
            accounts = FinanceCalculator.spendByAccount(current).entries.sortedByDescending { it.value }.map { (id, v) -> id?.let { accs[it] } to v },
            income = FinanceCalculator.incomeByCategory(current).entries.sortedByDescending { it.value }.map { (id, v) -> id?.let { cats[it] } to v },
            weekdays = weekdays,
        )
    }
}
