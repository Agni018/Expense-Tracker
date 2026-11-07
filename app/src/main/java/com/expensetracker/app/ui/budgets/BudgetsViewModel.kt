package com.expensetracker.app.ui.budgets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expensetracker.app.data.db.BudgetEntity
import com.expensetracker.app.data.db.CategoryEntity
import com.expensetracker.app.data.db.GoalContributionEntity
import com.expensetracker.app.data.db.GoalEntity
import com.expensetracker.app.data.db.RecurringEntity
import com.expensetracker.app.data.repo.BudgetRepository
import com.expensetracker.app.data.repo.CategoryRepository
import com.expensetracker.app.data.repo.GoalRepository
import com.expensetracker.app.data.repo.RecurringRepository
import com.expensetracker.app.data.repo.TransactionRepository
import com.expensetracker.app.domain.engine.BudgetStatus
import com.expensetracker.app.domain.model.RecurringStatus
import com.expensetracker.app.ui.home.budgetStatuses
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

data class BudgetRow(val budget: BudgetEntity, val category: CategoryEntity?, val status: BudgetStatus)

data class GoalRow(val goal: GoalEntity, val savedMinor: Long, val contributions: List<GoalContributionEntity>)

data class BudgetsState(
    val month: YearMonth = YearMonth.now(),
    val overall: BudgetRow? = null,
    val rows: List<BudgetRow> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val recurring: List<RecurringEntity> = emptyList(),
    val monthlySubscriptionMinor: Long = 0,
    val goals: List<GoalRow> = emptyList(),
    val refreshing: Boolean = false,
)

@HiltViewModel
class BudgetsViewModel @Inject constructor(
    transactions: TransactionRepository,
    categories: CategoryRepository,
    private val budgets: BudgetRepository,
    private val recurring: RecurringRepository,
    private val goals: GoalRepository,
    private val zone: ZoneId,
) : ViewModel() {

    private val month = MutableStateFlow(YearMonth.now(zone))
    private val refreshing = MutableStateFlow(false)

    private data class GoalData(val goals: List<GoalEntity>, val contributions: List<GoalContributionEntity>)

    private val goalData = combine(goals.observeGoals(), goals.observeContributions(), ::GoalData)

    val state: StateFlow<BudgetsState> = combine(
        combine(transactions.observeData(), categories.observeAll(), budgets.observeAll(), ::Triple),
        recurring.observeAll(), goalData, month, refreshing,
    ) { (data, cats, budgetList), rec, g, m, busy ->
        val catMap = cats.associateBy { it.id }
        val statuses = budgetStatuses(budgetList, data.ledger, catMap, zone, m)
        val rows = budgetList.zip(statuses).map { (b, st) -> BudgetRow(b, st.first, st.second) }
        val active = rec.filter { it.status != RecurringStatus.DISMISSED }
        BudgetsState(
            month = m,
            overall = rows.firstOrNull { it.budget.categoryId == null },
            rows = rows.filter { it.budget.categoryId != null }.sortedByDescending { it.status.utilizationPct },
            categories = cats,
            recurring = rec,
            monthlySubscriptionMinor = active.sumOf { it.expectedAmountMinor * 30 / it.intervalDays.coerceAtLeast(1) },
            goals = g.goals.map { goal ->
                val list = g.contributions.filter { it.goalId == goal.id }
                GoalRow(goal, list.sumOf { it.amountMinor }, list)
            },
            refreshing = busy,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetsState())

    init {
        refreshRecurring()
    }

    fun shiftMonth(delta: Long) { month.value = month.value.plusMonths(delta) }

    fun saveBudget(id: Long, categoryId: Long?, amountMinor: Long, rollover: Boolean) = viewModelScope.launch {
        budgets.save(BudgetEntity(id = id, categoryId = categoryId, amountMinor = amountMinor, rollover = rollover, createdAt = System.currentTimeMillis()))
    }

    fun deleteBudget(id: Long) = viewModelScope.launch { budgets.delete(id) }

    fun refreshRecurring() = viewModelScope.launch {
        refreshing.value = true
        try {
            recurring.refresh()
        } finally {
            refreshing.value = false
        }
    }

    fun setRecurringStatus(item: RecurringEntity, status: RecurringStatus) = viewModelScope.launch { recurring.setStatus(item, status) }
    fun setReminder(item: RecurringEntity, enabled: Boolean) = viewModelScope.launch { recurring.setReminder(item, enabled) }

    fun saveGoal(goal: GoalEntity) = viewModelScope.launch { goals.save(goal) }
    fun deleteGoal(id: Long) = viewModelScope.launch { goals.delete(id) }
    fun contribute(goalId: Long, amountMinor: Long, note: String?) = viewModelScope.launch { goals.contribute(goalId, amountMinor, note) }
}
