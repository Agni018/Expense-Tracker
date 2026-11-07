package com.expensetracker.app.ui.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.expensetracker.app.domain.model.PaymentMethod
import com.expensetracker.app.domain.model.TxnStatus
import com.expensetracker.app.domain.model.TxnType
import com.expensetracker.app.ui.Routes
import com.expensetracker.app.ui.common.Fmt
import com.expensetracker.app.ui.components.CategoryPickerSheet
import com.expensetracker.app.ui.components.DateDialog
import com.expensetracker.app.ui.components.DropdownField
import com.expensetracker.app.ui.components.EmojiBadge
import com.expensetracker.app.ui.components.SimpleTopBar
import com.expensetracker.app.ui.components.TimeDialog
import com.expensetracker.app.ui.components.categoryColor
import com.expensetracker.app.ui.theme.AmountStyle
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun EditTransactionScreen(nav: NavController, vm: EditTransactionViewModel = hiltViewModel()) {
    val f by vm.form.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    var pickCategory by remember { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }

    val save: () -> Unit = {
        vm.save { id ->
            nav.popBackStack()
            if (vm.isNew) nav.navigate(Routes.txn(id))
        }
    }

    Scaffold(
        topBar = {
            SimpleTopBar(if (vm.isNew) "Add transaction" else "Edit transaction", onBack = { nav.popBackStack() }) {
                TextButton(onClick = save) { Text("Save") }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        Column(
            Modifier.padding(inner).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val mainTypes = listOf(TxnType.EXPENSE, TxnType.INCOME, TxnType.TRANSFER)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (mainTypes + TxnType.entries.filter { it !in mainTypes }).forEach { type ->
                    FilterChip(selected = f.type == type, onClick = { vm.setType(type) }, label = { Text(type.label) })
                }
            }
            OutlinedTextField(
                value = f.amount,
                onValueChange = { v -> vm.update { it.copy(amount = v.filter { c -> c.isDigit() || c == '.' || c == ',' }) } },
                prefix = { Text("₹", fontSize = 28.sp) },
                textStyle = MaterialTheme.typography.displaySmall.merge(AmountStyle).copy(fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Start),
                placeholder = { Text("0", style = MaterialTheme.typography.displaySmall) },
                singleLine = true,
                isError = f.error != null,
                supportingText = f.error?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = f.merchant,
                onValueChange = vm::setMerchant,
                label = { Text(if (f.type == TxnType.INCOME) "Received from" else "Paid to / merchant") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val category = categories.firstOrNull { it.id == f.categoryId }
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().clickable { pickCategory = true },
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    EmojiBadge(category?.emoji ?: "❔", categoryColor(category?.colorArgb), size = 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Category", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(category?.name ?: "Choose a category", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.CalendarMonth, null)
                    Spacer(Modifier.width(8.dp))
                    Text(Fmt.dayHeader(f.date))
                }
                OutlinedButton(onClick = { pickTime = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Schedule, null)
                    Spacer(Modifier.width(8.dp))
                    Text(f.time.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)))
                }
            }
            DropdownField(
                "Account",
                accounts.firstOrNull { it.id == f.accountId },
                listOf(null) + accounts.filter { !it.isArchived },
                { it?.name ?: "No account" },
                { a -> vm.update { it.copy(accountId = a?.id) } },
            )
            DropdownField("Payment method", f.method, PaymentMethod.entries, { it.label }, { m -> vm.update { it.copy(method = m) } })
            DropdownField("Status", f.status, TxnStatus.entries, { it.label }, { st -> vm.update { it.copy(status = st) } })
            OutlinedTextField(f.reference, { v -> vm.update { it.copy(reference = v) } }, label = { Text("Reference no. (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(f.notes, { v -> vm.update { it.copy(notes = v) } }, label = { Text("Note") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(f.tags, { v -> vm.update { it.copy(tags = v) } }, label = { Text("Tags (comma separated)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = save, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(if (vm.isNew) "Add transaction" else "Save changes") }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (pickCategory) {
        CategoryPickerSheet(categories, f.categoryId, onDismiss = { pickCategory = false }, preferredKind = vm.preferredKind()) { c ->
            vm.update { it.copy(categoryId = c.id, categoryTouched = true) }
            pickCategory = false
        }
    }
    if (pickDate) DateDialog(f.date, onDismiss = { pickDate = false }) { d -> vm.update { it.copy(date = d) }; pickDate = false }
    if (pickTime) TimeDialog(f.time, onDismiss = { pickTime = false }) { t -> vm.update { it.copy(time = t) }; pickTime = false }
}
