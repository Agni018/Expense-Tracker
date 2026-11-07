package com.expensetracker.app.ui.assistant

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.expensetracker.app.data.repo.CategoryRepository
import com.expensetracker.app.data.repo.RecurringRepository
import com.expensetracker.app.data.repo.TransactionRepository
import com.expensetracker.app.domain.engine.FinanceQueryEngine
import com.expensetracker.app.domain.engine.RecurringCandidate
import com.expensetracker.app.domain.model.RecurringStatus
import com.expensetracker.app.ui.components.SimpleTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class ChatMessage(val fromUser: Boolean, val text: String, val details: List<String> = emptyList())

/** Local question answering over the transaction database; no network or API key needed. */
@HiltViewModel
class AssistantViewModel @Inject constructor(
    private val transactions: TransactionRepository,
    private val categories: CategoryRepository,
    private val recurring: RecurringRepository,
    private val zone: ZoneId,
) : ViewModel() {
    val messages = MutableStateFlow(
        listOf(ChatMessage(false, "Hi! Ask me about your spending. Everything is answered from the data on this phone.")),
    )

    fun ask(question: String) = viewModelScope.launch {
        if (question.isBlank()) return@launch
        messages.update { it + ChatMessage(true, question.trim()) }
        val engine = FinanceQueryEngine(zone, LocalDate.now(zone))
        val ledger = transactions.observeData().first().ledger
        val names = categories.all().associate { it.id to it.name }
        val subs = recurring.all().filter { it.status != RecurringStatus.DISMISSED }.map {
            RecurringCandidate(it.merchantKey, it.displayName, it.categoryId, it.frequency, it.intervalDays, it.expectedAmountMinor, it.lastAmountMinor, it.lastPaidAt, it.nextDueAt, it.occurrences, it.amountChanged, it.isSubscription, it.confidence)
        }
        val answer = engine.answer(question, ledger, names, subs)
        messages.update { it + ChatMessage(false, answer.text, answer.details) }
    }
}

private val suggestions = listOf(
    "How much did I spend this month?",
    "How much did I spend on Swiggy last month?",
    "What were my biggest expenses?",
    "Compare this month with last month",
    "How much money did I receive this year?",
    "Show my subscriptions",
    "Top merchants this month",
)

@Composable
fun AssistantScreen(nav: NavController, vm: AssistantViewModel = hiltViewModel()) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(messages.size) { list.animateScrollToItem(messages.lastIndex.coerceAtLeast(0)) }
    Scaffold(topBar = { SimpleTopBar("Ask", onBack = { nav.popBackStack() }) }, containerColor = MaterialTheme.colorScheme.background) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).imePadding()) {
            LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(messages) { m ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = if (m.fromUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.widthIn(max = 320.dp),
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Text(m.text, color = if (m.fromUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                                m.details.forEach { d -> Text("• $d", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { q -> AssistChip(onClick = { vm.ask(q) }, label = { Text(q) }) }
            }
            Spacer(Modifier.padding(4.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(input, { input = it }, placeholder = { Text("Ask a question") }, singleLine = true, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.large)
                IconButton(onClick = { vm.ask(input); input = "" }, enabled = input.isNotBlank()) { Icon(Icons.AutoMirrored.Rounded.Send, "Send") }
            }
        }
    }
}
