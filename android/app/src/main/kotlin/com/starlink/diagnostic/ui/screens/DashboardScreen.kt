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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.starlink.diagnostic.ui.AppViewModel
import com.starlink.diagnostic.ui.BadRed
import com.starlink.diagnostic.ui.GlassCard
import com.starlink.diagnostic.ui.GoodGreen
import com.starlink.diagnostic.ui.KVRow
import com.starlink.diagnostic.ui.MetricCard
import com.starlink.diagnostic.ui.MutedText
import com.starlink.diagnostic.ui.NavyCard
import com.starlink.diagnostic.ui.SkyBlue
import com.starlink.diagnostic.ui.SkySoft
import com.starlink.diagnostic.ui.StatusPill
import com.starlink.diagnostic.ui.StrongText
import com.starlink.diagnostic.ui.WarnAmber
import com.starlink.diagnostic.ui.formatGpsAr
import com.starlink.diagnostic.ui.formatUptimeAr

@Composable
fun DashboardScreen(vm: AppViewModel, nav: NavHostController) {
    val conn by vm.conn.collectAsState()

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF0B1026), Color(0xFF0E1430))),
            ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 28.dp, bottom = 110.dp),
        ) {
            // ── Header ───────────────────────────────────────────────────
            Text(
                "STARLINK DIAGNOSTIC PRO",
                style = MaterialTheme.typography.titleLarge,
                color = SkySoft,
            )
            Text(
                "تشخيص مباشر من الطبق عبر gRPC — 192.168.100.1:9200",
                style = MaterialTheme.typography.labelSmall,
                color = MutedText,
            )
            Spacer(Modifier.height(14.dp))

            if (conn.mode != "real") {
                Surface(
                    color = WarnAmber.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        if (conn.mode == "demo") "وضع العرض التجريبي (بيانات محاكاة)"
                        else "عرض العينة المرفقة (GPS Code 14)",
                        style = MaterialTheme.typography.labelSmall,
                        color = WarnAmber,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
            }

            // ── DISH card ────────────────────────────────────────────────
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🛰️ DISH", style = MaterialTheme.typography.titleMedium, color = StrongText)
                    Spacer(Modifier.weight(1f))
                    when {
                        conn.loading -> CircularProgressIndicator(
                            modifier = Modifier.height(16.dp).width(16.dp),
                            strokeWidth = 2.dp,
                            color = SkyBlue,
                        )
                        else -> StatusPill(
                            text = conn.status?.state ?: "غير متصل",
                            status = if (conn.status?.state == "CONNECTED") "ok"
                            else if (conn.errorAr != null) "fail" else "warn",
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                KVRow("العنوان", "${conn.host}:${conn.port}", valueColor = SkySoft)
                KVRow("Hardware", conn.status?.deviceInfo?.hardwareVersion ?: "—")
                KVRow("Firmware", conn.status?.deviceInfo?.softwareVersion ?: "—")
                KVRow("Uptime", formatUptimeAr(conn.status?.uptimeS))
                KVRow(
                    "إيثرنت",
                    conn.status?.ethSpeedMbps?.let { "$it Mbps" } ?: "—",
                )
                conn.errorAr?.let { err ->
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = BadRed.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(err, color = BadRed, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { vm.refreshStatus() }) {
                                Text("إعادة المحاولة", color = SkyBlue)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // ── HARDWARE SELF TEST card ──────────────────────────────────
            GlassCard {
                Text(
                    "HARDWARE SELF TEST",
                    style = MaterialTheme.typography.titleMedium,
                    color = StrongText,
                )
                Spacer(Modifier.height(8.dp))
                val codes = conn.status?.alertHwCodes?.filter { it != 0 } ?: emptyList()
                val gpsLabel = conn.status?.gps?.label ?: "unknown"
                if (codes.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill("🔴 FAILED", "fail")
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Failed Tests:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedText,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    codes.forEach { code ->
                        val name = when (code) {
                            14 -> "GPS"
                            else -> "CODE_$code"
                        }
                        KVRow(name, "Code $code", valueColor = BadRed)
                    }
                } else {
                    StatusPill(
                        if (conn.status == null) "UNKNOWN" else "🟢 PASSED",
                        if (conn.status == null) "na" else "pass",
                    )
                }
                Spacer(Modifier.height(6.dp))
                KVRow("GPS", formatGpsAr(gpsLabel))
            }
            Spacer(Modifier.height(12.dp))

            // ── V42 EVIDENCE card (outage / restriction / update / power) ─
            val st = conn.status
            if (st != null) {
                GlassCard {
                    Text("حالة الطبق الحية", style = MaterialTheme.typography.titleMedium, color = StrongText)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (st.outage?.ongoing == true) {
                            StatusPill("انقطاع: ${st.outage.causeAr}", "warn")
                        } else {
                            StatusPill("لا انقطاع جارٍ", "ok")
                        }
                    }
                    val restrictions = listOfNotNull(
                        st.dlRestrictedAr?.takeIf { st.dlRestrictedAr != "بلا تقييد" },
                        st.ulRestrictedAr?.takeIf { st.ulRestrictedAr != "بلا تقييد" },
                    )
                    Spacer(Modifier.height(8.dp))
                    KVRow(
                        "تقييد النطاق",
                        if (restrictions.isEmpty()) "بلا تقييد" else restrictions.distinct().joinToString("، "),
                        valueColor = if (restrictions.isEmpty()) GoodGreen else WarnAmber,
                    )
                    KVRow(
                        "تحديث البرامج",
                        st.softwareUpdateStateAr ?: "—",
                        valueColor = when (st.softwareUpdateState) {
                            null, 1 -> MutedText
                            8 -> BadRed
                            else -> WarnAmber
                        },
                    )
                    if (st.disablementCode != null && st.disablementCode != 1) {
                        KVRow("رمز الإيقاف", st.disablementAr ?: "${st.disablementCode}", valueColor = BadRed)
                    }
                    st.power?.dishW?.let {
                        KVRow("سحب الطبق", "%.0f W".format(it), valueColor = SkySoft)
                    }
                    st.alignment?.let { al ->
                        val deltaAz = if (al.boresightAzimuthDeg != null && al.desiredAzimuthDeg != null) {
                            kotlin.math.abs(al.boresightAzimuthDeg - al.desiredAzimuthDeg)
                        } else null
                        KVRow(
                            "المحاذاة",
                            if (deltaAz != null) "انحراف %.1f° عن المطلوب".format(deltaAz) else "غير معروفة",
                            valueColor = if ((deltaAz ?: 0.0) > 5.0) WarnAmber else GoodGreen,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── NETWORK card ─────────────────────────────────────────────
            GlassCard {
                Text("NETWORK", style = MaterialTheme.typography.titleMedium, color = StrongText)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard(
                        "Download",
                        conn.status?.downMbps?.let { "%.2f".format(it) } ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                    MetricCard(
                        "Upload",
                        conn.status?.upMbps?.let { "%.2f".format(it) } ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard(
                        "Latency",
                        conn.status?.latencyMs?.let { "%.1f".format(it) } ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                    MetricCard(
                        "Packet Loss",
                        conn.status?.dropRate?.let { "%.1f%%".format(it * 100) } ?: "—",
                        modifier = Modifier.weight(1f),
                        accent = if ((conn.status?.dropRate ?: 0.0) > 0.02) WarnAmber else GoodGreen,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            // ── Actions ──────────────────────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        vm.runFullDiagnostic(includeNet = true)
                        nav.navigate("diagnostics")
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = SkyBlue),
                ) {
                    Text(
                        "تشخيص كامل",
                        color = Color(0xFF06263B),
                        fontWeight = FontWeight.Bold,
                    )
                }
                OutlinedButton(
                    onClick = { nav.navigate("live") },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("مراقبة مباشرة", color = SkySoft)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "البيانات تُقرأ مباشرة من الطبق — بلا إنترنت ولا سحابة",
                style = MaterialTheme.typography.labelSmall,
                color = MutedText,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
