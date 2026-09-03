package com.starlink.diagnostic.diagnostics

import org.json.JSONArray
import org.json.JSONObject

/** Parsers for the JSON contract produced by python/bridge.py. */

data class DishInfo(
    val id: String?,
    val hardwareVersion: String?,
    val softwareVersion: String?,
    val countryCode: String?,
    val bootcount: Int?,
    val buildId: String?,
    val boardRev: Int?,
    val manufacturedVersion: String?,
    val antiRollbackVersion: Int?,
    val generationNumber: Long?,
    val partitionsEqual: Boolean?,
)

/** One GPS fault from the dish's own announced evidence (V2.3). */
data class GpsIssue(
    val key: String,
    val code: Int?,
    val severity: String,
    val en: String,
    val ar: String,
    val noteAr: String?,
) {
    companion object {
        fun parse(o: JSONObject): GpsIssue = GpsIssue(
            key = o.optString("key"),
            code = o.optIntOrNull("code"),
            severity = o.optString("severity", "warn"),
            en = o.optString("en"),
            ar = o.optString("ar"),
            noteAr = o.optStringOrNull("noteAr"),
        )
    }
}

data class GpsInfo(
    val verdict: String,
    val label: String,
    val valid: Boolean?,
    val sats: Int?,
    val inhibited: Boolean?,
    val hwCode: Int?,
    val inhibitEvidence: JSONObject?,
    val noSatsAfterTtff: Boolean?,
    val pntState: Int?,
    val pntStateAr: String?,
    val issues: List<GpsIssue>,
)

data class ObstructionInfo(
    val currentlyObstructed: Boolean?,
    val fractionObstructed: Double?,
    val last24hObstructedS: Long?,
    val validS: Long?,
    val avgProlongedObstructionDurationS: Double?,
    val avgProlongedObstructionIntervalS: Double?,
)

/** v42 outage message (status.outage=1014). */
data class OutageInfo(
    val cause: Int,
    val causeAr: String,
    val ongoing: Boolean,
    val startTs: Double?,
    val durationS: Double?,
)

/** v42 alignment_stats=1027 (desired vs actual boresight). */
data class AlignmentInfo(
    val tiltAngleDeg: Double?,
    val boresightAzimuthDeg: Double?,
    val boresightElevationDeg: Double?,
    val desiredAzimuthDeg: Double?,
    val desiredElevationDeg: Double?,
    val attitudeState: String?,
    val attitudeUncertaintyDeg: Double?,
    val actuatorState: String?,
)

/** Power draw from v42 upsu_stats=1043. */
data class PowerInfo(
    val dishW: Double?,
    val routerW: Double?,
    val upsuUptimeS: Long?,
)

/** Active announced alert with its Arabic label + severity (V2.3). */
data class ActiveAlert(
    val key: String,
    val en: String,
    val ar: String,
    val severity: String,
)

/** One DishReadyStates row with Arabic label (V2.3). */
data class ReadyRow(
    val key: String,
    val en: String,
    val ar: String,
    val ready: Boolean?,
)

