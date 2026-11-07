package com.expensetracker.app.ui.transactions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expensetracker.app.core.Money
import com.expensetracker.app.data.db.AccountEntity
import com.expensetracker.app.data.db.AppDatabase
import com.expensetracker.app.data.db.CategoryEntity
import com.expensetracker.app.data.db.TransactionEntity
import com.expensetracker.app.data.repo.AccountRepository
import com.expensetracker.app.data.repo.CategoryRepository
import com.expensetracker.app.data.repo.TransactionRepository
import com.expensetracker.app.domain.model.CategoryKind
import com.expensetracker.app.domain.model.DefaultCategories
import com.expensetracker.app.domain.model.Direction
import com.expensetracker.app.domain.model.PaymentMethod
import com.expensetracker.app.domain.model.SourceType
import com.expensetracker.app.domain.model.TxnStatus
import com.expensetracker.app.domain.model.TxnType
import com.expensetracker.app.domain.parser.MerchantDirectory
import com.expensetracker.app.ui.common.Fmt
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject

data class EditForm(
    val amount: String = "",
    val type: TxnType = TxnType.EXPENSE,
    val direction: Direction = Direction.DEBIT,
    val merchant: String = "",
    val categoryId: Long? = null,
    val categoryTouched: Boolean = false,
    val accountId: Long? = null,
    val method: PaymentMethod = PaymentMethod.UPI,
    val date: LocalDate = LocalDate.now(Fmt.zone),
    val time: LocalTime = LocalTime.now(Fmt.zone).withSecond(0).withNano(0),
    val status: TxnStatus = TxnStatus.COMPLETED,
    val reference: String = "",
    val notes: String = "",
    val tags: String = "",
    val error: String? = null,
)

@HiltViewModel
class EditTransactionViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: TransactionRepository,
    private val db: AppDatabase,
    categories: CategoryRepository,
    accounts: AccountRepository,
) : ViewModel() {

    private val editId = savedState.get<Long>("id")?.takeIf { it > 0 }
    private val sourceId = savedState.get<Long>("sourceId")?.takeIf { it > 0 }
    val isNew = editId == null
    private var original: TransactionEntity? = null

    private val _form = MutableStateFlow(EditForm())
    val form: StateFlow<EditForm> = _form.asStateFlow()
    val categories: StateFlow<List<CategoryEntity>> = categories.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val accounts: StateFlow<List<AccountEntity>> = accounts.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            editId?.let { id ->
                val t = repo.get(id) ?: return@let
                original = t
                val dt = Fmt.dateTime(t.timestamp)
                _form.value = EditForm(
                    amount = Money.formatPlain(t.amountMinor), type = t.type, direction = t.direction, merchant = t.merchant.orEmpty(),
                    categoryId = t.categoryId, categoryTouched = true, accountId = t.accountId, method = t.paymentMethod,
                    date = dt.toLocalDate(), time = dt.toLocalTime().withSecond(0).withNano(0), status = t.status,
                    reference = t.reference.orEmpty(), notes = t.notes.orEmpty(), tags = t.tags.replace(",", ", "),
                )
            }
            sourceId?.let { sid ->
                val src = db.sourceRecordDao().get(sid) ?: return@let
                val dt = Fmt.dateTime(src.receivedAt)
                _form.update {
                    it.copy(
                        amount = src.parsedAmountMinor?.let { a -> Money.formatPlain(a) }.orEmpty(),
                        date = dt.toLocalDate(),
                        time = dt.toLocalTime().withSecond(0).withNano(0),
                        notes = "From ${src.sourceType.label.lowercase()}${src.sender?.let { s -> " ($s)" } ?: ""}",
                    )
                }
            }
        }
    }

    fun update(transform: (EditForm) -> EditForm) = _form.update { transform(it).copy(error = null) }

    fun setType(type: TxnType) = _form.update {
        val direction = repo.directionFor(type, it.direction)
        val fixed = DefaultCategories.forType(type)?.let { name -> categories.value.firstOrNull { c -> c.name == name }?.id }
        it.copy(type = type, direction = direction, categoryId = fixed ?: if (it.categoryTouched) it.categoryId else null)
    }

    /** Suggests a category from the merchant name until the user picks one. */
    fun setMerchant(name: String) = _form.update { f ->
        val suggestion = if (!f.categoryTouched && f.type == TxnType.EXPENSE) {
            MerchantDirectory.match(name)?.category?.let { cat -> categories.value.firstOrNull { it.name == cat }?.id }
        } else null
        f.copy(merchant = name, categoryId = suggestion ?: f.categoryId)
    }

    fun preferredKind(): CategoryKind = when (_form.value.type) {
        TxnType.EXPENSE -> CategoryKind.EXPENSE
        TxnType.INCOME, TxnType.REFUND, TxnType.REIMBURSEMENT -> CategoryKind.INCOME
        else -> CategoryKind.NEUTRAL
    }

    fun save(onSaved: (Long) -> Unit) = viewModelScope.launch {
        val f = _form.value
        val amount = Money.parseToMinor(f.amount)
        if (amount == null || amount <= 0) {
            _form.update { it.copy(error = "Enter an amount greater than zero") }
            return@launch
        }
        val timestamp = LocalDateTime.of(f.date, f.time).atZone(Fmt.zone).toInstant().toEpochMilli()
        // Keep exactly what the user typed; the directory is only used to suggest a category.
        val merchant = f.merchant.trim().ifEmpty { null }
        val tags = f.tags.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().joinToString(",")
        val now = System.currentTimeMillis()
        val existing = original
        if (existing == null) {
            val id = repo.addManual(
                TransactionEntity(
                    amountMinor = amount, timestamp = timestamp, direction = f.direction, type = f.type, status = f.status,
                    merchant = merchant, categoryId = f.categoryId, accountId = f.accountId, paymentMethod = f.method,
                    reference = f.reference.trim().ifEmpty { null }, notes = f.notes.trim().ifEmpty { null }, tags = tags,
                    sourceType = SourceType.MANUAL, categoryReason = "Set by you", createdAt = now, updatedAt = now,
                ),
            )
            sourceId?.let { repo.linkUnparsed(it, id) }
            onSaved(id)
        } else {
            repo.update(
                existing.copy(
                    amountMinor = amount, timestamp = timestamp, direction = f.direction, type = f.type, status = f.status,
                    merchant = merchant, categoryId = f.categoryId, accountId = f.accountId, paymentMethod = f.method,
                    reference = f.reference.trim().ifEmpty { null }, notes = f.notes.trim().ifEmpty { null }, tags = tags,
                    categoryReason = if (f.categoryId != existing.categoryId) "Set by you" else existing.categoryReason,
                    needsReview = false, reviewReason = null,
                ),
            )
            onSaved(existing.id)
        }
    }
}
