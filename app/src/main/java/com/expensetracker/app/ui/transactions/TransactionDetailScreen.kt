package com.expensetracker.app.ui.transactions

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.expensetracker.app.core.Money
import com.expensetracker.app.data.db.CategoryEntity
import com.expensetracker.app.data.db.SplitEntity
import com.expensetracker.app.domain.model.SourceOutcome
import com.expensetracker.app.domain.model.SourceType
import com.expensetracker.app.domain.model.TxnStatus
import com.expensetracker.app.domain.model.TxnType
import com.expensetracker.app.ui.Routes
import com.expensetracker.app.ui.common.Fmt
import com.expensetracker.app.ui.components.AmountText
import com.expensetracker.app.ui.components.CategoryPickerSheet
import com.expensetracker.app.ui.components.ConfirmDialog
import com.expensetracker.app.ui.components.DropdownField
import com.expensetracker.app.ui.components.EmojiBadge
import com.expensetracker.app.ui.components.InfoRow
import com.expensetracker.app.ui.components.InitialsAvatar
import com.expensetracker.app.ui.components.LoadingBox
import com.expensetracker.app.ui.components.Pill
import com.expensetracker.app.ui.components.SectionCard
import com.expensetracker.app.ui.components.SimpleTopBar
import com.expensetracker.app.ui.components.categoryColor
import com.expensetracker.app.ui.theme.LocalFinanceColors