data class StatusData(
    val state: String?,
    val uptimeS: Long?,
    val deviceInfo: DishInfo,
    val downMbps: Double?,
    val upMbps: Double?,
    val latencyMs: Double?,
    val dropRate: Double?,
    val isSnrAboveNoiseFloor: Boolean?,
    val isSnrPersistentlyLow: Boolean?,
    val ethSpeedMbps: Int?,
    val stowRequested: Boolean?,
    val alertsBitfield: Long,
    val alertHwCodes: List<Int>,
    val alerts: Map<String, Boolean>,
    val activeAlerts: List<ActiveAlert>,
    val rebootReasonCode: Int?,
    val rebootReasonAr: String?,
    val readyStatesAr: List<ReadyRow>,
    val obstruction: ObstructionInfo,
    val gps: GpsInfo,
    val boresightAzimuthDeg: Double?,
    val boresightElevationDeg: Double?,
    // ── v42 evidence surface (V2.1) ──
    val outage: OutageInfo?,
    val disablementCode: Int?,
    val disablementAr: String?,
    val disablementSeverity: String?,
    val softwareUpdateState: Int?,
    val softwareUpdateStateAr: String?,
    val swupdateRebootReady: Boolean?,
    val dlRestrictedAr: String?,
    val ulRestrictedAr: String?,
    val mobilityClass: String?,
    val alignment: AlignmentInfo?,
    val power: PowerInfo?,
    val mode: String,
    val raw: JSONObject,
) {
    companion object {
        fun parse(data: JSONObject): StatusData {
            val s = data.optJSONObject("status") ?: JSONObject()
            val di = s.optJSONObject("deviceInfo") ?: JSONObject()
            val ob = s.optJSONObject("obstruction") ?: JSONObject()
            val gp = s.optJSONObject("gps") ?: JSONObject()
            val al = s.optJSONObject("alerts") ?: JSONObject()
            val codes = mutableListOf<Int>()
            val codesArr = s.optJSONArray("alertHwCodes") ?: JSONArray()
            for (i in 0 until codesArr.length()) codes.add(codesArr.optInt(i, 0))
            val alerts = mutableMapOf<String, Boolean>()
            al.keys().forEach { alerts[it] = al.optBoolean(it, false) }
            val activeAlerts = mutableListOf<ActiveAlert>()
            val aaArr = s.optJSONArray("activeAlerts") ?: JSONArray()
            for (i in 0 until aaArr.length()) {
                val x = aaArr.optJSONObject(i) ?: continue
                activeAlerts.add(
                    ActiveAlert(
                        key = x.optString("key"),
                        en = x.optString("en"),
                        ar = x.optString("ar"),
                        severity = x.optString("severity", "warn"),
                    ),
                )
            }
            val readyRows = mutableListOf<ReadyRow>()
            val rsArr = s.optJSONArray("readyStatesAr") ?: JSONArray()
            for (i in 0 until rsArr.length()) {
                val x = rsArr.optJSONObject(i) ?: continue
                readyRows.add(
                    ReadyRow(
                        key = x.optString("key"),
                        en = x.optString("en"),
                        ar = x.optString("ar"),
                        ready = x.optBoolOrNull("ready"),
                    ),
                )
            }
            val gpsIssues = mutableListOf<GpsIssue>()
            val giArr = gp.optJSONArray("issues") ?: JSONArray()
            for (i in 0 until giArr.length()) {
                val x = giArr.optJSONObject(i) ?: continue
                gpsIssues.add(GpsIssue.parse(x))
            }
            return StatusData(
                state = s.optStringOrNull("state"),
                uptimeS = if (s.has("uptimeS") && !s.isNull("uptimeS")) s.optLong("uptimeS") else null,
                deviceInfo = DishInfo(
                    id = di.optStringOrNull("id"),
                    hardwareVersion = di.optStringOrNull("hardwareVersion"),
                    softwareVersion = di.optStringOrNull("softwareVersion"),
                    countryCode = di.optStringOrNull("countryCode"),
                    bootcount = di.optIntOrNull("bootcount"),
                    buildId = di.optStringOrNull("buildId"),
                    boardRev = di.optIntOrNull("boardRev"),
                    manufacturedVersion = di.optStringOrNull("manufacturedVersion"),
                    antiRollbackVersion = di.optIntOrNull("antiRollbackVersion"),
                    generationNumber = if (di.has("generationNumber") && !di.isNull("generationNumber")) di.optLong("generationNumber") else null,
                    partitionsEqual = di.optBoolOrNull("partitionsEqual"),
                ),
                downMbps = s.optDoubleOrNull("downMbps"),
                upMbps = s.optDoubleOrNull("upMbps"),
                latencyMs = s.optDoubleOrNull("latencyMs"),
                dropRate = s.optDoubleOrNull("dropRate"),
                isSnrAboveNoiseFloor = s.optBoolOrNull("isSnrAboveNoiseFloor"),
                isSnrPersistentlyLow = s.optBoolOrNull("isSnrPersistentlyLow"),
                ethSpeedMbps = if (s.has("ethSpeedMbps") && !s.isNull("ethSpeedMbps")) s.optInt("ethSpeedMbps") else null,
                stowRequested = s.optBoolOrNull("stowRequested"),
                alertsBitfield = s.optLong("alertsBitfield", 0L),
                alertHwCodes = codes,
                alerts = alerts,
                activeAlerts = activeAlerts,
                rebootReasonCode = s.optIntOrNull("rebootReasonCode"),
                rebootReasonAr = s.optStringOrNull("rebootReasonAr"),
                readyStatesAr = readyRows,
                obstruction = ObstructionInfo(
                    currentlyObstructed = ob.optBoolOrNull("currentlyObstructed"),
                    fractionObstructed = ob.optDoubleOrNull("fractionObstructed"),
                    last24hObstructedS = if (ob.has("last24hObstructedS") && !ob.isNull("last24hObstructedS")) ob.optLong("last24hObstructedS") else null,
                    validS = if (ob.has("validS") && !ob.isNull("validS")) ob.optLong("validS") else null,
                    avgProlongedObstructionDurationS = ob.optDoubleOrNull("avgProlongedObstructionDurationS"),
                    avgProlongedObstructionIntervalS = ob.optDoubleOrNull("avgProlongedObstructionIntervalS"),
                ),
                gps = GpsInfo(
                    verdict = gp.optString("verdict", "unknown"),
                    label = gp.optString("label", "unknown"),
                    valid = gp.optBoolOrNull("valid"),
                    sats = if (gp.has("sats") && !gp.isNull("sats")) gp.optInt("sats") else null,
                    inhibited = gp.optBoolOrNull("inhibited"),
                    hwCode = if (gp.has("hwCode") && !gp.isNull("hwCode")) gp.optInt("hwCode") else null,
                    inhibitEvidence = gp.optJSONObject("inhibitEvidence"),
                    noSatsAfterTtff = gp.optBoolOrNull("noSatsAfterTtff"),
                    pntState = gp.optIntOrNull("pntState"),
                    pntStateAr = gp.optStringOrNull("pntStateAr"),
                    issues = gpsIssues,
                ),
                boresightAzimuthDeg = s.optDoubleOrNull("boresightAzimuthDeg"),
                boresightElevationDeg = s.optDoubleOrNull("boresightElevationDeg"),
                outage = s.optJSONObject("outage")?.let { o ->
                    OutageInfo(
                        cause = o.optInt("cause", 0),
                        causeAr = o.optString("causeAr", ""),
                        ongoing = o.optBoolean("ongoing", false),
                        startTs = o.optDoubleOrNull("startTs"),
                        durationS = o.optDoubleOrNull("durationS"),
                    )
                },
                disablementCode = if (s.has("disablementCode") && !s.isNull("disablementCode")) s.optInt("disablementCode") else null,
                disablementAr = s.optStringOrNull("disablementAr"),
                disablementSeverity = s.optStringOrNull("disablementSeverity"),
                softwareUpdateState = if (s.has("softwareUpdateState") && !s.isNull("softwareUpdateState")) s.optInt("softwareUpdateState") else null,
                softwareUpdateStateAr = s.optStringOrNull("softwareUpdateStateAr"),
                swupdateRebootReady = s.optBoolOrNull("swupdateRebootReady"),
                dlRestrictedAr = s.optStringOrNull("dlRestrictedAr"),
                ulRestrictedAr = s.optStringOrNull("ulRestrictedAr"),
                mobilityClass = s.optStringOrNull("mobilityClass"),
                alignment = s.optJSONObject("alignment")?.let { al ->
                    AlignmentInfo(
                        tiltAngleDeg = al.optDoubleOrNull("tiltAngleDeg"),
                        boresightAzimuthDeg = al.optDoubleOrNull("boresightAzimuthDeg"),
                        boresightElevationDeg = al.optDoubleOrNull("boresightElevationDeg"),
                        desiredAzimuthDeg = al.optDoubleOrNull("desiredBoresightAzimuthDeg"),
                        desiredElevationDeg = al.optDoubleOrNull("desiredBoresightElevationDeg"),
                        attitudeState = al.optStringOrNull("attitudeState"),
                        attitudeUncertaintyDeg = al.optDoubleOrNull("attitudeUncertaintyDeg"),
                        actuatorState = al.optStringOrNull("actuatorState"),
                    )
                },
                power = s.optJSONObject("power")?.let { p ->
                    PowerInfo(
                        dishW = p.optDoubleOrNull("dishW"),
                        routerW = p.optDoubleOrNull("routerW"),
                        upsuUptimeS = if (p.has("upsuUptimeS") && !p.isNull("upsuUptimeS")) p.optLong("upsuUptimeS") else null,
                    )
                },
                mode = data.optString("mode", "real"),
                raw = data.optJSONObject("raw") ?: JSONObject(),
            )
        }
    }
}

