package com.starlink.diagnostic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.starlink.diagnostic.ui.KVRow
import com.starlink.diagnostic.ui.MutedText
import com.starlink.diagnostic.ui.SkyBlue
import com.starlink.diagnostic.ui.SkySoft
import com.starlink.diagnostic.ui.StrongText

private val CHANGELOG = listOf(
    "v2.2.0 — إحصاءات دقيقة p50/p95/p99 + jitter، مؤشر جودة تدفق البيانات، " +
        "اتجاهات طويلة المدى (توفر/انقطاعات/اتجاه الكمون عبر 6س/24س/7أيام)، " +
        "تصدير CSV، معالج أول تشغيل، صحة اتصال ذكية مع تراجع تصاعدي، تقرير PDF موسع",
    "v2.1.0 — محاذاة البروتوكول مع أجهزة 2026 (API v42): إسناد أسباب الانقطاع، " +
        "خريطة العرقلة القطبية، عدادات المحاذاة، الطاقة والبطارية، حالة التحديث، " +
        "اختبار سرعة الطبق، تشخيص الراوتر وأجهزته، أهداف ping، تحكم GPS والنوم",
    "v2.0.0 — المحرك الكامل: سلسلة أدلة تشخيصية، مراقبة مباشرة، قاعدة محلية، " +
        "GPS ثلاثي الحالات، RAW gRPC، تشخيص شبكة، أوامر الطبق، تقرير PDF",
)

/**
 * V2.2 — in-app About: what the app is, how it talks to the dish, and the
 * release changelog. No Internet: the GitHub URL is informational only.
 */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF101A33),
        titleContentColor = SkySoft,
        textContentColor = StrongText,
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("إغلاق", color = SkyBlue)
            }
        },
        title = { Text("حول التطبيق") },
        text = {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
            ) {
                Surface(
                    color = SkyBlue.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(
                            "Starlink Diagnostic Pro v2.2.0",
                            style = MaterialTheme.typography.titleMedium,
                            color = StrongText,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "تشخيص محلي مباشر من الطبق عبر gRPC — بلا إنترنت وبلا خادم وسيط",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedText,
                        )
                        Spacer(Modifier.height(8.dp))
                        KVRow("بروتوكول", "SpaceX API v42 (أجهزة 2026+)")
                        KVRow("عنوان الطبق", "192.168.100.1:9200")
                        KVRow("الراوتر (اختياري)", "192.168.1.1:9000")
                        KVRow("قاعدة البيانات", "StarlinkDiagnostic.db محلية")
                        KVRow("المرجع المجتمعي", "starlink-grpc-tools v1.2.5")
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "سجل الإصدارات",
                    style = MaterialTheme.typography.titleSmall,
                    color = SkySoft,
                )
                Spacer(Modifier.height(6.dp))
                CHANGELOG.forEach { entry ->
                    Surface(
                        color = Color(0xFF0D1430),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(vertical = 3.dp),
                    ) {
                        Text(
                            entry,
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedText,
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "github.com/NassarAlshabi1/StarlinkDiagnostic",
                    style = MaterialTheme.typography.labelSmall,
                    color = SkyBlue,
                )
            }
        },
    )
}
