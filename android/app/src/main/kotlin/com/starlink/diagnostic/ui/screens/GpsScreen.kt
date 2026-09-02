package com.starlink.diagnostic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.starlink.diagnostic.ui.AppViewModel
import com.starlink.diagnostic.ui.BadRed
import com.starlink.diagnostic.ui.GlassCard
import com.starlink.diagnostic.ui.KVRow
import com.starlink.diagnostic.ui.MutedText
import com.starlink.diagnostic.ui.SkySoft
import com.starlink.diagnostic.ui.StatusPill
import com.starlink.diagnostic.ui.StrongText
import com.starlink.diagnostic.ui.WarnAmber

@Composable
fun GpsScreen(vm: AppViewModel) {
    val conn by vm.conn.collectAsState()
    val st = conn.status

    Box(Modifier.fillMaxSize().background(Color(0xFF0B1026))) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 28.dp, bottom = 110.dp),
        ) {
            Text("GPS / GNSS", style = MaterialTheme.typography.titleLarge, color = SkySoft)
            Text(
                "نفرّق بوضوح بين ثلاث حالات غير متكافئة",
                style = MaterialTheme.typography.labelSmall,
                color = MutedText,
            )
            Spacer(Modifier.height(12.dp))

            // ── Verdict banner ───────────────────────────────────────────
            val label = st?.gps?.label ?: "unknown"
            val (bannerColor, bannerText) = when (label) {
                "ok" -> com.starlink.diagnostic.ui.GoodGreen to "GPS يعمل بشكل طبيعي"
                "inhibited" -> WarnAmber to "GPS موقوف عمداً (INHIBITED) — ليس عطلاً"
                "no_fix" -> WarnAmber to "GPS غير متاح حالياً (NO FIX) — قد يكون مؤقتاً"
                "hardware_failure" -> BadRed to "فشل اختبار عتاد GPS (HARDWARE TEST FAILURE)"
                else -> MutedText to "لا بيانات GPS — شغّل الاتصال أو التشخيص أولاً"
            }
            Surface(
                color = bannerColor.copy(alpha = 0.14f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    bannerText,
                    color = bannerColor,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                )
            }
            Spacer(Modifier.height(12.dp))

            GlassCard {
                Text("GPS / GNSS", style = MaterialTheme.typography.titleMedium, color = StrongText)
                Spacer(Modifier.height(8.dp))
                KVRow(
                    "GPS Valid",
                    st?.gps?.valid?.let { if (it) "YES" else "NO" } ?: "—",
                    valueColor = if (st?.gps?.valid == true) com.starlink.diagnostic.ui.GoodGreen else BadRed,
                )
                KVRow(
                    "Satellites",
                    st?.gps?.sats?.toString() ?: "—",
                )
                KVRow(
                    "GPS Inhibited",
                    st?.gps?.inhibited?.let { if (it) "YES" else "NO" } ?: "unknown",
                    valueColor = if (st?.gps?.inhibited == true) WarnAmber else com.starlink.diagnostic.ui.GoodGreen,
                )
                Spacer(Modifier.height(8.dp))
                KVRow("Self Test", if (label == "hardware_failure") "FAILED" else if (st == null) "—" else "PASSED/غير مرتبط",
                    valueColor = if (label == "hardware_failure") BadRed else com.starlink.diagnostic.ui.GoodGreen)
                KVRow("Test Code", st?.gps?.hwCode?.toString() ?: "—")
                KVRow("Test", if (label == "hardware_failure") "GPS" else "—")
                st?.gps?.inhibitEvidence?.let { ev ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "دليل الـ inhibit (حقل مجهول في المخطط المضمّن): ${ev}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedText,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // ── The 3-state explanation ──────────────────────────────────
            GlassCard {
                Text("الحالات الثلاث ليست متساوية", style = MaterialTheme.typography.titleMedium, color = StrongText)
                Spacer(Modifier.height(8.dp))
                StateRow(
                    "GPS unavailable",
                    "صالح=false، أقمار=0، بدون inhibit — غالباً مؤقت (موقع، إقلاع، تغطية)",
                    WarnAmber,
                )
                StateRow(
                    "GPS inhibited",
                    "التوقف مقصود (من الإعدادات/النظام) — لا تستنتج عطلاً",
                    WarnAmber,
                )
                StateRow(
                    "GPS hardware test failure",
                    "الطبق نفسه يعلن كود فشل (مثل Code 14) — دليل عتاد فعلي",
                    BadRed,
                )
            }
            Spacer(Modifier.height(12.dp))

            Surface(
                color = com.starlink.diagnostic.ui.SkyBlue.copy(alpha = 0.10f),
                shape = RoundedCornerShape(10.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "ملاحظة مهمة (README v1.2.5)",
                        style = MaterialTheme.typography.labelMedium,
                        color = com.starlink.diagnostic.ui.SkySoft,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "منذ مايو 2026 بيانات الموقع الجغرافي عبر gRPC لم تعد متاحة لمعظم خطط الخدمة. " +
                            "لهذا لا يبني التطبيق أي افتراض على إحداثيات الموقع — بل على أعلام GPS " +
                            "التشغيلية (valid / sats / inhibit) التي تبقى متاحة للتشخيص.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedText,
                    )
                }
            }
        }
    }
}

@Composable
private fun StateRow(title: String, body: String, color: Color) {
    Column(Modifier.padding(vertical = 5.dp)) {
        StatusPill(title, when (color) {
            BadRed -> "fail"
            WarnAmber -> "warn"
            else -> "na"
        })
        Text(body, style = MaterialTheme.typography.bodySmall, color = MutedText)
        Spacer(Modifier.height(4.dp))
    }
}
