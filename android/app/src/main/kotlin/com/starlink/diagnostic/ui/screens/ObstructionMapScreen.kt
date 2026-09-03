package com.starlink.diagnostic.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starlink.diagnostic.diagnostics.ObstructionMapData
import com.starlink.diagnostic.ui.AppViewModel
import com.starlink.diagnostic.ui.BadRed
import com.starlink.diagnostic.ui.GlassCard
import com.starlink.diagnostic.ui.GoodGreen
import com.starlink.diagnostic.ui.KVRow
import com.starlink.diagnostic.ui.MutedText
import com.starlink.diagnostic.ui.SkyBlue
import com.starlink.diagnostic.ui.SkySoft
import com.starlink.diagnostic.ui.StrongText
import com.starlink.diagnostic.ui.WarnAmber
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Polar obstruction map (v42 dish_get_obstruction_map = 2008).
 *
 * Geometry (community-verified): num_cols azimuth sectors sweep 0..360,
 * num_rows elevation rings from min_elevation_deg (row 0, outer ring) up
 * to zenith (last row, center). SNR value drives the cell color; values
 * <= 0 mean "no data" and stay dark.
 */
@Composable
fun ObstructionMapScreen(vm: AppViewModel) {
    val mapUi by vm.map.collectAsState()

    Box(Modifier.fillMaxSize().background(Color(0xFF0B1026))) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 28.dp, bottom = 110.dp),
        ) {
            Text("خريطة العرقلة القطبية", style = MaterialTheme.typography.titleLarge, color = SkySoft)
            Text(
                "SNR لكل اتجاه — RPC dish_get_obstruction_map=2008",
                style = MaterialTheme.typography.labelSmall,
                color = MutedText,
            )
            Spacer(Modifier.height(12.dp))

            Button(
                onClick = { vm.loadObstructionMap() },
                enabled = !mapUi.loading,
                colors = ButtonDefaults.buttonColors(containerColor = SkyBlue),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (mapUi.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(16.dp).width(16.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF06263B),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("يجلب الخريطة…", color = Color(0xFF06263B), fontWeight = FontWeight.Bold)
                } else {
                    Text("جلب الخريطة من الطبق", color = Color(0xFF06263B), fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(12.dp))

            mapUi.errorAr?.let {
                Surface(color = WarnAmber.copy(alpha = 0.14f), shape = RoundedCornerShape(10.dp)) {
                    Text(
                        it,
                        color = WarnAmber,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
            }

            val data = mapUi.map
            if (data != null && data.numRows > 0 && data.numCols > 0) {
                GlassCard {
                    Text("خريطة السماء", style = MaterialTheme.typography.titleMedium, color = StrongText)
                    Spacer(Modifier.height(8.dp))
                    PolarMapCanvas(data, modifier = Modifier.fillMaxWidth().height(300.dp))
                    Spacer(Modifier.height(8.dp))
                    MapLegend()
                }
                Spacer(Modifier.height(12.dp))
                GlassCard {
                    Text("تفاصيل الخريطة", style = MaterialTheme.typography.titleMedium, color = StrongText)
                    Spacer(Modifier.height(6.dp))
                    KVRow("الأبعاد", "%d حلقة × %d قطاع".format(data.numRows, data.numCols))
                    KVRow(
                        "أدنى ارتفاع",
                        data.minElevationDeg?.let { "%.1f°".format(it) } ?: "—",
                    )
                    KVRow(
                        "أقصى زاوية سمتية",
                        data.maxThetaDeg?.let { "%.1f°".format(it) } ?: "—",
                    )
                    KVRow("الإطار المرجعي", data.referenceFrame ?: "—")
                    KVRow("المصدر", if (data.source == "real") "الطبق مباشرة" else "محاكاة/عينة")
                    val valid = data.snr.count { it > 0 }
                    val pct = if (data.snr.isNotEmpty()) valid * 100 / data.snr.size else 0
                    KVRow("خلايا صالحة", "%d (%d%%)".format(valid, pct))
                }
            } else if (!mapUi.loading) {
                GlassCard {
                    Text(
                        "اضغط «جلب الخريطة» لطلب خريطة العرقلة من الطبق. الخريطة تُبنى تراكمياً " +
                            "خلال ساعات التشغيل وتُظهر بالضبط أين تقطع الأشجار/المباني مسار القمر.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedText,
                    )
                }
            }
        }
    }
}

@Composable
private fun PolarMapCanvas(data: ObstructionMapData, modifier: Modifier) {
    val density = LocalDensity.current
    val labelPx = with(density) { 10.sp.toPx() }
    val gridColor = Color(0x1F38BDF8)

    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = min(cx, cy) * 0.92f

        // concentric ring grid (elevation bands) + cross hairs
        for (r in 1 until data.numRows) {
            val rr = radius * (1f - r.toFloat() / data.numRows)
            drawCircle(gridColor, radius = rr, center = Offset(cx, cy), style = Stroke(width = 1f))
        }
        drawLine(gridColor, Offset(cx - radius, cy), Offset(cx + radius, cy), 1f)
        drawLine(gridColor, Offset(cx, cy - radius), Offset(cx, cy + radius), 1f)
        drawCircle(
            Color(0x3387CEEB),
            radius = radius,
            center = Offset(cx, cy),
            style = Stroke(width = 1.4f),
        )

        val cols = data.numCols
        val rows = data.numRows
        val cellAngle = (2.0 * Math.PI / cols).toFloat()
        val ringWidth = radius / rows

        // draw from outer ring (row 0) to center (last row)
        for (r in 0 until rows) {
            val rOuter = radius - r * ringWidth
            val rInner = rOuter - ringWidth
            for (c in 0 until cols) {
                val v = data.snr.getOrNull(r * cols + c) ?: continue
                if (v <= 0.0) continue // no data -> leave dark
                val color = when {
                    v < 0.35 -> BadRed.copy(alpha = 0.85f)
                    v < 0.6 -> WarnAmber.copy(alpha = 0.8f)
                    else -> GoodGreen.copy(alpha = 0.65f)
                }
                val a0 = c * cellAngle
                val a1 = (c + 1) * cellAngle
                val path = Path()
                path.moveTo(cx + rOuter * cos(a0), cy + rOuter * sin(a0))
                for (s in 1..4) {
                    val a = a0 + (a1 - a0) * s / 4f
                    path.lineTo(cx + rOuter * cos(a), cy + rOuter * sin(a))
                }
                path.lineTo(cx + rInner * cos(a1), cy + rInner * sin(a1))
                for (s in 3 downTo 0) {
                    val a = a0 + (a1 - a0) * s / 4f
                    path.lineTo(cx + rInner * cos(a), cy + rInner * sin(a))
                }
                path.close()
                drawPath(path, color)
            }
        }

        // compass letters (N up / E right / S down / W left)
        val paint = android.graphics.Paint().apply {
            textSize = labelPx
            isAntiAlias = true
            color = android.graphics.Color.argb(200, 125, 211, 252)
        }
        val nc = drawContext.canvas.nativeCanvas
        nc.drawText("N", cx - paint.textSize / 3f, cy - radius - 6f, paint)
        nc.drawText("S", cx - paint.textSize / 3f, cy + radius + paint.textSize + 4f, paint)
        nc.drawText("E", cx + radius + 6f, cy + paint.textSize / 3f, paint)
        nc.drawText("W", cx - radius - paint.textSize * 1.3f, cy + paint.textSize / 3f, paint)
    }
}

@Composable
private fun MapLegend() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LegendDot(GoodGreen, "سماء صافية")
        Spacer(Modifier.width(10.dp))
        LegendDot(WarnAmber, "SNR متوسط")
        Spacer(Modifier.width(10.dp))
        LegendDot(BadRed, "عرقلة")
        Spacer(Modifier.width(10.dp))
        LegendDot(Color(0xFF0D1430), "لا بيانات")
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Surface(color = color, shape = CircleShape) {
        Box(Modifier.size(10.dp))
    }
    Spacer(Modifier.width(4.dp))
    Text(label, style = MaterialTheme.typography.labelSmall, color = MutedText)
}
