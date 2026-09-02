package com.starlink.diagnostic.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.starlink.diagnostic.bridge.PythonBridge
import com.starlink.diagnostic.diagnostics.Assessment
import com.starlink.diagnostic.diagnostics.DbSummary
import com.starlink.diagnostic.diagnostics.NetProbe
import com.starlink.diagnostic.diagnostics.NetVerdict
import com.starlink.diagnostic.diagnostics.NewSample
import com.starlink.diagnostic.diagnostics.NetworkProber
import com.starlink.diagnostic.diagnostics.PollResult
import com.starlink.diagnostic.diagnostics.SeriesPoint
import com.starlink.diagnostic.diagnostics.StatusData
import com.starlink.diagnostic.diagnostics.TestRecord
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
    )

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

    // ── network prober ───────────────────────────────────────────────────
    data class NetUi(
        val running: Boolean = false,
        val probe: NetProbe? = null,
        val verdict: NetVerdict? = null,
        val errorAr: String? = null,
    )

    private val _net = MutableStateFlow(NetUi())
    val net: StateFlow<NetUi> = _net

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
                )
            } catch (e: PythonBridge.BridgeException) {
                _conn.value = _conn.value.copy(loading = false, errorAr = e.errorAr)
            } catch (e: Exception) {
                _conn.value = _conn.value.copy(loading = false, errorAr = "${e.message}")
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
                    )
                    val merged = (_live.value.points + pr.newSamples).takeLast(maxLivePoints)
                    _live.value = _live.value.copy(
                        points = merged,
                        pollCount = _live.value.pollCount + 1,
                    )
                } catch (e: PythonBridge.BridgeException) {
                    _live.value = _live.value.copy(errorAr = e.errorAr)
                    _conn.value = _conn.value.copy(errorAr = e.errorAr)
                } catch (e: Exception) {
                    _live.value = _live.value.copy(errorAr = "${e.message}")
                }
                delay(_live.value.intervalSec * 1000L)
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

    // ── dish control ─────────────────────────────────────────────────────
    fun control(action: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val data = PythonBridge.callSuspend("control", JSONObject().put("action", action))
                val note = data.optStringOrNull("noteAr")
                onResult(note ?: "تم إرسال الأمر إلى الطبق بنجاح")
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

    override fun onCleared() {
        stopPolling()
        super.onCleared()
    }
}
