package com.expensetracker.app.ui.imports

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.expensetracker.app.core.Hashing
import com.expensetracker.app.core.Money
import com.expensetracker.app.data.db.TransactionEntity
import com.expensetracker.app.data.ingest.TransactionIngestor
import com.expensetracker.app.data.repo.TransactionRepository
import com.expensetracker.app.domain.model.Direction
import com.expensetracker.app.domain.model.PaymentMethod
import com.expensetracker.app.domain.model.SourceType
import com.expensetracker.app.domain.model.TxnStatus
import com.expensetracker.app.domain.model.TxnType
import com.expensetracker.app.domain.parser.MerchantNormalizer
import com.expensetracker.app.domain.parser.ParsedTransaction
import com.expensetracker.app.domain.statement.ReceiptParser
import com.expensetracker.app.sources.statement.OcrEngine
import com.expensetracker.app.ui.Routes
import com.expensetracker.app.ui.common.Fmt
import com.expensetracker.app.ui.components.DateDialog
import com.expensetracker.app.ui.components.EmptyState
import com.expensetracker.app.ui.components.LoadingBox
import com.expensetracker.app.ui.components.SectionCard
import com.expensetracker.app.ui.components.SimpleTopBar
import com.expensetracker.app.ui.components.TransactionRow
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import javax.inject.Inject

data class ReceiptState(
    val imagePath: String? = null,
    val reading: Boolean = false,
    val merchant: String = "",
    val amount: String = "",
    val date: LocalDate = LocalDate.now(Fmt.zone),
    val ocrText: String = "",
    val candidates: List<TransactionEntity> = emptyList(),
)

@HiltViewModel
class ReceiptViewModel @Inject constructor(
    savedState: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val ocr: OcrEngine,
    private val ingestor: TransactionIngestor,
    private val transactions: TransactionRepository,
) : ViewModel() {
    val state = MutableStateFlow(ReceiptState())
    val events = MutableSharedFlow<Pair<String, Long?>>(extraBufferCapacity = 2)

    init {
        savedState.get<String>("uri")?.takeIf { it.isNotBlank() }?.let { onImage(Uri.parse(it)) }
    }

    fun cameraTarget(): Uri {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(dir, "receipt-capture.jpg"))
    }

    fun onImage(uri: Uri) = viewModelScope.launch {
        state.update { it.copy(reading = true) }
        val stored = withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "receipts").apply { mkdirs() }
            val file = File(dir, "receipt-${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: return@withContext null
            file
        }
        if (stored == null) {
            state.update { it.copy(reading = false) }
            events.emit("Could not read the image" to null)
            return@launch
        }
        val lines = runCatching { ocr.lines(Uri.fromFile(stored)) }.getOrElse { emptyList() }
        val receipt = ReceiptParser.parse(lines, LocalDate.now(Fmt.zone))
        state.update {
            it.copy(
                imagePath = stored.absolutePath, reading = false, merchant = receipt.merchant.orEmpty(),
                amount = receipt.amountMinor?.let { a -> Money.formatPlain(a) }.orEmpty(), date = receipt.date ?: it.date,
                ocrText = lines.joinToString("\n"),
            )
        }
        refreshCandidates()
        if (lines.isEmpty()) events.emit("No text found. Enter the details manually." to null)
    }

    fun update(transform: (ReceiptState) -> ReceiptState) {
        state.update(transform)
        viewModelScope.launch { refreshCandidates() }
    }

    /** Existing transactions with the same amount within two days, to attach the receipt to. */
    private suspend fun refreshCandidates() {
        val s = state.value
        val amount = Money.parseToMinor(s.amount) ?: return state.update { it.copy(candidates = emptyList()) }
        val from = s.date.minusDays(2).atStartOfDay(Fmt.zone).toInstant().toEpochMilli()
        val to = s.date.plusDays(3).atStartOfDay(Fmt.zone).toInstant().toEpochMilli()
        val list = transactions.ledgerSince(from).filter { it.timestamp < to && it.amountMinor == amount }.mapNotNull { transactions.get(it.id) }
        state.update { it.copy(candidates = list) }
    }

    fun saveNew() = viewModelScope.launch {
        val s = state.value
        val amount = Money.parseToMinor(s.amount)
        if (amount == null || amount <= 0) {
            events.emit("Enter the total amount" to null)
            return@launch
        }
        val normalized = MerchantNormalizer.normalize(s.merchant.ifBlank { null })
        val parsed = ParsedTransaction(
            amountMinor = amount, direction = Direction.DEBIT, type = normalized?.known?.type ?: TxnType.EXPENSE, status = TxnStatus.COMPLETED,
            timestamp = s.date.atTime(LocalTime.NOON).atZone(Fmt.zone).toInstant().toEpochMilli(),
            merchant = normalized?.name, merchantRaw = s.merchant, paymentMethod = PaymentMethod.OTHER,
            description = "Receipt", confidence = 0.75f, parser = "Receipt OCR",
        )
        val item = TransactionIngestor.SourceItem(
            SourceType.RECEIPT_OCR, Hashing.sha256("receipt", UUID.randomUUID()), null, s.ocrText.take(2000), System.currentTimeMillis(), receiptPath = s.imagePath,
        )
        when (val outcome = ingestor.ingest(item, parsed)) {
            is TransactionIngestor.Outcome.Merged -> {
                transactions.get(outcome.transactionId)?.takeIf { it.receiptPath == null }?.let { transactions.setReceipt(it.id, s.imagePath) }
                events.emit("Matched an existing transaction – receipt attached" to outcome.transactionId)
            }
            is TransactionIngestor.Outcome.Created -> events.emit("Saved" to outcome.transactionId)
            is TransactionIngestor.Outcome.FlaggedDuplicate -> events.emit("Saved – marked for review as a possible duplicate" to outcome.transactionId)
            TransactionIngestor.Outcome.AlreadyProcessed -> events.emit("Already saved" to null)
        }
    }

    fun attachTo(id: Long) = viewModelScope.launch {
        transactions.setReceipt(id, state.value.imagePath)
        events.emit("Receipt attached" to id)
    }
}