data class DiagStep(
    val id: String,
    val titleEn: String,
    val titleAr: String,
    val status: String, // pass | fail | warn | info | skip
    val evidence: JSONObject,
    val noteAr: String?,
)

data class HardwareComp(
    val key: String,
    val status: String, // ok | fail | warn | na
    val code: Int?,
    val noteAr: String?,
)

data class Assessment(
    val ts: Double?,
    val selfTestStatus: String, // PASSED | FAILED | WARN | UNKNOWN
    val selfTestCode: Int?,
    val selfTestComponent: String?,
    val selfTestComponentAr: String?,
    val selfTestCodes: List<Int>,
    val gpsVerdict: String,
    val gpsValid: Boolean?,
    val gpsSats: Int?,
    val gpsInhibited: Boolean?,
    val gpsHwCode: Int?,
    val hardware: List<HardwareComp>,
    val steps: List<DiagStep>,
    val verdictAr: String,
    val nextTests: List<String>,
    val canConcludeHwFault: Boolean,
    val failed: Boolean,
    val networkVerdictAr: String?,
    val networkHops: List<NetHop>,
    val netQuality: HistStats?,   // V2.2: precision window stats (PDF)
) {
    companion object {
        fun parse(a: JSONObject): Assessment {
            val self = a.optJSONObject("selfTest") ?: JSONObject()
            val gps = a.optJSONObject("gps") ?: JSONObject()
            val final = a.optJSONObject("final") ?: JSONObject()
            val net = a.optJSONObject("network")
            val codes = mutableListOf<Int>()
            val codesArr = self.optJSONArray("codes") ?: JSONArray()
            for (i in 0 until codesArr.length()) codes.add(codesArr.optInt(i, 0))
            val hw = mutableListOf<HardwareComp>()
            val hwArr = a.optJSONArray("hardware") ?: JSONArray()
            for (i in 0 until hwArr.length()) {
                val h = hwArr.optJSONObject(i) ?: continue
                hw.add(
                    HardwareComp(
                        key = h.optString("key"),
                        status = h.optString("status", "na"),
                        code = if (h.has("code") && !h.isNull("code")) h.optInt("code") else null,
                        noteAr = h.optStringOrNull("noteAr"),
                    ),
                )
            }
            val steps = mutableListOf<DiagStep>()
            val stepArr = a.optJSONArray("steps") ?: JSONArray()
            for (i in 0 until stepArr.length()) {
                val st = stepArr.optJSONObject(i) ?: continue
                steps.add(
                    DiagStep(
                        id = st.optString("id"),
                        titleEn = st.optString("titleEn"),
                        titleAr = st.optString("titleAr"),
                        status = st.optString("status", "info"),
                        evidence = st.optJSONObject("evidence") ?: JSONObject(),
                        noteAr = st.optStringOrNull("noteAr"),
                    ),
                )
            }
            val next = mutableListOf<String>()
            val nextArr = final.optJSONArray("nextTests") ?: JSONArray()
            for (i in 0 until nextArr.length()) next.add(nextArr.optString(i))
            val hops = mutableListOf<NetHop>()
            val hopsArr = net?.optJSONArray("hops") ?: JSONArray()
            for (i in 0 until hopsArr.length()) {
                val h = hopsArr.optJSONObject(i) ?: continue
                hops.add(
                    NetHop(
                        hop = h.optString("hop"),
                        labelAr = h.optString("labelAr"),
                        ok = h.optBoolean("ok", false),
                        detail = h.optString("detail"),
                    ),
                )
            }
            return Assessment(
                ts = if (a.has("ts") && !a.isNull("ts")) a.optDouble("ts") else null,
                selfTestStatus = self.optString("status", "UNKNOWN"),
                selfTestCode = if (self.has("code") && !self.isNull("code")) self.optInt("code") else null,
                selfTestComponent = self.optStringOrNull("component"),
                selfTestComponentAr = self.optStringOrNull("componentAr"),
                selfTestCodes = codes,
                gpsVerdict = gps.optString("verdict", "unknown"),
                gpsValid = gps.optBoolOrNull("valid"),
                gpsSats = if (gps.has("sats") && !gps.isNull("sats")) gps.optInt("sats") else null,
                gpsInhibited = gps.optBoolOrNull("inhibited"),
                gpsHwCode = if (gps.has("hwCode") && !gps.isNull("hwCode")) gps.optInt("hwCode") else null,
                hardware = hw,
                steps = steps,
                verdictAr = final.optString("verdictAr"),
                nextTests = next,
                canConcludeHwFault = final.optBoolean("canConcludeHwFault", false),
                failed = final.optBoolean("failed", false),
                networkVerdictAr = net?.optStringOrNull("verdictAr"),
                networkHops = hops,
                netQuality = HistStats.parse(a.optJSONObject("netQuality")),
            )
        }
    }
}

