package com.expensetracker.app.ui.components

import android.icu.text.BreakIterator
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.expensetracker.app.data.db.CategoryEntity
import com.expensetracker.app.domain.model.CategoryKind

/** Shown for a category saved without an emoji. */
const val DEFAULT_CATEGORY_EMOJI = "🏷️"

val categoryPalette = listOf(
    0xFFF97316, 0xFF22C55E, 0xFFEC4899, 0xFFEAB308, 0xFFEF4444, 0xFF0EA5E9, 0xFF8B5CF6, 0xFF14B8A6,
    0xFF6366F1, 0xFF3B82F6, 0xFFD946EF, 0xFFF43F5E, 0xFF84CC16, 0xFF64748B, 0xFFB45309, 0xFF06B6D4,
).map { it.toInt() }

private val emojiChoices = listOf(
    "🍔", "🛒", "🛍️", "🚕", "⛽", "🛣️", "🚆", "✈️", "🏠", "💡", "🌐", "📱", "🎬", "💊", "🎓", "🔁", "🛡️", "🏦", "🤝", "🏪",
    "💇", "📦", "📈", "🔄", "🏧", "💳", "💼", "💰", "↩️", "🧾", "🐶", "👶", "🎁", "🍺", "☕", "🏋️", "🧹", "🛠️", "⚽", "🎮",
)

/**
 * Creates or edits a category. The emoji is optional: pick one, type any emoji from the
 * keyboard, or leave it empty for [DEFAULT_CATEGORY_EMOJI]. [takenNames] blocks duplicates.
 */
@Composable
fun CategoryDialog(
    existing: CategoryEntity?,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    takenNames: Collection<String> = emptyList(),
    onSave: (CategoryEntity) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var emoji by remember { mutableStateOf(existing?.emoji.orEmpty()) }
    var color by remember { mutableStateOf(existing?.colorArgb ?: categoryPalette.first()) }
    var kind by remember { mutableStateOf(existing?.kind ?: CategoryKind.EXPENSE) }
    var archived by remember { mutableStateOf(existing?.isArchived ?: false) }
    val duplicate = name.isNotBlank() && takenNames.any { it.equals(name.trim(), ignoreCase = true) && !it.equals(existing?.name, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New category" else "Edit category") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    name, { name = it }, label = { Text("Name") }, singleLine = true, isError = duplicate,
                    supportingText = if (duplicate) ({ Text("You already have a category with this name") }) else null,
                )
                DropdownField("Kind", kind, CategoryKind.entries, { when (it) { CategoryKind.EXPENSE -> "Spending"; CategoryKind.INCOME -> "Income"; CategoryKind.NEUTRAL -> "Not spending" } }, { kind = it })
                Text("Emoji (optional)", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EmojiBadge(emoji.ifEmpty { DEFAULT_CATEGORY_EMOJI }, categoryColor(color), size = 48.dp)
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        emoji, { emoji = lastCharacter(it) },
                        placeholder = { Text("Type any emoji") },
                        singleLine = true,
                        trailingIcon = { if (emoji.isNotEmpty()) IconButton(onClick = { emoji = "" }) { Icon(Icons.Rounded.Close, "Remove emoji") } },
                        modifier = Modifier.weight(1f),
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    emojiChoices.forEach { e ->
                        Box(
                            Modifier.size(38.dp).clip(CircleShape).background(if (e == emoji) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable { emoji = if (e == emoji) "" else e },
                            contentAlignment = Alignment.Center,
                        ) { Text(e) }
                    }
                }
                Text("Colour", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    categoryPalette.forEach { c ->
                        Box(
                            Modifier.size(30.dp).clip(CircleShape).background(Color(c))
                                .border(if (c == color) 3.dp else 0.dp, if (c == color) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                                .clickable { color = c },
                        )
                    }
                }
                if (existing != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Hide from pickers", modifier = Modifier.weight(1f))
                        Switch(archived, { archived = it })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && !duplicate, onClick = {
                val icon = emoji.ifEmpty { DEFAULT_CATEGORY_EMOJI }
                onSave((existing ?: CategoryEntity(name = name, emoji = icon, colorArgb = color, kind = kind)).copy(name = name.trim(), emoji = icon, colorArgb = color, kind = kind, isArchived = archived))
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Keeps only the last character typed (emoji sequences count as one), so a new emoji replaces the old one. */
private fun lastCharacter(text: String): String {
    val t = text.trim()
    if (t.isEmpty()) return ""
    val it = BreakIterator.getCharacterInstance().apply { setText(t) }
    val end = it.last()
    return t.substring(it.previous().coerceAtLeast(0), end)
}
