package com.expensetracker.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.expensetracker.app.data.db.AccountEntity
import com.expensetracker.app.data.db.CategoryEntity
import com.expensetracker.app.data.db.TransactionEntity
import com.expensetracker.app.domain.model.TxnStatus
import com.expensetracker.app.domain.model.TxnType
import com.expensetracker.app.ui.common.Fmt
import com.expensetracker.app.ui.theme.LocalFinanceColors

@Composable
fun TransactionRow(
    t: TransactionEntity,
    category: CategoryEntity?,
    account: AccountEntity?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDate: Boolean = false,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val finance = LocalFinanceColors.current
    val counted = t.status == TxnStatus.COMPLETED
    Row(
        modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary)
            }
        } else if (category != null) {
            EmojiBadge(category.emoji, categoryColor(category.colorArgb))
        } else {
            InitialsAvatar(t.merchant ?: t.type.label, MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                t.merchant ?: t.type.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val parts = listOfNotNull(
                    category?.name,
                    if (showDate) Fmt.dayShort(Fmt.date(t.timestamp)) else Fmt.time(t.timestamp),
                    account?.name,
                )
                Text(
                    parts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            AmountText(t.amountMinor, t.direction, t.type, muted = !counted)
            when {
                !counted -> Pill(t.status.label, if (t.status == TxnStatus.PENDING) finance.warning else finance.expense)
                t.needsReview -> Pill("Review", finance.warning)
                t.type != TxnType.EXPENSE && t.type != TxnType.INCOME -> Pill(t.type.label, finance.neutral)
                else -> Text(t.paymentMethod.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