@Composable
fun TransactionDetailScreen(nav: NavController, vm: TransactionDetailViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val finance = LocalFinanceColors.current
    var menu by remember { mutableStateOf(false) }
    var pickCategory by remember { mutableStateOf(false) }
    var editNotes by remember { mutableStateOf(false) }
    var editSplit by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var applyToMerchant by remember { mutableStateOf(true) }
    var rememberRule by remember { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(it) } }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::attachReceipt) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) cameraUri?.let(vm::attachReceipt) }

    Scaffold(
        topBar = {
            SimpleTopBar("Transaction", onBack = { nav.popBackStack() }) {
                IconButton(onClick = { nav.navigate(Routes.edit(vm.id)) }) { Icon(Icons.Rounded.Edit, "Edit") }
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    Text("Change type", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TxnType.entries.filter { it != s.txn?.type }.forEach { type ->
                        DropdownMenuItem(text = { Text(type.label) }, onClick = { menu = false; vm.setType(type) })
                    }
                    HorizontalDivider()
                    Text("Status", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TxnStatus.entries.filter { it != s.txn?.status }.forEach { st ->
                        DropdownMenuItem(text = { Text(st.label) }, onClick = { menu = false; vm.setStatus(st) })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Split across categories") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.CallSplit, null) }, onClick = { menu = false; editSplit = true })
                    DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; confirmDelete = true })
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        val t = s.txn
        if (!s.loaded) {
            LoadingBox(Modifier.padding(inner))
            return@Scaffold
        }
        if (t == null) {
            Text("This transaction no longer exists.", modifier = Modifier.padding(inner).padding(24.dp))
            return@Scaffold
        }
        LazyColumn(contentPadding = PaddingValues(16.dp, inner.calculateTopPadding() + 4.dp, 16.dp, inner.calculateBottomPadding() + 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                SectionCard {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (s.category != null) EmojiBadge(s.category!!.emoji, categoryColor(s.category!!.colorArgb), size = 64.dp)
                        else InitialsAvatar(t.merchant ?: t.type.label, MaterialTheme.colorScheme.primary, size = 64.dp)
                        Spacer(Modifier.height(12.dp))
                        Text(t.merchant ?: t.type.label, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(4.dp))
                        AmountText(t.amountMinor, t.direction, t.type, style = MaterialTheme.typography.displaySmall, muted = t.status != TxnStatus.COMPLETED)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Pill(t.type.label)
                            if (t.status != TxnStatus.COMPLETED) Pill(t.status.label, finance.expense)
                            if (t.isSplit) Pill("Split")
                        }
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable { pickCategory = true },
                        ) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(s.category?.let { "${it.emoji}  ${it.name}" } ?: "Choose category", style = MaterialTheme.typography.labelLarge)
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Rounded.Edit, null, modifier = Modifier.height(16.dp))
                            }
                        }
                        t.categoryReason?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }

            if (t.needsReview) {
                item {
                    Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = finance.warning.copy(alpha = 0.12f))) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Warning, null, tint = finance.warning)
                                Spacer(Modifier.width(8.dp))
                                Text("Needs your review", style = MaterialTheme.typography.titleSmall)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(t.reviewReason ?: "Please confirm the details.", style = MaterialTheme.typography.bodyMedium)
                            s.duplicateOf?.let { d ->
                                Spacer(Modifier.height(10.dp))
                                Text("Possible duplicate of:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    "${d.merchant ?: d.type.label} · ${Money.format(d.amountMinor)} · ${Fmt.dateAndTime(d.timestamp)} (${d.sourceType.label})",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.clickable { nav.navigate(Routes.txn(d.id)) },
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (s.duplicateOf != null) {
                                    Button(onClick = { vm.mergeIntoDuplicate { nav.popBackStack() } }) { Text("Merge") }
                                    OutlinedButton(onClick = vm::approve) { Text("Keep both") }
                                } else {
                                    Button(onClick = vm::approve) { Text("Looks right") }
                                    OutlinedButton(onClick = { nav.navigate(Routes.edit(vm.id)) }) { Text("Edit") }
                                }
                            }
                        }
                    }
                }
            }

            item {
                SectionCard(title = "Details") {
                    InfoRow("Date", Fmt.dateAndTime(t.timestamp))
                    InfoRow("Account", s.account?.name)
                    InfoRow("Payment method", t.paymentMethod.label)
                    InfoRow("Direction", if (t.direction.name == "DEBIT") "Money out" else "Money in")
                    InfoRow("Reference", t.reference)
                    InfoRow("UPI ID", t.upiId)
                    InfoRow("Order ID", t.orderId)
                    InfoRow("Balance after", t.balanceAfterMinor?.let { Money.format(it) })
                    InfoRow("Recorded from", t.sourceType.label)
                    InfoRow("Confidence", "${(t.confidence * 100).toInt()}%")
                    // SMS and notification text is already shown in full under Source records.
                    if (t.sourceType != SourceType.SMS && t.sourceType != SourceType.NOTIFICATION) {
                        InfoRow("Description", t.description?.takeIf { it != t.merchant }?.take(160))
                    }
                }
            }

            s.linked?.let { linked ->
                item {
                    SectionCard(title = if (t.type == TxnType.REFUND) "Refund of" else "Linked transaction") {
                        Text(
                            "${linked.merchant ?: linked.type.label} · ${Money.format(linked.amountMinor)} · ${Fmt.dayHeader(Fmt.date(linked.timestamp))}",
                            modifier = Modifier.clickable { nav.navigate(Routes.txn(linked.id)) },
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            item {
                SectionCard(title = "Notes & tags", action = "Edit", onAction = { editNotes = true }) {
                    if (t.notes.isNullOrBlank() && t.tagList.isEmpty()) {
                        Text("Add a note or tags like #trip or #office.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                    t.notes?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    if (t.tagList.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { t.tagList.forEach { Pill("#$it") } }
                    }
                }
            }

            if (s.splits.isNotEmpty()) {
                item {
                    SectionCard(title = "Split", action = "Edit", onAction = { editSplit = true }) {
                        s.splits.forEach { sp ->
                            val c = sp.categoryId?.let { s.categoryMap[it] }
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text("${c?.emoji ?: ""} ${c?.name ?: "Uncategorised"}", modifier = Modifier.weight(1f))
                                Text(Money.format(sp.amountMinor))
                            }
                        }
                        val rest = t.amountMinor - s.splits.sumOf { it.amountMinor }
                        if (rest > 0) Text("Remaining ${Money.format(rest)} stays in ${s.category?.name ?: "the main category"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item {
                SectionCard(title = "Receipt") {
                    val path = t.receiptPath
                    val bitmap = remember(path) {
                        path?.let { p -> runCatching { BitmapFactory.decodeFile(p, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap() }.getOrNull() }
                    }
                    if (bitmap != null) {
                        Image(bitmap, contentDescription = "Receipt", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(MaterialTheme.shapes.medium))
                        TextButton(onClick = vm::removeReceipt) { Text("Remove receipt") }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = {
                                val uri = vm.cameraTarget()
                                cameraUri = uri
                                camera.launch(uri)
                            }) { Icon(Icons.Rounded.CameraAlt, null); Spacer(Modifier.width(6.dp)); Text("Camera") }
                            OutlinedButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                                Icon(Icons.Rounded.PhotoLibrary, null); Spacer(Modifier.width(6.dp)); Text("Gallery")
                            }
                        }
                    }
                }
            }

            if (s.merchantCount > 1 && t.merchant != null) {
                item {
                    SectionCard {
                        Row(Modifier.fillMaxWidth().clickable { nav.navigate(Routes.filtered(t.merchant, merchant = t.merchant)) }, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("All payments to ${t.merchant}", style = MaterialTheme.typography.titleSmall)
                                Text("${s.merchantCount} payments · ${Money.format(s.merchantTotalMinor, false)} in total", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("View", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            item {
                SectionCard(title = "Source records (${s.sources.size})") {
                    if (s.sources.isEmpty()) Text("No source records.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    s.sources.forEachIndexed { i, src ->
                        if (i > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(src.sourceType.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Pill(
                                when (src.outcome) {
                                    SourceOutcome.CREATED -> "Created"
                                    SourceOutcome.MERGED -> "Merged"
                                    SourceOutcome.POSSIBLE_DUPLICATE -> "Possible duplicate"
                                    SourceOutcome.UNMERGED -> "Separated"
                                    SourceOutcome.UNPARSED -> "Unparsed"
                                    SourceOutcome.DELETED_BY_USER -> "Deleted"
                                },
                            )
                        }
                        Text(
                            listOfNotNull(src.sender, Fmt.dateAndTime(src.receivedAt), src.parser?.let { "parser: $it" }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        src.rawText?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(10.dp),
                            )
                        }
                        src.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
                        if (src.outcome == SourceOutcome.MERGED && s.sources.size > 1) {
                            TextButton(onClick = { vm.separate(src.id) }) { Text("Not the same payment? Separate") }
                        }
                    }
                }
            }
        }
    }

    val t = s.txn
    if (pickCategory && t != null) {
        CategoryPickerSheet(
            s.categories, t.categoryId, onDismiss = { pickCategory = false },
            header = if (t.merchant != null) {
                {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Apply to all ${t.merchant} transactions", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(applyToMerchant, { applyToMerchant = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Always use this category for ${t.merchant}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(rememberRule, { rememberRule = it })
                    }
                }
            } else null,
        ) {
            pickCategory = false
            vm.setCategory(it.id, applyToMerchant && t.merchant != null, rememberRule && t.merchant != null)
        }
    }
    if (editNotes && t != null) {
        var notes by remember { mutableStateOf(t.notes.orEmpty()) }
        var tags by remember { mutableStateOf(t.tags.replace(",", ", ")) }
        AlertDialog(
            onDismissRequest = { editNotes = false },
            icon = { Icon(Icons.Rounded.EditNote, null) },
            title = { Text("Notes & tags") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(notes, { notes = it }, label = { Text("Note") }, minLines = 2)
                    OutlinedTextField(tags, { tags = it }, label = { Text("Tags (comma separated)") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = { vm.saveNotes(notes, tags); editNotes = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editNotes = false }) { Text("Cancel") } },
        )
    }
    if (editSplit && t != null) {
        SplitDialog(t.amountMinor, s.splits, s.categories.filter { !it.isArchived }, onDismiss = { editSplit = false }) {
            vm.saveSplits(it)
            editSplit = false
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            "Delete transaction?",
            "It will not come back on later SMS or statement scans.",
            "Delete",
            onConfirm = { confirmDelete = false; vm.delete { nav.popBackStack() } },
            onDismiss = { confirmDelete = false },
            destructive = true,
        )
    }
}

@Composable
private fun SplitDialog(
    totalMinor: Long,
    existing: List<SplitEntity>,
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onSave: (List<SplitEntity>) -> Unit,
) {
    data class Part(val categoryId: Long?, val amount: String)
    val parts = remember {
        mutableStateListOf<Part>().apply {
            if (existing.isEmpty()) {
                add(Part(categories.firstOrNull()?.id, ""))
                add(Part(categories.getOrNull(1)?.id, ""))
            } else {
                existing.forEach { add(Part(it.categoryId, Money.formatPlain(it.amountMinor))) }
            }
        }
    }
    val allocated = parts.sumOf { Money.parseToMinor(it.amount) ?: 0L }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Rounded.CallSplit, null) },
        title = { Text("Split ${Money.format(totalMinor)}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                parts.forEachIndexed { i, p ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DropdownField(
                            "Category",
                            categories.firstOrNull { it.id == p.categoryId },
                            categories,
                            { it?.let { c -> "${c.emoji} ${c.name}" } ?: "Choose" },
                            { c -> parts[i] = p.copy(categoryId = c?.id) },
                            modifier = Modifier.weight(1.4f),
                        )
                        OutlinedTextField(
                            p.amount, { parts[i] = p.copy(amount = it) }, label = { Text("₹") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                        )
                    }
                }
                TextButton(onClick = { parts.add(Part(null, "")) }) { Icon(Icons.Rounded.Add, null); Text("Add part") }
                val remaining = totalMinor - allocated
                Text(
                    if (remaining >= 0) "Unallocated: ${Money.format(remaining)} (stays in the main category)" else "Over by ${Money.format(-remaining)}",
                    color = if (remaining >= 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = allocated <= totalMinor, onClick = {
                onSave(parts.mapNotNull { p -> Money.parseToMinor(p.amount)?.takeIf { it > 0 }?.let { SplitEntity(transactionId = 0, categoryId = p.categoryId, amountMinor = it) } })
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (existing.isNotEmpty()) TextButton(onClick = { onSave(emptyList()) }) { Text("Remove split") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
