package com.expensetracker.app.ui.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.expensetracker.app.core.Money
import com.expensetracker.app.domain.model.PaymentMethod
import com.expensetracker.app.domain.model.PeriodPreset
import com.expensetracker.app.domain.model.SourceType
import com.expensetracker.app.ui.Routes
import com.expensetracker.app.ui.common.Fmt
import com.expensetracker.app.ui.common.PeriodSelection
import com.expensetracker.app.ui.components.CategoryPickerSheet
import com.expensetracker.app.ui.components.ConfirmDialog
import com.expensetracker.app.ui.components.DateRangeDialog
import com.expensetracker.app.ui.components.EmptyState
import com.expensetracker.app.ui.components.SimpleTopBar
import com.expensetracker.app.ui.components.TransactionRow
import com.expensetracker.app.ui.theme.AmountStyle

@Composable
fun TransactionsScreen(nav: NavController, padding: PaddingValues, filtered: Boolean, vm: TransactionsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var showFilters by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    var pickCategory by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pickRange by remember { mutableStateOf(false) }
    val selecting = s.selection.isNotEmpty()

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text("${s.selection.size} selected") },
                    navigationIcon = { IconButton(onClick = vm::clearSelection) { Icon(Icons.Rounded.Close, "Clear selection") } },
                    actions = {
                        IconButton(onClick = vm::selectAll) { Icon(Icons.Rounded.SelectAll, "Select all") }
                        IconButton(onClick = { pickCategory = true }) { Icon(Icons.Rounded.Category, "Categorise") }
                        IconButton(onClick = vm::bulkMarkTransfer) { Icon(Icons.Rounded.SwapHoriz, "Mark as self transfer") }
                        IconButton(onClick = vm::bulkApprove) { Icon(Icons.Rounded.DoneAll, "Mark reviewed") }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, "Delete") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                )
            } else {
                SimpleTopBar(s.title, onBack = if (filtered) ({ nav.popBackStack() }) else null)
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = if (filtered) WindowInsets.navigationBars else WindowInsets(0),
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(top = inner.calculateTopPadding())) {
            OutlinedTextField(
                value = s.query,
                onValueChange = vm::setQuery,
                placeholder = { Text("Search merchant, amount, note", maxLines = 1) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (s.query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Rounded.Close, "Clear") } },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = s.filters.activeCount > 0,
                    onClick = { showFilters = true },
                    label = { Text(if (s.filters.activeCount > 0) "Filters (${s.filters.activeCount})" else "Filters") },
                    leadingIcon = { Icon(Icons.Rounded.FilterList, null) },
                )
                if (s.filters.fixedRange == null) {
                    FilterChip(selected = s.filters.period == null, onClick = { vm.setPeriod(null) }, label = { Text("All time") })
                    listOf(PeriodPreset.THIS_MONTH, PeriodPreset.LAST_MONTH, PeriodPreset.THIS_YEAR).forEach { p ->
                        FilterChip(selected = s.filters.period?.preset == p, onClick = { vm.setPeriod(PeriodSelection(p)) }, label = { Text(p.label) })
                    }
                    val custom = s.filters.period?.takeIf { it.preset == PeriodPreset.CUSTOM }?.custom
                    FilterChip(selected = custom != null, onClick = { pickRange = true }, label = { Text(custom?.let { Fmt.range(it) } ?: "Custom dates") })
                }
                Box {
                    FilterChip(selected = false, onClick = { showSort = true }, label = { Text(s.sort.label) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Sort, null) })
                    DropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
                        SortOrder.entries.forEach { o ->
                            DropdownMenuItem(text = { Text(o.label) }, onClick = { vm.setSort(o); showSort = false })
                        }
                    }
                }
            }
            Row(Modifier.padding(horizontal = 20.dp, vertical = 2.dp)) {
                Text(
                    "${s.count} transactions · spent ${Money.format(s.spentMinor, false)} · received ${Money.format(s.receivedMinor, false)}",
                    style = MaterialTheme.typography.bodySmall.merge(AmountStyle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!s.loading && s.count == 0) {
                EmptyState(
                    Icons.AutoMirrored.Rounded.ReceiptLong,
                    "Nothing here",
                    if (s.query.isNotBlank() || s.filters.activeCount > 0) "No transactions match your search or filters." else "Transactions from SMS, notifications, emails, statements and manual entries appear here.",
                    action = if (s.filters.activeCount > 0) "Reset filters" else null,
                    onAction = vm::resetFilters,
                )
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = padding.calculateBottomPadding() + 88.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                val onClick: (Long) -> Unit = { id -> if (selecting) vm.toggleSelection(id) else nav.navigate(Routes.txn(id)) }
                if (s.groups.isNotEmpty()) {
                    s.groups.forEach { group ->
                        stickyHeader(key = "h-${group.date}") {
                            Row(
                                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(Fmt.dayHeader(group.date), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                if (group.netSpendMinor > 0) {
                                    Text("−${Money.format(group.netSpendMinor, false)}", style = MaterialTheme.typography.labelMedium.merge(AmountStyle), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        items(group.items, key = { it.id }) { t ->
                            TransactionRow(
                                t, t.categoryId?.let { s.categories[it] }, t.accountId?.let { s.accounts[it] },
                                onClick = { onClick(t.id) }, selectionMode = selecting, selected = t.id in s.selection,
                                onLongClick = { vm.toggleSelection(t.id) },
                            )
                        }
                    }
                } else {
                    items(s.flat, key = { it.id }) { t ->
                        TransactionRow(
                            t, t.categoryId?.let { s.categories[it] }, t.accountId?.let { s.accounts[it] },
                            onClick = { onClick(t.id) }, showDate = true, selectionMode = selecting, selected = t.id in s.selection,
                            onLongClick = { vm.toggleSelection(t.id) },
                        )
                    }
                }
            }
        }
    }

    if (showFilters) {
        FilterSheet(s, onDismiss = { showFilters = false }, onApply = { vm.setFilters(it); showFilters = false }, onReset = { vm.resetFilters(); showFilters = false })
    }
    if (pickRange) {
        DateRangeDialog(s.filters.period?.custom, onDismiss = { pickRange = false }) { range ->
            vm.setPeriod(PeriodSelection(PeriodPreset.CUSTOM, range))
            pickRange = false
        }
    }
    if (pickCategory) {
        CategoryPickerSheet(s.categories.values.toList(), null, onDismiss = { pickCategory = false }) {
            vm.bulkCategorize(it.id)
            pickCategory = false
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            "Delete ${s.selection.size} transactions?",
            "They will not be re-imported by later scans. This cannot be undone.",
            "Delete",
            onConfirm = { vm.bulkDelete(); confirmDelete = false },
            onDismiss = { confirmDelete = false },
            destructive = true,
        )
    }
}

@Composable
private fun FilterSheet(s: TxnListState, onDismiss: () -> Unit, onApply: (TxnFilters) -> Unit, onReset: () -> Unit) {
    var f by remember { mutableStateOf(s.filters) }
    var min by remember { mutableStateOf(f.minAmountMinor?.let { Money.formatPlain(it) }.orEmpty()) }
    var max by remember { mutableStateOf(f.maxAmountMinor?.let { Money.formatPlain(it) }.orEmpty()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("Filters", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                FlowFilter.entries.forEachIndexed { i, ff ->
                    SegmentedButton(selected = f.flow == ff, onClick = { f = f.copy(flow = ff) }, shape = SegmentedButtonDefaults.itemShape(i, FlowFilter.entries.size)) {
                        Text(ff.label, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Section("Categories") {
                s.categories.values.filter { !it.isArchived }.sortedBy { it.sortOrder }.forEach { c ->
                    FilterChip(selected = c.id in f.categoryIds, onClick = { f = f.copy(categoryIds = f.categoryIds.toggle(c.id)) }, label = { Text("${c.emoji} ${c.name}") })
                }
            }
            if (s.accounts.isNotEmpty()) {
                Section("Accounts") {
                    s.accounts.values.forEach { a ->
                        FilterChip(selected = a.id in f.accountIds, onClick = { f = f.copy(accountIds = f.accountIds.toggle(a.id)) }, label = { Text(a.name) })
                    }
                }
            }
            Section("Payment method") {
                PaymentMethod.entries.forEach { m ->
                    FilterChip(selected = m in f.methods, onClick = { f = f.copy(methods = f.methods.toggle(m)) }, label = { Text(m.label) })
                }
            }
            Section("Source") {
                SourceType.entries.forEach { src ->
                    FilterChip(selected = src in f.sources, onClick = { f = f.copy(sources = f.sources.toggle(src)) }, label = { Text(src.label) })
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Amount range (₹)", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(min, { min = it }, label = { Text("Min") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                OutlinedTextField(max, { max = it }, label = { Text("Max") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Hide failed and pending", modifier = Modifier.weight(1f))
                Switch(checked = f.hideFailed, onCheckedChange = { f = f.copy(hideFailed = it) })
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f)) { Text("Reset") }
                Button(
                    onClick = { onApply(f.copy(minAmountMinor = Money.parseToMinor(min), maxAmountMinor = Money.parseToMinor(max))) },
                    modifier = Modifier.weight(1f),
                ) { Text("Apply") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(18.dp))
    Text(title, style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

private fun <T> Set<T>.toggle(v: T): Set<T> = if (v in this) this - v else this + v
