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
import com.starlink.diagnostic.diagnostics.ErrorEntry
import com.starlink.diagnostic.ui.AppViewModel
import com.starlink.diagnostic.ui.BadRed
import com.starlink.diagnostic.ui.GlassCard
import com.starlink.diagnostic.ui.GoodGreen
import com.starlink.diagnostic.ui.MutedText
import com.starlink.diagnostic.ui.NeutralGrey
import com.starlink.diagnostic.ui.SkyBlue
import com.starlink.diagnostic.ui.SkySoft
import com.starlink.diagnostic.ui.StatusDot
import com.starlink.diagnostic.ui.StrongText
import com.starlink.diagnostic.ui.WarnAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun sevColor(sev: String): Color = when (sev) {
    "hard" -> BadRed
    "warn" -> WarnAmber
    else -> NeutralGrey
}

private fun sevLabelAr(sev: String): String = when (sev) {
    "hard" -> "عطل"
    "warn" -> "تحذير"
    else -> "معلومة"
}

private fun sourceLabelAr(source: String): String = when (source) {
    "alert" -> "تنبيه نظام"
    "hw_code" -> "كود عتاد"
    "disablement" -> "إيقاف خدمة"
    "swupdate" -> "تحديث"
    "motion" -> "حركة/محركات"
    "reboot" -> "إعادة تشغيل"
    "outage" -> "انقطاع جارٍ"
    "outage_log" -> "سجل انقطاع"
    "gps" -> "GPS"
    else -> source
}

private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

@Composable
fun ErrorsScreen(vm: AppViewModel) {
    val errors by vm.errors.collectAsState()

    Box(Modifier.fillMaxSize().background(Color(0xFF0B1026))) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 28.dp, bottom = 110.dp),
        ) {
            Text("ERRORS & ALERTS", style = MaterialTheme.typography.titleLarge, color = SkySoft)
            Text(
                "كل ما يعلنه الطبق من أعطال — مجمّع من كل مصادر gRPC مع الشدة والتفسير",
                style = MaterialTheme.typography.labelSmall,
                color = MutedText,
            )
            Spacer(Modifier.height(12.dp))

            GlassCard {
                Text("السجل الموحد للأخطاء", style = MaterialTheme.typography.titleMedium, color = StrongText)
                Spacer(Modifier.height(4.dp))
                Text(
                    "يغطي تنبيهات DishAlerts، رمز الإيقاف، فشل التحديث، المحركات، إعادة التشغيل، الانقطاعات، وأخطاء GPS.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedText,
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { vm.loadErrors() },
                    enabled = !errors.loading,
                    colors = ButtonDefaults.buttonColors(containerColor = SkyBlue),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (errors.loading) "جارٍ الجمع…" else "تحديث سجل الأخطاء", color = Color(0xFF0B1026))
                }
                errors.errorAr?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = BadRed, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(12.dp))

            errors.report?.let { rep ->
                // Summary pills
                Row(Modifier.fillMaxWidth()) {
                    SummaryPill("أعطال: ${rep.hard}", BadRed, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    SummaryPill("تحذيرات: ${rep.warn}", WarnAmber, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    SummaryPill("معلومات: ${rep.info}", NeutralGrey, Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))

                if (rep.entries.isEmpty()) {
                    GlassCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot("ok", size = 12)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "لا أخطاء معلنة — الطبق يعلن حالة نظيفة تماماً",
                                style = MaterialTheme.typography.bodyMedium,
                                color = GoodGreen,
                            )
                        }
                    }
                } else {
                    GlassCard {
                        Text(
                            "الأخطاء المكتشفة (%d)".format(rep.total),
                            style = MaterialTheme.typography.titleMedium,
                            color = StrongText,
                        )
                        Spacer(Modifier.height(8.dp))
                        rep.entries.forEachIndexed { idx, e ->
                            ErrorRow(e)
                            if (idx < rep.entries.size - 1) {
                                Spacer(Modifier.height(8.dp))
                                Surface(
                                    color = NeutralGrey.copy(alpha = 0.18f),
                                    modifier = Modifier.fillMaxWidth().height(1.dp),
                                ) {}
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "الترتيب حسب الشدة: العطل ثم التحذير ثم المعلومات. كل بند مبني على حقل معلن فعلياً من الطبق.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedText,
                    )
                }
            }

            if (errors.report == null && !errors.loading) {
                GlassCard {
                    Text(
                        "اضغط «تحديث سجل الأخطاء» لجمع كل الأعطال المعلنة من الطبق.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MutedText,
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryPill(label: String, color: Color, modifier: Modifier = Modifier) {
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(10.dp), modifier = modifier) {
        Text(
            label,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ErrorRow(e: ErrorEntry) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(
                when (e.severity) {
                    "hard" -> "fail"
                    "warn" -> "warn"
                    else -> "info"
                },
                size = 11,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                e.ar,
                style = MaterialTheme.typography.titleMedium,
                color = sevColor(e.severity),
                modifier = Modifier.weight(1f),
            )
            Text(
                sevLabelAr(e.severity),
                style = MaterialTheme.typography.labelMedium,
                color = sevColor(e.severity),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(e.en, style = MaterialTheme.typography.labelSmall, color = MutedText)
        e.detailAr?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MutedText)
        }
        Row {
            Surface(
                color = SkyBlue.copy(alpha = 0.12f),
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    sourceLabelAr(e.source),
                    style = MaterialTheme.typography.labelSmall,
                    color = SkySoft,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            e.ts?.let { ts ->
                Spacer(Modifier.width(6.dp))
                Text(
                    fmt.format(Date((ts * 1000).toLong())),
                    style = MaterialTheme.typography.labelSmall,
                    color = NeutralGrey,
                )
            }
        }
    }
}
