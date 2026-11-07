package com.expensetracker.app.ui.budgets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.expensetracker.app.core.Money
import com.expensetracker.app.data.db.CategoryEntity
import com.expensetracker.app.data.db.GoalEntity
import com.expensetracker.app.domain.model.CategoryKind
import com.expensetracker.app.domain.model.RecurringStatus
import com.expensetracker.app.ui.Routes
import com.expensetracker.app.ui.common.Fmt
import com.expensetracker.app.ui.components.BudgetBar
import com.expensetracker.app.ui.components.ConfirmDialog
import com.expensetracker.app.ui.components.DateDialog
import com.expensetracker.app.ui.components.DropdownField
import com.expensetracker.app.ui.components.EmojiBadge
import com.expensetracker.app.ui.components.EmptyState
import com.expensetracker.app.ui.components.IconBadge
import com.expensetracker.app.ui.components.Pill
import com.expensetracker.app.ui.components.SectionCard
import com.expensetracker.app.ui.components.categoryColor
import com.expensetracker.app.ui.theme.AmountStyle
import com.expensetracker.app.ui.theme.LocalFinanceColors
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Composable
fun BudgetsScreen(nav: NavController, padding: PaddingValues, vm: BudgetsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
        Text("Plan", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp))
        PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
            listOf("Budgets", "Bills", "Goals").forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label, maxLines = 1) })
            }
        }
        val contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = padding.calculateBottomPadding() + 24.dp)
        when (tab) {
            0 -> BudgetsTab(s, vm, contentPadding)
            1 -> BillsTab(s, vm, nav, contentPadding)
            else -> GoalsTab(s, vm, contentPadding)
        }
    }
}

