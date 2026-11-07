package com.expensetracker.app.ui.imports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.expensetracker.app.core.Hashing
import com.expensetracker.app.core.Money
import com.expensetracker.app.data.ingest.TransactionIngestor
import com.expensetracker.app.domain.model.SourceType
import com.expensetracker.app.domain.parser.EmailParser
import com.expensetracker.app.domain.parser.MessageParseResult
import com.expensetracker.app.domain.parser.SmsParser
import com.expensetracker.app.ui.Routes
import com.expensetracker.app.ui.common.Fmt
import com.expensetracker.app.ui.components.InfoRow
import com.expensetracker.app.ui.components.SectionCard
import com.expensetracker.app.ui.components.SimpleTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ParserTestViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val sms: SmsParser,
    private val email: EmailParser,
    private val ingestor: TransactionIngestor,
) : ViewModel() {
    val initialText: String = savedState.get<String>("text").orEmpty()
    val saved = MutableSharedFlow<Pair<String, Long?>>(extraBufferCapacity = 2)

    fun parse(text: String, sender: String, asEmail: Boolean): MessageParseResult =
        if (asEmail) email.parse(EmailParser.Email(sender, text.lineSequence().firstOrNull().orEmpty(), text, System.currentTimeMillis()))
        else sms.parse(sender.ifBlank { "SHARED" }, text, System.currentTimeMillis(), allowPersonalSender = true)

    /** Saves a pasted or shared message through the normal pipeline, so duplicates are still merged. */
    fun save(text: String, sender: String, asEmail: Boolean) = viewModelScope.launch {
        val result = parse(text, sender, asEmail)
        if (result !is MessageParseResult.Transaction) {
            saved.emit("Nothing to save – this message is not a completed transaction" to null)
            return@launch
        }
        val type = if (asEmail) SourceType.EMAIL else SourceType.SMS
        val item = TransactionIngestor.SourceItem(type, Hashing.sha256("shared", sender, text), sender.ifBlank { "Shared" }, text.take(2000), System.currentTimeMillis())
        when (val outcome = ingestor.ingest(item, result.txn)) {
            is TransactionIngestor.Outcome.Created -> saved.emit("Saved" to outcome.transactionId)
            is TransactionIngestor.Outcome.FlaggedDuplicate -> saved.emit("Saved for review" to outcome.transactionId)
            is TransactionIngestor.Outcome.Merged -> saved.emit("Already recorded – merged" to outcome.transactionId)
            TransactionIngestor.Outcome.AlreadyProcessed -> saved.emit("This exact message was already saved" to null)
        }
    }
}

private val samples = listOf(
    "VM-HDFCBK" to "Rs 450 debited from A/c XX4521 through UPI to SWIGGY on 04-11-2026. Ref 629384751102.",
    "JD-SBIUPI" to "Dear UPI user A/C X4521 debited by 250.0 on date 04Nov26 trf to ZOMATO Refno 629384751104. If not u? call 1800111109. -SBI",
    "AD-ICICIT" to "INR 1,250.00 spent using ICICI Bank Card XX1234 on 04-Nov-26 on AMAZON. Avl Limit: INR 45,000.00.",
    "VM-HDFCBK" to "Payment of Rs 15,000.00 has been received on your HDFC Bank Credit Card ending 1234. Thank you.",
)

@Composable
fun ParserTestScreen(nav: NavController, vm: ParserTestViewModel = hiltViewModel()) {
    var text by rememberSaveable { mutableStateOf(vm.initialText) }
    var sender by rememberSaveable { mutableStateOf(if (vm.initialText.isNotBlank()) "" else "VM-HDFCBK") }
    var asEmail by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        vm.saved.collect { (message, id) ->
            if (id != null) nav.navigate(Routes.txn(id)) { popUpTo(Routes.PARSER) { inclusive = true } }
            else snackbar.showSnackbar(message)
        }
    }
    val result = remember(text, sender, asEmail) { if (text.isBlank()) null else vm.parse(text, sender, asEmail) }

    Scaffold(
        topBar = { SimpleTopBar(if (vm.initialText.isNotBlank()) "Shared message" else "Test the parser", onBack = { nav.popBackStack() }) },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        Column(Modifier.padding(inner).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !asEmail, onClick = { asEmail = false }, label = { Text("SMS / notification") })
                FilterChip(selected = asEmail, onClick = { asEmail = true }, label = { Text("Email") })
            }
            OutlinedTextField(sender, { sender = it }, label = { Text(if (asEmail) "From (e.g. alerts@hdfcbank.net)" else "Sender (e.g. VM-HDFCBK)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(text, { text = it }, label = { Text("Message text") }, minLines = 4, modifier = Modifier.fillMaxWidth())
            if (vm.initialText.isBlank()) {
                Text("Try a sample", style = MaterialTheme.typography.labelLarge)
                samples.forEach { (s, body) ->
                    OutlinedButton(onClick = { sender = s; text = body; asEmail = false }, modifier = Modifier.fillMaxWidth()) { Text(body, maxLines = 1) }
                }
            }
            when (val r = result) {
                null -> Unit
                is MessageParseResult.Transaction -> SectionCard(title = "Would be recorded") {
                    val t = r.txn
                    InfoRow("Amount", Money.format(t.amountMinor))
                    InfoRow("Type", "${t.type.label} (${if (t.direction.name == "DEBIT") "money out" else "money in"})")
                    InfoRow("Status", t.status.label)
                    InfoRow("Merchant", t.merchant ?: "—")
                    InfoRow("Person?", if (t.counterpartyIsPerson) "Yes – personal transfer" else "No")
                    InfoRow("Account", t.accountLast4?.let { "••$it" } ?: "—")
                    InfoRow("Bank", t.institution)
                    InfoRow("Method", t.paymentMethod.label)
                    InfoRow("Reference", t.reference)
                    InfoRow("UPI ID", t.upiId)
                    InfoRow("Date", Fmt.dateAndTime(t.timestamp))
                    InfoRow("Balance", t.balanceMinor?.let { Money.format(it) })
                    InfoRow("Confidence", "${(t.confidence * 100).toInt()}%${if (t.confidence < TransactionIngestor.REVIEW_CONFIDENCE) " – would be sent to review" else ""}")
                    InfoRow("Parser", t.parser)
                    if (t.notes.isNotEmpty()) InfoRow("Notes", t.notes.joinToString("; "))
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { vm.save(text, sender, asEmail) }, modifier = Modifier.fillMaxWidth()) { Text("Save this transaction") }
                }
                is MessageParseResult.Ignored -> SectionCard(title = "Ignored") { Text(r.detail) }
                is MessageParseResult.BalanceAlert -> SectionCard(title = "Balance update only") {
                    Text("Balance ${Money.format(r.balanceMinor)} for ${r.accountLast4?.let { "••$it" } ?: "an account"} – not a transaction.")
                }
                is MessageParseResult.Unparsed -> SectionCard(title = "Not understood") { Text(r.reason) }
            }
        }
    }
}
