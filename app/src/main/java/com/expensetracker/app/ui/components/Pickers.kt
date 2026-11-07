package com.expensetracker.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.expensetracker.app.data.db.CategoryEntity
import com.expensetracker.app.domain.model.CategoryKind
import com.expensetracker.app.domain.model.DateRange
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** Material date pickers work in UTC midnight millis. */
private fun LocalDate.toPickerMillis() = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
private fun Long.fromPickerMillis(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

@Composable
fun DateRangeDialog(initial: DateRange?, onDismiss: () -> Unit, onConfirm: (DateRange) -> Unit) {
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initial?.start?.toPickerMillis(),
        initialSelectedEndDateMillis = initial?.endInclusive?.toPickerMillis(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedStartDateMillis != null,
                onClick = {
                    val start = state.selectedStartDateMillis!!.fromPickerMillis()
                    val end = state.selectedEndDateMillis?.fromPickerMillis() ?: start
                    onConfirm(DateRange(start, end))
                },
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DateRangePicker(state = state, modifier = Modifier.height(520.dp), title = { Text("Custom period", Modifier.padding(start = 24.dp, top = 16.dp)) })
    }
}

@Composable
fun DateDialog(initial: LocalDate, onDismiss: () -> Unit, onConfirm: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.toPickerMillis())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { onConfirm(it.fromPickerMillis()) } }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state = state) }
}

@Composable
fun TimeDialog(initial: LocalTime, onDismiss: () -> Unit, onConfirm: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(state = state) },
    )
}

/** Read-only dropdown field. */
@Composable
fun <T> DropdownField(
    label: String,
    selected: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = optionLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(optionLabel(option)) }, onClick = {
                    expanded = false
                    onSelect(option)
                })
            }
        }
    }
}

/** Bottom sheet grid of categories, grouped by kind. */
@Composable
fun CategoryPickerSheet(
    categories: List<CategoryEntity>,
    selectedId: Long?,
    onDismiss: () -> Unit,
    preferredKind: CategoryKind? = null,
    header: (@Composable () -> Unit)? = null,
    onPick: (CategoryEntity) -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("Choose category", style = MaterialTheme.typography.titleLarge)
            header?.invoke()
            val order = listOfNotNull(preferredKind) + CategoryKind.entries.filter { it != preferredKind }
            order.forEach { kind ->
                val items = categories.filter { it.kind == kind && !it.isArchived }
                if (items.isEmpty()) return@forEach
                Spacer(Modifier.height(16.dp))
                Text(
                    when (kind) {
                        CategoryKind.EXPENSE -> "Spending"
                        CategoryKind.INCOME -> "Income"
                        CategoryKind.NEUTRAL -> "Not spending"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.forEach { c ->
                        val selected = c.id == selectedId
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.clickable { onPick(c) },
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(c.emoji)
                                Spacer(Modifier.width(8.dp))
                                Text(c.name, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