data class NetProbe(
    val phoneIp: String?,
    val gateway: String?,
    val tcp9200Ok: Boolean?,
    val icmpOk: Boolean?,
    val grpcOk: Boolean?,
    val popLatencyMs: Double?,
    val errorAr: String?,
    val targets: List<NetworkProber.IcmpTarget> = emptyList(),
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        phoneIp?.let { o.put("phoneIp", it) }
        gateway?.let { o.put("gateway", it) }
        tcp9200Ok?.let { o.put("tcp9200Ok", it) }
        icmpOk?.let { o.put("icmpOk", it) }
        grpcOk?.let { o.put("grpcOk", it) }
        popLatencyMs?.let { o.put("popLatencyMs", it) }
        if (targets.isNotEmpty()) {
            val arr = JSONArray()
            targets.forEach { t ->
                val t0 = JSONObject()
                t0.put("name", t.name)
                t0.put("labelAr", t.labelAr)
                t0.put("ok", t.ok)
                t.latencyMs?.let { t0.put("latencyMs", it) }
                arr.put(t0)
            }
            o.put("targets", arr)
        }
        return o
    }
}

data class NetHop(
    val hop: String,
    val labelAr: String,
    val ok: Boolean,
    val detail: String,
)

data class NetVerdict(
    val hops: List<NetHop>,
    val verdictAr: String,
) {
    companion object {
        fun parse(v: JSONObject): NetVerdict {
            val hops = mutableListOf<NetHop>()
            val arr = v.optJSONArray("hops") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val h = arr.optJSONObject(i) ?: continue
                hops.add(
                    NetHop(
                        hop = h.optString("hop"),
                        labelAr = h.optString("labelAr"),
                        ok = h.optBoolean("ok", false),
                        detail = h.optString("detail"),
                    ),
                )
            }
            return NetVerdict(hops = hops, verdictAr = v.optString("verdictAr"))
        }
    }
}

