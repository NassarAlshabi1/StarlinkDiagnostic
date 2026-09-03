package com.starlink.diagnostic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.starlink.diagnostic.ui.AppViewModel
import com.starlink.diagnostic.ui.BadRed
import com.starlink.diagnostic.ui.GlassCard
import com.starlink.diagnostic.ui.GoodGreen
import com.starlink.diagnostic.ui.KVRow
import com.starlink.diagnostic.ui.LineChart
import com.starlink.diagnostic.ui.MetricCard
import com.starlink.diagnostic.ui.MutedText
import com.starlink.diagnostic.ui.SkyBlue
import com.starlink.diagnostic.ui.SkySoft
import com.starlink.diagnostic.ui.StrongText
import com.starlink.diagnostic.ui.WarnAmber
import com.starlink.diagnostic.ui.formatUptimeAr
import androidx.compose.foundation.layout.Arrangement

@Composable
fun LiveMonitorScreen(vm: AppViewModel) {
    val live by vm.live.collectAsState()
    val conn by vm.conn.collectAsState()
    val histStats by vm.histStats.collectAsState()
    val freshness by vm.freshness.collectAsState()

    Box(Modifier.fillMaxSize().background(Color(0xFF0B1026))) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 28.dp, bottom = 110.dp),
        ) {
            Text("مراقبة مباشرة", style = MaterialTheme.typography.titleLarge, color = SkySoft)
            Text(
                "يجمع بيانات الـ Dish باستمرار ويخزنها في StarlinkDiagnostic.db",
                style = MaterialTheme.typography.labelSmall,
                color = MutedText,
            )
            Spacer(Modifier.height(12.dp))

            // ── interval picker ──────────────────────────────────────────
            GlassCard {
                Text("فاصل الجمع", style = MaterialTheme.typography.titleMedium, color = StrongText)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppViewModel.POLL_INTERVALS.forEach { sec ->
                        com.starlink.diagnostic.ui.SelectChip(
                            text = if (sec == 1) "1 ثانية" else "$sec ثوانٍ",
                            selected = live.intervalSec == sec,
                            onClick = { vm.setInterval(sec) },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (live.running) {
                        OutlinedButton(
                            onClick = { vm.stopPolling() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("إيقاف", color = BadRed)
                        }
                    } else {
                        Button(
                            onClick = { vm.startPolling() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = SkyBlue),
                        ) {
                            Text("بدء الجمع", color = Color(0xFF06263B), fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                KVRow("دورات الجمع", "${live.pollCount}")
                KVRow("عينات في الذاكرة", "${live.points.size}")
                KVRow("Uptime", formatUptimeAr(conn.status?.uptimeS))
                live.errorAr?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = BadRed)
                }
            }
            Spacer(Modifier.height(12.dp))

            // ── V2.2: data-flow freshness + precision quality card ──────
            freshness?.let { fr ->
                if (fr.streamStalled) {
                    Surface(
                        color = BadRed.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            "تنبيه: تدفق بيانات الطبق متوقف منذ ${fr.dataAgeS ?: "؟"} ثانية — " +
                                "الطبق متصل لكنه لا يبث عينات جديدة. راقب الحالة أو أعد تشغيل الطبق.",
                            color = BadRed,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                } else {
                    Text(
                        "التدفق حي — آخر بيانات قبل ${fr.dataAgeS ?: 0} ثانية",
                        style = MaterialTheme.typography.labelSmall,
                        color = GoodGreen,
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
            histStats?.let { h ->
                GlassCard {
                    Text(
                        "جودة الاتصال — إحصاءات دقيقة" +
                            (h.windowS?.let { " (آخر $it عينة)" } ?: ""),
                        style = MaterialTheme.typography.titleMedium,
                        color = StrongText,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCard(
                            "p50 (ms)", h.p50Ms?.let { "%.1f".format(it) } ?: "—",
                            modifier = Modifier.weight(1f),
                        )
                        MetricCard(
                            "p95 (ms)", h.p95Ms?.let { "%.1f".format(it) } ?: "—",
                            modifier = Modifier.weight(1f),
                        )
                        MetricCard(
                            "p99 (ms)", h.p99Ms?.let { "%.1f".format(it) } ?: "—",
                            modifier = Modifier.weight(1f),
                        )
                        MetricCard(
                            "Jitter (ms)", h.jitterMs?.let { "%.1f".format(it) } ?: "—",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    KVRow(
                        "فقد الحزم في النافذة",
                        h.lossPct?.let { "%.2f%%".format(it) } ?: "—",
                        valueColor = if ((h.lossPct ?: 0.0) > 2.0) BadRed else GoodGreen,
                    )
                    KVRow("متوسط التنزيل", h.downMbpsAvg?.let { "%.2f Mbps".format(it) } ?: "—")
                    KVRow("متوسط الرفع", h.upMbpsAvg?.let { "%.2f Mbps".format(it) } ?: "—")
                    KVRow("عينات الكمون المتاحة", "${h.nLat}/${h.n}")
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── charts ───────────────────────────────────────────────────
            val pts = live.points
            val down = pts.map { it.download }
            val up = pts.map { it.upload }
            val lat = pts.map { it.latency }
            val loss = pts.map { it.packetLoss * 100.0 }

            GlassCard {
                Text("Throughput", style = MaterialTheme.typography.titleMedium, color = StrongText)
                LineChart(
                    series = listOf(down, up),
                    colors = listOf(SkyBlue, GoodGreen),
                    labels = listOf("Download", "Upload"),
                    unit = "Mbps",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                KVRow("الآن", "%.2f ↓ / %.2f ↑ Mbps".format(
                    down.lastOrNull() ?: 0.0, up.lastOrNull() ?: 0.0,
                ), valueColor = SkySoft)
            }
            Spacer(Modifier.height(12.dp))
            GlassCard {
                Text("Latency", style = MaterialTheme.typography.titleMedium, color = StrongText)
                LineChart(
                    series = listOf(lat),
                    colors = listOf(WarnAmber),
                    labels = listOf("pop ping"),
                    unit = "ms",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                KVRow("الآن", "%.1f ms".format(lat.lastOrNull() ?: 0.0), valueColor = WarnAmber)
            }
            Spacer(Modifier.height(12.dp))
            GlassCard {
                Text("Packet Loss", style = MaterialTheme.typography.titleMedium, color = StrongText)
                LineChart(
                    series = listOf(loss),
                    colors = listOf(BadRed),
                    labels = listOf("drop rate"),
                    unit = "%",
                    fixedMin = 0.0,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                KVRow(
                    "الآن",
                    "%.1f%%".format(loss.lastOrNull() ?: 0.0),
                    valueColor = if ((loss.lastOrNull() ?: 0.0) > 2.0) BadRed else GoodGreen,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "كل عينة تُخزَّن محلياً للسجل والرسوم اللاحقة — بلا إنترنت",
                style = MaterialTheme.typography.labelSmall,
                color = MutedText,
            )
        }
    }
}
