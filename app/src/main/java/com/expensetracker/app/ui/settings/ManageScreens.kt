package com.expensetracker.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.expensetracker.app.core.Money
import com.expensetracker.app.data.db.AccountEntity
import com.expensetracker.app.data.db.CategoryEntity
import com.expensetracker.app.data.db.RuleEntity
import com.expensetracker.app.data.ingest.TransactionIngestor
import com.expensetracker.app.data.repo.AccountRepository
import com.expensetracker.app.data.repo.CategoryRepository
import com.expensetracker.app.data.repo.RuleRepository
import com.expensetracker.app.data.repo.TransactionRepository
import com.expensetracker.app.domain.engine.FinanceCalculator
import com.expensetracker.app.domain.model.AccountType
import com.expensetracker.app.domain.model.CategoryKind
import com.expensetracker.app.domain.model.PeriodPreset
import com.expensetracker.app.domain.model.RuleMatchType
import com.expensetracker.app.domain.model.RuleOrigin
import com.expensetracker.app.domain.model.TxnType
import com.expensetracker.app.ui.Routes
import com.expensetracker.app.ui.common.Fmt
import com.expensetracker.app.ui.components.ConfirmDialog
import com.expensetracker.app.ui.components.DropdownField
import com.expensetracker.app.ui.components.EmojiBadge
import com.expensetracker.app.ui.components.EmptyState
import com.expensetracker.app.ui.components.IconBadge
import com.expensetracker.app.ui.components.Pill
import com.expensetracker.app.ui.components.SectionCard
import com.expensetracker.app.ui.components.SimpleTopBar
import com.expensetracker.app.ui.components.categoryColor
import com.expensetracker.app.ui.theme.AmountStyle
import com.expensetracker.app.ui.theme.LocalFinanceColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

// ---------------------------------------------------------------- Accounts

data class AccountRow(val account: AccountEntity, val computedBalance: Long, val spentThisMonth: Long, val count: Int)