data class SeriesPoint(
    val ts: Long,
    val download: Double,
    val upload: Double,
    val latency: Double,
    val packetLoss: Double,
    val gps: String?,
) {
    companion object {
        fun parse(o: JSONObject): SeriesPoint = SeriesPoint(
            ts = o.optLong("ts", 0L),
            download = o.optDouble("download", 0.0),
            upload = o.optDouble("upload", 0.0),
            latency = o.optDouble("latency", 0.0),
            packetLoss = o.optDouble("packetLoss", 0.0),
            gps = o.optStringOrNull("gps"),
        )
    }
}

data class NewSample(
    val ts: Long,
    val download: Double,
    val upload: Double,
    val latency: Double,
    val packetLoss: Double,
)

// ── V2.2: precision window stats + data-flow freshness ──────────────────
data class HistStats(
    val n: Int,               // full window samples
    val nLat: Int?,           // latency samples available (connected)
    val p50Ms: Double?,
    val p95Ms: Double?,
    val p99Ms: Double?,
    val jitterMs: Double?,
    val lossPct: Double?,
    val downMbpsAvg: Double?,
    val upMbpsAvg: Double?,
    val windowS: Int?,
) {
    companion object {
        fun parse(o: JSONObject?): HistStats? {
            o ?: return null
            if (!o.has("p50Ms") && !o.has("lossPct") && o.optInt("n", -1) < 0) return null
            return HistStats(
                n = o.optInt("n", 0),
                nLat = o.optIntOrNull("nLat"),
                p50Ms = o.optDoubleOrNull("p50Ms"),
                p95Ms = o.optDoubleOrNull("p95Ms"),
                p99Ms = o.optDoubleOrNull("p99Ms"),
                jitterMs = o.optDoubleOrNull("jitterMs"),
                lossPct = o.optDoubleOrNull("lossPct"),
                downMbpsAvg = o.optDoubleOrNull("downMbpsAvg"),
                upMbpsAvg = o.optDoubleOrNull("upMbpsAvg"),
                windowS = o.optIntOrNull("windowS"),
            )
        }
    }
}

data class Freshness(
    val streamStalled: Boolean,
    val dataAgeS: Long?,
    val samplesInPoll: Int,
) {
    companion object {
        fun parse(o: JSONObject?): Freshness? {
            o ?: return null
            return Freshness(
                streamStalled = o.optBoolean("streamStalled", false),
                dataAgeS = if (o.has("dataAgeS") && !o.isNull("dataAgeS")) o.optLong("dataAgeS") else null,
                samplesInPoll = o.optInt("samplesInPoll", 0),
            )
        }
    }
}

data class PollResult(
    val status: StatusData,
    val newSamples: List<NewSample>,
    val mode: String,
    val hist: HistStats?,
    val freshness: Freshness?,
) {
    companion object {
        fun parse(data: JSONObject): PollResult {
            val arr = data.optJSONArray("newSamples") ?: JSONArray()
            val samples = mutableListOf<NewSample>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                samples.add(
                    NewSample(
                        ts = o.optLong("ts", 0L),
                        download = o.optDouble("download", 0.0),
                        upload = o.optDouble("upload", 0.0),
                        latency = o.optDouble("latency", 0.0),
                        packetLoss = o.optDouble("packetLoss", 0.0),
                    ),
                )
            }
            return PollResult(
                status = StatusData.parse(data),
                newSamples = samples,
                mode = data.optString("mode", "real"),
                hist = HistStats.parse(data.optJSONObject("hist")),
                freshness = Freshness.parse(data.optJSONObject("freshness")),
            )
        }
    }
}

data class TestRecord(
    val ts: Long,
    val kind: String,
    val result: String,
    val detail: JSONObject,
)

data class DbSummary(
    val samples: Long,
    val firstTs: Long?,
    val lastTs: Long?,
    val tests: Long,
    val alerts: Long,
)

