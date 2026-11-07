package com.expensetracker.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expensetracker.app.data.db.AccountEntity
import com.expensetracker.app.data.db.BudgetEntity
import com.expensetracker.app.data.db.CategoryEntity
import com.expensetracker.app.data.db.RecurringEntity
import com.expensetracker.app.data.db.TransactionEntity
import com.expensetracker.app.data.prefs.SettingsRepository
import com.expensetracker.app.data.repo.AccountRepository
import com.expensetracker.app.data.repo.BudgetRepository
import com.expensetracker.app.data.repo.CategoryRepository
import com.expensetracker.app.data.repo.RecurringRepository
import com.expensetracker.app.data.repo.TransactionRepository
import com.expensetracker.app.domain.engine.BudgetCalculator
import com.expensetracker.app.domain.engine.BudgetSpec
import com.expensetracker.app.domain.engine.BudgetStatus
import com.expensetracker.app.domain.engine.FinanceCalculator
import com.expensetracker.app.domain.engine.FinanceSummary
import com.expensetracker.app.domain.engine.Insight
import com.expensetracker.app.domain.engine.InsightsEngine
import com.expensetracker.app.domain.engine.LedgerEntry
import com.expensetracker.app.domain.engine.UpcomingPayment
import com.expensetracker.app.domain.model.DateRange
import com.expensetracker.app.domain.model.RecurringStatus
import com.expensetracker.app.sources.sms.SmsReader
import com.expensetracker.app.ui.common.Fmt
import com.expensetracker.app.ui.common.PeriodSelection
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
import androidx.compose.ui.graphics.Color
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

data class HomeState(
    val loading: Boolean = true,
    val name: String = "",
    val period: PeriodSelection = PeriodSelection(),
    val range: DateRange = PeriodSelection().range(),
    val summary: FinanceSummary = FinanceSummary.EMPTY,
    val slices: List<ChartSlice> = emptyList(),
    val trend: List<Pair<String, Long>> = emptyList(),
    val trendIsMonthly: Boolean = false,
    val overallBudget: BudgetStatus? = null,
    val categoryBudgets: List<Pair<CategoryEntity?, BudgetStatus>> = emptyList(),
    val recent: List<TransactionEntity> = emptyList(),
    val insights: List<Insight> = emptyList(),
    val topMerchants: List<Pair<String, Long>> = emptyList(),
    val upcoming: List<RecurringEntity> = emptyList(),
    val reviewCount: Int = 0,
    val categories: Map<Long, CategoryEntity> = emptyMap(),
    val accounts: Map<Long, AccountEntity> = emptyMap(),
    val hasAnyTransactions: Boolean = false,
    val smsPermission: Boolean = true,
    val lastSmsScanAt: Long = 0,
)

