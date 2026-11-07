package com.expensetracker.app.ui.imports

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expensetracker.app.data.db.AccountEntity
import com.expensetracker.app.data.repo.AccountRepository
import com.expensetracker.app.data.repo.ImportRepository
import com.expensetracker.app.domain.parser.TextSignals
import com.expensetracker.app.domain.statement.ColumnMapping
import com.expensetracker.app.domain.statement.ColumnRole
import com.expensetracker.app.domain.statement.StatementParseResult
import com.expensetracker.app.sources.statement.StatementImporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

sealed interface ImportStep {
    data object Pick : ImportStep
    data class Working(val message: String) : ImportStep
    data class NeedsPassword(val wrong: Boolean) : ImportStep
    data object Preview : ImportStep
    data class Done(val summary: StatementImporter.Summary) : ImportStep
    data class Error(val message: String) : ImportStep
}

data class StatementUiState(
    val step: ImportStep = ImportStep.Pick,
    val file: StatementImporter.StoredFile? = null,
    val accountId: Long? = null,
    val result: StatementParseResult? = null,
    val rows: List<StatementImporter.PreviewRow> = emptyList(),
    val selected: Set<Int> = emptySet(),
    val tabular: List<List<String>>? = null,
    val usedOcr: Boolean = false,
)

@HiltViewModel
class StatementImportViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val importer: StatementImporter,
    private val imports: ImportRepository,
    accounts: AccountRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(StatementUiState())
    val state: StateFlow<StatementUiState> = _state.asStateFlow()
    val accounts: StateFlow<List<AccountEntity>> = accounts.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var loaded: StatementImporter.Loaded? = null
    private var password: String? = null
    private var mapping: ColumnMapping? = null

    init {
        val uri = savedState.get<String>("uri")?.takeIf { it.isNotBlank() }
        val importId = savedState.get<Long>("importId")?.takeIf { it > 0 }
        when {
            importId != null -> reprocess(importId)
            uri != null -> onPicked(Uri.parse(uri))
        }
    }

    fun onPicked(uri: Uri) = viewModelScope.launch {
        _state.value = StatementUiState(step = ImportStep.Working("Reading file…"))
        val file = try {
            importer.store(uri)
        } catch (e: Exception) {
            _state.update { it.copy(step = ImportStep.Error(e.message ?: "Could not open the file")) }
            return@launch
        }
        password = null
        mapping = null
        _state.update { it.copy(file = file) }
        load()
    }

    private fun reprocess(importId: Long) = viewModelScope.launch {
        val record = imports.get(importId)
        val file = record?.storedPath?.let { importer.storedFromPath(it, record.fileName ?: "statement", record.mimeType) }
        if (file == null || !File(file.path).exists()) {
            _state.value = StatementUiState(step = ImportStep.Error("The original file for this import is no longer available. Pick it again."))
            return@launch
        }
        // Reprocessing creates a new history entry; rows imported last time are skipped by their hash.
        _state.value = StatementUiState(file = file, accountId = record.accountId)
        load()
    }

    fun submitPassword(value: String) = viewModelScope.launch {
        password = value
        load()
    }

    private suspend fun load() {
        val file = _state.value.file ?: return
        _state.update { it.copy(step = ImportStep.Working(if (file.kind == StatementImporter.FileKind.IMAGE) "Reading text from image…" else "Reading statement…")) }
        when (val l = importer.load(file, password)) {
            is StatementImporter.Loaded.NeedsPassword -> _state.update { it.copy(step = ImportStep.NeedsPassword(l.wrongPassword)) }
            is StatementImporter.Loaded.Error -> {
                importer.recordFailure(file, l.message, _state.value.accountId)
                _state.update { it.copy(step = ImportStep.Error(l.message)) }
            }
            else -> {
                loaded = l
                if (_state.value.accountId == null) _state.update { it.copy(accountId = guessAccount(l)) }
                parseAndPreview()
            }
        }
    }

    /** Statements usually print the masked account number near the top. */
    private fun guessAccount(l: StatementImporter.Loaded): Long? {
        val head = when (l) {
            is StatementImporter.Loaded.Text -> l.lines.take(40).joinToString(" | ")
            is StatementImporter.Loaded.Tabular -> l.rows.take(15).joinToString(" | ") { it.joinToString(" ") }
            else -> return null
        }
        val last4 = TextSignals.accounts(head).own ?: Regex("(?i)(?:account|a/c|card)[^0-9]{0,20}\\d*[x*]+(\\d{4})").find(head)?.groupValues?.get(1)
        return last4?.let { l4 -> accounts.value.firstOrNull { it.last4 == l4 }?.id }
    }

    private suspend fun parseAndPreview() {
        val l = loaded ?: return
        val file = _state.value.file ?: return
        val result = importer.parse(l, mapping) ?: return
        if (mapping == null) mapping = result.mapping
        val account = accounts.value.firstOrNull { it.id == _state.value.accountId }
        val rows = importer.preview(file, result, account)
        if (rows.isEmpty() && (l !is StatementImporter.Loaded.Tabular)) {
            val reason = result.failed.firstOrNull()?.reason ?: "No transactions were recognised in this file"
            importer.recordFailure(file, "No valid transactions: $reason", account?.id)
            _state.update { it.copy(step = ImportStep.Error("No transactions could be read from ${file.displayName}. $reason."), result = result) }
            return
        }
        _state.update {
            it.copy(
                step = ImportStep.Preview,
                result = result,
                rows = rows,
                selected = rows.indices.filter { i -> !rows[i].alreadyImported }.toSet(),
                tabular = (l as? StatementImporter.Loaded.Tabular)?.rows,
                usedOcr = (l as? StatementImporter.Loaded.Text)?.usedOcr == true,
            )
        }
    }

    fun setAccount(id: Long?) = viewModelScope.launch {
        _state.update { it.copy(accountId = id) }
        if (loaded != null) parseAndPreview()
    }

    fun setMapping(role: ColumnRole, index: Int) = viewModelScope.launch {
        val current = mapping ?: ColumnMapping()
        mapping = current.with(role, index)
        parseAndPreview()
    }

    fun setHeaderRow(index: Int) = viewModelScope.launch {
        mapping = (mapping ?: ColumnMapping()).copy(headerRow = index)
        parseAndPreview()
    }

    fun toggle(i: Int) = _state.update { s -> s.copy(selected = if (i in s.selected) s.selected - i else s.selected + i) }
    fun selectAll(all: Boolean) = _state.update { s -> s.copy(selected = if (all) s.rows.indices.filter { !s.rows[it].alreadyImported }.toSet() else emptySet()) }

    fun commit() = viewModelScope.launch {
        val s = _state.value
        val file = s.file ?: return@launch
        _state.update { it.copy(step = ImportStep.Working("Importing ${s.selected.size} transactions…")) }
        val account = accounts.value.firstOrNull { it.id == s.accountId }
        val summary = importer.commit(file, s.rows, s.selected, account, s.result?.failed?.size ?: 0)
        _state.update { it.copy(step = ImportStep.Done(summary)) }
    }

    fun reset() {
        loaded = null
        mapping = null
        password = null
        _state.value = StatementUiState()
    }
}
