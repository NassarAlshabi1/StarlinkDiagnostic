package com.starlink.diagnostic.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.starlink.diagnostic.export.JsonExporter
import com.starlink.diagnostic.ui.AppViewModel
import com.starlink.diagnostic.ui.BadRed
import com.starlink.diagnostic.ui.JsonBlock
import com.starlink.diagnostic.ui.MutedText
import com.starlink.diagnostic.ui.SkyBlue
import com.starlink.diagnostic.ui.SkySoft
import com.starlink.diagnostic.ui.WarnAmber

private val RAW_TABS = listOf(
    "status" to "Status",
    "history" to "History",
    "alerts" to "Alerts",
    "obstruction" to "Obstruction",
    "diagnostics" to "Diagnostics",
)

@Composable
fun RawScreen(vm: AppViewModel) {
    val raw by vm.raw.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) { vm.loadRaw(raw.section) }

    Box(Modifier.fillMaxSize().background(Color(0xFF0B1026))) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .padding(top = 28.dp, bottom = 110.dp),
        ) {
            Text("RAW DATA", style = MaterialTheme.typography.titleLarge, color = SkySoft)
            Text(
                "الناتج الخام كما هو — انسخه أو صدّره للتحليل",
                style = MaterialTheme.typography.labelSmall,
                color = MutedText,
            )
            Spacer(Modifier.height(10.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().let { it },
            ) {
                RAW_TABS.forEach { (key, label) ->
                    com.starlink.diagnostic.ui.SelectChip(
                        text = label,
                        selected = raw.section == key,
                        onClick = { vm.loadRaw(key) },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("starlink_raw", raw.json))
                        Toast.makeText(context, "تم نسخ JSON", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SkyBlue),
                ) {
                    Text("Copy JSON", color = Color(0xFF06263B))
                }
                OutlinedButton(onClick = {
                    try {
                        val file = JsonExporter.save(
                            context,
                            "starlink_raw_%s_%d.json".format(raw.section, System.currentTimeMillis()),
                            raw.json,
                        )
                        JsonExporter.share(context, file)
                    } catch (e: Exception) {
                        Toast.makeText(context, "فشل التصدير: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }) {
                    Text("Export JSON", color = SkySoft)
                }
            }
            Spacer(Modifier.height(10.dp))

            raw.noteAr?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = WarnAmber)
                Spacer(Modifier.height(6.dp))
            }
            raw.errorAr?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = BadRed)
                Spacer(Modifier.height(6.dp))
            }

            if (raw.loading) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(16.dp).width(16.dp),
                        strokeWidth = 2.dp,
                        color = SkyBlue,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("يجلب البيانات الخام…", color = MutedText, style = MaterialTheme.typography.bodySmall)
                }
            } else if (raw.json.isNotEmpty()) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    JsonBlock(raw.json)
                    Spacer(Modifier.height(16.dp))
                }
            } else {
                Text("—", color = MutedText)
            }
        }
    }
}
