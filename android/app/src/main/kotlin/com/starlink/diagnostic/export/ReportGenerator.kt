package com.starlink.diagnostic.export

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.BidiFormatter
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import androidx.core.content.FileProvider
import com.starlink.diagnostic.R
import com.starlink.diagnostic.diagnostics.Assessment
import com.starlink.diagnostic.diagnostics.StatusData
import com.starlink.diagnostic.diagnostics.optDoubleOrNull
import com.starlink.diagnostic.diagnostics.optStringOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Generates Starlink_Diagnostic_Report.pdf locally (android.graphics.pdf,
 * no Internet, no libraries). Content is English (like the upstream tools),
 * with the Arabic assessment paragraph rendered via proper bidi shaping.
 */
object ReportGenerator {

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 46f

    private val NAVY = Color.rgb(11, 16, 38)
    private val SKY = Color.rgb(56, 189, 248)
    private val TEXT = Color.rgb(30, 35, 55)
    private val MUTED = Color.rgb(110, 118, 140)
    private val RED = Color.rgb(214, 69, 65)
    private val GREEN = Color.rgb(34, 160, 100)
    private val AMBER = Color.rgb(216, 158, 30)

    data class Input(
        val status: StatusData?,
        val assessment: Assessment?,
        val netVerdictAr: String?,
        val mode: String,
    )

    fun generate(context: Context, input: Input): File {
        val cairo = ResourcesCompat.getFont(context, R.font.cairo)
        val doc = PdfDocument()
        var pageNo = 1
        var page = doc.startPage(
            PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create(),
        )
        var canvas = page.canvas
        var y = MARGIN

        fun newPage() {
            doc.finishPage(page)
            pageNo += 1
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            canvas = page.canvas
            y = MARGIN
        }

        fun ensure(needed: Float) {
            if (y + needed > PAGE_H - MARGIN) newPage()
        }

        val titlePaint = TextPaint().apply {
            color = NAVY; textSize = 20f; typeface = cairo; isFakeBoldText = true
        }
        val sectionPaint = TextPaint().apply {
            color = SKY; textSize = 12.5f; typeface = cairo; isFakeBoldText = true
        }
        val bodyPaint = TextPaint().apply { color = TEXT; textSize = 10.5f; typeface = cairo }
        val kvKey = TextPaint().apply { color = MUTED; textSize = 10f; typeface = cairo }
        val kvVal = TextPaint().apply {
            color = TEXT; textSize = 10f; typeface = cairo; isFakeBoldText = true
        }
        val linePaint = Paint().apply { color = Color.rgb(225, 230, 240); strokeWidth = 1f }

        fun wrapped(text: String, paint: TextPaint, width: Float): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(2f, 1f)
                .build()

        fun sectionTitle(en: String) {
            ensure(34f)
            y += 10f
            canvas.drawText(en, MARGIN, y, sectionPaint)
            y += 6f
            canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, linePaint)
            y += 14f
        }

        fun kv(key: String, value: String, valueColor: Int? = null) {
            ensure(17f)
            canvas.drawText(key, MARGIN + 6f, y, kvKey)
            val vp = if (valueColor != null) {
                TextPaint(kvVal).apply { color = valueColor }
            } else kvVal
            val layout = wrapped(value, vp, PAGE_W - 2 * MARGIN - 210f)
            canvas.save()
            canvas.translate(MARGIN + 210f, y - 9f)
            layout.draw(canvas)
            canvas.restore()
            y += maxOf(16f, layout.height + 5f)
        }

        fun paragraph(text: String, paint: TextPaint = bodyPaint) {
            val layout = wrapped(text, paint, PAGE_W - 2 * MARGIN)
            ensure(layout.height + 8f)
            canvas.save()
            canvas.translate(MARGIN, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + 8f
        }

        // ── Header ──────────────────────────────────────────────────────
        canvas.drawText("STARLINK DIAGNOSTIC REPORT", MARGIN, y, titlePaint)
        y += 14f
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        canvas.drawText("Generated: $ts    (mode: ${input.mode})", MARGIN, y, bodyPaint)
        y += 8f
        canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, linePaint)
        y += 12f

        // ── Device ──────────────────────────────────────────────────────
        input.status?.let { st ->
            sectionTitle("Device")
            kv("Dish Address", "192.168.100.1:9200")
            kv("Hardware", st.deviceInfo.hardwareVersion ?: "—")
            kv("Firmware", st.deviceInfo.softwareVersion ?: "—")
            kv("Dish ID", st.deviceInfo.id ?: "—")
            kv("State", st.state ?: "—")
            kv("Uptime", formatUptime(st.uptimeS))
        }

