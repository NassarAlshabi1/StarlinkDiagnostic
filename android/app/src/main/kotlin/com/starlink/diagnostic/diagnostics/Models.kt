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
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        phoneIp?.let { o.put("phoneIp", it) }
        gateway?.let { o.put("gateway", it) }
        tcp9200Ok?.let { o.put("tcp9200Ok", it) }
        icmpOk?.let { o.put("icmpOk", it) }
        grpcOk?.let { o.put("grpcOk", it) }
        popLatencyMs?.let { o.put("popLatencyMs", it) }
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
