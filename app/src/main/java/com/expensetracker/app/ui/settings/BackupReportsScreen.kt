package com.expensetracker.app.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.expensetracker.app.sources.backup.BackupManager
import com.expensetracker.app.sources.export.ReportExporter
import com.expensetracker.app.ui.common.Fmt
import com.expensetracker.app.ui.common.PeriodSelection
import com.expensetracker.app.ui.components.ConfirmDialog
import com.expensetracker.app.ui.components.PeriodChips
import com.expensetracker.app.ui.components.SectionCard
import com.expensetracker.app.ui.components.SettingsRow
import com.expensetracker.app.ui.components.SimpleTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class BackupViewModel @Inject constructor(private val backup: BackupManager, private val exporter: ReportExporter) : ViewModel() {
    val busy = MutableStateFlow(false)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val period = MutableStateFlow(PeriodSelection())

    private fun run(block: suspend () -> String) = viewModelScope.launch {
        busy.value = true
        val message = try { block() } catch (e: Exception) { "Failed: ${e.message ?: e.javaClass.simpleName}" }
        busy.value = false
        messages.emit(message)
    }

    fun export(uri: Uri) = run { "Backup saved with ${backup.export(uri)} transactions" }
    fun restore(uri: Uri) = run {
        val r = backup.restore(uri)
        "Restored ${r.added} transactions (${r.skipped} already present), ${r.categories} categories and ${r.accounts} accounts"
    }
    fun csv(uri: Uri) = run { "Exported ${exporter.exportCsv(uri, period.value.range())} transactions to CSV" }
    fun pdf(uri: Uri) = run { "PDF report created with ${exporter.exportPdf(uri, period.value.range(), "Expense report")} transactions" }
}

@Composable
fun BackupReportsScreen(nav: NavController, vm: BackupViewModel = hiltViewModel()) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val period by vm.period.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val stamp = LocalDate.now(Fmt.zone).toString()
    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::export) }
    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { pendingRestore = it }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { it?.let(vm::csv) }
    val exportPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { it?.let(vm::pdf) }

    Scaffold(
        topBar = { SimpleTopBar("Backup & reports", onBack = { nav.popBackStack() }) },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        LazyColumn(contentPadding = PaddingValues(16.dp, inner.calculateTopPadding(), 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (busy) item { LinearProgressIndicator(Modifier) }
            item {
                SectionCard(title = "Backup") {
                    Text("A backup contains transactions, source records, accounts, categories, rules, budgets and goals. Restoring merges into the current data and skips anything already present.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    SettingsRow(Icons.Rounded.Backup, "Export backup", "Save a .json file to your phone or Drive", onClick = { exportBackup.launch("expense-tracker-backup-$stamp.json") })
                    SettingsRow(Icons.Rounded.Restore, "Restore from backup", "Pick a previously exported .json file", onClick = { pickBackup.launch(arrayOf("application/json", "application/octet-stream", "*/*")) })
                }
            }
            item {
                SectionCard(title = "Reports") {
                    PeriodChips(period, { vm.period.value = it })
                    Spacer(Modifier.height(8.dp))
                    Text(Fmt.range(period.range()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SettingsRow(Icons.Rounded.TableChart, "Export CSV", "All transactions in the period, for Excel or Sheets", onClick = { exportCsv.launch("transactions-$stamp.csv") })
                    SettingsRow(Icons.Rounded.PictureAsPdf, "PDF report", "Summary, category and merchant breakdowns, transaction list", onClick = { exportPdf.launch("expense-report-$stamp.pdf") })
                }
            }
        }
    }
    pendingRestore?.let { uri ->
        ConfirmDialog(
            "Restore backup?", "Transactions in the backup that are not already here will be added. Nothing is deleted.", "Restore",
            onConfirm = { vm.restore(uri); pendingRestore = null }, onDismiss = { pendingRestore = null },
        )
    }
}
