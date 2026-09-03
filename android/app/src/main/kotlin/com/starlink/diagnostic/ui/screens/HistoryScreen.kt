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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.starlink.diagnostic.ui.AppViewModel
import com.starlink.diagnostic.ui.BadRed
import com.starlink.diagnostic.ui.GlassCard
import com.starlink.diagnostic.ui.GoodGreen
import com.starlink.diagnostic.ui.KVRow
import com.starlink.diagnostic.ui.LineChart
import com.starlink.diagnostic.ui.MetricCard
import com.starlink.diagnostic.ui.MutedText
import com.starlink.diagnostic.ui.SelectChip
import com.starlink.diagnostic.ui.SkyBlue
import com.starlink.diagnostic.ui.SkySoft
import com.starlink.diagnostic.ui.StrongText
import com.starlink.diagnostic.ui.WarnAmber
import com.starlink.diagnostic.ui.formatTsAr
import com.starlink.diagnostic.ui.formatTsMsAr
import com.starlink.diagnostic.ui.statusColor

/** V2.2 — colored arrow + label for a trend direction. */
private fun trendAr(direction: String?): Pair<String, Color> = when (direction) {
    "improving" -> "تحسن" to GoodGreen
    "degrading" -> "تراجع" to BadRed
    else -> "مستقر" to MutedText
}