        // ── Hardware Self-Test ──────────────────────────────────────────
        input.assessment?.let { a ->
            sectionTitle("Hardware Self-Test")
            val color = when (a.selfTestStatus) {
                "FAILED" -> RED
                "PASSED" -> GREEN
                "WARN" -> AMBER
                else -> MUTED
            }
            kv("Status", a.selfTestStatus, color)
            if (a.selfTestCodes.isNotEmpty()) {
                kv("Codes", a.selfTestCodes.joinToString(", ") { "${it}" })
            }
            if (a.selfTestCode != null) {
                kv("Code", "${a.selfTestCode}")
                kv("Component", a.selfTestComponent ?: "—")
            }
            a.steps.firstOrNull { it.id == "self_test" }?.let {
                kv("Alert Flags", it.evidence.optJSONObject("alertFlags")?.let { f ->
                    f.keys().asSequence().toList().joinToString(", ")
                } ?: "none")
            }

            // ── GPS ─────────────────────────────────────────────────────
            sectionTitle("GPS / GNSS")
            kv("Valid", a.gpsValid?.toString() ?: "—", if (a.gpsValid == false) RED else GREEN)
            kv("Satellites", a.gpsSats?.toString() ?: "—")
            kv("Inhibit", a.gpsInhibited?.toString() ?: "unknown",
                if (a.gpsInhibited == true) AMBER else GREEN)
            if (a.gpsHwCode != null) kv("Self-Test Code", "${a.gpsHwCode}")
            kv("Verdict", verdictEn(a.gpsVerdict))

            // ── RF ──────────────────────────────────────────────────────
            sectionTitle("RF")
            val rfStep = a.steps.firstOrNull { it.id == "rf_phy" }
            rfStep?.let {
                kv("SNR Above Noise Floor", it.evidence.optStringOrNull("isSnrAboveNoiseFloor") ?: "—")
                kv("SNR Persistently Low", it.evidence.optStringOrNull("isSnrPersistentlyLow") ?: "—")
                val drop = it.evidence.optDoubleOrNull("popPingDropRate")
                kv("Ping Drop Rate", if (drop != null) String.format(Locale.US, "%.2f%%", drop * 100) else "—")
                val frac = it.evidence.optDoubleOrNull("fractionObstructed")
                kv("Obstruction Fraction", if (frac != null) String.format(Locale.US, "%.2f%%", frac * 100) else "—")
            }

            // ── Ethernet ────────────────────────────────────────────────
            sectionTitle("Ethernet")
            kv("Link Speed", input.status?.ethSpeedMbps?.let { "$it Mbps" } ?: "—")

            // ── Network ─────────────────────────────────────────────────
            sectionTitle("Network")
            val netStep = a.steps.firstOrNull { it.id == "net_path" }
            netStep?.let {
                kv("Phone IP", it.evidence.optStringOrNull("phoneIp") ?: "—")
                kv("Gateway", it.evidence.optStringOrNull("gateway") ?: "—")
                kv("Dish TCP 9200", it.evidence.optStringOrNull("dishTcp9200") ?: "—")
                val pop = it.evidence.optDoubleOrNull("popLatencyMs")
                kv("POP Latency", if (pop != null) String.format(Locale.US, "%.1f ms", pop) else "—")
            }
            val histStep = a.steps.firstOrNull { it.id == "history" }
            histStep?.let {
                val drop = it.evidence.optDoubleOrNull("totalPingDrop")
                kv("Window Ping Drop", if (drop != null) String.format(Locale.US, "%.2f%%", drop * 100) else "—")
                kv("Full Drops (window)", it.evidence.optStringOrNull("countFullPingDrop") ?: "—")
                kv("Samples", it.evidence.optStringOrNull("samples") ?: "—")
            }

            // ── Assessment ──────────────────────────────────────────────
            sectionTitle("Assessment")
            paragraph(BidiFormatter.getInstance().unicodeWrap(a.verdictAr))
            if (a.nextTests.isNotEmpty()) {
                kv("Next Tests", "")
                a.nextTests.forEachIndexed { i, t ->
                    paragraph("${i + 1}. ${BidiFormatter.getInstance().unicodeWrap(t)}")
                }
            }
            kv("Hardware Fault Concluded", if (a.canConcludeHwFault) "YES" else "NOT YET — more tests required",
                if (a.canConcludeHwFault) RED else AMBER)
            input.netVerdictAr?.let {
                kv("Network Path", BidiFormatter.getInstance().unicodeWrap(it))
            }

            // ── V2.2: precision window stats ─────────────────────────────
            a.netQuality?.let { nq ->
                sectionTitle("Network Quality (window)")
                kv("Window Samples", "${nq.n}" +
                    (nq.nLat?.let { " (latency available: $it)" } ?: ""))
                nq.p50Ms?.let { kv("Latency p50", String.format(Locale.US, "%.1f ms", it)) }
                nq.p95Ms?.let { kv("Latency p95", String.format(Locale.US, "%.1f ms", it)) }
                nq.p99Ms?.let { kv("Latency p99", String.format(Locale.US, "%.1f ms", it)) }
                nq.jitterMs?.let { kv("Jitter", String.format(Locale.US, "%.1f ms", it)) }
                nq.lossPct?.let { kv("Window Loss", String.format(Locale.US, "%.2f%%", it)) }
                nq.downMbpsAvg?.let { kv("Avg Download", String.format(Locale.US, "%.2f Mbps", it)) }
                nq.upMbpsAvg?.let { kv("Avg Upload", String.format(Locale.US, "%.2f Mbps", it)) }
            }
        }