// ── V2.2: long-range trends (from StarlinkDiagnostic.db) ────────────────
data class TrendWindow(
    val samples: Int,
    val availabilityPct: Double?,
    val outages: Int?,
    val outageSamplesS: Int?,
    val p50Ms: Double?,
    val p95Ms: Double?,
    val downAvgMbps: Double?,
    val upAvgMbps: Double?,
    val latencyTrend: String?,   // improving | stable | degrading
    val downloadTrend: String?,
    val windowS: Int?,
) {
    val empty: Boolean get() = samples == 0

    companion object {
        fun parse(o: JSONObject): TrendWindow = TrendWindow(
            samples = o.optInt("samples", 0),
            availabilityPct = o.optDoubleOrNull("availabilityPct"),
            outages = o.optIntOrNull("outages"),
            outageSamplesS = o.optIntOrNull("outageSamplesS"),
            p50Ms = o.optDoubleOrNull("p50Ms"),
            p95Ms = o.optDoubleOrNull("p95Ms"),
            downAvgMbps = o.optDoubleOrNull("downAvgMbps"),
            upAvgMbps = o.optDoubleOrNull("upAvgMbps"),
            latencyTrend = o.optStringOrNull("latencyTrend"),
            downloadTrend = o.optStringOrNull("downloadTrend"),
            windowS = o.optIntOrNull("windowS"),
        )
    }
}

data class TrendsData(
    val w6h: TrendWindow,
    val w24h: TrendWindow,
    val w7d: TrendWindow,
    val dbReady: Boolean,
) {
    companion object {
        fun parse(data: JSONObject): TrendsData {
            val t = data.optJSONObject("trends") ?: JSONObject()
            return TrendsData(
                w6h = TrendWindow.parse(t.optJSONObject("6h") ?: JSONObject()),
                w24h = TrendWindow.parse(t.optJSONObject("24h") ?: JSONObject()),
                w7d = TrendWindow.parse(t.optJSONObject("7d") ?: JSONObject()),
                dbReady = data.optBoolean("dbReady", false),
            )
        }
    }
}

// ── org.json defensive helpers ──────────────────────────────────────────
fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key) else null

fun JSONObject.optBoolOrNull(key: String): Boolean? =
    if (has(key) && !isNull(key)) optBoolean(key) else null

// ── v42 additions: obstruction map / speedtest / router ─────────────────

data class ObstructionMapData(
    val numRows: Int,
    val numCols: Int,
    val snr: List<Double>,
    val minElevationDeg: Double?,
    val maxThetaDeg: Double?,
    val referenceFrame: String?,
    val source: String,
) {
    companion object {
        fun parse(data: JSONObject): ObstructionMapData? {
            val m = data.optJSONObject("map") ?: return null
            val arr = m.optJSONArray("snr") ?: return null
            val snr = mutableListOf<Double>()
            for (i in 0 until arr.length()) snr.add(arr.optDouble(i, -1.0))
            return ObstructionMapData(
                numRows = m.optInt("numRows", 0),
                numCols = m.optInt("numCols", 0),
                snr = snr,
                minElevationDeg = m.optDoubleOrNull("minElevationDeg"),
                maxThetaDeg = m.optDoubleOrNull("maxThetaDeg"),
                referenceFrame = m.optStringOrNull("referenceFrame"),
                source = data.optString("source", "real"),
            )
        }
    }
}

data class SpeedtestDirection(
    val throughputsMbps: List<Double>,
    val peakMbps: Double?,
    val err: Int,
)

data class SpeedtestState(
    val running: Boolean,
    val down: SpeedtestDirection?,
    val up: SpeedtestDirection?,
    val demo: Boolean,
) {
    companion object {
        fun parse(data: JSONObject): SpeedtestState {
            fun dir(name: String): SpeedtestDirection? {
                val d = data.optJSONObject(name) ?: return null
                val arr = d.optJSONArray("throughputsMbps")
                val list = mutableListOf<Double>()
                if (arr != null) for (i in 0 until arr.length()) list.add(arr.optDouble(i, 0.0))
                return SpeedtestDirection(
                    throughputsMbps = list,
                    peakMbps = d.optDoubleOrNull("peakMbps"),
                    err = d.optInt("err", 0),
                )
            }
            return SpeedtestState(
                running = data.optBoolean("running", false),
                down = dir("down"),
                up = dir("up"),
                demo = data.optBoolean("demo", false),
            )
        }
    }
}

data class RouterClient(
    val name: String?,
    val mac: String?,
    val ip: String?,
    val signalDbm: Double?,
)

data class RouterInfo(
    val reachable: Boolean,
    val tried: Boolean,
    val host: String,
    val port: Int,
    val id: String?,
    val hardwareVersion: String?,
    val softwareVersion: String?,
    val uptimeS: Long?,
    val wanIp: String?,
    val dishPingLatencyMs: Double?,
    val popPingLatencyMs: Double?,
    val clients: List<RouterClient>,
    val errorAr: String?,
) {
    companion object {
        fun parse(data: JSONObject): RouterInfo {
            val clients = mutableListOf<RouterClient>()
            val arr = data.optJSONArray("clients")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    clients.add(
                        RouterClient(
                            name = c.optStringOrNull("name"),
                            mac = c.optStringOrNull("mac"),
                            ip = c.optStringOrNull("ip"),
                            signalDbm = c.optDoubleOrNull("signalDbm"),
                        ),
                    )
                }
            }
            return RouterInfo(
                reachable = data.optBoolean("reachable", false),
                tried = data.optBoolean("tried", false),
                host = data.optString("host", "192.168.1.1"),
                port = data.optInt("port", 9000),
                id = data.optStringOrNull("id"),
                hardwareVersion = data.optStringOrNull("hardwareVersion"),
                softwareVersion = data.optStringOrNull("softwareVersion"),
                uptimeS = if (data.has("uptimeS") && !data.isNull("uptimeS")) data.optLong("uptimeS") else null,
                wanIp = data.optStringOrNull("wanIp"),
                dishPingLatencyMs = data.optDoubleOrNull("dishPingLatencyMs"),
                popPingLatencyMs = data.optDoubleOrNull("popPingLatencyMs"),
                clients = clients,
                errorAr = data.optStringOrNull("errorAr"),
            )
        }
    }
}

