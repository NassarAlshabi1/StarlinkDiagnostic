package com.starlink.diagnostic.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.starlink.diagnostic.ui.AppViewModel
import com.starlink.diagnostic.ui.BadRed
import com.starlink.diagnostic.ui.GlassCard
import com.starlink.diagnostic.ui.GoodGreen
import com.starlink.diagnostic.ui.KVRow
import com.starlink.diagnostic.ui.MutedText
import com.starlink.diagnostic.ui.NeutralGrey
import com.starlink.diagnostic.ui.SkyBlue
import com.starlink.diagnostic.ui.SkySoft
import com.starlink.diagnostic.ui.StatusDot
import com.starlink.diagnostic.ui.StrongText
import com.starlink.diagnostic.ui.WarnAmber
import com.starlink.diagnostic.ui.formatUptimeAr
import com.starlink.diagnostic.ui.statusColor

private fun sevColor(sev: String): Color = when (sev) {
    "hard" -> BadRed
    "warn" -> WarnAmber
    else -> NeutralGrey
}

@Composable
fun HardwareScreen(vm: AppViewModel) {
    val diag by vm.diag.collectAsState()
    val hw by vm.hw.collectAsState()

    Box(Modifier.fillMaxSize().background(Color(0xFF0B1026))) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 28.dp, bottom = 110.dp),
        ) {
            Text("HARDWARE", style = MaterialTheme.typography.titleLarge, color = SkySoft)
            Text(
                "فحص عتاد شامل من بيانات الطبق المعلنة عبر gRPC — لا أكواد مخترعة",
                style = MaterialTheme.typography.labelSmall,
                color = MutedText,
            )
            Spacer(Modifier.height(12.dp))

            // ── Whole-dish hardware check (V2.3) ────────────────────────
            GlassCard {
                Text("فحص العتاد الشامل", style = MaterialTheme.typography.titleMedium, color = StrongText)
                Spacer(Modifier.height(4.dp))
                Text(
                    "يجمع هوية الجهاز، جاهزية الأنظمة الست، كل التنبيهات، الحركة، الحرارة، الطاقة، وآخر إعادة تشغيل في تقرير واحد.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedText,
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { vm.loadHardwareCheck() },
                    enabled = !hw.loading,
                    colors = ButtonDefaults.buttonColors(containerColor = SkyBlue),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (hw.loading) "جارٍ الفحص…" else "تشغيل فحص العتاد الشامل", color = Color(0xFF0B1026))
                }
                hw.errorAr?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = BadRed, style = MaterialTheme.typography.bodySmall)
                }
            }

            hw.report?.let { rep ->
                Spacer(Modifier.height(12.dp))

                // Overall verdict banner
                val (oc, ot) = when (rep.overall) {
                    "ok" -> GoodGreen to "العتاد سليم — لا أعطال معلنة"
                    "warn" -> WarnAmber to "عتاد يعمل مع تنبيهات تحتاج متابعة"
                    else -> BadRed to "توجد أعطال عتاد معلنة من الطبق"
                }
                Surface(color = oc.copy(alpha = 0.14f), shape = RoundedCornerShape(12.dp)) {
                    Text(
                        ot,
                        color = oc,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))

                // ── Device identity ─────────────────────────────────────
                GlassCard {
                    Text("هوية الجهاز", style = MaterialTheme.typography.titleMedium, color = StrongText)
                    Spacer(Modifier.height(8.dp))
                    KVRow("رقم الطبق", rep.identity.id ?: "—")
                    KVRow("إصدار العتاد", rep.identity.hardwareVersion ?: "—")
                    KVRow("إصدار البرامج", rep.identity.softwareVersion ?: "—")
                    KVRow("Build", rep.identity.buildId ?: "—")
                    KVRow("مراجعة اللوحة", rep.identity.boardRev?.toString() ?: "—")
                    KVRow("إصدار التصنيع", rep.identity.manufacturedVersion ?: "—")
                    KVRow("حماية الرجوع", rep.identity.antiRollbackVersion?.toString() ?: "—")
                    KVRow("الجيل", rep.identity.generationNumber?.toString() ?: "—")
                    KVRow("أقسام متساوية", rep.identity.partitionsEqual?.let { if (it) "نعم" else "لا" } ?: "—")
                    KVRow("الدولة", rep.identity.countryCode ?: "—")
                    KVRow("عدد الإقلاعات", rep.identity.bootcount?.toString() ?: "—")
                    KVRow("العمل الحالي", formatUptimeAr(rep.identity.uptimeS))
                }
                Spacer(Modifier.height(12.dp))

                // ── Six subsystem readiness bits ────────────────────────
                GlassCard {
                    Text(
                        "جاهزية الأنظمة الفرعية (DishReadyStates)",
                        style = MaterialTheme.typography.titleMedium,
                        color = StrongText,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "ست بتات جاهزية يعلنها الطبق مباشرة لكل وحدة داخلية",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedText,
                    )
                    Spacer(Modifier.height(8.dp))
                    rep.readyStates.forEach { r ->
                        val (st, label) = when (r.ready) {
                            true -> "ok" to "جاهز"
                            false -> "fail" to "غير جاهز"
                            null -> "na" to "غير معلن"
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StatusDot(st, size = 11)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.ar, style = MaterialTheme.typography.bodyMedium, color = StrongText)
                                Text(r.en, style = MaterialTheme.typography.labelSmall, color = MutedText)
                            }
                            Text(label, style = MaterialTheme.typography.labelMedium, color = statusColor(st))
                        }
                    }
                    if (rep.notReadyCount > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "%d وحدة غير جاهزة — أعد التشغيل إن استمرت".format(rep.notReadyCount),
                            color = BadRed,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))

                // ── Active alerts (from the full 20-field set) ──────────
                GlassCard {
                    Text(
                        "التنبيهات النشطة (%d)".format(rep.activeAlertCount),
                        style = MaterialTheme.typography.titleMedium,
                        color = StrongText,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (rep.activeAlerts.isEmpty()) {
                        Text("لا توجد تنبيهات نشطة", style = MaterialTheme.typography.bodySmall, color = MutedText)
                    } else {
                        rep.activeAlerts.forEach { a ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                StatusDot(if (a.severity == "hard") "fail" else "warn", size = 11)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(a.ar, style = MaterialTheme.typography.bodyMedium, color = StrongText)
                                    Text(a.en, style = MaterialTheme.typography.labelSmall, color = MutedText)
                                }
                                Text(
                                    if (a.severity == "hard") "عطل" else "تحذير",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = sevColor(a.severity),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "يُفحص كل تنبيه من تنبيهات DishAlerts العشرين المعلنة في البروتوكول",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedText,
                    )
                }
                Spacer(Modifier.height(12.dp))

                // ── Motion / thermal / power / reboot summary ───────────
                GlassCard {
                    Text("الحركة والحرارة والطاقة", style = MaterialTheme.typography.titleMedium, color = StrongText)
                    Spacer(Modifier.height(8.dp))
                    KVRow(
                        "حالة المحركات",
                        rep.actuatorState ?: "—",
                        valueColor = if (rep.actuatorFaulted) BadRed else GoodGreen,
                    )
                    rep.tiltAngleDeg?.let { KVRow("زاوية الميل", "%.1f°".format(it)) }
                    rep.attitudeAr?.let {
                        KVRow(
                            "مرشح الاتجاه (IMU)",
                            it,
                            valueColor = if (rep.attitudeAr.contains("فاشل") || rep.attitudeAr.contains("غير صالح")) BadRed else GoodGreen,
                        )
                    }
                    rep.attitudeUncertaintyDeg?.let { KVRow("عدم اليقين", "%.2f°".format(it)) }
                    Spacer(Modifier.height(6.dp))
                    KVRow(
                        "الحرارة",
                        when {
                            rep.thermalShutdown -> "إيقاف حراري!"
                            rep.thermalThrottle -> "خفض أداء حراري"
                            rep.psuThrottle -> "خفض حراري لمزود الطاقة"
                            rep.heating -> "التسخين نشط (شتاء)"
                            else -> "طبيعية"
                        },
                        valueColor = when {
                            rep.thermalShutdown -> BadRed
                            rep.thermalThrottle || rep.psuThrottle -> WarnAmber
                            else -> GoodGreen
                        },
                    )
                    rep.dishW?.let { KVRow("سحب الطبق", "%.1f واط".format(it)) }
                    rep.routerW?.let { KVRow("سحب الراوتر", "%.1f واط".format(it)) }
                    Spacer(Modifier.height(6.dp))
                    KVRow(
                        "آخر إعادة تشغيل",
                        rep.lastReasonAr ?: "—",
                        valueColor = sevColor(rep.lastReasonSeverity ?: "info"),
                    )
                    KVRow("عدد الإقلاعات", rep.bootcount?.toString() ?: "—")
                    rep.swuStateAr?.let { KVRow("تحديث البرامج", it) }
                }
                Spacer(Modifier.height(12.dp))

                // ── GPS inside the hardware report ──────────────────────
                GlassCard {
                    Text("GPS داخل فحص العتاد", style = MaterialTheme.typography.titleMedium, color = StrongText)
                    Spacer(Modifier.height(8.dp))
                    val vLabel = when (rep.gpsVerdict) {
                        "ok" -> "طبيعي"
                        "inhibited" -> "موقوف عمداً"
                        "no_fix" -> "لا إصلاح"
                        "hardware_failure" -> "فشل عتاد"
                        else -> "غير معروف"
                    }
                    KVRow("الحكم", vLabel)
                    KVRow("الأقمار", rep.gpsSats?.toString() ?: "—")
                    if (rep.gpsIssues.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        rep.gpsIssues.forEach { g ->
                            Text(
                                "• ${g.ar}",
                                color = sevColor(g.severity),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── Legacy 8-component map (from full diagnostic) ───────────
            val a = diag.assessment
            if (a != null) {
                GlassCard {
                    Text(
                        "خريطة المكونات (من التشخيص الكامل)",
                        style = MaterialTheme.typography.titleMedium,
                        color = StrongText,
                    )
                    Spacer(Modifier.height(6.dp))
                    a.hardware.forEach { h ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StatusDot(h.status, size = 12)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    h.key.replace('_', ' ').uppercase(),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = StrongText,
                                )
                                h.noteAr?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MutedText,
                                    )
                                }
                            }
                            Text(
                                when (h.status) {
                                    "ok", "pass" -> "🟢"
                                    "warn", "info" -> "🟡"
                                    "fail" -> "🔴"
                                    else -> "⚪"
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
                Spacer(Modifier.padding(top = 12.dp))
                GlassCard {
                    Text(
                        "أكواد العتاد المعلنة",
                        style = MaterialTheme.typography.titleMedium,
                        color = StrongText,
                    )
                    Spacer(Modifier.padding(top = 6.dp))
                    val codes = a.selfTestCodes
                    if (codes.isEmpty()) {
                        Text(
                            "لا توجد أكواد عتاد معلنة حالياً",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedText,
                        )
                    } else {
                        codes.forEach { c ->
                            Text(
                                "Code $c — ${componentArOf(c)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = statusColor(if (c == 14 || c in 1..2 || c == 9) "fail" else "warn"),
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun componentArOf(code: Int): String = when (code) {
    1 -> "إيقاف حراري"
    2 -> "خفض حراري للأداء"
    3 -> "مزود الطاقة خامل"
    4 -> "السارية غير رأسية"
    5 -> "موقع غير متوقع"
    6 -> "عرقلة مجال الرؤية"
    7 -> "إيثرنت بطيء"
    8 -> "التسخين نشط"
    9 -> "جهد غير آمن"
    10 -> "سلسلة RF غير خاملة"
    11 -> "استهلاك بطارية"
    12 -> "ATTY خامل"
    13 -> "تنازل حركي"
    14 -> "نظام تحديد المواقع GPS"
    else -> "رمز غير معروف"
}