        // ── V2.2: v42 evidence surface (from the live status) ────────────
        input.status?.let { st ->
            val hasEvidence = st.outage != null || st.disablementAr != null ||
                st.softwareUpdateStateAr != null || st.alignment != null ||
                st.power?.dishW != null
            if (hasEvidence) {
                newPageIfFooter()
                sectionTitle("V42 Evidence (live status)")
                st.outage?.let { o ->
                    kv("Outage", if (o.ongoing) "ONGOING" else "COMPLETED")
                    kv("Outage Cause", o.causeAr)
                    o.durationS?.let { kv("Outage Duration", String.format(Locale.US, "%.0f s", it)) }
                }
                st.disablementAr?.let { kv("Disablement", it) }
                st.softwareUpdateStateAr?.let { kv("Software Update", it) }
                if (st.swupdateRebootReady == true) kv("Update Reboot Ready", "YES", AMBER)
                st.dlRestrictedAr?.let { kv("Downlink Restriction", it) }
                st.ulRestrictedAr?.let { kv("Uplink Restriction", it) }
                st.alignment?.let { al ->
                    kv("Attitude State", al.attitudeState ?: "—")
                    al.desiredAzimuthDeg?.let {
                        kv("Desired Boresight Az", String.format(Locale.US, "%.1f deg", it))
                    }
                    al.desiredElevationDeg?.let {
                        kv("Desired Boresight El", String.format(Locale.US, "%.1f deg", it))
                    }
                    al.boresightAzimuthDeg?.let {
                        kv("Current Boresight Az", String.format(Locale.US, "%.1f deg", it))
                    }
                    al.boresightElevationDeg?.let {
                        kv("Current Boresight El", String.format(Locale.US, "%.1f deg", it))
                    }
                    al.tiltAngleDeg?.let {
                        kv("Tilt Angle", String.format(Locale.US, "%.1f deg", it))
                    }
                }
                st.power?.dishW?.let { kv("Dish Power Draw", String.format(Locale.US, "%.1f W", it)) }
                st.power?.routerW?.let { kv("Router Power Draw", String.format(Locale.US, "%.1f W", it)) }
            }
        }

        // ── Footer ──────────────────────────────────────────────────────
        newPageIfFooter()
        fun drawFooter() {
            canvas.drawText(
                "Starlink Diagnostic Pro V2 — offline gRPC diagnostics via starlink_grpc (v1.2.5-derived)",
                MARGIN, PAGE_H - 28f,
                TextPaint().apply { color = MUTED; textSize = 8.5f; typeface = cairo },
            )
        }
        drawFooter()

        doc.finishPage(page)
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val name = "Starlink_Diagnostic_Report_%d.pdf".format(System.currentTimeMillis())
        val out = File(dir, name)
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        return out
    }

    private fun newPageIfFooter() {
        // no-op placeholder kept for layout clarity
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "Share diagnostic report").addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK,
            ),
        )
    }

    private fun verdictEn(verdict: String): String = when (verdict) {
        "hardware_failure" -> "HARDWARE TEST FAILURE"
        "inhibited" -> "GPS INHIBITED (intentional)"
        "no_fix" -> "GPS UNAVAILABLE (no fix)"
        "ok" -> "OK"
        else -> "UNKNOWN"
    }

    private fun formatUptime(seconds: Long?): String {
        if (seconds == null || seconds <= 0) return "—"
        var s = seconds
        val d = s / 86400; s %= 86400
        val h = s / 3600; s %= 3600
        val m = s / 60; s %= 60
        return if (d > 0) "%dd %02d:%02d:%02d".format(d, h, m, s)
        else "%02d:%02d:%02d".format(h, m, s)
    }
}
