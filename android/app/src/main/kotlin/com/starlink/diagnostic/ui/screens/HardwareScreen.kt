package com.starlink.diagnostic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.starlink.diagnostic.ui.AppViewModel
import com.starlink.diagnostic.ui.GlassCard
import com.starlink.diagnostic.ui.MutedText
import com.starlink.diagnostic.ui.SkySoft
import com.starlink.diagnostic.ui.StatusDot
import com.starlink.diagnostic.ui.StrongText
import com.starlink.diagnostic.ui.statusColor

@Composable
fun HardwareScreen(vm: AppViewModel) {
    val diag by vm.diag.collectAsState()

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
                "الحالة مرتبطة بالأكواد التي يعلنها gRPC — لا أكواد مخترعة",
                style = MaterialTheme.typography.labelSmall,
                color = MutedText,
            )
            Spacer(Modifier.padding(top = 12.dp))

            val a = diag.assessment
            if (a == null) {
                GlassCard {
                    Text(
                        "شغّل «تشخيص كامل» أولاً لبناء خريطة العتاد من بيانات الطبق.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MutedText,
                    )
                }
            } else {
                GlassCard {
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
                                    "ok" -> "🟢"
                                    "warn" -> "🟡"
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