data class DishPingTarget(
    val target: String,
    val dropRate: Double,
    val latencyMs: Double,
)

// ── V2.3: hardware check report + unified error ledger ──────────────────

data class HardwareIdentity(
    val id: String?,
    val hardwareVersion: String?,
    val softwareVersion: String?,
    val buildId: String?,
    val boardRev: Int?,
    val manufacturedVersion: String?,
    val antiRollbackVersion: Int?,
    val generationNumber: Long?,
    val partitionsEqual: Boolean?,
    val countryCode: String?,
    val bootcount: Int?,
    val uptimeS: Long?,
)

data class HwReadyRow(val key: String, val en: String, val ar: String, val ready: Boolean?)

data class HwAlertRow(
    val key: String,
    val en: String,
    val ar: String,
    val severity: String,
    val active: Boolean,
    val announced: Boolean,
)

data class HardwareReport(
    val ts: Double?,
    val overall: String,          // ok | warn | fail
    val identity: HardwareIdentity,
    val readyStates: List<HwReadyRow>,
    val notReadyCount: Int,
    val alerts: List<HwAlertRow>,
    val activeAlerts: List<ActiveAlert>,
    val activeAlertCount: Int,
    val bootcount: Int?,
    val lastReasonCode: Int?,
    val lastReasonAr: String?,
    val lastReasonSeverity: String?,
    val actuatorState: String?,
    val actuatorFaulted: Boolean,
    val tiltAngleDeg: Double?,
    val attitudeAr: String?,
    val attitudeUncertaintyDeg: Double?,
    val dishW: Double?,
    val routerW: Double?,
    val thermalThrottle: Boolean,
    val thermalShutdown: Boolean,
    val heating: Boolean,
    val psuThrottle: Boolean,
    val gpsVerdict: String,
    val gpsSats: Int?,
    val gpsInhibited: Boolean?,
    val gpsIssues: List<GpsIssue>,
    val swuStateAr: String?,
    val swuProgress: Double?,
    val swuRequiresReboot: Boolean?,
    val mode: String,
) {
    companion object {
        fun parse(d: JSONObject): HardwareReport {
            val id = d.optJSONObject("identity") ?: JSONObject()
            val rb = d.optJSONObject("reboot") ?: JSONObject()
            val mo = d.optJSONObject("motion") ?: JSONObject()
            val th = d.optJSONObject("thermal") ?: JSONObject()
            val pw = d.optJSONObject("power") ?: JSONObject()
            val gp = d.optJSONObject("gps") ?: JSONObject()
            val sw = d.optJSONObject("softwareUpdate") ?: JSONObject()

            val ready = mutableListOf<HwReadyRow>()
            val rsArr = d.optJSONArray("readyStates") ?: JSONArray()
            for (i in 0 until rsArr.length()) {
                val x = rsArr.optJSONObject(i) ?: continue
                ready.add(
                    HwReadyRow(
                        key = x.optString("key"),
                        en = x.optString("en"),
                        ar = x.optString("ar"),
                        ready = x.optBoolOrNull("ready"),
                    ),
                )
            }
            val alerts = mutableListOf<HwAlertRow>()
            val alArr = d.optJSONArray("alerts") ?: JSONArray()
            for (i in 0 until alArr.length()) {
                val x = alArr.optJSONObject(i) ?: continue
                alerts.add(
                    HwAlertRow(
                        key = x.optString("key"),
                        en = x.optString("en"),
                        ar = x.optString("ar"),
                        severity = x.optString("severity", "warn"),
                        active = x.optBoolean("active", false),
                        announced = x.optBoolean("announced", false),
                    ),
                )
            }
            val activeAlerts = mutableListOf<ActiveAlert>()
            val aaArr = d.optJSONArray("activeAlerts") ?: JSONArray()
            for (i in 0 until aaArr.length()) {
                val x = aaArr.optJSONObject(i) ?: continue
                activeAlerts.add(
                    ActiveAlert(
                        key = x.optString("key"),
                        en = x.optString("en"),
                        ar = x.optString("ar"),
                        severity = x.optString("severity", "warn"),
                    ),
                )
            }
            val gpsIssues = mutableListOf<GpsIssue>()
            val giArr = gp.optJSONArray("issues") ?: JSONArray()
            for (i in 0 until giArr.length()) {
                val x = giArr.optJSONObject(i) ?: continue
                gpsIssues.add(GpsIssue.parse(x))
            }
            return HardwareReport(
                ts = d.optDoubleOrNull("ts"),
                overall = d.optString("overall", "ok"),
                identity = HardwareIdentity(
                    id = id.optStringOrNull("id"),
                    hardwareVersion = id.optStringOrNull("hardwareVersion"),
                    softwareVersion = id.optStringOrNull("softwareVersion"),
                    buildId = id.optStringOrNull("buildId"),
                    boardRev = id.optIntOrNull("boardRev"),
                    manufacturedVersion = id.optStringOrNull("manufacturedVersion"),
                    antiRollbackVersion = id.optIntOrNull("antiRollbackVersion"),
                    generationNumber = if (id.has("generationNumber") && !id.isNull("generationNumber")) id.optLong("generationNumber") else null,
                    partitionsEqual = id.optBoolOrNull("partitionsEqual"),
                    countryCode = id.optStringOrNull("countryCode"),
                    bootcount = id.optIntOrNull("bootcount"),
                    uptimeS = if (id.has("uptimeS") && !id.isNull("uptimeS")) id.optLong("uptimeS") else null,
                ),
                readyStates = ready,
                notReadyCount = d.optInt("notReadyCount", 0),
                alerts = alerts,
                activeAlerts = activeAlerts,
                activeAlertCount = d.optInt("activeAlertCount", 0),
                bootcount = rb.optIntOrNull("bootcount"),
                lastReasonCode = rb.optIntOrNull("lastReasonCode"),
                lastReasonAr = rb.optStringOrNull("lastReasonAr"),
                lastReasonSeverity = rb.optStringOrNull("lastReasonSeverity"),
                actuatorState = mo.optStringOrNull("actuatorState"),
                actuatorFaulted = mo.optBoolean("actuatorFaulted", false),
                tiltAngleDeg = mo.optDoubleOrNull("tiltAngleDeg"),
                attitudeAr = mo.optStringOrNull("attitudeAr"),
                attitudeUncertaintyDeg = mo.optDoubleOrNull("attitudeUncertaintyDeg"),
                dishW = pw.optDoubleOrNull("dishW"),
                routerW = pw.optDoubleOrNull("routerW"),
                thermalThrottle = th.optBoolean("throttle", false),
                thermalShutdown = th.optBoolean("shutdown", false),
                heating = th.optBoolean("heating", false),
                psuThrottle = th.optBoolean("psuThrottle", false),
                gpsVerdict = gp.optString("verdict", "unknown"),
                gpsSats = gp.optIntOrNull("sats"),
                gpsInhibited = gp.optBoolOrNull("inhibited"),
                gpsIssues = gpsIssues,
                swuStateAr = sw.optStringOrNull("stateAr"),
                swuProgress = sw.optDoubleOrNull("progress"),
                swuRequiresReboot = sw.optBoolOrNull("requiresReboot"),
                mode = d.optString("mode", "real"),
            )
        }
    }
}

