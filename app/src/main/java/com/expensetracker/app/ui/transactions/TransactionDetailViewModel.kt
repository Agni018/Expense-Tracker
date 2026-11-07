package com.expensetracker.app.ui.transactions

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expensetracker.app.data.db.AccountEntity
import com.expensetracker.app.data.db.CategoryEntity
import com.expensetracker.app.data.db.SourceRecordEntity
import com.expensetracker.app.data.db.SplitEntity
import com.expensetracker.app.data.db.TransactionEntity
import com.expensetracker.app.data.repo.AccountRepository
import com.expensetracker.app.data.repo.CategoryRepository
import com.expensetracker.app.data.repo.TransactionRepository
import com.expensetracker.app.domain.model.TxnStatus
import com.expensetracker.app.domain.model.TxnType
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class DetailState(
    val loaded: Boolean = false,
    val txn: TransactionEntity? = null,
    val category: CategoryEntity? = null,
    val account: AccountEntity? = null,
    val sources: List<SourceRecordEntity> = emptyList(),
    val splits: List<SplitEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val categoryMap: Map<Long, CategoryEntity> = emptyMap(),
    val duplicateOf: TransactionEntity? = null,
    val linked: TransactionEntity? = null,
    val merchantCount: Int = 0,
    val merchantTotalMinor: Long = 0,
)

@HiltViewModel
class TransactionDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: TransactionRepository,
    categories: CategoryRepository,
    accounts: AccountRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val id: Long = savedState.get<Long>("id") ?: 0L

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events

    val state: StateFlow<DetailState> = combine(
        repo.observeData(), repo.observeSources(id), repo.observeSplits(id), categories.observeAll(), accounts.observeAll(),
    ) { data, sources, splits, cats, accs ->
        val txn = data.transactions.firstOrNull { it.id == id }
        val categoryMap = cats.associateBy { it.id }
        val sameMerchant = txn?.merchant?.let { m -> data.transactions.filter { it.merchant == m && it.status == TxnStatus.COMPLETED && it.type == TxnType.EXPENSE } }.orEmpty()
        DetailState(
            loaded = true,
            txn = txn,
            category = txn?.categoryId?.let { categoryMap[it] },
            account = txn?.accountId?.let { a -> accs.firstOrNull { it.id == a } },
            sources = sources,
            splits = splits,
            categories = cats,
            categoryMap = categoryMap,
            duplicateOf = txn?.possibleDuplicateOf?.let { d -> data.transactions.firstOrNull { it.id == d } },
            linked = txn?.linkedTxnId?.let { l -> data.transactions.firstOrNull { it.id == l } },
            merchantCount = sameMerchant.size,
            merchantTotalMinor = sameMerchant.sumOf { it.amountMinor },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailState())

    fun setCategory(categoryId: Long, applyToMerchant: Boolean, remember: Boolean) = viewModelScope.launch {
        val updated = repo.setCategory(id, categoryId, applyToMerchant, remember)
        _events.emit(
            when {
                applyToMerchant && updated > 0 -> "Updated $updated other transactions" + if (remember) " and saved a rule" else ""
                remember -> "Saved a rule for future transactions"
                else -> "Category updated"
            },
        )
    }

    fun setType(type: TxnType) = viewModelScope.launch {
        repo.setType(id, type)
        _events.emit("Marked as ${type.label.lowercase()}")
    }

    fun setStatus(status: TxnStatus) = viewModelScope.launch {
        repo.get(id)?.let { repo.update(it.copy(status = status)) }
    }

    fun approve() = viewModelScope.launch { repo.approve(id) }

    fun mergeIntoDuplicate(onDone: () -> Unit) = viewModelScope.launch {
        val target = state.value.duplicateOf ?: return@launch
        repo.mergeInto(id, target.id)
        _events.emit("Merged into the existing transaction")
        onDone()
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        repo.delete(id)
        onDone()
    }

    fun separate(sourceId: Long) = viewModelScope.launch {
        val newId = repo.separateSource(sourceId)
        _events.emit(if (newId != null) "Split into a separate transaction" else "Could not separate this record")
    }

    fun saveNotes(notes: String, tags: String) = viewModelScope.launch {
        val t = repo.get(id) ?: return@launch
        val cleanTags = tags.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().joinToString(",")
        repo.update(t.copy(notes = notes.trim().ifEmpty { null }, tags = cleanTags))
    }

    fun saveSplits(parts: List<SplitEntity>) = viewModelScope.launch {
        val t = repo.get(id) ?: return@launch
        val total = parts.sumOf { it.amountMinor }
        if (total > t.amountMinor) {
            _events.emit("Split parts add up to more than the transaction")
            return@launch
        }
        repo.setSplits(id, parts.filter { it.amountMinor > 0 })
        _events.emit(if (parts.isEmpty()) "Split removed" else "Split saved")
    }

    fun attachReceipt(uri: Uri) = viewModelScope.launch {
        val path = withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "receipts").apply { mkdirs() }
            val file = File(dir, "txn-$id-${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: return@withContext null
            file.absolutePath
        }
        if (path == null) _events.emit("Could not read the image") else repo.setReceipt(id, path)
    }

    fun removeReceipt() = viewModelScope.launch {
        state.value.txn?.receiptPath?.let { runCatching { File(it).delete() } }
        repo.setReceipt(id, null)
    }

    fun cameraTarget(): Uri {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "receipt-$id.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}
