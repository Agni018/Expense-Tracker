package com.expensetracker.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.app.core.Money
import com.expensetracker.app.ui.theme.AmountStyle
import com.expensetracker.app.ui.theme.LocalFinanceColors
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

data class ChartSlice(val key: Long?, val label: String, val value: Long, val color: Color, val emoji: String = "")

/**
 * Animated donut chart. Tapping a segment selects it; the centre shows the selection or total.
 * With [onOpen], tapping the centre of a selected segment opens it; only segments with a
 * positive key (a real category, not "Uncategorised" or "Others") can be opened.
 */
@Composable
fun DonutChart(
    slices: List<ChartSlice>,
    modifier: Modifier = Modifier,
    centerLabel: String = "Spent",
    onSelect: (ChartSlice?) -> Unit = {},
    onOpen: ((ChartSlice) -> Unit)? = null,
) {
    val open by rememberUpdatedState(onOpen)
    fun ChartSlice.opens() = open != null && key != null && key > 0
    val total = slices.sumOf { it.value }.coerceAtLeast(1)
    val progress = remember { Animatable(0f) }
    LaunchedEffect(slices) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
    }
    var selected by remember(slices) { mutableStateOf<Int?>(null) }
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.matchParentSize().pointerInput(slices) {
                detectTapGestures { pos ->
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val r = minOf(size.width, size.height) / 2f
                    val d = hypot(pos.x - c.x, pos.y - c.y)
                    val current = selected?.let { slices.getOrNull(it) }
                    if (d < r * 0.55f && current != null && current.opens()) {
                        open?.invoke(current)
                        return@detectTapGestures
                    }
                    if (d < r * 0.55f || d > r) {
                        selected = null
                        onSelect(null)
                        return@detectTapGestures
                    }
                    var angle = Math.toDegrees(atan2((pos.y - c.y).toDouble(), (pos.x - c.x).toDouble())).toFloat() + 90f
                    if (angle < 0) angle += 360f
                    var acc = 0f
                    for ((i, s) in slices.withIndex()) {
                        val sweep = 360f * s.value / total
                        if (angle in acc..(acc + sweep)) {
                            selected = if (selected == i) null else i
                            onSelect(selected?.let { slices[it] })
                            break
                        }
                        acc += sweep
                    }
                }
            },
        ) {
            val stroke = size.minDimension * 0.13f
            val inset = stroke / 2f + size.minDimension * 0.03f
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            var start = -90f
            val gap = if (slices.size > 1) 1.6f else 0f
            slices.forEachIndexed { i, s ->
                val sweep = 360f * s.value / total * progress.value
                val isSelected = selected == i
                drawArc(
                    color = if (selected == null || isSelected) s.color else s.color.copy(alpha = 0.35f),
                    startAngle = start + gap / 2,
                    sweepAngle = max(0f, sweep - gap),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(if (isSelected) stroke * 1.25f else stroke, cap = StrokeCap.Butt),
                )
                start += sweep
            }
        }
        val sel = selected?.let { slices.getOrNull(it) }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                sel?.let { "${it.emoji} ${it.label}".trim() } ?: centerLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(Money.format(sel?.value ?: slices.sumOf { it.value }, showPaise = false), style = MaterialTheme.typography.titleLarge.merge(AmountStyle))
            if (sel != null) {
                Row {
                    Text("${(sel.value * 100 / total)}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (sel.opens()) Text(" · View ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
fun LegendDot(color: Color) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

data class BarGroup(val label: String, val first: Long, val second: Long)

/** Grouped bars (e.g. income vs spending per month) with a tap-to-inspect tooltip. */
@Composable
fun GroupedBarChart(
    groups: List<BarGroup>,
    firstLabel: String,
    secondLabel: String,
    modifier: Modifier = Modifier,
    firstColor: Color = LocalFinanceColors.current.income,
    secondColor: Color = MaterialTheme.colorScheme.primary,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val progress = remember { Animatable(0f) }
    LaunchedEffect(groups) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
    }
    var selected by remember(groups) { mutableStateOf<Int?>(null) }
    val maxValue = groups.maxOfOrNull { max(it.first, it.second) }?.coerceAtLeast(1) ?: 1
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) { LegendDot(firstColor); Spacer(Modifier.width(6.dp)); Text(firstLabel, style = MaterialTheme.typography.labelMedium) }
            Row(verticalAlignment = Alignment.CenterVertically) { LegendDot(secondColor); Spacer(Modifier.width(6.dp)); Text(secondLabel, style = MaterialTheme.typography.labelMedium) }
            Spacer(Modifier.weight(1f))
            selected?.let { i ->
                val g = groups[i]
                Text("${g.label}: ${Money.compact(g.first)} / ${Money.compact(g.second)}", style = MaterialTheme.typography.labelMedium.merge(AmountStyle))
            }
        }
        Spacer(Modifier.height(12.dp))
        Canvas(
            Modifier.fillMaxWidth().height(180.dp).pointerInput(groups) {
                detectTapGestures { pos ->
                    val left = 40.dp.toPx()
                    val slot = (size.width - left) / groups.size.coerceAtLeast(1)
                    val i = ((pos.x - left) / slot).toInt()
                    selected = if (i in groups.indices && selected != i) i else null
                }
            },
        ) {
            val left = 40.dp.toPx()
            val bottom = size.height - 18.dp.toPx()
            val chartHeight = bottom - 6.dp.toPx()
            for (step in 0..3) {
                val y = bottom - chartHeight * step / 3f
                drawLine(grid, Offset(left, y), Offset(size.width, y), strokeWidth = 1f, pathEffect = if (step == 0) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                val label = measurer.measure(Money.compact(maxValue * step / 3), labelStyle)
                drawText(label, topLeft = Offset(0f, y - label.size.height / 2f))
            }
            if (groups.isEmpty()) return@Canvas
            val slot = (size.width - left) / groups.size
            val barWidth = minOf(slot * 0.28f, 18.dp.toPx())
            groups.forEachIndexed { i, g ->
                val cx = left + slot * i + slot / 2f
                val dim = selected != null && selected != i
                listOf(g.first to firstColor, g.second to secondColor).forEachIndexed { j, (value, color) ->
                    val h = chartHeight * value / maxValue * progress.value
                    val x = if (j == 0) cx - barWidth - 2f else cx + 2f
                    drawRoundRect(
                        color = if (dim) color.copy(alpha = 0.35f) else color,
                        topLeft = Offset(x, bottom - h),
                        size = Size(barWidth, h),
                        cornerRadius = CornerRadius(barWidth / 3f),
                    )
                }
                val label = measurer.measure(g.label, labelStyle)
                drawText(label, topLeft = Offset(cx - label.size.width / 2f, bottom + 4.dp.toPx()))
            }
        }
    }
}

