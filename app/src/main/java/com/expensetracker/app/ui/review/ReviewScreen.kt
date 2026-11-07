package com.expensetracker.app.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.expensetracker.app.core.Money
import com.expensetracker.app.data.db.AccountEntity
import com.expensetracker.app.data.db.CategoryEntity
import com.expensetracker.app.data.db.SourceRecordEntity
import com.expensetracker.app.data.db.TransactionEntity
import com.expensetracker.app.data.repo.AccountRepository
import com.expensetracker.app.data.repo.CategoryRepository
import com.expensetracker.app.data.repo.TransactionRepository
import com.expensetracker.app.ui.Routes
import com.expensetracker.app.ui.common.Fmt
import com.expensetracker.app.ui.components.EmptyState
import com.expensetracker.app.ui.components.SectionCard
import com.expensetracker.app.ui.components.SimpleTopBar
import com.expensetracker.app.ui.components.TransactionRow
import com.expensetracker.app.ui.theme.LocalFinanceColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReviewState(
    val items: List<TransactionEntity> = emptyList(),
    val unparsed: List<SourceRecordEntity> = emptyList(),
    val categories: Map<Long, CategoryEntity> = emptyMap(),
    val accounts: Map<Long, AccountEntity> = emptyMap(),
    val all: Map<Long, TransactionEntity> = emptyMap(),
)

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val repo: TransactionRepository,
    categories: CategoryRepository,
    accounts: AccountRepository,
) : ViewModel() {
    val state = combine(repo.observeReview(), repo.observeUnparsed(), categories.observeAll(), accounts.observeAll(), repo.observeAll()) { items, unparsed, cats, accs, all ->
        ReviewState(items, unparsed, cats.associateBy { it.id }, accs.associateBy { it.id }, all.associateBy { it.id })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewState())

    fun approve(id: Long) = viewModelScope.launch { repo.approve(id) }
    fun approveAll() = viewModelScope.launch { repo.approveAll(state.value.items.map { it.id }) }
    fun merge(id: Long, into: Long) = viewModelScope.launch { repo.mergeInto(id, into) }
    fun delete(id: Long) = viewModelScope.launch { repo.delete(id) }
    fun dismiss(sourceId: Long) = viewModelScope.launch { repo.dismissUnparsed(sourceId) }
}

@Composable
fun ReviewScreen(nav: NavController, vm: ReviewViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val finance = LocalFinanceColors.current
    Scaffold(
        topBar = {
            SimpleTopBar("Review", onBack = { nav.popBackStack() }) {
                if (tab == 0 && s.items.isNotEmpty()) IconButton(onClick = vm::approveAll) { Icon(Icons.Rounded.DoneAll, "Approve all") }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(top = inner.calculateTopPadding())) {
            PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("To review (${s.items.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Unrecognised (${s.unparsed.size})") })
            }
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (tab == 0) {
                    if (s.items.isEmpty()) item { EmptyState(Icons.Rounded.CheckCircle, "All caught up", "Uncertain or possibly duplicate transactions will appear here.") }
                    items(s.items, key = { it.id }) { t ->
                        SectionCard(contentPadding = PaddingValues(8.dp)) {
                            TransactionRow(t, t.categoryId?.let { s.categories[it] }, t.accountId?.let { s.accounts[it] }, onClick = { nav.navigate(Routes.txn(t.id)) }, showDate = true)
                            Text(t.reviewReason ?: "Please confirm", style = MaterialTheme.typography.bodySmall, color = finance.warning, modifier = Modifier.padding(horizontal = 8.dp))
                            val dup = t.possibleDuplicateOf?.let { s.all[it] }
                            if (dup != null) {
                                Text(
                                    "Looks like: ${dup.merchant ?: dup.type.label} · ${Money.format(dup.amountMinor)} · ${Fmt.dateAndTime(dup.timestamp)} (${dup.sourceType.label})",
                                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                            Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (dup != null) {
                                    Button(onClick = { vm.merge(t.id, dup.id) }) { Text("Merge") }
                                    OutlinedButton(onClick = { vm.approve(t.id) }) { Text("Keep both") }
                                } else {
                                    Button(onClick = { vm.approve(t.id) }) { Text("Correct") }
                                    OutlinedButton(onClick = { nav.navigate(Routes.edit(t.id)) }) { Text("Edit") }
                                }
                                TextButton(onClick = { vm.delete(t.id) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                } else {
                    item {
                        Text(
                            "Messages that look financial but could not be read reliably. Add them manually if they are real payments.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (s.unparsed.isEmpty()) item { EmptyState(Icons.Rounded.Sms, "Nothing unrecognised", "Every financial message so far was understood.") }
                    items(s.unparsed, key = { it.id }) { src ->
                        SectionCard {
                            Text("${src.sourceType.label}${src.sender?.let { " · $it" } ?: ""} · ${Fmt.dateAndTime(src.receivedAt)}", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                src.rawText.orEmpty(),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(10.dp),
                            )
                            src.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { nav.navigate(Routes.edit(sourceId = src.id)) }) { Text("Add manually") }
                                TextButton(onClick = { vm.dismiss(src.id) }) { Text("Dismiss") }
                            }
                        }
                    }
                }
            }
        }
    }
}