@HiltViewModel
class AccountsViewModel @Inject constructor(private val accounts: AccountRepository, transactions: TransactionRepository) : ViewModel() {
    val rows = combine(accounts.observeAll(), transactions.observeData()) { list, data ->
        val month = PeriodPreset.THIS_MONTH.range(LocalDate.now(Fmt.zone))
        list.map { a ->
            val entries = data.ledger.filter { it.accountId == a.id }
            val sinceOpening = entries.filter { it.timestamp >= a.openingBalanceAt }
            val monthEntries = entries.filter { it.timestamp >= month.startMillis(Fmt.zone) && it.timestamp < month.endMillisExclusive(Fmt.zone) }
            AccountRow(a, FinanceCalculator.accountBalance(a.openingBalanceMinor, sinceOpening), FinanceCalculator.summarize(monthEntries).netSpend, entries.size)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(a: AccountEntity) = viewModelScope.launch { accounts.save(a) }
    fun delete(a: AccountEntity) = viewModelScope.launch { accounts.delete(a) }
}

@Composable
fun AccountsScreen(nav: NavController, vm: AccountsViewModel = hiltViewModel()) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<AccountEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    val finance = LocalFinanceColors.current
    Scaffold(
        topBar = { SimpleTopBar("Accounts", onBack = { nav.popBackStack() }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Add account") }) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        LazyColumn(contentPadding = PaddingValues(16.dp, inner.calculateTopPadding(), 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(
                    "Accounts are created automatically from the last digits in bank alerts. Set an opening balance to track the running balance and compare it with the balance your bank reports.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(rows, key = { it.account.id }) { row ->
                val a = row.account
                SectionCard(modifier = Modifier.clickable { editing = a }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(
                            when (a.type) {
                                AccountType.CREDIT_CARD -> Icons.Rounded.CreditCard
                                AccountType.CASH -> Icons.Rounded.Payments
                                AccountType.WALLET, AccountType.UPI -> Icons.Rounded.AccountBalanceWallet
                                else -> Icons.Rounded.AccountBalance
                            },
                            tint = Color(a.colorArgb),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.name, style = MaterialTheme.typography.titleSmall)
                            Text(listOfNotNull(a.type.label, a.institution, a.last4?.let { "••$it" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (a.autoDetected) Pill("Auto")
                    }
                    Spacer(Modifier.size(10.dp))
                    Row {
                        Column(Modifier.weight(1f)) {
                            Text("Spent this month", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(Money.format(row.spentThisMonth, false), style = MaterialTheme.typography.bodyMedium.merge(AmountStyle))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(if (a.type == AccountType.CREDIT_CARD) "Net since opening" else "Computed balance", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(Money.format(row.computedBalance, false), style = MaterialTheme.typography.bodyMedium.merge(AmountStyle))
                        }
                        a.reportedBalanceMinor?.let { reported ->
                            Column(Modifier.weight(1f)) {
                                Text("Bank reported", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(Money.format(reported, false), style = MaterialTheme.typography.bodyMedium.merge(AmountStyle))
                            }
                        }
                    }
                    a.reportedBalanceMinor?.let { reported ->
                        val diff = reported - row.computedBalance
                        if (a.openingBalanceAt > 0 && diff != 0L) {
                            Text(
                                "Difference ${Money.format(diff, false)} – some transactions may be missing or the opening balance needs updating (reported ${Fmt.relative(a.reportedBalanceAt ?: 0)}).",
                                style = MaterialTheme.typography.bodySmall, color = finance.warning,
                            )
                        }
                    }
                    TextButton(onClick = { nav.navigate(Routes.filtered(a.name, accountId = a.id)) }) { Text("${row.count} transactions") }
                }
            }
        }
    }
    if (adding || editing != null) {
        AccountDialog(editing, onDismiss = { adding = false; editing = null }, onDelete = editing?.let { e -> { vm.delete(e); editing = null } }) {
            vm.save(it)
            adding = false
            editing = null
        }
    }
}

@Composable
private fun AccountDialog(existing: AccountEntity?, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (AccountEntity) -> Unit) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var type by remember { mutableStateOf(existing?.type ?: AccountType.BANK) }
    var institution by remember { mutableStateOf(existing?.institution.orEmpty()) }
    var last4 by remember { mutableStateOf(existing?.last4.orEmpty()) }
    var opening by remember { mutableStateOf(existing?.openingBalanceMinor?.takeIf { it != 0L }?.let { Money.formatPlain(it) }.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New account" else "Edit account") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                DropdownField("Type", type, AccountType.entries, { it.label }, { type = it })
                OutlinedTextField(institution, { institution = it }, label = { Text("Bank / provider") }, singleLine = true)
                OutlinedTextField(last4, { v -> last4 = v.filter(Char::isDigit).take(4) }, label = { Text("Last 4 digits") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(
                    opening, { opening = it }, label = { Text("Balance today (₹)") }, singleLine = true,
                    supportingText = { Text("Sets the opening balance from today for balance tracking") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                val openingMinor = Money.parseToMinor(opening)
                val base = existing ?: AccountEntity(name = name, type = type, colorArgb = TransactionIngestor.ACCOUNT_COLORS[(name.hashCode() and 0x7fffffff) % TransactionIngestor.ACCOUNT_COLORS.size], createdAt = System.currentTimeMillis())
                val openingChanged = openingMinor != null && openingMinor != existing?.openingBalanceMinor
                onSave(
                    base.copy(
                        name = name.trim(), type = type, institution = institution.trim().ifEmpty { null }, last4 = last4.ifEmpty { null },
                        openingBalanceMinor = openingMinor ?: base.openingBalanceMinor,
                        openingBalanceAt = if (openingChanged) System.currentTimeMillis() else base.openingBalanceAt,
                    ),
                )
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
    if (confirmDelete && onDelete != null) {
        ConfirmDialog("Delete account?", "Its transactions stay but will no longer be linked to an account.", "Delete", onConfirm = { confirmDelete = false; onDelete() }, onDismiss = { confirmDelete = false }, destructive = true)
    }
}

// ---------------------------------------------------------------- Categories

@HiltViewModel
class CategoriesViewModel @Inject constructor(private val categories: CategoryRepository) : ViewModel() {
    val list = categories.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun save(c: CategoryEntity) = viewModelScope.launch { categories.save(c) }
    fun delete(c: CategoryEntity) = viewModelScope.launch { categories.delete(c) }
}

private val palette = listOf(
    0xFFF97316, 0xFF22C55E, 0xFFEC4899, 0xFFEAB308, 0xFFEF4444, 0xFF0EA5E9, 0xFF8B5CF6, 0xFF14B8A6,
    0xFF6366F1, 0xFF3B82F6, 0xFFD946EF, 0xFFF43F5E, 0xFF84CC16, 0xFF64748B, 0xFFB45309, 0xFF06B6D4,
).map { it.toInt() }

private val emojiChoices = listOf(
    "🍔", "🛒", "🛍️", "🚕", "⛽", "🛣️", "🚆", "✈️", "🏠", "💡", "🌐", "📱", "🎬", "💊", "🎓", "🔁", "🛡️", "🏦", "🤝", "🏪",
    "💇", "📦", "📈", "🔄", "🏧", "💳", "💼", "💰", "↩️", "🧾", "🐶", "👶", "🎁", "🍺", "☕", "🏋️", "🧹", "🛠️", "⚽", "🎮",
)

@Composable
fun CategoriesScreen(nav: NavController, vm: CategoriesViewModel = hiltViewModel()) {
    val list by vm.list.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    Scaffold(
        topBar = { SimpleTopBar("Categories", onBack = { nav.popBackStack() }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("New category") }) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        LazyColumn(contentPadding = PaddingValues(16.dp, inner.calculateTopPadding(), 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CategoryKind.entries.forEach { kind ->
                val items = list.filter { it.kind == kind }
                if (items.isEmpty()) return@forEach
                item(key = "h-$kind") {
                    Text(
                        when (kind) { CategoryKind.EXPENSE -> "Spending"; CategoryKind.INCOME -> "Income"; CategoryKind.NEUTRAL -> "Not counted as spending" },
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                }
                items(items, key = { it.id }) { c ->
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { editing = c }.padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EmojiBadge(c.emoji, categoryColor(c.colorArgb))
                        Spacer(Modifier.width(12.dp))
                        Text(c.name, modifier = Modifier.weight(1f), color = if (c.isArchived) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        if (c.isArchived) Pill("Hidden")
                    }
                }
            }
        }
    }
    if (adding || editing != null) {
        CategoryDialog(editing, onDismiss = { adding = false; editing = null }, onDelete = editing?.takeIf { !it.isDefault }?.let { e -> { vm.delete(e); editing = null } }) {
            vm.save(it)
            adding = false
            editing = null
        }
    }
}

@Composable
private fun CategoryDialog(existing: CategoryEntity?, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (CategoryEntity) -> Unit) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var emoji by remember { mutableStateOf(existing?.emoji ?: "📦") }
    var color by remember { mutableStateOf(existing?.colorArgb ?: palette.first()) }
    var kind by remember { mutableStateOf(existing?.kind ?: CategoryKind.EXPENSE) }
    var archived by remember { mutableStateOf(existing?.isArchived ?: false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New category" else "Edit category") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                DropdownField("Kind", kind, CategoryKind.entries, { when (it) { CategoryKind.EXPENSE -> "Spending"; CategoryKind.INCOME -> "Income"; CategoryKind.NEUTRAL -> "Not spending" } }, { kind = it })
                Text("Icon", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    emojiChoices.forEach { e ->
                        Box(
                            Modifier.size(38.dp).clip(CircleShape).background(if (e == emoji) MaterialTheme.colorScheme.primaryContainer else Color.Transparent).clickable { emoji = e },
                            contentAlignment = Alignment.Center,
                        ) { Text(e) }
                    }
                }
                Text("Colour", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    palette.forEach { c ->
                        Box(
                            Modifier.size(30.dp).clip(CircleShape).background(Color(c))
                                .border(if (c == color) 3.dp else 0.dp, if (c == color) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                                .clickable { color = c },
                        )
                    }
                }
                if (existing != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Hide from pickers", modifier = Modifier.weight(1f))
                        Switch(archived, { archived = it })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onSave((existing ?: CategoryEntity(name = name, emoji = emoji, colorArgb = color, kind = kind)).copy(name = name.trim(), emoji = emoji, colorArgb = color, kind = kind, isArchived = archived))
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

// ---------------------------------------------------------------- Rules

@HiltViewModel
class RulesViewModel @Inject constructor(private val rules: RuleRepository, categories: CategoryRepository) : ViewModel() {
    val rulesList = rules.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val categories = categories.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 2)

    fun add(pattern: String, matchType: RuleMatchType, categoryId: Long, type: TxnType?, apply: Boolean) = viewModelScope.launch {
        val changed = rules.add(pattern, matchType, categoryId, type, apply)
        messages.emit(if (apply) "Rule saved and applied to $changed transactions" else "Rule saved")
    }

    fun delete(rule: RuleEntity) = viewModelScope.launch { rules.delete(rule.id) }
}

@Composable
fun RulesScreen(nav: NavController, vm: RulesViewModel = hiltViewModel()) {
    val rules by vm.rulesList.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    val byId = categories.associateBy { it.id }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    Scaffold(
        topBar = { SimpleTopBar("Categorisation rules", onBack = { nav.popBackStack() }) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("New rule") }) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        LazyColumn(contentPadding = PaddingValues(16.dp, inner.calculateTopPadding(), 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(
                    "Rules run before the built-in merchant list. When you change a merchant's category twice, a learned rule is added automatically.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (rules.isEmpty()) item { EmptyState(Icons.Rounded.Tune, "No rules yet", "Create one, or change a transaction's category and choose \"Always use this category\".") }
            items(rules, key = { it.id }) { r ->
                val c = byId[r.categoryId]
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${r.matchType.label} \"${r.pattern}\"", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "→ ${c?.let { "${it.emoji} ${it.name}" } ?: "Deleted category"}" + (r.typeOverride?.let { " · as ${it.label.lowercase()}" } ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text("Used ${r.hitCount} times", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Pill(if (r.origin == RuleOrigin.USER) "Yours" else "Learned")
                        IconButton(onClick = { vm.delete(r) }) { Icon(Icons.Rounded.Delete, "Delete rule") }
                    }
                }
            }
        }
    }
    if (adding) {
        var pattern by remember { mutableStateOf("") }
        var matchType by remember { mutableStateOf(RuleMatchType.CONTAINS) }
        var category by remember { mutableStateOf(categories.firstOrNull()) }
        var type by remember { mutableStateOf<TxnType?>(null) }
        var apply by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("New rule") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DropdownField("Match", matchType, RuleMatchType.entries, { it.label }, { matchType = it })
                    OutlinedTextField(pattern, { pattern = it }, label = { Text(if (matchType == RuleMatchType.CONTAINS) "Text in merchant or description" else "Merchant name") }, singleLine = true)
                    DropdownField("Category", category, categories.filter { !it.isArchived }, { it?.let { c -> "${c.emoji} ${c.name}" } ?: "Choose" }, { category = it })
                    DropdownField("Treat as", type, listOf(null) + TxnType.entries, { it?.label ?: "Keep detected type" }, { type = it })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Apply to existing transactions", modifier = Modifier.weight(1f))
                        Switch(apply, { apply = it })
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = pattern.isNotBlank() && category != null, onClick = {
                    vm.add(pattern, matchType, category!!.id, type, apply)
                    adding = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}
