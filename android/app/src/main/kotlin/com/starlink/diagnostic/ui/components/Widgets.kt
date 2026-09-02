package com.starlink.diagnostic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starlink.diagnostic.diagnostics.DiagStep

// ── status color mapping ─────────────────────────────────────────────────
fun statusColor(status: String): Color = when (status) {
    "ok", "pass", "PASSED", "green" -> GoodGreen
    "fail", "FAILED", "red" -> BadRed
    "warn", "WARN", "amber" -> WarnAmber
    "info", "na", "UNKNOWN", "grey" -> NeutralGrey
    else -> NeutralGrey
}

// ── glass card ───────────────────────────────────────────────────────────
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = GlassSurface),
    ) {
        Column(modifier = Modifier.padding(14.dp)) { content() }
    }
}

// ── status dot ───────────────────────────────────────────────────────────
@Composable
fun StatusDot(status: String, size: Int = 10) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .border(1.dp, Hairline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Surface(color = statusColor(status), shape = CircleShape) {
            Box(modifier = Modifier.size((size - 3).dp))
        }
    }
}

// ── section header ───────────────────────────────────────────────────────
@Composable
fun SectionHeader(title: String, subtitle: String? = null) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = SkySoft,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MutedText,
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

// ── metric card ──────────────────────────────────────────────────────────
@Composable
fun MetricCard(
    label: String,
    value: String,
    accent: Color = SkyBlue,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MutedText)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                color = accent,
            )
        }
    }
}

// ── key/value row ────────────────────────────────────────────────────────
@Composable
fun KVRow(key: String, value: String, valueColor: Color = StrongText) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            key,
            style = MaterialTheme.typography.bodySmall,
            color = MutedText,
            modifier = Modifier.weight(0.42f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            modifier = Modifier.weight(0.58f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── status pill ──────────────────────────────────────────────────────────
@Composable
fun StatusPill(text: String, status: String) {
    val color = statusColor(status)
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(50),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(status, size = 8)
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

// ── selectable chip ──────────────────────────────────────────────────────
@Composable
fun SelectChip(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color(0x14101833),
            selectedContainerColor = SkyBlue.copy(alpha = 0.22f),
            selectedLabelColor = SkySoft,
            labelColor = MutedText,
        ),
        label = { Text(text, style = MaterialTheme.typography.labelSmall) },
    )
}

// ── confirm dialog ───────────────────────────────────────────────────────
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NavyCard,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmLabel,
                    color = if (destructive) BadRed else SkyBlue,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء", color = MutedText) }
        },
    )
}

