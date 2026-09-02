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
import com.starlink.diagnostic.ui.AppViewModel
import com.starlink.diagnostic.ui.BadRed
import com.starlink.diagnostic.ui.GlassCard
import com.starlink.diagnostic.ui.GoodGreen
import com.starlink.diagnostic.ui.MutedText
import com.starlink.diagnostic.ui.SkyBlue
import com.starlink.diagnostic.ui.SkySoft
import com.starlink.diagnostic.ui.StatusDot
import com.starlink.diagnostic.ui.StrongText
import com.starlink.diagnostic.ui.WarnAmber

@Composable
fun NetworkScreen(vm: AppViewModel) {
    val net by vm.net.collectAsState()

    Box(Modifier.fillMaxSize().background(Color(0xFF0B1026))) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 28.dp, bottom = 110.dp),
        ) {
            Text("تشخيص الشبكة", style = MaterialTheme.typography.titleLarge, color = SkySoft)
            Text(
                "يفصل مشكلة الراوتر عن مشكلة الطبق — قفزة بقفزة",
                style = MaterialTheme.typography.labelSmall,
                color = MutedText,
            )
            Spacer(Modifier.height(12.dp))

            Button(
                onClick = { vm.runNetworkCheck() },
                enabled = !net.running,
                colors = ButtonDefaults.buttonColors(containerColor = SkyBlue),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (net.running) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(16.dp).width(16.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF06263B),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("يفحص المسار…", color = Color(0xFF06263B), fontWeight = FontWeight.Bold)
                } else {
                    Text("فحص المسار الآن", color = Color(0xFF06263B), fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(12.dp))

            net.errorAr?.let {
                Surface(color = BadRed.copy(alpha = 0.12f), shape = RoundedCornerShape(10.dp)) {
                    Text(
                        it,
                        color = BadRed,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
            }

            net.probe?.let { p ->
                GlassCard {
                    Text("قياسات مباشرة", style = MaterialTheme.typography.titleMedium, color = StrongText)
                    Spacer(Modifier.height(6.dp))
                    netRow("Phone IP", p.phoneIp ?: "غير معروف", p.phoneIp != null)
                    netRow("Gateway (الراوتر)", p.gateway ?: "غير معروف", p.gateway != null)
                    netRow(
                        "Dish TCP 192.168.100.1:9200",
                        if (p.tcp9200Ok == true) "مفتوح" else "لا وصول",
                        p.tcp9200Ok == true,
                    )
                    netRow("ICMP ping (معلومي)", if (p.icmpOk == true) "يرد" else "لا يرد/محجوب", p.icmpOk == true)
                    netRow(
                        "gRPC Handle",
                        if (p.grpcOk == true) "استجابة سليمة" else "فشل",
                        p.grpcOk == true,
                    )
                    p.popLatencyMs?.let {
                        netRow("POP Latency (من الطبق)", "%.1f ms".format(it), true)
                    }
                    p.errorAr?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.labelSmall, color = WarnAmber)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            net.verdict?.let { v ->
                GlassCard {
                    Text("المسار", style = MaterialTheme.typography.titleMedium, color = StrongText)
                    Spacer(Modifier.height(8.dp))
                    v.hops.forEachIndexed { i, hop ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(if (hop.ok) "ok" else "fail", size = 10)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(hop.labelAr, style = MaterialTheme.typography.labelMedium, color = StrongText)
                                Text(hop.detail, style = MaterialTheme.typography.labelSmall, color = MutedText)
                            }
                        }
                        if (i < v.hops.size - 1) {
                            Box(
                                Modifier
                                    .padding(start = 4.dp)
                                    .height(10.dp)
                                    .width(2.dp)
                                    .background(Color(0x3338BDF8)),
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        color = com.starlink.diagnostic.ui.SkyBlue.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            v.verdictAr,
                            style = MaterialTheme.typography.bodySmall,
                            color = SkySoft,
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                        )
                    }
                }
            }

            if (net.probe == null && !net.running) {
                GlassCard {
                    Text(
                        "الفحص يمر بالتسلسل: عنوان الهاتف → بوابة الراوتر → منفذ الطبق 9200 → استدعاء gRPC حقيقي. " +
                            "إن فشل المنفذ فالمشكلة في المسار الشبكي (الراوتر)، وإن فتح المنفذ وفشل gRPC فالمشكلة عند الطبق نفسه.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedText,
                    )
                }
            }
        }
    }
}

@Composable
private fun netRow(label: String, value: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MutedText)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) GoodGreen else BadRed,
            )
            Spacer(Modifier.width(6.dp))
            StatusDot(if (ok) "ok" else "fail", size = 7)
        }
    }
}