/** Smoothed line with gradient fill (e.g. daily spending). */
@Composable
fun LineChart(
    points: List<Pair<String, Long>>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surfaceContainer
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(points) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
    }
    val maxValue = points.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(20.dp)) {
            selected?.let { i ->
                Text("${points[i].first}: ${Money.format(points[i].second, showPaise = false)}", style = MaterialTheme.typography.labelMedium.merge(AmountStyle))
            }
        }
        Canvas(
            Modifier.fillMaxWidth().height(150.dp).pointerInput(points) {
                detectTapGestures { pos ->
                    if (points.size < 2) return@detectTapGestures
                    val left = 40.dp.toPx()
                    val step = (size.width - left) / (points.size - 1)
                    val i = ((pos.x - left) / step + 0.5f).toInt().coerceIn(0, points.lastIndex)
                    selected = if (selected == i) null else i
                }
            },
        ) {
            val left = 40.dp.toPx()
            val bottom = size.height - 18.dp.toPx()
            val chartHeight = bottom - 8.dp.toPx()
            for (s in 0..2) {
                val y = bottom - chartHeight * s / 2f
                drawLine(grid, Offset(left, y), Offset(size.width, y), strokeWidth = 1f, pathEffect = if (s == 0) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                val label = measurer.measure(Money.compact(maxValue * s / 2), labelStyle)
                drawText(label, topLeft = Offset(0f, y - label.size.height / 2f))
            }
            if (points.size < 2) return@Canvas
            val step = (size.width - left) / (points.size - 1)
            val coords = points.mapIndexed { i, p -> Offset(left + step * i, bottom - chartHeight * p.second / maxValue * progress.value) }
            val line = Path().apply {
                moveTo(coords[0].x, coords[0].y)
                for (i in 1 until coords.size) {
                    val prev = coords[i - 1]
                    val cur = coords[i]
                    val midX = (prev.x + cur.x) / 2f
                    cubicTo(midX, prev.y, midX, cur.y, cur.x, cur.y)
                }
            }
            val fill = Path().apply {
                addPath(line)
                lineTo(coords.last().x, bottom)
                lineTo(coords.first().x, bottom)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0.02f)), startY = 0f, endY = bottom))
            drawPath(line, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
            listOf(0, points.size / 2, points.lastIndex).distinct().forEach { i ->
                val label = measurer.measure(points[i].first, labelStyle)
                val x = (coords[i].x - label.size.width / 2f).coerceIn(left, size.width - label.size.width)
                drawText(label, topLeft = Offset(x, bottom + 4.dp.toPx()))
            }
            selected?.let { i ->
                drawLine(grid, Offset(coords[i].x, 0f), Offset(coords[i].x, bottom), strokeWidth = 2f)
                drawCircle(surface, radius = 6.dp.toPx(), center = coords[i])
                drawCircle(color, radius = 4.dp.toPx(), center = coords[i])
            }
        }
    }
}

/** Ranked horizontal bars (merchants, payment methods). */
@Composable
fun RankedBars(items: List<Triple<String, Long, Color>>, modifier: Modifier = Modifier, onClick: ((Int) -> Unit)? = null) {
    val maxValue = items.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEachIndexed { i, (label, value, color) ->
            Column(if (onClick != null) Modifier.clip(MaterialTheme.shapes.small).pointerInput(i) { detectTapGestures { onClick(i) } } else Modifier) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(Money.format(value, showPaise = false), style = MaterialTheme.typography.bodyMedium.merge(AmountStyle))
                }
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                    Box(Modifier.fillMaxWidth(value.toFloat() / maxValue).height(8.dp).clip(CircleShape).background(color))
                }
            }
        }
    }
}
