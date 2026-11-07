package com.expensetracker.app.ui.imports

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.expensetracker.app.core.Money
import com.expensetracker.app.domain.model.Direction
import com.expensetracker.app.domain.model.ImportStatus
import com.expensetracker.app.domain.statement.ColumnRole
import com.expensetracker.app.domain.statement.StatementParseResult
import com.expensetracker.app.ui.Routes
import com.expensetracker.app.ui.common.Fmt
import com.expensetracker.app.ui.components.DropdownField
import com.expensetracker.app.ui.components.EmptyState
import com.expensetracker.app.ui.components.IconBadge
import com.expensetracker.app.ui.components.LoadingBox
import com.expensetracker.app.ui.components.Pill
import com.expensetracker.app.ui.components.SectionCard
import com.expensetracker.app.ui.components.SimpleTopBar
import com.expensetracker.app.ui.theme.AmountStyle
import com.expensetracker.app.ui.theme.LocalFinanceColors

@Composable
fun StatementImportScreen(nav: NavController, vm: StatementImportViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val finance = LocalFinanceColors.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::onPicked) }
    val mimeTypes = arrayOf(
        "application/pdf", "text/csv", "text/comma-separated-values", "text/plain", "text/html",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "image/*", "application/octet-stream",
    )

    Scaffold(
        topBar = { SimpleTopBar("Import statement", onBack = { nav.popBackStack() }) },
        bottomBar = {
            if (s.step == ImportStep.Preview && s.rows.isNotEmpty()) {
                Column(Modifier.padding(16.dp)) {
                    Button(onClick = vm::commit, enabled = s.selected.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text("Import ${s.selected.size} of ${s.rows.size} transactions")
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        when (val step = s.step) {
            ImportStep.Pick -> Column(Modifier.padding(inner).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SectionCard {
                    EmptyState(
                        Icons.Rounded.Description,
                        "Choose a statement file",
                        "Bank or card statements as PDF (password protected is fine), CSV or Excel (.xlsx), a photo or scan of a statement, " +
                            "a PhonePe statement PDF, or Google Pay activity exported from Google Takeout (My Activity.html).",
                        action = "Pick file",
                        onAction = { picker.launch(mimeTypes) },
                    )
                }
                Text(
                    "Rows already recorded from SMS or another import are detected and merged instead of being counted twice.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is ImportStep.Working -> LoadingBox(Modifier.padding(inner), step.message)
            is ImportStep.NeedsPassword -> PasswordStep(step.wrong, Modifier.padding(inner), vm::submitPassword)
            is ImportStep.Error -> Column(Modifier.padding(inner).padding(16.dp)) {
                SectionCard {
                    EmptyState(Icons.Rounded.ErrorOutline, "Import failed", step.message, action = "Pick another file", onAction = { picker.launch(mimeTypes) })
                }
            }
            is ImportStep.Done -> Column(Modifier.padding(inner).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val sum = step.summary
                SectionCard {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        IconBadge(if (sum.status == ImportStatus.NO_TRANSACTIONS) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle, tint = if (sum.status == ImportStatus.NO_TRANSACTIONS) finance.warning else finance.income, size = 64.dp)
                        Spacer(Modifier.height(12.dp))
                        Text(sum.status.label, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(12.dp))
                        Text("${sum.imported} new transactions added", style = MaterialTheme.typography.bodyLarge)
                        Text("${sum.merged} matched existing transactions (merged)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (sum.alreadyImported > 0) Text("${sum.alreadyImported} were already imported", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (sum.skipped > 0) Text("${sum.skipped} rows skipped by you", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (sum.failed > 0) Text("${sum.failed} lines could not be read", color = finance.warning)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { nav.popBackStack() }, modifier = Modifier.weight(1f)) { Text("Done") }
                    OutlinedButton(onClick = { nav.navigate(Routes.IMPORTS) }, modifier = Modifier.weight(1f)) { Text("Import history") }
                }
                TextButton(onClick = vm::reset) { Text("Import another file") }
            }
            ImportStep.Preview -> {
                val result = s.result
                LazyColumn(contentPadding = PaddingValues(16.dp, inner.calculateTopPadding(), 16.dp, inner.calculateBottomPadding() + 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        SectionCard {
                            Text(s.file?.displayName ?: "", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(result?.format, if (s.usedOcr) "read with OCR – please check amounts" else null, "${s.rows.size} transactions", result?.failed?.size?.takeIf { it > 0 }?.let { "$it unreadable lines" })
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            DropdownField(
                                "Account for this statement",
                                accounts.firstOrNull { it.id == s.accountId },
                                listOf(null) + accounts,
                                { it?.name ?: "Not specified" },
                                { vm.setAccount(it?.id) },
                            )
                        }
                    }
                    if (s.tabular != null && result != null) {
                        item { MappingCard(s.tabular!!, result, vm) }
                    }
                    if (s.rows.isEmpty()) {
                        item { Text("No rows were recognised with the current column mapping. Choose the correct date and amount columns above.", color = finance.warning) }
                    } else {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Preview", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                TextButton(onClick = { vm.selectAll(s.selected.size < s.rows.size) }) { Text(if (s.selected.size < s.rows.size) "Select all" else "Select none") }
                            }
                        }
                    }
                    itemsIndexed(s.rows) { i, r ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !r.alreadyImported) { vm.toggle(i) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = i in s.selected, onCheckedChange = { vm.toggle(i) }, enabled = !r.alreadyImported)
                            Column(Modifier.weight(1f)) {
                                Text(r.parsed.merchant ?: r.row.description, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${Fmt.dayShort(r.row.date)} · ${r.parsed.type.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    when {
                                        r.alreadyImported -> Pill("Already imported", finance.neutral)
                                        r.duplicateOf != null -> Pill("Matches existing – will merge", MaterialTheme.colorScheme.primary)
                                    }
                                    if (!r.row.directionCertain) Pill("Check debit/credit", finance.warning)
                                }
                            }
                            Text(
                                (if (r.row.direction == Direction.DEBIT) "−" else "+") + Money.format(r.row.amountMinor),
                                style = MaterialTheme.typography.bodyMedium.merge(AmountStyle),
                                color = if (r.row.direction == Direction.CREDIT) finance.income else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    if (!result?.failed.isNullOrEmpty()) {
                        item {
                            var expanded by remember { mutableStateOf(false) }
                            SectionCard(title = "${result!!.failed.size} lines could not be read", action = if (expanded) "Hide" else "Show", onAction = { expanded = !expanded }) {
                                Text("They are not imported. Add them manually if they are real transactions.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (expanded) {
                                    result.failed.take(50).forEach { f ->
                                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                                        Text("Line ${f.lineNumber}: ${f.reason}", style = MaterialTheme.typography.labelMedium)
                                        Text(f.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PasswordStep(wrong: Boolean, modifier: Modifier, onSubmit: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.Lock)
                Spacer(Modifier.width(12.dp))
                Text("This PDF is password protected", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Banks usually use a combination such as the first letters of your name and date of birth (e.g. RAHU0101) or your customer ID. The password is only used to open the file and is not stored.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                password, { password = it }, label = { Text("PDF password") }, singleLine = true, isError = wrong,
                supportingText = if (wrong) ({ Text("Wrong password, try again") }) else null,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { onSubmit(password) }, enabled = password.isNotEmpty()) { Text("Open") }
        }
    }
}

@Composable
private fun MappingCard(rows: List<List<String>>, result: StatementParseResult, vm: StatementImportViewModel) {
    val mapping = result.mapping ?: return
    var expanded by remember { mutableStateOf(!mapping.isValid || result.rows.isEmpty()) }
    val headerRow = mapping.headerRow
    val width = rows.maxOfOrNull { it.size } ?: 0
    val headers = (0 until width).map { i -> rows.getOrNull(headerRow)?.getOrNull(i)?.takeIf { headerRow >= 0 && it.isNotBlank() } ?: "Column ${i + 1}" }
    SectionCard(title = "Column mapping", action = if (expanded) "Hide" else "Adjust", onAction = { expanded = !expanded }) {
        Text(
            listOf(ColumnRole.DATE, ColumnRole.DESCRIPTION, ColumnRole.DEBIT, ColumnRole.CREDIT, ColumnRole.AMOUNT, ColumnRole.BALANCE)
                .mapNotNull { role -> mapping.indexOf(role).takeIf { it >= 0 }?.let { "${role.label}: ${headers.getOrElse(it) { "?" }}" } }
                .joinToString(" · ").ifEmpty { "Columns not detected" },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (expanded) {
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DropdownField("Header row", headerRow, (-1 until minOf(rows.size, 40)).toList(), { if (it < 0) "No header" else "Row ${it + 1}: ${rows[it].joinToString(" | ").take(40)}" }, { vm.setHeaderRow(it) })
                ColumnRole.entries.forEach { role ->
                    DropdownField(role.label, mapping.indexOf(role), (-1 until width).toList(), { if (it < 0) "Not used" else headers[it] }, { vm.setMapping(role, it) })
                }
            }
        }
    }
}
