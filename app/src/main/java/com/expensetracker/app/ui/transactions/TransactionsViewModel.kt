package com.expensetracker.app.ui.transactions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expensetracker.app.core.Money
import com.expensetracker.app.data.db.AccountEntity
import com.expensetracker.app.data.db.CategoryEntity
import com.expensetracker.app.data.db.TransactionEntity
import com.expensetracker.app.data.repo.AccountRepository
import com.expensetracker.app.data.repo.CategoryRepository
import com.expensetracker.app.data.repo.TransactionRepository
import com.expensetracker.app.domain.engine.FinanceCalculator
import com.expensetracker.app.domain.model.PaymentMethod
import com.expensetracker.app.domain.model.SourceType
import com.expensetracker.app.domain.model.TxnStatus
import com.expensetracker.app.domain.model.TxnType
import com.expensetracker.app.ui.common.Fmt
import com.expensetracker.app.ui.common.PeriodSelection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

enum class FlowFilter(val label: String) { ALL("All"), EXPENSES("Expenses"), INCOME("Income"), OTHER("Not spending") }

enum class SortOrder(val label: String) { NEWEST("Newest first"), OLDEST("Oldest first"), HIGHEST("Highest amount"), LOWEST("Lowest amount") }

data class TxnFilters(
    val period: PeriodSelection? = null,
    val fixedRange: LongRange? = null,
    val categoryIds: Set<Long> = emptySet(),
    val accountIds: Set<Long> = emptySet(),
    val methods: Set<PaymentMethod> = emptySet(),
    val sources: Set<SourceType> = emptySet(),
    val flow: FlowFilter = FlowFilter.ALL,
    val minAmountMinor: Long? = null,
    val maxAmountMinor: Long? = null,
    val merchant: String? = null,
    val hideFailed: Boolean = false,
) {
    val activeCount: Int
        get() = listOf(
            categoryIds.isNotEmpty(), accountIds.isNotEmpty(), methods.isNotEmpty(), sources.isNotEmpty(), flow != FlowFilter.ALL,
            minAmountMinor != null || maxAmountMinor != null, hideFailed,
        ).count { it }
}

data class DayGroup(val date: LocalDate, val items: List<TransactionEntity>, val netSpendMinor: Long)

data class TxnListState(
    val title: String = "Transactions",
    val groups: List<DayGroup> = emptyList(),
    val flat: List<TransactionEntity> = emptyList(),
    val count: Int = 0,
    val spentMinor: Long = 0,
    val receivedMinor: Long = 0,
    val filters: TxnFilters = TxnFilters(),
    val query: String = "",
    val sort: SortOrder = SortOrder.NEWEST,
    val selection: Set<Long> = emptySet(),
    val categories: Map<Long, CategoryEntity> = emptyMap(),
    val accounts: Map<Long, AccountEntity> = emptyMap(),
    val loading: Boolean = true,
)