@Composable
fun HistoryScreen(vm: AppViewModel) {
    val hist by vm.history.collectAsState()
    val trends by vm.trends.collectAsState()
    val csv by vm.csv.collectAsState()
    val windowHours = androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(24) }

    // V2.2: load long-range trends once when the screen opens
    LaunchedEffect(Unit) { vm.loadTrends() }

    Box(Modifier.fillMaxSize().background(Color(0xFF0B1026))) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 28.dp, bottom = 110.dp),
        ) {
            Text("السجل المحلي", style = MaterialTheme.typography.titleLarge, color = SkySoft)
            Text(
                "StarlinkDiagnostic.db — يملأ أثناء تشغيل المراقبة المباشرة",
                style = MaterialTheme.typography.labelSmall,
                color = MutedText,
            )
            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(6 to "6 ساعات", 24 to "24 ساعة", 168 to "7 أيام").forEach { (h, label) ->
                    SelectChip(label, windowHours.intValue == h) {
                        windowHours.intValue = h
                        vm.loadHistory(h)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Button(
                    onClick = { vm.loadHistory(windowHours.intValue) },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = SkyBlue,
                    ),
                ) {
                    Text("تحديث", color = Color(0xFF06263B))
                }
                androidx.compose.material3.OutlinedButton(
                    onClick = { vm.exportCsv(windowHours.intValue) },
                    enabled = !csv.exporting,
                ) {
                    Text(
                        if (csv.exporting) "جارٍ التصدير…" else "تصدير CSV",
                        color = if (csv.exporting) MutedText else SkySoft,
                    )
                }
            }
            csv.lastFileAr?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = GoodGreen)
            }
            csv.errorAr?.let {
                Spacer(Modifier.height(6.dp))
                Text("فشل التصدير: $it", style = MaterialTheme.typography.labelSmall, color = BadRed)
            }
            Spacer(Modifier.height(12.dp))

            // ── V2.2: long-range trends (24 h primary + 6 h / 7 d) ──────
            trends.trends?.let { td ->
                val w = td.w24h
                if (w.empty) {
                    GlassCard {
                        Text(
                            "الاتجاهات طويلة المدى تُحسب من السجل المحلي — شغّل المراقبة لتجميع البيانات",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MutedText,
                        )
                    }
                } else {
                    val (latLabel, latColor) = trendAr(w.latencyTrend)
                    val (dlLabel, dlColor) = trendAr(w.downloadTrend)
                    GlassCard {
                        Text(
                            "الاتجاهات — آخر 24 ساعة",
                            style = MaterialTheme.typography.titleMedium,
                            color = StrongText,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MetricCard(
                                "التوفر", "%.2f%%".format(w.availabilityPct ?: 0.0),
                                accent = if ((w.availabilityPct ?: 100.0) >= 99.0) GoodGreen
                                else if ((w.availabilityPct ?: 100.0) >= 95.0) WarnAmber else BadRed,
                                modifier = Modifier.weight(1f),
                            )
                            MetricCard(
                                "انقطاعات", "${w.outages ?: 0}",
                                accent = if ((w.outages ?: 0) == 0) GoodGreen else WarnAmber,
                                modifier = Modifier.weight(1f),
                            )
                            MetricCard(
                                "p95 الكمون", w.p95Ms?.let { "%.0f ms".format(it) } ?: "—",
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        KVRow("اتجاه الكمون", latLabel, valueColor = latColor)
                        KVRow("اتجاه التنزيل", dlLabel, valueColor = dlColor)
                        KVRow("متوسط التنزيل", w.downAvgMbps?.let { "%.2f Mbps".format(it) } ?: "—")
                        KVRow("عينات النافذة", "${w.samples}")
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("6 ساعات" to td.w6h, "7 أيام" to td.w7d).forEach { (label, win) ->
                            GlassCard(modifier = Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.labelMedium, color = SkySoft)
                                Spacer(Modifier.height(4.dp))
                                if (win.empty) {
                                    Text("لا بيانات", style = MaterialTheme.typography.labelSmall, color = MutedText)
                                } else {
                                    KVRow("توفر", "%.2f%%".format(win.availabilityPct ?: 0.0))
                                    KVRow("انقطاعات", "${win.outages ?: 0}")
                                    val (ll, lc) = trendAr(win.latencyTrend)
                                    KVRow("كمون", ll, valueColor = lc)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            hist.errorAr?.let {
                Surface(
                    color = BadRed.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        it,
                        color = BadRed,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
            }

            hist.summary?.let { s ->
                GlassCard {
                    Text("قاعدة البيانات", style = MaterialTheme.typography.titleMedium, color = StrongText)
                    Spacer(Modifier.height(6.dp))
                    KVRow("عينات مخزنة", "${s.samples}")
                    KVRow("أول عينة", formatTsAr(s.firstTs))
                    KVRow("آخر عينة", formatTsAr(s.lastTs))
                    KVRow("اختبارات محفوظة", "${s.tests}")
                    KVRow("تنبيهات محفوظة", "${s.alerts}")
                }
                Spacer(Modifier.height(12.dp))
            }

            if (hist.series.isEmpty()) {
                GlassCard {
                    Text(
                        "لا عينات بعد في هذه النافذة — شغّل «مراقبة مباشرة» لدقائق ثم عد إلى هنا.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MutedText,
                    )
                }
            } else {
                val dl = hist.series.map { it.download }
                val ul = hist.series.map { it.upload }
                val lat = hist.series.map { it.latency }
                val loss = hist.series.map { it.packetLoss * 100.0 }

                GlassCard {
                    Text("Download / Upload", style = MaterialTheme.typography.titleMedium, color = StrongText)
                    LineChart(
                        series = listOf(dl, ul),
                        colors = listOf(SkyBlue, GoodGreen),
                        labels = listOf("↓", "↑"),
                        unit = "Mbps",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(10.dp))
                GlassCard {
                    Text("Latency", style = MaterialTheme.typography.titleMedium, color = StrongText)
                    LineChart(
                        series = listOf(lat),
                        colors = listOf(WarnAmber),
                        labels = listOf("ms"),
                        unit = "ms",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(10.dp))
                GlassCard {
                    Text("Packet Loss", style = MaterialTheme.typography.titleMedium, color = StrongText)
                    LineChart(
                        series = listOf(loss),
                        colors = listOf(BadRed),
                        labels = listOf("%"),
                        unit = "%",
                        fixedMin = 0.0,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "اختبارات محفوظة",
                style = MaterialTheme.typography.titleMedium,
                color = SkySoft,
            )
            Spacer(Modifier.height(6.dp))
            hist.tests.take(8).forEach { t ->
                GlassCard(modifier = Modifier.padding(vertical = 4.dp)) {
                    Row {
                        Text(
                            formatTsMsAr(t.ts),
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedText,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            t.result,
                            style = MaterialTheme.typography.labelMedium,
                            color = statusColor(t.result),
                        )
                    }
                    Text(
                        t.kind,
                        style = MaterialTheme.typography.labelSmall,
                        color = SkySoft,
                    )
                    val verdict = t.detail.optJSONObject("final")?.optString("verdictAr")
                    if (verdict != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            verdict,
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedText,
                            maxLines = 3,
                        )
                    }
                }
            }
        }
    }
}
