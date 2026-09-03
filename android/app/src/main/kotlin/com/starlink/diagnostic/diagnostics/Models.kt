package com.starlink.diagnostic.diagnostics

import org.json.JSONArray
import org.json.JSONObject

/** Parsers for the JSON contract produced by python/bridge.py. */

data class DishInfo(
    val id: String?,
    val hardwareVersion: String?,
    val softwareVersion: String?,
)

data class GpsInfo(
    val verdict: String,
    val label: String,
    val valid: Boolean?,
    val sats: Int?,
    val inhibited: Boolean?,
    val hwCode: Int?,
    val inhibitEvidence: JSONObject?,
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
            return StatusData(
                state = s.optStringOrNull("state"),
                uptimeS = if (s.has("uptimeS") && !s.isNull("uptimeS")) s.optLong("uptimeS") else null,
                deviceInfo = DishInfo(
                    id = di.optStringOrNull("id"),
                    hardwareVersion = di.optStringOrNull("hardwareVersion"),
                    softwareVersion = di.optStringOrNull("softwareVersion"),
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

data class PollResult(
    val status: StatusData,
    val newSamples: List<NewSample>,
    val mode: String,
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

// ── org.json defensive helpers ──────────────────────────────────────────
fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

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