// ── line chart (Canvas) ──────────────────────────────────────────────────
@Composable
fun LineChart(
    series: List<List<Double>>,
    colors: List<Color>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    unit: String = "",
    fixedMin: Double? = null,
) {
    val gridColor = Hairline
    val labelColor = MutedText
    val density = LocalDensity.current
    val labelPx = with(density) { 9.sp.toPx() }

    Box(modifier = modifier.height(120.dp)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
            if (series.isEmpty() || series[0].isEmpty()) {
                drawLine(gridColor, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
                return@Canvas
            }
            val all = series.flatten().filter { !it.isNaN() }
            if (all.isEmpty()) return@Canvas
            var lo = fixedMin ?: all.minOrNull() ?: return@Canvas
            var hi = all.maxOrNull() ?: lo
            if (fixedMin != null) hi = max(hi, fixedMin)
            if (hi - lo < 1e-6) { hi += 1.0; lo -= 0.5 }
            val pad = (hi - lo) * 0.12
            if (fixedMin == null) lo -= pad
            hi += pad

            fun y(v: Double): Float {
                val t = (v - lo) / (hi - lo)
                return (size.height * (1.0 - t)).toFloat().coerceIn(0f, size.height)
            }

            // grid: 3 horizontal lines
            for (i in 1..2) {
                val yy = size.height * i / 3f
                drawLine(gridColor, Offset(0f, yy), Offset(size.width, yy), 1f)
            }

            // series
            series.forEachIndexed { si, data ->
                if (data.isEmpty()) return@forEachIndexed
                val n = data.size
                val stepX = if (n > 1) size.width / (n - 1) else size.width
                val path = Path()
                for (i in data.indices) {
                    val x = if (n > 1) i * stepX else size.width / 2f
                    val yy = y(data[i])
                    if (i == 0) path.moveTo(x, yy) else path.lineTo(x, yy)
                }
                drawPath(path, colors[si], style = Stroke(width = 2.2f))
                // last point dot
                val lx = if (n > 1) (n - 1) * stepX else size.width / 2f
                drawCircle(colors[si], radius = 3.4f, center = Offset(lx, y(data.last())))
            }

            // axis labels (min/max) via native canvas
            val paint = android.graphics.Paint().apply {
                textSize = labelPx
                isAntiAlias = true
            }
            paint.color = android.graphics.Color.argb(190, 148, 163, 184)
            drawContext.canvas.nativeCanvas.drawText(
                formatAxis(lo), 4f, size.height - 4f, paint,
            )
            val hiTxt = formatAxis(hi)
            drawContext.canvas.nativeCanvas.drawText(
                hiTxt, 4f, labelPx + 4f, paint,
            )
        }
        // legend
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            labels.forEachIndexed { i, l ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = colors[i], shape = CircleShape) {
                        Box(Modifier.size(6.dp))
                    }
                    Spacer(Modifier.width(3.dp))
                    Text(l, style = MaterialTheme.typography.labelSmall, color = MutedText)
                }
            }
            if (unit.isNotEmpty()) {
                Text(unit, style = MaterialTheme.typography.labelSmall, color = MutedText)
            }
        }
    }
}

private fun formatAxis(v: Double): String = when {
    v >= 1000 -> "%.0fk".format(v / 1000)
    v >= 100 -> "%.0f".format(v)
    v >= 10 -> "%.1f".format(v)
    else -> "%.2f".format(v)
}

// ── diagnostics step timeline ────────────────────────────────────────────
@Composable
fun StepTimeline(steps: List<DiagStep>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.forEach { step ->
            var open by remember(step.id) { mutableStateOf(false) }
            GlassCard {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(step.status)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                step.titleAr,
                                style = MaterialTheme.typography.titleMedium,
                                color = StrongText,
                            )
                            Text(
                                step.titleEn,
                                style = MaterialTheme.typography.labelSmall,
                                color = MutedText,
                            )
                        }
                        TextButton(onClick = { open = !open }) {
                            Text(
                                if (open) "إخفاء الدليل" else "الدليل",
                                style = MaterialTheme.typography.labelSmall,
                                color = SkyBlue,
                            )
                        }
                    }
                    step.noteAr?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MutedText)
                    }
                    if (open && step.evidence.length() > 0) {
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            color = Color(0x330B1026),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                step.evidence.toString().replace(",", ",\n"),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = SkySoft,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── JSON block (raw viewer) ──────────────────────────────────────────────
@Composable
fun JsonBlock(text: String) {
    Surface(
        color = Color(0xFF0A0F24),
        shape = RoundedCornerShape(10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
        ) {
            horizontalScroll(rememberScrollState()) {
                Text(
                    text,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = SkySoft,
                )
            }
        }
    }
}

private fun formatUptime(seconds: Long?): String {
    if (seconds == null || seconds <= 0) return "—"
    var s = seconds
    val d = s / 86400; s %= 86400
    val h = s / 3600; s %= 3600
    val m = s / 60; s %= 60
    return if (d > 0) "%dd %02d:%02d:%02d".format(d, h, m, s)
    else "%02d:%02d:%02d".format(h, m, s)
}

@Composable
fun UptimeLabel(seconds: Long?) {
    Text(formatUptime(seconds), style = MaterialTheme.typography.bodySmall)
}