/** Monthly budget statuses shared by the Home and Budgets screens. */
fun budgetStatuses(
    budgets: List<BudgetEntity>,
    ledger: List<LedgerEntry>,
    categories: Map<Long, CategoryEntity>,
    zone: ZoneId,
    month: YearMonth = YearMonth.now(zone),
): List<Pair<CategoryEntity?, BudgetStatus>> {
    val today = LocalDate.now(zone)
    val thisMonth = DateRange.month(month)
    val lastMonth = DateRange.month(month.minusMonths(1))
    fun within(r: DateRange) = ledger.filter { it.timestamp >= r.startMillis(zone) && it.timestamp < r.endMillisExclusive(zone) }
    val now = within(thisMonth)
    val before = within(lastMonth)
    return budgets.map { b ->
        val spec = BudgetSpec(b.id, b.categoryId, b.amountMinor, b.rollover)
        b.categoryId?.let { categories[it] } to BudgetCalculator.status(
            spec, month, today, BudgetCalculator.spentFor(b.categoryId, now), BudgetCalculator.spentFor(b.categoryId, before),
        )
    }
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    transactions: TransactionRepository,
    categories: CategoryRepository,
    accounts: AccountRepository,
    budgets: BudgetRepository,
    recurring: RecurringRepository,
    settings: SettingsRepository,
    private val smsReader: SmsReader,
    private val zone: ZoneId,
) : ViewModel() {

    private val period = MutableStateFlow(PeriodSelection())

    private data class Base(
        val data: TransactionRepository.TxnData,
        val categories: List<CategoryEntity>,
        val accounts: List<AccountEntity>,
        val budgets: List<BudgetEntity>,
        val recurring: List<RecurringEntity>,
    )

    private val base = combine(transactions.observeData(), categories.observeAll(), accounts.observeAll(), budgets.observeAll(), recurring.observeAll(), ::Base)

    val state: StateFlow<HomeState> = combine(base, period, settings.settings, transactions.observeReviewCount()) { b, p, s, review ->
        compute(b, p, s.userName, review, s.lastSmsScanAt)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    fun setPeriod(p: PeriodSelection) {
        period.value = p
    }

    private fun compute(b: Base, p: PeriodSelection, name: String, review: Int, lastScan: Long): HomeState {
        val today = LocalDate.now(zone)
        val range = p.range(today)
        val categoryMap = b.categories.associateBy { it.id }
        fun within(r: DateRange) = b.data.ledger.filter { it.timestamp >= r.startMillis(zone) && it.timestamp < r.endMillisExclusive(zone) }
        val current = within(range)
        val previous = within(range.previous())
        val summary = FinanceCalculator.summarize(current)

        val byCategory = FinanceCalculator.spendByCategory(current).entries.sortedByDescending { it.value }
        val top = byCategory.take(6).map { (id, v) ->
            val c = id?.let { categoryMap[it] }
            ChartSlice(id, c?.name ?: "Uncategorised", v, categoryColor(c?.colorArgb), c?.emoji ?: "❔")
        }
        val rest = byCategory.drop(6).sumOf { it.value }
        val slices = if (rest > 0) top + ChartSlice(-2, "Others", rest, Color(0xFF94A3B8), "•••") else top

        val monthly = range.days > 62
        val trend = if (!monthly) {
            FinanceCalculator.dailySpend(current, range, zone).map { (d, v) -> Fmt.dayShort(d) to v }
        } else {
            val months = generateSequence(YearMonth.from(range.start)) { it.plusMonths(1) }.takeWhile { !it.isAfter(YearMonth.from(range.endInclusive)) }.toList()
            FinanceCalculator.monthly(current, months, zone).map { Fmt.monthShort(it.month.atDay(1)) to it.netSpend }
        }

        val statuses = budgetStatuses(b.budgets, b.data.ledger, categoryMap, zone)
        val upcoming = b.recurring.filter { it.status != RecurringStatus.DISMISSED && it.nextDueAt >= today.atStartOfDay(zone).toInstant().toEpochMilli() }
            .sortedBy { it.nextDueAt }
        val history = within(DateRange(today.minusDays(120), today))
        val insights = InsightsEngine.generate(
            InsightsEngine.Input(
                current = current,
                previous = previous,
                history = history,
                range = range,
                today = today,
                categoryNames = categoryMap.mapValues { it.value.name },
                budgets = statuses.map { (c, st) -> (c?.name ?: "monthly") to st },
                upcoming = upcoming.map { UpcomingPayment(it.displayName, Fmt.date(it.nextDueAt), it.expectedAmountMinor) },
            ),
        )
        return HomeState(
            loading = false,
            name = name,
            period = p,
            range = range,
            summary = summary,
            slices = slices,
            trend = trend,
            trendIsMonthly = monthly,
            overallBudget = statuses.firstOrNull { it.first == null }?.second,
            categoryBudgets = statuses.filter { it.first != null }.sortedByDescending { it.second.utilizationPct },
            recent = b.data.transactions.take(6),
            insights = insights,
            topMerchants = FinanceCalculator.spendByMerchant(current).take(5),
            upcoming = upcoming.take(4),
            reviewCount = review,
            categories = categoryMap,
            accounts = b.accounts.associateBy { it.id },
            hasAnyTransactions = b.data.transactions.isNotEmpty(),
            smsPermission = smsReader.hasReadPermission(),
            lastSmsScanAt = lastScan,
        )
    }
}
