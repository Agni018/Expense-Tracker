package com.expensetracker.app.ui.imports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.expensetracker.app.data.repo.ImportRepository
import com.expensetracker.app.domain.model.ImportKind
import com.expensetracker.app.domain.model.ImportStatus
import com.expensetracker.app.ui.Routes
import com.expensetracker.app.ui.common.Fmt
import com.expensetracker.app.ui.components.EmptyState
import com.expensetracker.app.ui.components.IconBadge
import com.expensetracker.app.ui.components.Pill
import com.expensetracker.app.ui.components.SectionCard
import com.expensetracker.app.ui.components.SimpleTopBar
import com.expensetracker.app.ui.theme.LocalFinanceColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ImportHistoryViewModel @Inject constructor(private val imports: ImportRepository) : ViewModel() {
    val items = imports.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun delete(id: Long) = viewModelScope.launch { imports.delete(id) }
}

@Composable
fun ImportHistoryScreen(nav: NavController, vm: ImportHistoryViewModel = hiltViewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    val finance = LocalFinanceColors.current
    Scaffold(topBar = { SimpleTopBar("Import & scan history", onBack = { nav.popBackStack() }) }, containerColor = MaterialTheme.colorScheme.background) { inner ->
        LazyColumn(contentPadding = PaddingValues(16.dp, inner.calculateTopPadding(), 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (items.isEmpty()) item { EmptyState(Icons.Rounded.History, "No imports yet", "SMS scans, email syncs and statement imports are listed here.") }
            items(items, key = { it.id }) { item ->
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(
                            when (item.kind) {
                                ImportKind.SMS_SCAN -> Icons.Rounded.Sms
                                ImportKind.EMAIL_SYNC, ImportKind.EML_FILE -> Icons.Rounded.Email
                                ImportKind.STATEMENT_CSV, ImportKind.STATEMENT_XLSX -> Icons.Rounded.TableChart
                                ImportKind.STATEMENT_IMAGE -> Icons.Rounded.Image
                                ImportKind.BACKUP_RESTORE -> Icons.Rounded.Restore
                                else -> Icons.Rounded.Description
                            },
                            size = 36.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.kind.label, style = MaterialTheme.typography.titleSmall)
                            Text(listOfNotNull(item.fileName, Fmt.dateAndTime(item.startedAt)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                        Pill(
                            item.status.label,
                            when (item.status) {
                                ImportStatus.SUCCESS -> finance.income
                                ImportStatus.PARTIAL, ImportStatus.NO_TRANSACTIONS, ImportStatus.RUNNING -> finance.warning
                                ImportStatus.FAILED -> finance.expense
                            },
                        )
                    }
                    if (item.status != ImportStatus.RUNNING) {
                        Text(
                            "${item.importedCount} added · ${item.duplicateCount} duplicates/already present · ${item.failedCount} failed · ${item.skippedCount} skipped (of ${item.totalCount})",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier,
                        )
                    }
                    item.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (item.status == ImportStatus.FAILED) finance.expense else MaterialTheme.colorScheme.onSurfaceVariant) }
                    Row {
                        if (item.storedPath != null && File(item.storedPath).exists()) {
                            TextButton(onClick = { nav.navigate(Routes.statement(importId = item.id)) }) { Text("Reprocess") }
                        }
                        TextButton(onClick = { vm.delete(item.id) }) { Text("Remove from history") }
                    }
                }
            }
        }
    }
}
