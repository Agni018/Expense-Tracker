package com.expensetracker.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.expensetracker.app.core.Money
import com.expensetracker.app.domain.engine.InsightKind
import com.expensetracker.app.domain.model.PeriodPreset
import com.expensetracker.app.ui.Routes
import com.expensetracker.app.ui.common.Fmt
import com.expensetracker.app.ui.components.BudgetBar
import com.expensetracker.app.ui.components.DonutChart
import com.expensetracker.app.ui.components.EmojiBadge
import com.expensetracker.app.ui.components.EmptyState
import com.expensetracker.app.ui.components.IconBadge
import com.expensetracker.app.ui.components.LegendDot
import com.expensetracker.app.ui.components.LineChart
import com.expensetracker.app.ui.components.PeriodChips
import com.expensetracker.app.ui.components.RankedBars
import com.expensetracker.app.ui.components.SectionCard
import com.expensetracker.app.ui.components.TransactionRow
import com.expensetracker.app.ui.components.categoryColor
import com.expensetracker.app.ui.switchTab
import com.expensetracker.app.ui.theme.AmountStyle
import com.expensetracker.app.ui.theme.LocalFinanceColors
import java.time.LocalTime

@Composable
fun HomeScreen(nav: NavController, padding: PaddingValues, vm: HomeViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val finance = LocalFinanceColors.current
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 88.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val hour = LocalTime.now().hour
                    val greet = when (hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }
                    Text(if (s.name.isBlank()) greet else "$greet, ${s.name}", style = MaterialTheme.typography.headlineSmall)
                    Text(Fmt.range(s.range), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { nav.navigate(Routes.ASSISTANT) }) { Icon(Icons.Rounded.QuestionAnswer, contentDescription = "Ask about your spending") }
            }
        }
        item { PeriodChips(s.period, vm::setPeriod) }
        item { HeroCard(s) }

        if (!s.smsPermission) {
            item {
                ActionBanner(
                    icon = Icons.Rounded.Sms,
                    title = "Turn on automatic tracking",
                    message = "Allow SMS access so bank alerts are recorded automatically.",
                    action = "Set up",
                    onClick = { nav.switchTab(Routes.SETTINGS) },
                )
            }
        }
        if (s.reviewCount > 0) {
            item {
                ActionBanner(
                    icon = Icons.Rounded.Warning,
                    title = "${s.reviewCount} transaction${if (s.reviewCount == 1) "" else "s"} to review",
                    message = "Uncertain or possibly duplicate entries are waiting for your confirmation.",
                    action = "Review",
                    tint = finance.warning,
                    onClick = { nav.navigate(Routes.REVIEW) },
                )
            }
        }
        if (!s.loading && !s.hasAnyTransactions) {
            item {
                SectionCard {
                    EmptyState(
                        icon = Icons.Rounded.AutoAwesome,
                        title = "No transactions yet",
                        message = "Scan your SMS inbox, import a bank statement or add an expense with the + button.",
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                        FilledTonalButton(onClick = { nav.switchTab(Routes.SETTINGS) }) { Text("Scan SMS") }
                        OutlinedButton(onClick = { nav.navigate(Routes.statement()) }) { Text("Import statement") }
                    }
                }
            }
            return@LazyColumn
        }

        item {
            SectionCard(title = "Where your money went", action = "Analytics", onAction = { nav.switchTab(Routes.ANALYTICS) }) {
                if (s.slices.isEmpty()) {
                    Text("No spending in this period.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DonutChart(s.slices, Modifier.width(150.dp), onOpen = { slice ->
                            nav.navigate(Routes.filtered(slice.label, categoryId = slice.key, start = s.range.startMillis(Fmt.zone), end = s.range.endMillisExclusive(Fmt.zone)))
                        })
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val total = s.slices.sumOf { it.value }.coerceAtLeast(1)
                            s.slices.take(6).forEach { slice ->
                                Row(
                                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(enabled = slice.key != null && slice.key > 0) {
                                        nav.navigate(Routes.filtered(slice.label, categoryId = slice.key, start = s.range.startMillis(Fmt.zone), end = s.range.endMillisExclusive(Fmt.zone)))
                                    },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    LegendDot(slice.color)
                                    Spacer(Modifier.width(8.dp))
                                    Text(slice.label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Text("${slice.value * 100 / total}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (s.trend.any { it.second > 0 }) {
            item {
                SectionCard(title = if (s.trendIsMonthly) "Monthly spending" else "Daily spending") {
                    LineChart(s.trend)
                }
            }
        }

        item {
            SectionCard(title = "Budgets", action = if (s.overallBudget == null && s.categoryBudgets.isEmpty()) "Set budget" else "Manage", onAction = { nav.switchTab(Routes.BUDGETS) }) {
                val overall = s.overallBudget
                if (overall == null && s.categoryBudgets.isEmpty()) {
                    Text("Set a monthly limit to see how much you have left and get alerts at 50%, 80% and 100%.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                if (overall != null) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            Text("This month", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                if (overall.remainingMinor >= 0) "${Money.format(overall.remainingMinor, false)} left" else "${Money.format(-overall.remainingMinor, false)} over",
                                style = MaterialTheme.typography.titleLarge.merge(AmountStyle),
                                color = if (overall.isExceeded) finance.expense else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Text("${Money.format(overall.spentMinor, false)} / ${Money.format(overall.effectiveLimitMinor, false)}", style = MaterialTheme.typography.bodySmall.merge(AmountStyle), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    BudgetBar((overall.utilizationPct / 100).toFloat(), over = overall.isExceeded, warn = overall.utilizationPct >= 80 || overall.willExceed)
                    Spacer(Modifier.height(12.dp))
                }
                s.categoryBudgets.take(3).forEach { (category, st) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                        EmojiBadge(category?.emoji ?: "📦", categoryColor(category?.colorArgb), size = 32.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row {
                                Text(category?.name ?: "", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
                                Text("${st.utilizationPct.toInt()}%", style = MaterialTheme.typography.labelMedium, color = if (st.isExceeded) finance.expense else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(4.dp))
                            BudgetBar((st.utilizationPct / 100).toFloat(), height = 6.dp, over = st.isExceeded, warn = st.utilizationPct >= 80 || st.willExceed)
                        }
                    }
                }
            }
        }

        if (s.insights.isNotEmpty()) {
            item {
                Text("Insights", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(s.insights, key = { it.id }) { insight ->
                        val tint = when (insight.kind) {
                            InsightKind.POSITIVE -> finance.income
                            InsightKind.WARNING -> finance.warning
                            InsightKind.INFO -> MaterialTheme.colorScheme.primary
                        }
                        Card(
                            Modifier.width(260.dp),
                            shape = MaterialTheme.shapes.large,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                IconBadge(
                                    when (insight.kind) {
                                        InsightKind.POSITIVE -> Icons.AutoMirrored.Rounded.TrendingDown
                                        InsightKind.WARNING -> Icons.AutoMirrored.Rounded.TrendingUp
                                        InsightKind.INFO -> Icons.Rounded.Info
                                    },
                                    tint = tint, size = 32.dp,
                                )
                                Spacer(Modifier.height(10.dp))
                                Text(insight.title, style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(4.dp))
                                Text(insight.message, style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(6.dp))
                                Text(insight.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        if (s.upcoming.isNotEmpty()) {
            item {
                SectionCard(title = "Upcoming payments", action = "All bills", onAction = { nav.switchTab(Routes.BUDGETS) }) {
                    s.upcoming.forEach { r ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(Icons.Rounded.Repeat, size = 34.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.displayName, style = MaterialTheme.typography.bodyMedium)
                                Text("Expected ${Fmt.dayHeader(Fmt.date(r.nextDueAt))} · ${r.frequency.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(Money.format(r.expectedAmountMinor, false), style = MaterialTheme.typography.bodyMedium.merge(AmountStyle))
                        }
                    }
                }
            }
        }

        if (s.topMerchants.isNotEmpty()) {
            item {
                SectionCard(title = "Top merchants") {
                    RankedBars(
                        s.topMerchants.map { Triple(it.first, it.second, MaterialTheme.colorScheme.primary) },
                        onClick = { i ->
                            val m = s.topMerchants[i].first
                            nav.navigate(Routes.filtered(m, merchant = m, start = s.range.startMillis(Fmt.zone), end = s.range.endMillisExclusive(Fmt.zone)))
                        },
                    )
                }
            }
        }

        item {
            SectionCard(title = "Recent transactions", action = "See all", onAction = { nav.switchTab(Routes.TRANSACTIONS) }, contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 16.dp, bottom = 8.dp)) {
                s.recent.forEach { t ->
                    TransactionRow(t, t.categoryId?.let { s.categories[it] }, t.accountId?.let { s.accounts[it] }, onClick = { nav.navigate(Routes.txn(t.id)) }, showDate = true)
                }
            }
        }
    }
}

@Composable
private fun HeroCard(s: HomeState) {
    val finance = LocalFinanceColors.current
    val label = when (s.period.preset) {
        PeriodPreset.TODAY -> "Spent today"
        PeriodPreset.THIS_WEEK -> "Spent this week"
        PeriodPreset.THIS_MONTH -> "Spent this month"
        PeriodPreset.LAST_MONTH -> "Spent last month"
        PeriodPreset.THIS_YEAR -> "Spent this year"
        PeriodPreset.CUSTOM -> "Spent in this period"
    }
    Box(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraLarge)
            .background(Brush.linearGradient(listOf(finance.heroStart, finance.heroEnd)))
            .padding(20.dp),
    ) {
        Column {
            Text(label, style = MaterialTheme.typography.labelLarge, color = finance.onHero.copy(alpha = 0.8f))
            Spacer(Modifier.height(4.dp))
            Text(Money.format(s.summary.netSpend, showPaise = false), style = MaterialTheme.typography.displaySmall.merge(AmountStyle), color = finance.onHero)
            if (s.summary.refunds > 0) {
                Text("after ${Money.format(s.summary.refunds, false)} in refunds", style = MaterialTheme.typography.bodySmall, color = finance.onHero.copy(alpha = 0.75f))
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeroStat("Income", Money.format(s.summary.income, false), Modifier.weight(1f))
                HeroStat(if (s.summary.savings >= 0) "Saved" else "Overspent", Money.format(kotlin.math.abs(s.summary.savings), false), Modifier.weight(1f))
                HeroStat("Transactions", s.summary.countedTransactions.toString(), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String, modifier: Modifier) {
    val finance = LocalFinanceColors.current
    Column(modifier.clip(MaterialTheme.shapes.medium).background(Color.White.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = finance.onHero.copy(alpha = 0.8f), maxLines = 1)
        Text(value, style = MaterialTheme.typography.titleSmall.merge(AmountStyle), color = finance.onHero, maxLines = 1)
    }
}

@Composable
private fun ActionBanner(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String,
    action: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.10f)),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Text(action, color = tint, style = MaterialTheme.typography.labelLarge)
        }
    }
}