@HiltViewModel
class TransactionsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val transactions: TransactionRepository,
    private val categories: CategoryRepository,
    accounts: AccountRepository,
) : ViewModel() {

    private val initial = TxnFilters(
        categoryIds = savedState.get<Long>("categoryId")?.takeIf { it > 0 }?.let { setOf(it) } ?: emptySet(),
        accountIds = savedState.get<Long>("accountId")?.takeIf { it > 0 }?.let { setOf(it) } ?: emptySet(),
        merchant = savedState.get<String>("merchant")?.takeIf { it.isNotBlank() },
        fixedRange = run {
            val start = savedState.get<Long>("start") ?: -1
            val end = savedState.get<Long>("end") ?: -1
            if (start > 0 && end > 0) start until end else null
        },
    )
    private val title = savedState.get<String>("title")?.takeIf { it.isNotBlank() } ?: "Transactions"

    private val filters = MutableStateFlow(initial)
    private val query = MutableStateFlow("")
    private val sort = MutableStateFlow(SortOrder.NEWEST)
    private val selection = MutableStateFlow<Set<Long>>(emptySet())

    private data class Inputs(val filters: TxnFilters, val query: String, val sort: SortOrder, val selection: Set<Long>)

    private val inputs = combine(filters, query, sort, selection, ::Inputs)

    val state: StateFlow<TxnListState> = combine(transactions.observeData(), categories.observeAll(), accounts.observeAll(), inputs) { data, cats, accs, inp ->
        val categoryMap = cats.associateBy { it.id }
        val filtered = data.transactions.filter { matches(it, inp.filters, inp.query, categoryMap) }
        val sorted = when (inp.sort) {
            SortOrder.NEWEST -> filtered
            SortOrder.OLDEST -> filtered.sortedBy { it.timestamp }
            SortOrder.HIGHEST -> filtered.sortedByDescending { it.amountMinor }
            SortOrder.LOWEST -> filtered.sortedBy { it.amountMinor }
        }
        val ids = filtered.map { it.id }.toSet()
        val ledger = data.ledger.filter { it.id in ids }
        val summary = FinanceCalculator.summarize(ledger)
        val groups = if (inp.sort == SortOrder.NEWEST || inp.sort == SortOrder.OLDEST) {
            sorted.groupBy { Fmt.date(it.timestamp) }.map { (date, items) ->
                DayGroup(date, items, FinanceCalculator.summarize(ledger.filter { e -> items.any { it.id == e.id } }).netSpend)
            }
        } else {
            emptyList()
        }
        TxnListState(
            title = title,
            groups = groups,
            flat = sorted,
            count = filtered.size,
            spentMinor = summary.netSpend,
            receivedMinor = summary.income,
            filters = inp.filters,
            query = inp.query,
            sort = inp.sort,
            selection = inp.selection.intersect(ids),
            categories = categoryMap,
            accounts = accs.associateBy { it.id },
            loading = false,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TxnListState(title = title))

    private fun matches(t: TransactionEntity, f: TxnFilters, q: String, categories: Map<Long, CategoryEntity>): Boolean {
        f.fixedRange?.let { if (t.timestamp !in it) return false }
        f.period?.let { p ->
            val r = p.range()
            if (t.timestamp < r.startMillis(Fmt.zone) || t.timestamp >= r.endMillisExclusive(Fmt.zone)) return false
        }
        if (f.categoryIds.isNotEmpty() && t.categoryId !in f.categoryIds) return false
        if (f.accountIds.isNotEmpty() && t.accountId !in f.accountIds) return false
        if (f.methods.isNotEmpty() && t.paymentMethod !in f.methods) return false
        if (f.sources.isNotEmpty() && t.sourceType !in f.sources) return false
        if (f.merchant != null && !t.merchant.equals(f.merchant, ignoreCase = true)) return false
        when (f.flow) {
            FlowFilter.ALL -> Unit
            FlowFilter.EXPENSES -> if (t.type != TxnType.EXPENSE) return false
            FlowFilter.INCOME -> if (t.type != TxnType.INCOME && t.type != TxnType.REFUND && t.type != TxnType.REIMBURSEMENT) return false
            FlowFilter.OTHER -> if (t.type == TxnType.EXPENSE || t.type == TxnType.INCOME) return false
        }
        f.minAmountMinor?.let { if (t.amountMinor < it) return false }
        f.maxAmountMinor?.let { if (t.amountMinor > it) return false }
        if (f.hideFailed && t.status != TxnStatus.COMPLETED) return false
        if (q.isNotBlank()) {
            val needle = q.trim().lowercase()
            val haystack = listOfNotNull(
                t.merchant, t.rawMerchant, t.description, t.notes, t.tags, t.reference, t.upiId,
                t.categoryId?.let { categories[it]?.name }, Money.formatPlain(t.amountMinor),
            ).joinToString(" ").lowercase()
            if (!haystack.contains(needle)) return false
        }
        return true
    }

    fun setQuery(q: String) { query.value = q }
    fun setSort(s: SortOrder) { sort.value = s }
    fun setFilters(f: TxnFilters) { filters.value = f }
    fun resetFilters() { filters.value = initial }
    fun setPeriod(p: PeriodSelection?) = filters.update { it.copy(period = p) }

    fun addCategory(c: CategoryEntity) = viewModelScope.launch { categories.save(c) }

    fun toggleSelection(id: Long) = selection.update { if (id in it) it - id else it + id }
    fun clearSelection() { selection.value = emptySet() }
    fun selectAll() { selection.value = state.value.flat.map { it.id }.toSet() }

    fun bulkCategorize(categoryId: Long) = viewModelScope.launch {
        transactions.bulkSetCategory(selection.value, categoryId)
        clearSelection()
    }

    fun bulkMarkTransfer() = viewModelScope.launch {
        transactions.bulkSetType(selection.value, TxnType.TRANSFER)
        clearSelection()
    }

    fun bulkApprove() = viewModelScope.launch {
        transactions.approveAll(selection.value)
        clearSelection()
    }

    fun bulkDelete() = viewModelScope.launch {
        transactions.bulkDelete(selection.value)
        clearSelection()
    }
}
