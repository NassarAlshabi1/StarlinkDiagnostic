package com.starlink.diagnostic.ui.screens

import android.widget.Toast
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.starlink.diagnostic.ui.AppViewModel
import com.starlink.diagnostic.ui.BadRed
import com.starlink.diagnostic.ui.ConfirmDialog
import com.starlink.diagnostic.ui.GlassCard
import com.starlink.diagnostic.ui.MutedText
import com.starlink.diagnostic.ui.SkyBlue
import com.starlink.diagnostic.ui.SkySoft
import com.starlink.diagnostic.ui.StrongText
import com.starlink.diagnostic.ui.WarnAmber

@Composable
fun ControlScreen(vm: AppViewModel) {
    var pending by remember { mutableStateOf<String?>(null) }
    var lastNote by remember { mutableStateOf<String?>(null) }
    val conn by vm.conn.collectAsState()
    val context = LocalContext.current

    Box(Modifier.fillMaxSize().background(Color(0xFF0B1026))) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 28.dp, bottom = 110.dp),
        ) {
            Text("DISH CONTROL", style = MaterialTheme.typography.titleLarge, color = SkySoft)
            Text(
                "أوامر gRPC الحقيقية فقط: reboot=1001، dish_stow=2002 (unstow=true للفك)",
                style = MaterialTheme.typography.labelSmall,
                color = MutedText,
            )
            Spacer(Modifier.height(12.dp))

            if (conn.mode != "real") {
                Surface(color = WarnAmber.copy(alpha = 0.14f), shape = RoundedCornerShape(10.dp)) {
                    Text(
                        "أنت في وضع العرض — الأوامر لن تُرسل فعلياً إلى طبق",
                        color = WarnAmber,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
            }

            ControlCard(
                title = "↻ Restart Dish",
                body = "إعادة تشغيل كاملة للطبق. يستغرق دقائق وستنقطع الكاميرات والمراقبة أثناءها.",
                danger = true,
                onClick = { pending = "reboot" },
            )
            Spacer(Modifier.height(10.dp))
            ControlCard(
                title = "⬇ Stow",
                body = "طوي الطبق في وضع التخزين (يفيد قبل العواصف أو النقل).",
                danger = false,
                onClick = { pending = "stow" },
            )
            Spacer(Modifier.height(10.dp))
            ControlCard(
                title = "⬆ Unstow",
                body = "فك الطبق من وضع التخزين لمتابعة الخدمة.",
                danger = false,
                onClick = { pending = "unstow" },
            )

            lastNote?.let {
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = com.starlink.diagnostic.ui.GoodGreen.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        it,
                        color = com.starlink.diagnostic.ui.GoodGreen,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                    )
                }
            }
        }
    }

    pending?.let { action ->
        val (title, body, confirm) = when (action) {
            "reboot" -> Triple(
                "تأكيد إعادة التشغيل",
                "سيُعاد تشغيل الطبق الآن. خلال ذلك لن يتوفر إنترنت وقد تتأخر الاستجابة دقائق. هل تريد المتابعة؟",
                "نعم، أعد التشغيل",
            )
            "stow" -> Triple(
                "تأكيد Stow",
                "سيُطوى الطبق في وضع التخزين وقد تتأثر الخدمة. متابعة؟",
                "نعم، طوِ الطبق",
            )
            else -> Triple(
                "تأكيد Unstow",
                "سيُفك الطبق ويستأنف العمل. متابعة؟",
                "نعم، فك الطبق",
            )
        }
        ConfirmDialog(
            title = title,
            body = body,
            confirmLabel = confirm,
            destructive = action == "reboot",
            onConfirm = {
                pending = null
                vm.control(action) { note ->
                    lastNote = note
                    Toast.makeText(context, note, Toast.LENGTH_LONG).show()
                }
            },
            onDismiss = { pending = null },
        )
    }
}

@Composable
private fun ControlCard(title: String, body: String, danger: Boolean, onClick: () -> Unit) {
    GlassCard {
        Text(title, style = MaterialTheme.typography.titleMedium, color = StrongText)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = MutedText)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            if (danger) {
                Button(
                    onClick = onClick,
                    colors = ButtonDefaults.buttonColors(containerColor = BadRed),
                ) {
                    Text("تنفيذ", color = Color(0xFF2B0707), fontWeight = FontWeight.Bold)
                }
            } else {
                OutlinedButton(onClick = onClick) {
                    Text("تنفيذ", color = SkySoft)
                }
            }
        }
    }
}
