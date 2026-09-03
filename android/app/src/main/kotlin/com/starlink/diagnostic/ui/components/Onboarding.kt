package com.starlink.diagnostic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.starlink.diagnostic.ui.MutedText
import com.starlink.diagnostic.ui.SkyBlue
import com.starlink.diagnostic.ui.SkySoft
import com.starlink.diagnostic.ui.StrongText

private data class OnboardStep(
    val num: String,
    val title: String,
    val body: String,
)

private val ONBOARD_STEPS = listOf(
    OnboardStep(
        "1",
        "اتصل بشبكة الطبق",
        "من إعدادات Wi-Fi في هاتفك اتصل بشبكة راوتر Starlink. " +
            "لا حاجة لظهور الإنترنت في الهاتف — التطبيق يتحدث مع الطبق محلياً " +
            "عبر 192.168.100.1:9200 مباشرة.",
    ),
    OnboardStep(
        "2",
        "تأكد من جاهزية الطبق",
        "وصّل الطبق بالطاقة وانتظر دقيقة حتى يكتمل الإقلاع، وتأكد أن " +
            "مجال رؤيته للسماء واضح. إذا كان الطبق مطوياً (Stowed) فسيظهر " +
            "ذلك في اللوحة مع سبب الانقطاع.",
    ),
    OnboardStep(
        "3",
        "اضغط «تحديث الحالة»",
        "من اللوحة الرئيسية اضغط زر تحديث الحالة — ستظهر قراءات الطبق " +
            "الحقيقية خلال ثوانٍ. لتجميع السجل والرسوم شغّل «المراقبة المباشرة» " +
            "ثم تصفح السجل والاتجاهات لاحقاً.",
    ),
)

/**
 * V2.2 — first-run onboarding wizard. Shown once; the user can bring it
 * back any time from Settings («دليل البدء»).
 */
@Composable
fun OnboardingDialog(onDone: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    val s = ONBOARD_STEPS[step]

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDone,
        containerColor = Color(0xFF101A33),
        titleContentColor = SkySoft,
        textContentColor = StrongText,
        confirmButton = {
            Button(
                onClick = { if (step < ONBOARD_STEPS.size - 1) step++ else onDone() },
                colors = ButtonDefaults.buttonColors(containerColor = SkyBlue),
            ) {
                Text(
                    if (step < ONBOARD_STEPS.size - 1) "التالي" else "ابدأ الاستخدام",
                    color = Color(0xFF06263B),
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            if (step > 0) {
                androidx.compose.material3.TextButton(onClick = { step-- }) {
                    Text("السابق", color = MutedText)
                }
            } else {
                androidx.compose.material3.TextButton(onClick = onDone) {
                    Text("تخطي", color = MutedText)
                }
            }
        },
        title = {
            Column {
                Text("أهلاً بك في Starlink Diagnostic Pro")
                Text(
                    "ثلاث خطوات للبدء (${step + 1}/${ONBOARD_STEPS.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedText,
                )
            }
        },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ONBOARD_STEPS.forEachIndexed { i, _ ->
                        Box(
                            Modifier
                                .size(if (i == step) 10.dp else 7.dp)
                                .background(
                                    if (i == step) SkyBlue else MutedText.copy(alpha = 0.4f),
                                    CircleShape,
                                ),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = SkyBlue.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = SkyBlue.copy(alpha = 0.2f),
                                shape = CircleShape,
                            ) {
                                Text(
                                    s.num,
                                    color = SkySoft,
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                            Spacer(Modifier.size(8.dp))
                            Text(
                                s.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = StrongText,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            s.body,
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedText,
                        )
                    }
                }
            }
        },
    )
}
