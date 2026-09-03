package com.starlink.diagnostic.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.starlink.diagnostic.bridge.PythonBridge
import com.starlink.diagnostic.diagnostics.Assessment
import com.starlink.diagnostic.diagnostics.DbSummary
import com.starlink.diagnostic.diagnostics.ErrorsReport
import com.starlink.diagnostic.diagnostics.HardwareReport
import com.starlink.diagnostic.diagnostics.HistStats
import com.starlink.diagnostic.diagnostics.NetProbe
import com.starlink.diagnostic.diagnostics.NetVerdict
import com.starlink.diagnostic.diagnostics.NewSample
import com.starlink.diagnostic.diagnostics.NetworkProber
import com.starlink.diagnostic.diagnostics.ObstructionMapData
import com.starlink.diagnostic.diagnostics.DishPingTarget
import com.starlink.diagnostic.diagnostics.PollResult
import com.starlink.diagnostic.diagnostics.RouterInfo
import com.starlink.diagnostic.diagnostics.SeriesPoint
import com.starlink.diagnostic.diagnostics.SpeedtestState
import com.starlink.diagnostic.diagnostics.StatusData
import com.starlink.diagnostic.diagnostics.TestRecord
import com.starlink.diagnostic.diagnostics.TrendsData
import com.starlink.diagnostic.diagnostics.optStringOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Central app state. All dish I/O goes through PythonBridge (Python layer).
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        val POLL_INTERVALS = listOf(1, 5, 10, 30, 60)
        const val PREFS = "starlink_diag_prefs"
    }

    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── connection / status ──────────────────────────────────────────────
    data class ConnUi(
        val loading: Boolean = false,
        val status: StatusData? = null,
        val errorAr: String? = null,
        val mode: String = "real",
        val host: String = "192.168.100.1",
        val port: Int = 9200,
        // V2.2 connection health: consecutive failures drive the poll backoff
        val consecutiveFails: Int = 0,
        val lastOkAt: Long? = null,
    ) {
        val healthy: Boolean get() = status != null && errorAr == null
    }

    private val _conn = MutableStateFlow(ConnUi())
    val conn: StateFlow<ConnUi> = _conn

    // ── live monitor ─────────────────────────────────────────────────────
    data class LiveUi(
        val running: Boolean = false,
        val intervalSec: Int = 5,
        val points: List<NewSample> = emptyList(),
        val pollCount: Long = 0,
        val errorAr: String? = null,
    )

    private val _live = MutableStateFlow(LiveUi())
    val live: StateFlow<LiveUi> = _live
    private var pollJob: Job? = null
    private val maxLivePoints = 1800

    // ── diagnostics ──────────────────────────────────────────────────────
    data class DiagUi(
        val running: Boolean = false,
        val assessment: Assessment? = null,
        val errorAr: String? = null,
        val reportNoteAr: String? = null,
    )

    private val _diag = MutableStateFlow(DiagUi())
    val diag: StateFlow<DiagUi> = _diag

    // ── V2.3: hardware check (فحص الهاردوير) ─────────────────────────────
    data class HwUi(
        val loading: Boolean = false,
        val report: HardwareReport? = null,
        val errorAr: String? = null,
    )

    private val _hw = MutableStateFlow(HwUi())
    val hw: StateFlow<HwUi> = _hw

    // ── V2.3: unified error ledger (استخراج الأخطاء) ─────────────────────
    data class ErrorsUi(
        val loading: Boolean = false,
        val report: ErrorsReport? = null,
        val errorAr: String? = null,
    )

    private val _errors = MutableStateFlow(ErrorsUi())
    val errors: StateFlow<ErrorsUi> = _errors

    // ── network prober ───────────────────────────────────────────────────
    data class NetUi(
        val running: Boolean = false,
        val probe: NetProbe? = null,
        val verdict: NetVerdict? = null,
        val errorAr: String? = null,
    )

    private val _net = MutableStateFlow(NetUi())
    val net: StateFlow<NetUi> = _net

    // ── obstruction map (v42 RPC 2008) ──────────────────────────────
    data class MapUi(
        val loading: Boolean = false,
        val map: ObstructionMapData? = null,
        val errorAr: String? = null,
    )

    private val _map = MutableStateFlow(MapUi())
    val map: StateFlow<MapUi> = _map

    // ── dish-side speedtest (v42 RPC 1027/1028) ─────────────────────
    data class SpeedUi(
        val phase: String = "idle", // idle | running | done | error
        val result: SpeedtestState? = null,
        val errorAr: String? = null,
        val noteAr: String? = null,
    )

    private val _speed = MutableStateFlow(SpeedUi())
    val speed: StateFlow<SpeedUi> = _speed
    private var speedJob: Job? = null

    // ── router probe (v42 wifi_get_status=3004) ─────────────────────
    data class RouterUi(
        val loading: Boolean = false,
        val info: RouterInfo? = null,
    )

    private val _router = MutableStateFlow(RouterUi())
    val router: StateFlow<RouterUi> = _router

    // ── dish ping targets (v42 get_ping=1009) ───────────────────────
    data class DishPingUi(
        val loading: Boolean = false,
        val targets: List<DishPingTarget> = emptyList(),
        val errorAr: String? = null,
    )

    private val _dishPing = MutableStateFlow(DishPingUi())
    val dishPing: StateFlow<DishPingUi> = _dishPing

    // ── raw data ─────────────────────────────────────────────────────────
    data class RawUi(
        val section: String = "status",
        val json: String = "",
        val loading: Boolean = false,
        val errorAr: String? = null,
        val noteAr: String? = null,
    )

    private val _raw = MutableStateFlow(RawUi())
    val raw: StateFlow<RawUi> = _raw

    // ── history (DB) ─────────────────────────────────────────────────────
    data class HistoryUi(
        val series: List<SeriesPoint> = emptyList(),
        val tests: List<TestRecord> = emptyList(),
        val summary: DbSummary? = null,
        val loading: Boolean = false,
        val errorAr: String? = null,
    )

    private val _history = MutableStateFlow(HistoryUi())
    val history: StateFlow<HistoryUi> = _history

    // ── V2.2 long-range trends ──────────────────────────────────────────
    data class TrendsUi(
        val loading: Boolean = false,
        val trends: TrendsData? = null,
        val errorAr: String? = null,
    )

    private val _trends = MutableStateFlow(TrendsUi())
    val trends: StateFlow<TrendsUi> = _trends

    // ── V2.2 CSV export ────────────────────────────────────────────────
    data class CsvUi(
        val exporting: Boolean = false,
        val lastFileAr: String? = null,
        val errorAr: String? = null,
    )

    private val _csv = MutableStateFlow(CsvUi())
    val csv: StateFlow<CsvUi> = _csv

    // ── V2.2 live precision stats ─────────────────────────────────────
    private val _histStats = MutableStateFlow<HistStats?>(null)
    val histStats: StateFlow<HistStats?> = _histStats

    private val _freshness = MutableStateFlow<com.starlink.diagnostic.diagnostics.Freshness?>(null)
    val freshness: StateFlow<com.starlink.diagnostic.diagnostics.Freshness?> = _freshness

    val demoMode: Boolean get() = _conn.value.mode != "real"

    init {
        viewModelScope.launch {
            try {
                val dbPath = File(getApplication<Application>().filesDir, "StarlinkDiagnostic.db")
                PythonBridge.callSuspend("init", JSONObject().put("dbPath", dbPath.absolutePath))
                setTarget(prefs.getString("host", "192.168.100.1") ?: "192.168.100.1",
                    prefs.getInt("port", 9200), persist = false)
                refreshStatus()
            } catch (e: PythonBridge.BridgeException) {
                _conn.value = _conn.value.copy(errorAr = e.errorAr)
            } catch (e: Exception) {
                _conn.value = _conn.value.copy(errorAr = "تعذر تهيئة محرك بايثون: ${e.message}")
            }
        }
    }

    // ── target / mode ────────────────────────────────────────────────────
    fun setTarget(host: String, port: Int, persist: Boolean = true) {
        _conn.value = _conn.value.copy(host = host, port = port)
        if (persist) {
            prefs.edit().putString("host", host).putInt("port", port).apply()
        }
        viewModelScope.launch {
            try {
                PythonBridge.callSuspend("set_target", JSONObject().put("host", host).put("port", port))
            } catch (_: Exception) {
                // surface on next refresh
            }
        }
    }

    fun setDemo(enabled: Boolean) {
        viewModelScope.launch {
            try {
                PythonBridge.callSuspend("demo_set", JSONObject().put("enabled", enabled))
                stopPolling()
                _live.value = _live.value.copy(points = emptyList(), running = false)
                refreshStatus()
            } catch (e: Exception) {
                _conn.value = _conn.value.copy(errorAr = e.message)
            }
        }
    }

    fun loadSample() {
        viewModelScope.launch {
            try {
                PythonBridge.callSuspend("demo_load_sample", JSONObject().put("name", "gps14"))
                refreshStatus()
            } catch (e: Exception) {
                _conn.value = _conn.value.copy(errorAr = e.message)
            }
        }
    }

    // ── status ───────────────────────────────────────────────────────────
    fun refreshStatus() {
        viewModelScope.launch {
            _conn.value = _conn.value.copy(loading = true)
            try {
                val data = PythonBridge.callSuspend("get_status")
                _conn.value = _conn.value.copy(
                    loading = false,
                    status = StatusData.parse(data),
                    errorAr = null,
                    mode = data.optString("mode", "real"),
                    consecutiveFails = 0,
                    lastOkAt = System.currentTimeMillis(),
                )
            } catch (e: PythonBridge.BridgeException) {
                _conn.value = _conn.value.copy(
                    loading = false,
                    errorAr = e.errorAr,
                    consecutiveFails = _conn.value.consecutiveFails + 1,
                )
            } catch (e: Exception) {
                _conn.value = _conn.value.copy(
                    loading = false,
                    errorAr = "${e.message}",
                    consecutiveFails = _conn.value.consecutiveFails + 1,
                )
            }
        }
    }

    // ── live polling ─────────────────────────────────────────────────────
    fun setInterval(sec: Int) {
        _live.value = _live.value.copy(intervalSec = sec)
        if (_live.value.running) {
            startPolling(sec)
        }
    }

    fun startPolling(intervalSec: Int = _live.value.intervalSec) {
        pollJob?.cancel()
        _live.value = _live.value.copy(running = true, intervalSec = intervalSec, errorAr = null)
        pollJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val data = PythonBridge.callSuspend("poll")
                    val pr = PollResult.parse(data)
                    _conn.value = _conn.value.copy(
                        status = pr.status, errorAr = null, mode = pr.mode, loading = false,
                        consecutiveFails = 0, lastOkAt = System.currentTimeMillis(),
                    )
                    val merged = (_live.value.points + pr.newSamples).takeLast(maxLivePoints)
                    _live.value = _live.value.copy(
                        points = merged,
                        pollCount = _live.value.pollCount + 1,
                        errorAr = null,
                    )
                    _histStats.value = pr.hist
                    _freshness.value = pr.freshness
                } catch (e: PythonBridge.BridgeException) {
                    val fails = _conn.value.consecutiveFails + 1
                    _live.value = _live.value.copy(errorAr = e.errorAr)
                    _conn.value = _conn.value.copy(errorAr = e.errorAr, consecutiveFails = fails)
                } catch (e: Exception) {
                    val fails = _conn.value.consecutiveFails + 1
                    _live.value = _live.value.copy(errorAr = "${e.message}")
                    _conn.value = _conn.value.copy(errorAr = "${e.message}", consecutiveFails = fails)
                }
                // V2.2 backoff: on repeated failures stretch the interval
                // x2/x4/x8 (max 120 s) instead of hammering a dead link.
                val fails = _conn.value.consecutiveFails
                val backoff = if (fails > 0) 1L shl minOf(fails - 1, 3) else 1L
                val effSec = minOf(intervalSec * backoff, 120L)
                delay(effSec * 1000L)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        _live.value = _live.value.copy(running = false)
    }

    // ── full diagnostic ──────────────────────────────────────────────────
    fun runFullDiagnostic(includeNet: Boolean) {
        viewModelScope.launch {
            _diag.value = _diag.value.copy(running = true, errorAr = null, reportNoteAr = null)
            var probe: NetProbe? = null
            try {
                if (includeNet && !demoMode) {
                    val app = getApplication<Application>()
                    val c = _conn.value
                    val raw = NetworkProber.probe(app, c.host, c.port)
                    // gRPC probe = a real get_status through Python (10 s ceiling)
                    var grpcOk: Boolean? = null
                    try {
                        withTimeout(12_000) { refreshStatusAndWait() }
                        grpcOk = true
                    } catch (_: Exception) {
                        grpcOk = false
                    }
                    probe = NetProbe(
                        phoneIp = raw.phoneIp,
                        gateway = raw.gateway,
                        tcp9200Ok = raw.tcp9200Ok,
                        icmpOk = raw.icmpOk,
                        grpcOk = grpcOk,
                        popLatencyMs = _conn.value.status?.latencyMs,
                        errorAr = raw.tcpErrorAr,
                        targets = raw.targets,
                    )
                }
                val args = JSONObject().put("net", probe?.toJson() ?: JSONObject.NULL)
                val data = PythonBridge.callSuspend("full_diagnostic", args)
                val assessment = Assessment.parse(data.optJSONObject("assessment") ?: JSONObject())
                _diag.value = _diag.value.copy(running = false, assessment = assessment)
                // status may have been refreshed inside
                data.optJSONObject("status")?.let {
                    _conn.value = _conn.value.copy(status = StatusData.parse(data), errorAr = null)
                }
            } catch (e: PythonBridge.BridgeException) {
                _diag.value = _diag.value.copy(running = false, errorAr = e.errorAr)
            } catch (e: Exception) {
                _diag.value = _diag.value.copy(running = false, errorAr = "${e.message}")
            }
        }
    }

    private suspend fun refreshStatusAndWait() {
        val data = PythonBridge.callSuspend("get_status")
        _conn.value = _conn.value.copy(
            status = StatusData.parse(data),
            errorAr = null,
            mode = data.optString("mode", "real"),
        )
    }

    // ── network page ─────────────────────────────────────────────────────
    fun runNetworkCheck() {
        viewModelScope.launch {
            _net.value = _net.value.copy(running = true, errorAr = null)
            try {
                val app = getApplication<Application>()
                val c = _conn.value
                val raw = NetworkProber.probe(app, c.host, c.port)
                var grpcOk: Boolean? = null
                try {
                    withTimeout(12_000) { refreshStatusAndWait() }
                    grpcOk = true
                } catch (_: Exception) {
                    grpcOk = false
                }
                val probe = NetProbe(
                    phoneIp = raw.phoneIp,
                    gateway = raw.gateway,
                    tcp9200Ok = raw.tcp9200Ok,
                    icmpOk = raw.icmpOk,
                    grpcOk = grpcOk,
                    popLatencyMs = _conn.value.status?.latencyMs,
                    errorAr = raw.tcpErrorAr,
                    targets = raw.targets,
                )
                val verdict = try {
                    NetVerdict.parse(
                        PythonBridge.callSuspend("net_verdict", JSONObject().put("net", probe.toJson())),
                    )
                } catch (_: Exception) {
                    null
                }
                _net.value = _net.value.copy(running = false, probe = probe, verdict = verdict)
            } catch (e: Exception) {
                _net.value = _net.value.copy(running = false, errorAr = "${e.message}")
            }
        }
    }

    // ── raw data ─────────────────────────────────────────────────────────
    fun loadRaw(section: String) {
        viewModelScope.launch {
            _raw.value = _raw.value.copy(loading = true, errorAr = null, section = section)
            try {
                val data = PythonBridge.callSuspend("raw", JSONObject().put("section", section))
                val payload = data.optJSONObject("data") ?: JSONObject()
                val pretty = payload.toString(2)
                val tooBig = pretty.length > 400_000
                _raw.value = _raw.value.copy(
                    loading = false,
                    json = if (tooBig) {
                        payload.toString().take(120_000)
                    } else pretty,
                    noteAr = if (tooBig) {
                        "البيانات ضخمة — يُعرض مقتطف فقط، استخدم التصدير للملف الكامل"
                    } else null,
                )
            } catch (e: PythonBridge.BridgeException) {
                _raw.value = _raw.value.copy(loading = false, errorAr = e.errorAr)
            } catch (e: Exception) {
                _raw.value = _raw.value.copy(loading = false, errorAr = "${e.message}")
            }
        }
    }

    // ── history / DB ─────────────────────────────────────────────────────
    fun loadHistory(windowHours: Int = 24) {
        viewModelScope.launch {
            _history.value = _history.value.copy(loading = true, errorAr = null)
            try {
                val from = System.currentTimeMillis() / 1000 - windowHours * 3600L
                val seriesData = PythonBridge.callSuspend(
                    "history_query", JSONObject().put("fromTs", from),
                )
                val testsData = PythonBridge.callSuspend("tests")
                val sumData = PythonBridge.callSuspend("db_summary")
                val series = mutableListOf<SeriesPoint>()
                val arr = seriesData.optJSONArray("series") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { series.add(SeriesPoint.parse(it)) }
                }
                val tests = mutableListOf<TestRecord>()
                val tarr = testsData.optJSONArray("tests") ?: JSONArray()
                for (i in 0 until tarr.length()) {
                    val o = tarr.optJSONObject(i) ?: continue
                    tests.add(
                        TestRecord(
                            ts = o.optLong("ts", 0),
                            kind = o.optString("kind"),
                            result = o.optString("result"),
                            detail = o.optJSONObject("detail") ?: JSONObject(),
                        ),
                    )
                }
                val sum = sumData
                _history.value = _history.value.copy(
                    loading = false,
                    series = series,
                    tests = tests,
                    summary = DbSummary(
                        samples = sum.optLong("samples", 0),
                        firstTs = if (sum.has("firstTs") && !sum.isNull("firstTs")) sum.optLong("firstTs") else null,
                        lastTs = if (sum.has("lastTs") && !sum.isNull("lastTs")) sum.optLong("lastTs") else null,
                        tests = sum.optLong("tests", 0),
                        alerts = sum.optLong("alerts", 0),
                    ),
                )
            } catch (e: PythonBridge.BridgeException) {
                _history.value = _history.value.copy(loading = false, errorAr = e.errorAr)
            } catch (e: Exception) {
                _history.value = _history.value.copy(loading = false, errorAr = "${e.message}")
            }
        }
    }

    // ── V2.3: whole-dish hardware check ──────────────────────────────────
    fun loadHardwareCheck() {
        viewModelScope.launch {
            _hw.value = _hw.value.copy(loading = true, errorAr = null)
            try {
                val data = PythonBridge.callSuspend("hardware_check")
                _hw.value = _hw.value.copy(
                    loading = false,
                    report = HardwareReport.parse(data.optJSONObject("report") ?: JSONObject()),
                )
            } catch (e: PythonBridge.BridgeException) {
                _hw.value = _hw.value.copy(loading = false, errorAr = e.errorAr)
            } catch (e: Exception) {
                _hw.value = _hw.value.copy(loading = false, errorAr = "${e.message}")
            }
        }
    }

    // ── V2.3: unified error ledger ───────────────────────────────────────
    fun loadErrors() {
        viewModelScope.launch {
            _errors.value = _errors.value.copy(loading = true, errorAr = null)
            try {
                val data = PythonBridge.callSuspend("errors_log")
                _errors.value = _errors.value.copy(
                    loading = false,
                    report = ErrorsReport.parse(data.optJSONObject("ledger") ?: JSONObject()),
                )
            } catch (e: PythonBridge.BridgeException) {
                _errors.value = _errors.value.copy(loading = false, errorAr = e.errorAr)
            } catch (e: Exception) {
                _errors.value = _errors.value.copy(loading = false, errorAr = "${e.message}")
            }
        }
    }

    // ── V2.2: long-range trends ──────────────────────────────────────────
    fun loadTrends() {
        viewModelScope.launch {
            _trends.value = _trends.value.copy(loading = true, errorAr = null)
            try {
                val data = PythonBridge.callSuspend("trends")
                _trends.value = _trends.value.copy(
                    loading = false,
                    trends = TrendsData.parse(data),
                )
            } catch (e: PythonBridge.BridgeException) {
                _trends.value = _trends.value.copy(loading = false, errorAr = e.errorAr)
            } catch (e: Exception) {
                _trends.value = _trends.value.copy(loading = false, errorAr = "${e.message}")
            }
        }
    }

    /**
     * V2.2: export stored history as CSV (Python writes it into the
     * app-private exports dir), then hand it to the system share sheet.
     */
    fun exportCsv(hours: Int) {
        viewModelScope.launch {
            _csv.value = _csv.value.copy(exporting = true, errorAr = null, lastFileAr = null)
            try {
                val dir = File(getApplication<Application>().filesDir, "exports")
                dir.mkdirs()
                val out = File(dir, "starlink_history_%d.csv".format(System.currentTimeMillis()))
                val data = PythonBridge.callSuspend(
                    "export_csv",
                    JSONObject().put("hours", hours).put("path", out.absolutePath),
                )
                val rows = data.optInt("rows", 0)
                _csv.value = _csv.value.copy(
                    exporting = false,
                    lastFileAr = "تم تصدير $rows عينة إلى ${out.name}",
                )
                if (rows > 0) {
                    com.starlink.diagnostic.export.CsvExporter.share(getApplication(), out)
                }
            } catch (e: PythonBridge.BridgeException) {
                _csv.value = _csv.value.copy(exporting = false, errorAr = e.errorAr)
            } catch (e: Exception) {
                _csv.value = _csv.value.copy(exporting = false, errorAr = "${e.message}")
            }
        }
    }

    // ── dish control ─────────────────────────────────────────────────────
    fun control(action: String, onResult: (String) -> Unit) {
        controlWithArgs(action, JSONObject(), onResult)
    }

    fun controlWithArgs(action: String, extraArgs: JSONObject, onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val payload = JSONObject().put("action", action)
                extraArgs.keys().forEach { k -> payload.put(k, extraArgs.get(k)) }
                val data = PythonBridge.callSuspend("control", payload)
                onResult(data.optStringOrNull("noteAr") ?: "تم إرسال الأمر إلى الطبق بنجاح")
                if (action == "reboot") {
                    stopPolling()
                }
                delay(1500)
                refreshStatus()
            } catch (e: PythonBridge.BridgeException) {
                onResult("فشل الأمر: ${e.errorAr}")
            } catch (e: Exception) {
                onResult("فشل الأمر: ${e.message}")
            }
        }
    }

    // ── v42 additions: obstruction map / speedtest / router ───────────
    fun loadObstructionMap() {
        viewModelScope.launch {
            _map.value = _map.value.copy(loading = true, errorAr = null)
            try {
                val data = PythonBridge.callSuspend("obstruction_map")
                val parsed = ObstructionMapData.parse(data)
                _map.value = _map.value.copy(
                    loading = false,
                    map = parsed,
                    errorAr = data.optStringOrNull("errorAr"),
                )
            } catch (e: PythonBridge.BridgeException) {
                _map.value = _map.value.copy(loading = false, errorAr = e.errorAr)
            } catch (e: Exception) {
                _map.value = _map.value.copy(loading = false, errorAr = "${e.message}")
            }
        }
    }

    /** Ask the dish to run its own speed test, then poll until it finishes. */
    fun startSpeedtest() {
        speedJob?.cancel()
        _speed.value = SpeedUi(phase = "running")
        speedJob = viewModelScope.launch {
            try {
                val start = PythonBridge.callSuspend("speedtest_start")
                start.optStringOrNull("noteAr")?.let {
                    _speed.value = _speed.value.copy(noteAr = it)
                }
                var last: SpeedtestState? = null
                // The dish needs a few seconds to spin up; poll up to ~75 s.
                var polled = 0
                while (polled < 25 && isActive) {
                    delay(3000)
                    polled++
                    val data = PythonBridge.callSuspend("speedtest_status")
                    val st = SpeedtestState.parse(data)
                    last = st
                    if (!st.running && (st.down != null || st.up != null)) break
                }
                val finalState = last
                _speed.value = if (finalState != null && !finalState.running &&
                    (finalState.down != null || finalState.up != null)
                ) {
                    _speed.value.copy(phase = "done", result = finalState)
                } else {
                    _speed.value.copy(
                        phase = "error",
                        errorAr = "انتهت المهلة دون نتيجة — أعد المحاولة لاحقاً",
                    )
                }
            } catch (e: PythonBridge.BridgeException) {
                _speed.value = _speed.value.copy(phase = "error", errorAr = e.errorAr)
            } catch (e: Exception) {
                _speed.value = _speed.value.copy(phase = "error", errorAr = "${e.message}")
            }
        }
    }

    fun probeRouter() {
        viewModelScope.launch {
            _router.value = _router.value.copy(loading = true)
            try {
                val data = PythonBridge.callSuspend("router_probe")
                _router.value = _router.value.copy(loading = false, info = RouterInfo.parse(data))
            } catch (e: PythonBridge.BridgeException) {
                _router.value = _router.value.copy(
                    loading = false,
                    info = RouterInfo(
                        reachable = false, tried = true, host = "192.168.1.1", port = 9000,
                        id = null, hardwareVersion = null, softwareVersion = null,
                        uptimeS = null, wanIp = null, dishPingLatencyMs = null,
                        popPingLatencyMs = null, clients = emptyList(),
                        errorAr = e.errorAr,
                    ),
                )
            } catch (e: Exception) {
                _router.value = _router.value.copy(loading = false)
            }
        }
    }

    fun loadDishPing() {
        viewModelScope.launch {
            _dishPing.value = _dishPing.value.copy(loading = true, errorAr = null)
            try {
                val data = PythonBridge.callSuspend("dish_ping")
                val arr = data.optJSONArray("targets")
                val list = mutableListOf<DishPingTarget>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        list.add(
                            DishPingTarget(
                                target = o.optString("target", "target"),
                                dropRate = o.optDouble("dropRate", 0.0),
                                latencyMs = o.optDouble("latencyMs", 0.0),
                            ),
                        )
                    }
                }
                _dishPing.value = _dishPing.value.copy(
                    loading = false,
                    targets = list,
                    errorAr = data.optStringOrNull("errorAr"),
                )
            } catch (e: PythonBridge.BridgeException) {
                _dishPing.value = _dishPing.value.copy(loading = false, errorAr = e.errorAr)
            } catch (e: Exception) {
                _dishPing.value = _dishPing.value.copy(loading = false, errorAr = "${e.message}")
            }
        }
    }

    override fun onCleared() {
        stopPolling()
        super.onCleared()
    }
}
