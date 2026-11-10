package com.expensetracker.app.ui.analytics

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.expensetracker.app.core.Money
import com.expensetracker.app.ui.Routes
import com.expensetracker.app.ui.common.Fmt
import com.expensetracker.app.ui.components.DonutChart
import com.expensetracker.app.ui.components.EmojiBadge
import com.expensetracker.app.ui.components.EmptyState
import com.expensetracker.app.ui.components.GroupedBarChart
import com.expensetracker.app.ui.components.LineChart
import com.expensetracker.app.ui.components.PeriodChips
import com.expensetracker.app.ui.components.RankedBars
import com.expensetracker.app.ui.components.SectionCard
import com.expensetracker.app.ui.components.StatBlock
import com.expensetracker.app.ui.components.categoryColor
import com.expensetracker.app.ui.theme.AmountStyle
import com.expensetracker.app.ui.theme.LocalFinanceColors

@Composable
fun AnalyticsScreen(nav: NavController, padding: PaddingValues, vm: AnalyticsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val finance = LocalFinanceColors.current
    val rangeStart = s.range.startMillis(Fmt.zone)
    val rangeEnd = s.range.endMillisExclusive(Fmt.zone)
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("Analytics", style = MaterialTheme.typography.headlineSmall)
            Text(Fmt.range(s.range), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { PeriodChips(s.period, vm::setPeriod) }
        item {
            SectionCard {
                Row {
                    StatBlock("Income", Money.format(s.summary.income, false), Modifier.weight(1f), finance.income)
                    StatBlock("Spending", Money.format(s.summary.netSpend, false), Modifier.weight(1f))
                    StatBlock("Savings", Money.format(s.summary.savings, false), Modifier.weight(1f), if (s.summary.savings >= 0) finance.income else finance.expense)
                }
                Spacer(Modifier.height(14.dp))
                Row {
                    StatBlock("Savings rate", s.summary.savingsRatePct?.let { "${it.toInt()}%" } ?: "–", Modifier.weight(1f))
                    StatBlock("Refunds", Money.format(s.summary.refunds, false), Modifier.weight(1f))
                    StatBlock("Invested", Money.format(s.summary.investments, false), Modifier.weight(1f))
                }
                Spacer(Modifier.height(14.dp))
                val change = s.summary.netSpend - s.previous.netSpend
                if (s.previous.netSpend > 0) {
                    Text(
                        "${if (change >= 0) "▲" else "▼"} ${Money.format(kotlin.math.abs(change), false)} ${if (change >= 0) "more" else "less"} than the previous period",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (change > 0) finance.expense else finance.income,
                    )
                }
                Text(
                    "Not counted as spending: self transfers ${Money.format(s.summary.transfers, false)}, card bill payments ${Money.format(s.summary.cardBillPayments, false)}, cash withdrawals ${Money.format(s.summary.cashWithdrawals, false)}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!s.loading && s.slices.isEmpty() && s.summary.income == 0L) {
            item { EmptyState(Icons.Rounded.PieChart, "No data for this period", "Pick another period or import transactions.") }
            return@LazyColumn
        }
        if (s.slices.isNotEmpty()) {
            item {
                SectionCard(title = "Spending by category") {
                    DonutChart(s.slices, Modifier.fillMaxWidth(0.75f).align(Alignment.CenterHorizontally), onOpen = { slice ->
                        nav.navigate(Routes.filtered(slice.label, categoryId = slice.key, start = rangeStart, end = rangeEnd))
                    })
                    Spacer(Modifier.height(16.dp))
                    s.categoryRows.forEach { row ->
                        Row(
                            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(enabled = row.category != null) {
                                nav.navigate(Routes.filtered(row.category!!.name, categoryId = row.category.id, start = rangeStart, end = rangeEnd))
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            EmojiBadge(row.category?.emoji ?: "❔", categoryColor(row.category?.colorArgb), size = 34.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(row.category?.name ?: "Uncategorised", style = MaterialTheme.typography.bodyMedium)
                                if (s.previous.netSpend > 0 && row.changeMinor != 0L) {
                                    Text(
                                        "${if (row.changeMinor > 0) "+" else "−"}${Money.format(kotlin.math.abs(row.changeMinor), false)} vs previous",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (row.changeMinor > 0) finance.expense else finance.income,
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(Money.format(row.amountMinor, false), style = MaterialTheme.typography.bodyMedium.merge(AmountStyle))
                                Text("${row.sharePct}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        item {
            SectionCard(title = "Income vs spending") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(6, 12).forEach { n -> FilterChip(selected = s.monthsShown == n, onClick = { vm.setMonths(n) }, label = { Text("$n months") }) }
                }
                Spacer(Modifier.height(12.dp))
                GroupedBarChart(s.months, "Income", "Spending")
            }
        }
        if (s.trend.any { it.second > 0 }) {
            item { SectionCard(title = "Spending trend") { LineChart(s.trend) } }
        }
        if (s.merchants.isNotEmpty()) {
            item {
                SectionCard(title = "Top merchants") {
                    RankedBars(s.merchants.map { Triple(it.first, it.second, MaterialTheme.colorScheme.primary) }, onClick = { i ->
                        val m = s.merchants[i].first
                        nav.navigate(Routes.filtered(m, merchant = m, start = rangeStart, end = rangeEnd))
                    })
                }
            }
        }
        if (s.methods.isNotEmpty()) {
            item {
                SectionCard(title = "By payment method") {
                    RankedBars(s.methods.map { Triple(it.first.label, it.second, MaterialTheme.colorScheme.tertiary) })
                }
            }
        }
        if (s.accounts.isNotEmpty()) {
            item {
                SectionCard(title = "By account") {
                    RankedBars(s.accounts.map { Triple(it.first?.name ?: "Unknown account", it.second, MaterialTheme.colorScheme.secondary) }, onClick = { i ->
                        val a = s.accounts[i].first ?: return@RankedBars
                        nav.navigate(Routes.filtered(a.name, accountId = a.id, start = rangeStart, end = rangeEnd))
                    })
                }
            }
        }
        if (s.income.isNotEmpty()) {
            item {
                SectionCard(title = "Income sources") {
                    RankedBars(s.income.map { Triple(it.first?.let { c -> "${c.emoji} ${c.name}" } ?: "Uncategorised", it.second, finance.income) })
                }
            }
        }
        if (s.weekdays.any { it.second > 0 }) {
            item {
                SectionCard(title = "Spending by weekday") {
                    RankedBars(s.weekdays.map { Triple(it.first, it.second, MaterialTheme.colorScheme.primary) })
                }
            }
        }
    }
}
