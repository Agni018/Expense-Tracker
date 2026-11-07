package com.expensetracker.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.app.core.Money
import com.expensetracker.app.domain.model.Direction
import com.expensetracker.app.domain.model.PeriodPreset
import com.expensetracker.app.domain.model.TxnType
import com.expensetracker.app.ui.common.Fmt
import com.expensetracker.app.ui.common.PeriodSelection
import com.expensetracker.app.ui.theme.AmountStyle
import com.expensetracker.app.ui.theme.LocalFinanceColors

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(contentPadding).animateContentSize()) {
            if (title != null) {
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (action != null && onAction != null) {
                        Text(
                            action,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            content()
        }
    }
}

/** Signed, coloured amount: expenses in red with a minus, income in green with a plus. */
@Composable
fun AmountText(
    amountMinor: Long,
    direction: Direction,
    type: TxnType,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleSmall,
    muted: Boolean = false,
) {
    val colors = LocalFinanceColors.current
    val (prefix, color) = when {
        muted -> "" to MaterialTheme.colorScheme.onSurfaceVariant
        type == TxnType.EXPENSE -> "−" to MaterialTheme.colorScheme.onSurface
        type == TxnType.INCOME || type == TxnType.REFUND || type == TxnType.REIMBURSEMENT -> "+" to colors.income
        direction == Direction.CREDIT -> "+" to colors.neutral
        else -> "−" to colors.neutral
    }
    Text(prefix + Money.format(amountMinor), style = style.merge(AmountStyle), color = color, modifier = modifier, maxLines = 1)
}

@Composable
fun EmojiBadge(emoji: String, color: Color, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, fontSize = (size.value * 0.48f).sp)
    }
}

@Composable
fun InitialsAvatar(name: String, color: Color, size: Dp = 40.dp) {
    val initials = name.split(' ', '.', '-').filter { it.isNotBlank() && it[0].isLetterOrDigit() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }
    Box(Modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
        Text(initials, color = color, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.36f).sp)
    }
}

@Composable
fun IconBadge(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.primary, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(icon, size = 64.dp)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier, label: String? = null) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        if (label != null) {
            Spacer(Modifier.height(12.dp))
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SimpleTopBar(title: String, onBack: (() -> Unit)?, scrollBehavior: TopAppBarScrollBehavior? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
        },
        actions = { actions() },
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background, scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
}

@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    tint: Color = MaterialTheme.colorScheme.primary,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, tint = tint, size = 38.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            trailing != null -> trailing()
            onClick != null -> Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SwitchRow(icon: ImageVector, title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    SettingsRow(icon, title, subtitle, onClick = if (enabled) ({ onChange(!checked) }) else null) {
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun InfoRow(label: String, value: String?, modifier: Modifier = Modifier) {
    if (value.isNullOrBlank()) return
    Row(modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(132.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
fun BudgetBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 8.dp, over: Boolean = false, warn: Boolean = false) {
    val colors = LocalFinanceColors.current
    val color = when {
        over -> colors.expense
        warn -> colors.warning
        else -> MaterialTheme.colorScheme.primary
    }
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height)),
        color = color,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

@Composable
fun Pill(text: String, color: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier) {
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(50), modifier = modifier) {
        Text(text, color = color, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), maxLines = 1)
    }
}

/** Period chips: Today, Week, Month, Last month, Year, Custom. */
@Composable
fun PeriodChips(selection: PeriodSelection, onSelect: (PeriodSelection) -> Unit, modifier: Modifier = Modifier) {
    var pickingCustom by remember { mutableStateOf(false) }
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PeriodPreset.entries.forEach { preset ->
            val label = if (preset == PeriodPreset.CUSTOM && selection.preset == PeriodPreset.CUSTOM && selection.custom != null) Fmt.range(selection.custom) else preset.label
            FilterChip(
                selected = selection.preset == preset,
                onClick = { if (preset == PeriodPreset.CUSTOM) pickingCustom = true else onSelect(PeriodSelection(preset)) },
                label = { Text(label) },
            )
        }
    }
    if (pickingCustom) {
        DateRangeDialog(
            initial = selection.custom,
            onDismiss = { pickingCustom = false },
            onConfirm = { range ->
                pickingCustom = false
                onSelect(PeriodSelection(PeriodPreset.CUSTOM, range))
            },
        )
    }
}

@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = false) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun StatBlock(label: String, value: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleMedium.merge(AmountStyle), color = color, maxLines = 1)
    }
}

fun categoryColor(argb: Int?): Color = argb?.let { Color(it) } ?: Color(0xFF94A3B8)
