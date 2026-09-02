package com.starlink.diagnostic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.starlink.diagnostic.ui.AppViewModel
import com.starlink.diagnostic.ui.GlassCard
import com.starlink.diagnostic.ui.MutedText
import com.starlink.diagnostic.ui.SkySoft
import com.starlink.diagnostic.ui.StrongText

private data class MoreItem(
    val route: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
)

@Composable
fun MoreScreen(vm: AppViewModel, nav: NavHostController) {
    val conn by vm.conn.collectAsState()

    val items = listOf(
        MoreItem("hardware", "HARDWARE", "خريطة العتاد الثمانية والأكواد المعلنة", Icons.Rounded.Memory),
        MoreItem("gps", "GPS / GNSS", "الحالات الثلاث: unavailable / inhibited / hw failure", Icons.Rounded.GpsFixed),
        MoreItem("raw", "RAW gRPC", "Status · History · Alerts · Obstruction · Diagnostics", Icons.Rounded.DataObject),
        MoreItem("network", "NETWORK", "هاتف ← راوتر ← طبق ← gRPC ← POP", Icons.Rounded.NetworkCheck),
        MoreItem("control", "DISH CONTROL", "Restart · Stow · Unstow مع تأكيد", Icons.Rounded.RestartAlt),
        MoreItem("settings", "الإعدادات", "العنوان، المنفذ، الوضع التجريبي، العينة", Icons.Rounded.Settings),
    )

    Box(Modifier.fillMaxSize().background(Color(0xFF0B1026))) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 28.dp, bottom = 110.dp),
        ) {
            Text("المزيد", style = MaterialTheme.typography.titleLarge, color = SkySoft)
            Text(
                "أدوات V2 الكاملة",
                style = MaterialTheme.typography.labelSmall,
                color = MutedText,
            )
            Spacer(Modifier.height(12.dp))

            items.forEach { item ->
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .clickable { nav.navigate(item.route) },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(item.icon, contentDescription = item.title, tint = com.starlink.diagnostic.ui.SkyBlue)
                        Spacer(Modifier.height(0.dp))
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(item.title, style = MaterialTheme.typography.titleMedium, color = StrongText)
                            Text(item.subtitle, style = MaterialTheme.typography.labelSmall, color = MutedText)
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            GlassCard {
                Text("الهدف النهائي", style = MaterialTheme.typography.titleMedium, color = StrongText)
                Spacer(Modifier.height(4.dp))
                Text(
                    "APK حقيقي على هاتفك: تتصل بشبكة Starlink، تضغط «اتصال بالطبق»، " +
                        "وتظهر بيانات التشخيص الفعلية من 192.168.100.1:9200 — " +
                        "حالياً: ${conn.host}:${conn.port} (mode=${conn.mode})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedText,
                )
            }
        }
    }
}
