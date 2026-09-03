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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.starlink.diagnostic.ui.AppViewModel
import com.starlink.diagnostic.ui.GlassCard
import com.starlink.diagnostic.ui.MutedText
import com.starlink.diagnostic.ui.SkyBlue
import com.starlink.diagnostic.ui.SkySoft
import com.starlink.diagnostic.ui.StrongText

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val conn by vm.conn.collectAsState()

    var host by remember(conn.host) { mutableStateOf(conn.host) }
    var port by remember(conn.port) { mutableStateOf(conn.port.toString()) }

    Box(Modifier.fillMaxSize().background(Color(0xFF0B1026))) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 28.dp, bottom = 110.dp),
        ) {
            Text("الإعدادات", style = MaterialTheme.typography.titleLarge, color = SkySoft)
            Text(
                "عنوان الطبق والوضع — تُحفظ محلياً",
                style = MaterialTheme.typography.labelSmall,
                color = MutedText,
            )
            Spacer(Modifier.padding(top = 12.dp))

            GlassCard {
                Text("هدف gRPC", style = MaterialTheme.typography.titleMedium, color = StrongText)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("عنوان الطبق (Dish IP)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SkyBlue,
                        cursorColor = SkyBlue,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() } },
                    label = { Text("المنفذ (9200)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SkyBlue,
                        cursorColor = SkyBlue,
                    ),
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        vm.setTarget(host.ifBlank { "192.168.100.1" }, port.toIntOrNull() ?: 9200)
                        vm.refreshStatus()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SkyBlue),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("حفظ وتحديث", color = Color(0xFF06263B), fontWeight = FontWeight.Bold)
                }
                Text(
                    "ملاحظة: الراوتر غير الخاص بستارلينك قد يحتاج توجيهاً إضافياً للوصول إلى 192.168.100.1",
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedText,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))

            GlassCard {
                Text("الوضع", style = MaterialTheme.typography.titleMedium, color = StrongText)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("وضع العرض التجريبي", style = MaterialTheme.typography.bodyMedium, color = StrongText)
                        Text(
                            "محاكاة واقعية بلا طبق — للاختبار والعرض",
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedText,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = conn.mode == "demo",
                        onCheckedChange = { vm.setDemo(it) },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = SkyBlue,
                            checkedThumbColor = Color(0xFF06263B),
                        ),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { vm.loadSample() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SkyBlue.copy(alpha = 0.2f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "تحميل عينة الجهاز الحقيقية (GPS Code 14)",
                        color = SkySoft,
                    )
                }
                Text(
                    "تعيد إنتاج بيانات جهازك: rev3_proto2 / 2026.08.20 / فشل GPS بكود 14 مع inhibit",
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedText,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(12.dp))

            // V2.2: bring the first-run onboarding wizard back on demand
            val ctx = LocalContext.current
            var showGuide by remember { mutableStateOf(false) }
            if (showGuide) {
                com.starlink.diagnostic.ui.components.OnboardingDialog(onDone = { showGuide = false })
            }
            GlassCard {
                Text("دليل البدء", style = MaterialTheme.typography.titleMedium, color = StrongText)
                Spacer(Modifier.height(6.dp))
                Text(
                    "إعادة عرض معالج الإعداد الأول: الاتصال بشبكة الطبق، جاهزية الطبق، " +
                        "ثم أول تحديث حالة.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedText,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        ctx.getSharedPreferences(AppViewModel.PREFS, android.content.Context.MODE_PRIVATE)
                            .edit().putBoolean("onboarded", false).apply()
                        showGuide = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SkyBlue),
                ) {
                    Text("عرض دليل البدء", color = Color(0xFF06263B), fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(12.dp))

            GlassCard {
                Text("عن التشخيص", style = MaterialTheme.typography.titleMedium, color = StrongText)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Starlink Diagnostic Pro V2 — عميل gRPC مشتق من starlink-grpc-tools v1.2.5 " +
                        "يعمل داخل التطبيق عبر Chaquopy (Python 3.11 + grpcio 1.59.3). " +
                        "البروتوكول: spacex_api_device (get_status=1004, get_history=1007, " +
                        "dish_get_status=2004, dish_get_history=2006, reboot=1001, dish_stow=2002).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedText,
                )
            }
        }
    }
}