/** One fault in the unified error ledger (V2.3 «استخراج الأخطاء»). */
data class ErrorEntry(
    val source: String,
    val kind: String,
    val code: Int?,
    val en: String,
    val ar: String,
    val severity: String,
    val detailAr: String?,
    val ts: Double?,
)

data class ErrorsReport(
    val ts: Double?,
    val total: Int,
    val hard: Int,
    val warn: Int,
    val info: Int,
    val entries: List<ErrorEntry>,
    val mode: String,
) {
    companion object {
        fun parse(d: JSONObject): ErrorsReport {
            val counts = d.optJSONObject("counts") ?: JSONObject()
            val entries = mutableListOf<ErrorEntry>()
            val arr = d.optJSONArray("entries") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val x = arr.optJSONObject(i) ?: continue
                entries.add(
                    ErrorEntry(
                        source = x.optString("source"),
                        kind = x.optString("kind"),
                        code = x.optIntOrNull("code"),
                        en = x.optString("en"),
                        ar = x.optString("ar"),
                        severity = x.optString("severity", "info"),
                        detailAr = x.optStringOrNull("detailAr"),
                        ts = x.optDoubleOrNull("ts"),
                    ),
                )
            }
            return ErrorsReport(
                ts = d.optDoubleOrNull("ts"),
                total = d.optInt("total", 0),
                hard = counts.optInt("hard", 0),
                warn = counts.optInt("warn", 0),
                info = counts.optInt("info", 0),
                entries = entries,
                mode = d.optString("mode", "real"),
            )
        }
    }
}