@Composable
private fun BudgetsTab(s: BudgetsState, vm: BudgetsViewModel, contentPadding: PaddingValues) {
    val finance = LocalFinanceColors.current
    var editing by remember { mutableStateOf<BudgetRow?>(null) }
    var adding by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.shiftMonth(-1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous month") }
                Text(Fmt.month(s.month.atDay(1)), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                IconButton(onClick = { vm.shiftMonth(1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next month") }
            }
        }
        item {
            val o = s.overall
            SectionCard(title = "Monthly budget", action = if (o == null) "Set" else "Edit", onAction = { if (o == null) adding = true else editing = o }) {
                if (o == null) {
                    Text("Set an overall monthly limit. Spending is measured net of refunds; transfers and card bill payments never count.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                } else {
                    val st = o.status
                    Text(
                        if (st.remainingMinor >= 0) "${Money.format(st.remainingMinor, false)} left" else "${Money.format(-st.remainingMinor, false)} over budget",
                        style = MaterialTheme.typography.headlineSmall.merge(AmountStyle),
                        color = if (st.isExceeded) finance.expense else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "${Money.format(st.spentMinor, false)} spent of ${Money.format(st.effectiveLimitMinor, false)} · ${st.utilizationPct.toInt()}%",
                        style = MaterialTheme.typography.bodySmall.merge(AmountStyle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    BudgetBar((st.utilizationPct / 100).toFloat(), height = 12.dp, over = st.isExceeded, warn = st.utilizationPct >= 80 || st.willExceed)
                    Spacer(Modifier.height(8.dp))
                    if (st.carriedOverMinor > 0) Text("Includes ${Money.format(st.carriedOverMinor, false)} rolled over from last month", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (st.willExceed) Text("At this pace you'll spend about ${Money.format(st.projectedMinor, false)} this month.", style = MaterialTheme.typography.bodySmall, color = finance.warning)
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Category budgets", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = { adding = true }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(4.dp)); Text("Add") }
            }
        }
        if (s.rows.isEmpty()) {
            item { EmptyState(Icons.Rounded.Savings, "No category budgets", "Add limits for categories like Food & Dining or Shopping to track them separately.") }
        }
        items(s.rows, key = { it.budget.id }) { row ->
            val st = row.status
            SectionCard(modifier = Modifier.clickable { editing = row }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EmojiBadge(row.category?.emoji ?: "📦", categoryColor(row.category?.colorArgb))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(row.category?.name ?: "Category", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${Money.format(st.spentMinor, false)} of ${Money.format(st.effectiveLimitMinor, false)}",
                            style = MaterialTheme.typography.bodySmall.merge(AmountStyle),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${st.utilizationPct.toInt()}%", style = MaterialTheme.typography.titleSmall, color = if (st.isExceeded) finance.expense else MaterialTheme.colorScheme.onSurface)
                        when {
                            st.isExceeded -> Pill("Exceeded", finance.expense)
                            st.willExceed -> Pill("On pace to exceed", finance.warning)
                            st.thresholdReached != null -> Pill("${st.thresholdReached}% used", finance.warning)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                BudgetBar((st.utilizationPct / 100).toFloat(), over = st.isExceeded, warn = st.utilizationPct >= 80 || st.willExceed)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (st.remainingMinor >= 0) "${Money.format(st.remainingMinor, false)} remaining" else "${Money.format(-st.remainingMinor, false)} over",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (adding || editing != null) {
        BudgetDialog(
            existing = editing,
            categories = s.categories.filter { it.kind == CategoryKind.EXPENSE && !it.isArchived },
            usedCategoryIds = s.rows.map { it.budget.categoryId }.toSet(),
            hasOverall = s.overall != null,
            onDismiss = { adding = false; editing = null },
            onSave = { categoryId, amount, rollover ->
                vm.saveBudget(editing?.budget?.id ?: 0, categoryId, amount, rollover)
                adding = false
                editing = null
            },
            onDelete = editing?.let { e -> { vm.deleteBudget(e.budget.id); editing = null } },
        )
    }
}

@Composable
private fun BudgetDialog(
    existing: BudgetRow?,
    categories: List<CategoryEntity>,
    usedCategoryIds: Set<Long?>,
    hasOverall: Boolean,
    onDismiss: () -> Unit,
    onSave: (Long?, Long, Boolean) -> Unit,
    onDelete: (() -> Unit)?,
) {
    // null stands for the overall monthly budget, offered only while none exists.
    val options: List<CategoryEntity?> = if (existing != null) listOf(existing.category) else {
        (if (hasOverall) emptyList() else listOf(null)) + categories.filter { it.id !in usedCategoryIds }
    }
    var category by remember { mutableStateOf(existing?.category ?: options.firstOrNull()) }
    var amount by remember { mutableStateOf(existing?.budget?.amountMinor?.let { Money.formatPlain(it) }.orEmpty()) }
    var rollover by remember { mutableStateOf(existing?.budget?.rollover ?: false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New budget" else "Edit budget") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (existing == null) {
                    DropdownField("Budget for", category, options, { it?.let { c -> "${c.emoji} ${c.name}" } ?: "All spending (monthly)" }, { category = it })
                } else {
                    Text(existing.category?.let { "${it.emoji} ${it.name}" } ?: "All spending (monthly)", style = MaterialTheme.typography.titleSmall)
                }
                OutlinedTextField(amount, { amount = it }, label = { Text("Monthly limit (₹)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Roll over unused amount")
                        Text("Adds last month's unspent budget to this month", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(rollover, { rollover = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { Money.parseToMinor(amount)?.takeIf { it > 0 }?.let { onSave(category?.id, it, rollover) } }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun BillsTab(s: BudgetsState, vm: BudgetsViewModel, nav: NavController, contentPadding: PaddingValues) {
    val finance = LocalFinanceColors.current
    val today = LocalDate.now(Fmt.zone)
    val active = s.recurring.filter { it.status != RecurringStatus.DISMISSED }
    LazyColumn(contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Recurring payments per month", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(Money.format(s.monthlySubscriptionMinor, false), style = MaterialTheme.typography.headlineSmall.merge(AmountStyle))
                        Text("${active.size} detected · expected dates are predictions, not payments", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = vm::refreshRecurring, enabled = !s.refreshing) { Icon(Icons.Rounded.Refresh, "Detect again") }
                }
                if (s.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
        if (active.isEmpty()) {
            item {
                EmptyState(Icons.Rounded.Repeat, "No recurring payments yet", "Subscriptions, rent, EMIs and bills show up here after they repeat a few times on a regular schedule.")
            }
        }
        items(active, key = { it.id }) { r ->
            val due = Fmt.date(r.nextDueAt)
            val overdue = due.isBefore(today)
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.Repeat)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f).clickable { nav.navigate(Routes.filtered(r.displayName, merchant = r.displayName)) }) {
                        Text(r.displayName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${r.frequency.label} · ${r.occurrences} payments · last ${Fmt.dayShort(Fmt.date(r.lastPaidAt))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (overdue) "Expected ${Fmt.dayHeader(due)} – not seen yet" else "Next expected ${Fmt.dayHeader(due)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (overdue) finance.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(Money.format(r.expectedAmountMinor, false), style = MaterialTheme.typography.titleSmall.merge(AmountStyle))
                        if (r.amountChanged) Pill("Now ${Money.format(r.lastAmountMinor, false)}", finance.warning)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (r.status == RecurringStatus.SUGGESTED) {
                        Button(onClick = { vm.setRecurringStatus(r, RecurringStatus.CONFIRMED) }) { Text("Confirm") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { vm.setRecurringStatus(r, RecurringStatus.DISMISSED) }) { Text("Not recurring") }
                    } else {
                        Pill("Confirmed", finance.income)
                    }
                    Spacer(Modifier.weight(1f))
                    Text("Remind", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(6.dp))
                    Switch(r.reminderEnabled, { vm.setReminder(r, it) })
                }
            }
        }
        val dismissed = s.recurring.filter { it.status == RecurringStatus.DISMISSED }
        if (dismissed.isNotEmpty()) {
            item { Text("Dismissed", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(dismissed, key = { "d${it.id}" }) { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.displayName, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { vm.setRecurringStatus(r, RecurringStatus.SUGGESTED) }) { Text("Restore") }
                }
            }
        }
    }
}

@Composable
private fun GoalsTab(s: BudgetsState, vm: BudgetsViewModel, contentPadding: PaddingValues) {
    var editing by remember { mutableStateOf<GoalEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var contributeTo by remember { mutableStateOf<GoalRow?>(null) }
    var deleting by remember { mutableStateOf<GoalEntity?>(null) }
    LazyColumn(contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Savings goals", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = { adding = true }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(4.dp)); Text("New goal") }
            }
        }
        if (s.goals.isEmpty()) {
            item { EmptyState(Icons.Rounded.Flag, "No goals yet", "Save towards a trip, a gadget or an emergency fund and track your progress.") }
        }
        items(s.goals, key = { it.goal.id }) { row ->
            val g = row.goal
            val pct = if (g.targetMinor > 0) (row.savedMinor * 100 / g.targetMinor).toInt() else 0
            SectionCard(modifier = Modifier.clickable { editing = g }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EmojiBadge(g.emoji, categoryColor(g.colorArgb), size = 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(g.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${Money.format(row.savedMinor, false)} of ${Money.format(g.targetMinor, false)}" + (g.targetDate?.let { " · by ${Fmt.day(Fmt.date(it))}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall.merge(AmountStyle),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("$pct%", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(10.dp))
                BudgetBar(pct / 100f, height = 10.dp)
                Spacer(Modifier.height(8.dp))
                Row {
                    TextButton(onClick = { contributeTo = row }) { Text("Add money") }
                    TextButton(onClick = { deleting = g }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
                g.targetDate?.let { target ->
                    val remaining = g.targetMinor - row.savedMinor
                    val monthsLeft = ChronoUnit.MONTHS.between(LocalDate.now(Fmt.zone), Fmt.date(target)).coerceAtLeast(1)
                    if (remaining > 0) Text("Save about ${Money.format(remaining / monthsLeft, false)} per month to reach it on time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (adding || editing != null) GoalDialog(editing, onDismiss = { adding = false; editing = null }) { vm.saveGoal(it); adding = false; editing = null }
    contributeTo?.let { row ->
        var amount by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { contributeTo = null },
            title = { Text("Add to ${row.goal.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(amount, { amount = it }, label = { Text("Amount (₹)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    Money.parseToMinor(amount)?.takeIf { it > 0 }?.let { vm.contribute(row.goal.id, it, note.ifBlank { null }) }
                    contributeTo = null
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { contributeTo = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { g ->
        ConfirmDialog("Delete ${g.name}?", "The goal and its saved amounts will be removed.", "Delete", onConfirm = { vm.deleteGoal(g.id); deleting = null }, onDismiss = { deleting = null }, destructive = true)
    }
}

@Composable
private fun GoalDialog(existing: GoalEntity?, onDismiss: () -> Unit, onSave: (GoalEntity) -> Unit) {
    val emojis = listOf("🎯", "✈️", "🏍️", "🚗", "🏠", "💻", "📱", "🎓", "💍", "🏖️", "🛟", "🎁")
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var emoji by remember { mutableStateOf(existing?.emoji ?: "🎯") }
    var target by remember { mutableStateOf(existing?.targetMinor?.let { Money.formatPlain(it) }.orEmpty()) }
    var date by remember { mutableStateOf(existing?.targetDate?.let { Fmt.date(it) }) }
    var pickDate by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New goal" else "Edit goal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    emojis.take(6).forEach { e -> TextButton(onClick = { emoji = e }, modifier = Modifier.weight(1f)) { Text(e, style = if (e == emoji) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    emojis.drop(6).forEach { e -> TextButton(onClick = { emoji = e }, modifier = Modifier.weight(1f)) { Text(e, style = if (e == emoji) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium) } }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Goal name") }, singleLine = true)
                OutlinedTextField(target, { target = it }, label = { Text("Target amount (₹)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedButton(onClick = { pickDate = true }) { Text(date?.let { "Target date: ${Fmt.day(it)}" } ?: "Set a target date (optional)") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val amount = Money.parseToMinor(target)
                if (name.isNotBlank() && amount != null && amount > 0) {
                    onSave(
                        (existing ?: GoalEntity(name = name, emoji = emoji, targetMinor = amount, colorArgb = 0xFF10B981.toInt(), createdAt = System.currentTimeMillis()))
                            .copy(name = name.trim(), emoji = emoji, targetMinor = amount, targetDate = date?.atStartOfDay(Fmt.zone)?.toInstant()?.toEpochMilli()),
                    )
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (pickDate) DateDialog(date ?: LocalDate.now(Fmt.zone).plusMonths(6), onDismiss = { pickDate = false }) { date = it; pickDate = false }
}
