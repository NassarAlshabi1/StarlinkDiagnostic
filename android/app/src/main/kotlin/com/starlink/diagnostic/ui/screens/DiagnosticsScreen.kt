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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.starlink.diagnostic.export.ReportGenerator
import com.starlink.diagnostic.ui.AppViewModel
import com.starlink.diagnostic.ui.BadRed
import com.starlink.diagnostic.ui.GlassCard
import com.starlink.diagnostic.ui.GoodGreen
import com.starlink.diagnostic.ui.KVRow
import com.starlink.diagnostic.ui.MutedText
import com.starlink.diagnostic.ui.SkyBlue
import com.starlink.diagnostic.ui.StatusDot
import com.starlink.diagnostic.ui.StatusPill
import com.starlink.diagnostic.ui.StepTimeline
import com.starlink.diagnostic.ui.StrongText
import com.starlink.diagnostic.ui.WarnAmber
import com.starlink.diagnostic.ui.formatGpsAr
import com.starlink.diagnostic.ui.statusColor
import android.widget.Toast

@Composable
fun DiagnosticsScreen(vm: AppViewModel) {
    val diag by vm.diag.collectAsState()
    val conn by vm.conn.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B1026)),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 28.dp, bottom = 110.dp),
        ) {
            Text(
                "التشخيص المتقدم",
                style = MaterialTheme.typography.titleLarge,
                color = com.starlink.diagnostic.ui.SkySoft,
            )
            Text(
                "محرك تشخيص يبني سلسلة الدليل من بيانات الطبق المعلنة",
                style = MaterialTheme.typography.labelSmall,
                color = MutedText,
            )
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { vm.runFullDiagnostic(includeNet = true) },
                    enabled = !diag.running,
                    colors = ButtonDefaults.buttonColors(containerColor = SkyBlue),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (diag.running) "جارٍ التشخيص…" else "تشخيص كامل + شبكة",
                        color = Color(0xFF06263B),
                        fontWeight = FontWeight.Bold,
                    )
                }
                OutlinedButton(
                    onClick = { vm.runFullDiagnostic(includeNet = false) },
                    enabled = !diag.running,
                ) {
                    Text("بدون فحص الشبكة", color = com.starlink.diagnostic.ui.SkySoft)
                }
            }
            Spacer(Modifier.height(12.dp))

            if (diag.running) {
                GlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp).width(18.dp),
                            strokeWidth = 2.dp,
                            color = SkyBlue,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "يجمع الحالة والتاريخ والإحصاءات ويبني التقييم…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedText,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            diag.errorAr?.let { err ->
                Surface(
                    color = BadRed.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        err,
                        color = BadRed,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
            }

            val a = diag.assessment
            if (a == null) {
                if (!diag.running) {
                    GlassCard {
                        Text(
                            "لم يُجرَ تشخيص بعد — اضغط «تشخيص كامل».",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MutedText,
                        )
                    }
                }
            } else {
                // ── Self-test banner ─────────────────────────────────────
                GlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "HARDWARE SELF TEST",
                            style = MaterialTheme.typography.titleMedium,
                            color = StrongText,
                        )
                        Spacer(Modifier.weight(1f))
                        StatusPill(a.selfTestStatus, a.selfTestStatus)
                    }
                    Spacer(Modifier.height(6.dp))
                    if (a.selfTestCode != null) {
                        KVRow("Code", "${a.selfTestCode}", valueColor = statusColor(a.selfTestStatus))
                        KVRow("Component", a.selfTestComponent ?: "—")
                        KVRow("All Codes", a.selfTestCodes.joinToString(", "))
                    } else {
                        KVRow("Codes", "لا أكواد عتاد معلنة")
                    }
                    val g = a.gpsVerdict
                    KVRow("GPS", formatGpsAr(g))
                }
                Spacer(Modifier.height(12.dp))

                // ── Evidence chain ───────────────────────────────────────
                Text(
                    "سلسلة الدليل",
                    style = MaterialTheme.typography.titleMedium,
                    color = com.starlink.diagnostic.ui.SkySoft,
                )
                Spacer(Modifier.height(8.dp))
                StepTimeline(a.steps)
                Spacer(Modifier.height(12.dp))

                // ── Hardware summary ─────────────────────────────────────
                GlassCard {
                    Text(
                        "HARDWARE COMPONENTS",
                        style = MaterialTheme.typography.titleMedium,
                        color = StrongText,
                    )
                    Spacer(Modifier.height(8.dp))
                    a.hardware.forEach { h ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StatusDot(h.status)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    h.key.replace('_', ' ').uppercase(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = StrongText,
                                )
                                h.noteAr?.let {
                                    Text(it, style = MaterialTheme.typography.labelSmall, color = MutedText)
                                }
                            }
                            if (h.code != null) {
                                Text(
                                    "code ${h.code}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = statusColor(h.status),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                // ── Final assessment ─────────────────────────────────────
                GlassCard {
                    Text(
                        "التقييم النهائي",
                        style = MaterialTheme.typography.titleMedium,
                        color = StrongText,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(a.verdictAr, style = MaterialTheme.typography.bodyMedium, color = StrongText)
                    Spacer(Modifier.height(8.dp))
                    KVRow(
                        "إثبات عطل العتاد",
                        if (a.canConcludeHwFault) "ممكن بالدليل الحالي"
                        else "لا — يلزم اختبارات إضافية",
                        valueColor = if (a.canConcludeHwFault) BadRed else WarnAmber,
                    )
                    if (a.networkVerdictAr != null) {
                        KVRow("مسار الشبكة", a.networkVerdictAr)
                    }
                    if (a.nextTests.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "الاختبارات التالية المقترحة:",
                            style = MaterialTheme.typography.labelMedium,
                            color = com.starlink.diagnostic.ui.SkySoft,
                        )
                        a.nextTests.forEachIndexed { i, t ->
                            Text(
                                "${i + 1}. $t",
                                style = MaterialTheme.typography.bodySmall,
                                color = MutedText,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))

                Button(
                    onClick = {
                        try {
                            val file = ReportGenerator.generate(
                                context,
                                ReportGenerator.Input(
                                    status = conn.status,
                                    assessment = a,
                                    netVerdictAr = a.networkVerdictAr,
                                    mode = conn.mode,
                                ),
                            )
                            ReportGenerator.share(context, file)
                        } catch (e: Exception) {
                            Toast.makeText(
                                context,
                                "تعذر توليد التقرير: ${e.message}",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SkyBlue),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "GENERATE REPORT (PDF)",
                        color = Color(0xFF06263B),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