@Composable
fun ReceiptScanScreen(nav: NavController, vm: ReceiptViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var pickDate by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        vm.events.collect { (message, id) ->
            if (id != null) {
                nav.popBackStack()
                nav.navigate(Routes.txn(id))
            } else {
                snackbar.showSnackbar(message)
            }
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) cameraUri?.let(vm::onImage) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(vm::onImage) }

    Scaffold(
        topBar = { SimpleTopBar("Scan receipt", onBack = { nav.popBackStack() }) },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        if (s.reading) {
            LoadingBox(Modifier.padding(inner), "Reading receipt…")
            return@Scaffold
        }
        Column(Modifier.padding(inner).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (s.imagePath == null) {
                SectionCard {
                    EmptyState(Icons.Rounded.Receipt, "Scan a bill or receipt", "Text is read on this phone with ML Kit. You can review everything before saving.")
                }
            } else {
                val bitmap = remember(s.imagePath) { BitmapFactory.decodeFile(s.imagePath, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap() }
                bitmap?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(MaterialTheme.shapes.large)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = { vm.cameraTarget().also { cameraUri = it; camera.launch(it) } }) {
                    Icon(Icons.Rounded.CameraAlt, null); Spacer(Modifier.width(6.dp)); Text("Camera")
                }
                OutlinedButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Icon(Icons.Rounded.PhotoLibrary, null); Spacer(Modifier.width(6.dp)); Text("Gallery")
                }
            }
            if (s.imagePath != null) {
                SectionCard(title = "Details") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(s.merchant, { v -> vm.update { it.copy(merchant = v) } }, label = { Text("Merchant") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(s.amount, { v -> vm.update { it.copy(amount = v) } }, label = { Text("Total (₹)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                        OutlinedButton(onClick = { pickDate = true }) { Text("Date: ${Fmt.day(s.date)}") }
                        Button(onClick = vm::saveNew, modifier = Modifier.fillMaxWidth()) { Text("Save as transaction") }
                    }
                }
                if (s.candidates.isNotEmpty()) {
                    SectionCard(title = "Or attach to an existing transaction") {
                        s.candidates.forEach { t -> TransactionRow(t, null, null, onClick = { vm.attachTo(t.id) }, showDate = true) }
                    }
                }
                if (s.ocrText.isNotBlank()) {
                    var show by remember { mutableStateOf(false) }
                    TextButton(onClick = { show = !show }) { Text(if (show) "Hide recognised text" else "Show recognised text") }
                    if (show) Text(s.ocrText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (pickDate) DateDialog(s.date, onDismiss = { pickDate = false }) { d -> vm.update { it.copy(date = d) }; pickDate = false }
}
