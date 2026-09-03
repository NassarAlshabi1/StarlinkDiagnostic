"""bridge.py — the single Python entry point called from Kotlin via Chaquopy.

Kotlin calls:  rpc('<json payload>')  ->  '<json result>'
payload: {"op": "<operation>", "args": {...}}
result : {"ok": true, "data": {...}} | {"ok": false, "errorAr": "...", "error": "..."}

All dish interaction (starlink_grpc), diagnostics (diagnostics engine),
persistence (StarlinkDiagnostic.db) and demo mode live on this side, keeping
the Kotlin layer a thin UI shell — the same philosophy as the upstream
starlink-grpc-tools project (Python does the work).
"""

import json
import math
import threading
import time

import grpc
from google.protobuf.json_format import MessageToDict
from google.protobuf.unknown_fields import UnknownFieldSet

import demo_sim
import diagnostics as dx
import history as db
from starlink_grpc import (
    DEFAULT_ROUTER_TARGET,
    ChannelContext,
    GrpcError,
    get_history,
    get_obstruction_map,
    get_ping,
    get_speedtest_status,
    get_status,
    history_bulk_data,
    history_power_stats,
    history_stats,
    outages_from_history,
    reboot,
    router_clients,
    router_status,
    set_gps_inhibit,
    set_power_save,
    set_stow_state,
    start_speedtest,
    status_data,
)

# ── state ────────────────────────────────────────────────────────────────
_lock = threading.RLock()
_ctx = None            # ChannelContext (real mode)
_host = "192.168.100.1"
_port = 9200
_mode = "real"         # 'real' | 'demo' | 'sample'
_last_counter = -1     # history sample counter bookkeeping (real mode)
_last_sample_t = 0     # demo mode bookkeeping
_db_ready = False

# V2.2 precision tracking: data-flow freshness (stall detection) and the
# window size (in samples, 1 Hz) behind the p50/p95/p99 latency stats.
STATS_WINDOW = 300
STALL_THRESHOLD_S = 15
_fresh = {"lastDataTs": 0, "stallSince": None}

_ERR_AR = {
    grpc.StatusCode.UNAVAILABLE: "لا يمكن الوصول إلى الطبق — تأكد من اتصال الهاتف بشبكة ستارلينك ومن صحة العنوان",
    grpc.StatusCode.DEADLINE_EXCEEDED: "انتهت مهلة الاستجابة من الطبق — تحقق من قوة اتصال الشبكة",
    grpc.StatusCode.NOT_FOUND: "لم يتم العثور على خدمة gRPC في العنوان المحدد",
    grpc.StatusCode.PERMISSION_DENIED: "تم رفض الوصول إلى الطبق",
    grpc.StatusCode.INTERNAL: "خطأ داخلي في الطبق أثناء المعالجة",
}


def _clean(v):
    """Make a value JSON-safe (NaN/Inf -> None)."""
    if isinstance(v, float):
        return None if (math.isnan(v) or math.isinf(v)) else v
    if isinstance(v, dict):
        return {k: _clean(x) for k, x in v.items()}
    if isinstance(v, (list, tuple)):
        return [_clean(x) for x in v]
    return v


def _err_payload(exc):
    code = None
    if isinstance(exc, grpc.RpcError):
        try:
            code = exc.code()
        except Exception:
            code = None
    error_ar = _ERR_AR.get(
        code, "تعذر إتمام العملية — تحقق من الإعدادات والمحاولة مجدداً"
    )
    if isinstance(exc, (GrpcError,)):
        error_ar = "فشل اتصال gRPC بالطبق: %s" % exc
    return {
        "ok": False,
        "errorAr": error_ar,
        "error": "%s: %s" % (type(exc).__name__, exc),
    }


# ── status normalization (one path for real / demo / sample) ────────────
def _pct(sorted_vals, p):
    """Percentile p (0..100) over an already-sorted list, linear interpolation."""
    if not sorted_vals:
        return None
    if len(sorted_vals) == 1:
        return sorted_vals[0]
    k = (len(sorted_vals) - 1) * (p / 100.0)
    f = int(math.floor(k))
    c = min(f + 1, len(sorted_vals) - 1)
    if f == c:
        return sorted_vals[f]
    return sorted_vals[f] + (sorted_vals[c] - sorted_vals[f]) * (k - f)


def _stats_from_arrays(bulk):
    """Precision network stats over an aligned sample window.

    Latency percentiles use CONNECTED samples only (drop < 0.9): during a
    full outage the dish reports stale/zero pop-ping latency, which would
    poison p95/p99 and make a healthy dish look degraded.
    """
    lat_all = [v for v in (bulk.get("pop_ping_latency_ms") or []) if v is not None]
    loss = [v if v is not None else 0.0 for v in (bulk.get("pop_ping_drop_rate") or [])]
    down = [v if v is not None else 0.0 for v in (bulk.get("downlink_throughput_bps") or [])]
    up = [v if v is not None else 0.0 for v in (bulk.get("uplink_throughput_bps") or [])]
    # history_bulk_data already nulls latency while drop >= 1 (upstream
    # behaviour) — lat_all is therefore the connected-only series.
    lat_conn = sorted(lat_all)
    jitter = None
    if len(lat_conn) >= 2:
        diffs = [abs(lat_conn[i] - lat_conn[i - 1]) for i in range(1, len(lat_conn))]
        jitter = round(sum(diffs) / len(diffs), 1)
    return {
        "n": len(loss),          # full window size (samples)
        "nLat": len(lat_all),    # latency samples available (connected)
        "p50Ms": round(_pct(lat_conn, 50), 1) if lat_conn else None,
        "p95Ms": round(_pct(lat_conn, 95), 1) if lat_conn else None,
        "p99Ms": round(_pct(lat_conn, 99), 1) if lat_conn else None,
        "jitterMs": jitter,
        "lossPct": round(100.0 * sum(loss) / len(loss), 2) if loss else None,
        "downMbpsAvg": round(sum(down) / len(down) / 1e6, 2) if down else None,
        "upMbpsAvg": round(sum(up) / len(up) / 1e6, 2) if up else None,
    }


def _freshness(now, samples_in_poll):
    """Data-flow quality snapshot for the UI (stale/frozen early warning)."""
    stalled = False
    if _fresh["stallSince"] is not None:
        stalled = (now - _fresh["stallSince"]) >= STALL_THRESHOLD_S
    return {
        "streamStalled": stalled,
        "dataAgeS": max(0, now - _fresh["lastDataTs"]) if _fresh["lastDataTs"] else None,
        "samplesInPoll": samples_in_poll,
    }


def _gps_label(gps):
    v = gps.get("verdict")
    return {
        dx.GPS_OK: "ok",
        dx.GPS_NO_FIX: "no_fix",
        dx.GPS_INHIBITED: "inhibited",
        dx.GPS_HW_FAIL: "hardware_failure",
    }.get(v, "unknown")


def _normalize_status(status, obstruction, alerts):
    gps = dx.assess_gps(status)
    down = status.get("downlink_throughput_bps")
    up = status.get("uplink_throughput_bps")
    outage = status.get("outage")
    outage_norm = None
    if outage:
        _, cause_ar = dx.outage_cause_label(outage.get("cause", 0))
        outage_norm = {
            "cause": outage.get("cause"),
            "causeAr": cause_ar,
            "ongoing": outage.get("ongoing", False),
            "startTs": (outage.get("startTsNs") or 0) / 1e9,
            "durationS": (outage.get("durationNs") or 0) / 1e9,
        }
    disablement = status.get("disablement_code")
    dis_known = disablement not in (None, 0)
    _, dis_ar, dis_sev = dx.disablement_label(disablement or 0)
    swu = status.get("software_update_state")
    swu_entry = dx.SWU_STATE_AR.get(swu) if swu is not None else None
    dl_r = status.get("dl_restricted_reason")
    ul_r = status.get("ul_restricted_reason")
    return {
        "state": status.get("state"),
        "uptimeS": status.get("uptime"),
        "deviceInfo": {
            "id": status.get("id"),
            "hardwareVersion": status.get("hardware_version"),
            "softwareVersion": status.get("software_version"),
            "countryCode": status.get("country_code"),
            "bootcount": status.get("bootcount"),
            "buildId": status.get("build_id"),
        },
        "downMbps": round(down / 1e6, 2) if down is not None else None,
        "upMbps": round(up / 1e6, 2) if up is not None else None,
        "latencyMs": round(status.get("pop_ping_latency_ms"), 1)
        if status.get("pop_ping_latency_ms") is not None else None,
        "dropRate": status.get("pop_ping_drop_rate"),
        "isSnrAboveNoiseFloor": status.get("is_snr_above_noise_floor"),
        "isSnrPersistentlyLow": status.get("is_snr_persistently_low"),
        "ethSpeedMbps": status.get("eth_speed_mbps"),
        "stowRequested": status.get("stow_requested"),
        "alertsBitfield": status.get("alerts"),
        "alertHwCodes": status.get("alert_hw_codes") or [],
        "alerts": {
            "motorsStuck": alerts.get("alert_motors_stuck", False),
            "thermalShutdown": alerts.get("alert_thermal_shutdown", False),
            "thermalThrottle": alerts.get("alert_thermal_throttle", False),
            "unexpectedLocation": alerts.get("alert_unexpected_location", False),
            "mastNotNearVertical": alerts.get("alert_mast_not_near_vertical", False),
            "slowEthernetSpeeds": alerts.get("alert_slow_ethernet_speeds", False),
            "obstructed": alerts.get("alert_obstructed", False),
            "isHeating": alerts.get("alert_is_heating", False),
            "powerSupplyThermalThrottle": alerts.get("alert_power_supply_thermal_throttle", False),
            "noEthernetLink": alerts.get("alert_no_ethernet_link", False),
            "dishWaterDetected": alerts.get("alert_dish_water_detected", False),
            "lowerSignalThanPredicted": alerts.get("alert_lower_signal_than_predicted", False),
        },
        "obstruction": {
            "currentlyObstructed": status.get("currently_obstructed"),
            "fractionObstructed": round(status.get("fraction_obstructed"), 4)
            if status.get("fraction_obstructed") is not None else None,
            "last24hObstructedS": obstruction.get("last_24h_obstructed_s"),
            "validS": round(obstruction.get("valid_s") or 0),
            "timeObstructed": obstruction.get("time_obstructed"),
            "patchesValid": obstruction.get("patches_valid"),
            "avgProlongedObstructionDurationS": obstruction.get(
                "avg_prolonged_obstruction_duration_s"
            ),
            "avgProlongedObstructionIntervalS": obstruction.get(
                "avg_prolonged_obstruction_interval_s"
            ),
            "directionAzimuth": obstruction.get("direction_azimuth"),
            "directionElevation": obstruction.get("direction_elevation"),
        },
        "gps": {
            "verdict": gps["verdict"],
            "label": _gps_label(gps),
            "valid": gps["valid"],
            "sats": gps["sats"],
            "inhibited": gps["inhibited"],
            "inhibitEvidence": gps["inhibitEvidence"],
            "hwCode": gps["hwCode"],
        },
        "boresightAzimuthDeg": status.get("direction_azimuth"),
        "boresightElevationDeg": status.get("direction_elevation"),
        # ── v42 evidence surface (V2.1) ─────────────────────────────────
        "outage": outage_norm,
        "disablementCode": disablement if dis_known else None,
        "disablementAr": dis_ar if dis_known else None,
        "disablementSeverity": dis_sev if dis_known else None,
        "softwareUpdateState": swu,
        "softwareUpdateStateAr": swu_entry["ar"] if swu_entry else None,
        "swupdateRebootReady": status.get("swupdate_reboot_ready"),
        "softwareUpdateStats": status.get("software_update_stats"),
        "dlRestrictedReason": dl_r,
        "dlRestrictedAr": (dx.RLR_AR.get(dl_r, {}).get("ar") if dl_r is not None else None),
        "ulRestrictedReason": ul_r,
        "ulRestrictedAr": (dx.RLR_AR.get(ul_r, {}).get("ar") if ul_r is not None else None),
        "mobilityClass": status.get("mobility_class"),
        "readyStates": status.get("ready_states"),
        "alignment": status.get("alignment"),
        "power": {
            "dishW": status.get("dish_power_w"),
            "routerW": status.get("router_power_w"),
            "upsuUptimeS": status.get("upsu_uptime_s"),
        },
        "battery": status.get("battery"),
        "rebootReason": status.get("reboot_reason"),
    }


def _get_ctx():
    global _ctx
    if _ctx is None:
        _ctx = ChannelContext(target="%s:%s" % (_host, _port))
    return _ctx


def _reset_ctx():
    global _ctx
    if _ctx is not None:
        _ctx.close()
        _ctx = None


def _current_status_real():
    ctx = _get_ctx()
    status, obstruction, alerts = status_data(context=ctx)
    return status, obstruction, alerts, None


def _current_status():
    """Returns (status, obstruction, alerts, history_msg_or_None)."""
    if _mode == "real":
        return _current_status_real()
    if _mode == "demo":
        s, o, a = demo_sim.demo_get_status_data()
        return s, o, a, None
    s, o, a = demo_sim.sample_get_status_data()
    return s, o, a, None


# ── unknown-fields walk (Raw page transparency) ──────────────────────────
def _unknown_fields(msg, depth=0):
    out = []
    if msg is None or depth > 2:
        return out
    try:
        for f in UnknownFieldSet(msg):
            entry = {"field": f.field_number, "wireType": f.wire_type}
            try:
                if f.wire_type == 0:
                    entry["varint"] = int(f.data)
                elif f.wire_type == 2:
                    entry["bytesLen"] = len(f.data)
            except Exception:
                pass
            out.append(entry)
    except Exception:
        pass
    for field in msg.DESCRIPTOR.fields:
        if field.message_type is None:
            continue
        try:
            child = getattr(msg, field.name)
        except Exception:
            continue
        if child is not None and field.label != field.LABEL_REPEATED:
            out.extend(
                {"path": field.name, **e} for e in _unknown_fields(child, depth + 1)
            )
    return out


# ── op handlers ──────────────────────────────────────────────────────────
def _op_init(args):
    global _db_ready
    db.open_db(args.get("dbPath"))
    _db_ready = True
    return {"dbPath": args.get("dbPath")}


def _op_set_target(args):
    global _host, _port, _last_counter
    with _lock:
        _host = args.get("host") or "192.168.100.1"
        _port = int(args.get("port") or 9200)
        _last_counter = -1
        _reset_ctx()
        return {"host": _host, "port": _port}


def _op_get_status(_args):
    status, obstruction, alerts, hist = _current_status()
    norm = _normalize_status(status, obstruction, alerts)
    raw = {
        "snake": _clean(status),
        "obstruction": _clean(obstruction),
        "alerts": _clean(alerts),
    }
    if hist is not None:
        raw["proto"] = _clean(MessageToDict(hist, preserving_proto_field_name=True))
        raw["unknownFields"] = _unknown_fields(hist)
    return {"status": _clean(norm), "raw": raw, "mode": _mode}


def _poll_real(args):
    global _last_counter
    ctx = _get_ctx()
    now = int(time.time())
    status, obstruction, alerts, _ = _current_status_real()
    hist = get_history(context=ctx)
    start = _last_counter if _last_counter >= 0 else None
    gen, bulk = history_bulk_data(parse_samples=-1, start=start, context=ctx, history=hist)
    n = len(bulk["pop_ping_drop_rate"])
    end_counter = gen.get("end_counter") or 0
    new_samples = []
    if n > 0:
        end_time = now
        for i in range(n):
            new_samples.append({
                "ts": end_time - (n - 1 - i),
                "download": round((bulk["downlink_throughput_bps"][i] or 0) / 1e6, 2),
                "upload": round((bulk["uplink_throughput_bps"][i] or 0) / 1e6, 2),
                "latency": round(bulk["pop_ping_latency_ms"][i] or 0, 1)
                if bulk["pop_ping_latency_ms"][i] is not None else 0.0,
                "packetLoss": bulk["pop_ping_drop_rate"][i] or 0.0,
                "obstruction": status.get("fraction_obstructed"),
                "state": status.get("state"),
                "gps": _gps_label(dx.assess_gps(status)),
            })
    # ── freshness: is the dish actually STREAMING history samples? ────
    # end_counter advances ~1 sample/sec over gRPC. If our link is up but
    # the counter freezes, the stream stalled (half-dead TCP, dish hang)
    # — an early warning BEFORE the connection fully dies.
    counter_advanced = bool(end_counter and _last_counter >= 0 and end_counter != _last_counter)
    if _last_counter >= 0 and not counter_advanced:
        if _fresh["stallSince"] is None:
            _fresh["stallSince"] = now
    else:
        _fresh["stallSince"] = None
    if n > 0:
        _fresh["lastDataTs"] = now
    if end_counter and end_counter != _last_counter:
        _last_counter = end_counter
    # ── precision stats over the last STATS_WINDOW samples (no extra RPC) ─
    hist_stats = None
    try:
        _, win = history_bulk_data(
            parse_samples=STATS_WINDOW, context=ctx, history=hist,
        )
        hist_stats = _stats_from_arrays(win)
        hist_stats["windowS"] = STATS_WINDOW
    except Exception:  # noqa: BLE001 - stats must never break polling
        hist_stats = None
    stored = db.store_samples(new_samples) if _db_ready else 0
    if _db_ready:
        db.set_meta_bulk({
            "firmware": status.get("software_version") or "",
            "hardware": status.get("hardware_version") or "",
            "dish_id": status.get("id") or "",
            "last_uptime_s": status.get("uptime") or 0,
        })
    norm = _normalize_status(status, obstruction, alerts)
    return {"status": _clean(norm), "newSamples": new_samples, "stored": stored,
            "endCounter": end_counter, "mode": _mode,
            "hist": hist_stats, "freshness": _freshness(now, n)}


def _poll_demo(_args):
    global _last_sample_t
    now = int(time.time())
    status, obstruction, alerts, _ = _current_status()
    norm = _normalize_status(status, obstruction, alerts)
    new_samples = []
    if _mode == "demo":
        s = demo_sim.demo_latest_sample()
        if s is not None and s["t"] > _last_sample_t:
            _last_sample_t = s["t"]
            new_samples.append({
                "ts": s["t"],
                "download": s["downMbps"],
                "upload": s["upMbps"],
                "latency": s["latencyMs"],
                "packetLoss": s["dropRate"],
                "obstruction": status.get("fraction_obstructed"),
                "state": status.get("state"),
                "gps": _gps_label(dx.assess_gps(status)),
            })
            _fresh["lastDataTs"] = now
    stored = db.store_samples(new_samples) if _db_ready else 0
    if _db_ready and new_samples:
        db.set_meta_bulk({
            "firmware": status.get("software_version") or "",
            "hardware": status.get("hardware_version") or "",
            "dish_id": status.get("id") or "",
            "last_uptime_s": status.get("uptime") or 0,
        })
    win_samples = demo_sim.demo_history_window(STATS_WINDOW) if _mode == "demo" else []
    hist_stats = None
    if win_samples:
        hist_stats = _stats_from_arrays({
            "pop_ping_latency_ms": [s["latencyMs"] for s in win_samples],
            "pop_ping_drop_rate": [s["dropRate"] for s in win_samples],
            "downlink_throughput_bps": [s["downMbps"] * 1e6 for s in win_samples],
            "uplink_throughput_bps": [s["upMbps"] * 1e6 for s in win_samples],
        })
        hist_stats["windowS"] = STATS_WINDOW
    return {"status": _clean(norm), "newSamples": new_samples, "stored": stored,
            "endCounter": 0, "mode": _mode,
            "hist": hist_stats, "freshness": _freshness(now, len(new_samples))}


def _op_poll(args):
    if _mode == "real":
        return _poll_real(args)
    return _poll_demo(args)


def _op_live_series(args):
    window = int(args.get("windowSeconds") or 900)
    now = int(time.time())
    rows = db.series(from_ts=now - window) if _db_ready else []
    return {"series": rows, "windowSeconds": window}


def _op_full_diagnostic(args):
    status, obstruction, alerts, hist = _current_status()
    stats = None
    outages = None
    power = None
    if _mode == "real":
        ctx = _get_ctx()
        stats = history_stats(parse_samples=-1, context=ctx, history=hist)
        try:
            hist_msg = hist if hist is not None else get_history(context=ctx)
            outages = outages_from_history(hist_msg)
        except Exception:  # noqa: BLE001
            outages = None
        try:
            power = history_power_stats(
                parse_samples=-1, context=ctx,
                history=hist if hist is not None else get_history(context=ctx),
            )
        except Exception:  # noqa: BLE001
            power = None
    else:
        stats = _demo_stats()
        outages = demo_sim.demo_outage_records()
        power = demo_sim.demo_power_stats()
    net = args.get("net")
    assessment = dx.run(status, obstruction, alerts, stats, net=net,
                        outages=outages, power=power)
    assessment["ts"] = time.time()
    # V2.2: precision network stats inside the assessment (feeds the PDF report)
    try:
        if _mode == "real":
            ctx = _get_ctx()
            h = hist if hist is not None else get_history(context=ctx)
            _, win = history_bulk_data(parse_samples=STATS_WINDOW, context=ctx, history=h)
        else:
            ws = demo_sim.demo_history_window(STATS_WINDOW)
            win = {
                "pop_ping_latency_ms": [s["latencyMs"] for s in ws],
                "pop_ping_drop_rate": [s["dropRate"] for s in ws],
                "downlink_throughput_bps": [s["downMbps"] * 1e6 for s in ws],
                "uplink_throughput_bps": [s["upMbps"] * 1e6 for s in ws],
            }
        assessment["netQuality"] = _stats_from_arrays(win)
        assessment["netQuality"]["windowS"] = STATS_WINDOW
    except Exception:  # noqa: BLE001 - stats must never break the assessment
        assessment["netQuality"] = None
    if _db_ready:
        db.store_test(
            assessment["ts"], "full_diagnostic",
            "FAILED" if assessment["final"]["failed"] else "PASSED",
            assessment,
        )
        db.store_alerts(
            assessment["ts"],
            [
                {"kind": "code_%d" % c, "detail": dx.code_label(c)}
                for c in assessment["selfTest"]["codes"]
            ],
        )
        if assessment.get("outages", {}).get("ongoing"):
            db.store_alerts(
                assessment["ts"],
                [{"kind": "outage_ongoing",
                  "detail": assessment["outages"]["ongoing"]["causeAr"]}],
            )
        db.set_meta_bulk({
            "firmware": status.get("software_version") or "",
            "hardware": status.get("hardware_version") or "",
            "dish_id": status.get("id") or "",
            "last_uptime_s": status.get("uptime") or 0,
        })
    if net:
        assessment["network"] = dx.network_verdict(net)
    return {"assessment": _clean(assessment), "mode": _mode,
            "status": _clean(_normalize_status(status, obstruction, alerts))}


def _demo_stats():
    """Stats tuple for demo/sample modes, from the demo buffer or zeros."""
    if _mode == "demo":
        samples = demo_sim.demo_history_window(3600)
        n = len(samples)
        tot = sum(s["dropRate"] for s in samples)
        full = sum(1 for s in samples if s["dropRate"] >= 1)
        lat = [s["latencyMs"] for s in samples if s["dropRate"] < 1]
        mean_all = sum(lat) / len(lat) if lat else None
        down_usage = sum(s["downMbps"] for s in samples) * 1e6 / 8
        up_usage = sum(s["upMbps"] for s in samples) * 1e6 / 8
    else:
        n = 0
        tot = 0.0
        full = 0
        mean_all = 48.2
        down_usage = 0
        up_usage = 0
    return (
        {"samples": n, "end_counter": 0},
        {"total_ping_drop": tot, "count_full_ping_drop": full,
         "count_obstructed": 0, "total_obstructed_ping_drop": 0.0,
         "count_full_obstructed_ping_drop": 0, "count_unscheduled": 0,
         "total_unscheduled_ping_drop": 0.0, "count_full_unscheduled_ping_drop": 0},
        {"init_run_fragment": 0, "final_run_fragment": 0,
         "run_seconds[1,]": [0] * 60, "run_minutes[1,]": [0] * 60},
        {"mean_all_ping_latency": mean_all, "deciles_all_ping_latency[]": [None] * 11,
         "mean_full_ping_latency": mean_all, "deciles_full_ping_latency[]": [None] * 11,
         "stdev_full_ping_latency": None},
        {"load_bucket_samples[]": [0] * 15, "load_bucket_min_latency[]": [None] * 15,
         "load_bucket_median_latency[]": [None] * 15, "load_bucket_max_latency[]": [None] * 15},
        {"download_usage": int(down_usage), "upload_usage": int(up_usage)},
    )


def _op_raw(args):
    section = args.get("section") or "status"
    if section == "status":
        status, obstruction, alerts, _hist = _current_status()
        out = {
            "snake": _clean(status),
            "obstruction": _clean(obstruction),
            "alerts": _clean(alerts),
            "codeTable": {str(k): v for k, v in dx.CODE_TABLE.items()},
        }
        if _mode == "real":
            # One extra RPC: attach the untouched protobuf message as JSON
            # (full field fidelity) plus any unknown (newer-firmware) fields.
            try:
                msg = get_status(context=_get_ctx())
                out["proto"] = _clean(MessageToDict(
                    msg, preserving_proto_field_name=True))
                out["unknownFields"] = _unknown_fields(msg)
            except (grpc.RpcError, GrpcError):
                pass
        return {"section": section, "data": out}
    if section == "history":
        if _mode == "real":
            ctx = _get_ctx()
            hist = get_history(context=ctx)
            proto = _clean(MessageToDict(hist, preserving_proto_field_name=True))
            gen, bulk = history_bulk_data(parse_samples=-1, context=ctx, history=hist)
            stats = history_stats(parse_samples=-1, context=ctx, history=hist)
            return {"section": section, "data": {
                "proto": proto,
                "general": _clean(gen),
                "stats": _clean([_clean(s) for s in stats]),
                "unknownFields": _unknown_fields(hist),
            }}
        samples = demo_sim.demo_history_window(900) if _mode == "demo" else []
        return {"section": section, "data": {
            "proto": None,
            "demoSamples": samples[-120:],
            "noteAr": "الوضع التجريبي — لا يوجد بروتو حقيقي",
        }}
    if section == "alerts":
        status, obstruction, alerts, _ = _current_status()
        return {"section": section, "data": {
            "alerts": _clean(alerts),
            "alertHwCodes": status.get("alert_hw_codes") or [],
            "alertsBitfield": status.get("alerts"),
            "codeTable": {str(k): v for k, v in dx.CODE_TABLE.items()},
        }}
    if section == "obstruction":
        status, obstruction, _a, hist = _current_status()
        out = {"obstruction": _clean(obstruction),
               "currentlyObstructed": status.get("currently_obstructed"),
               "fractionObstructed": status.get("fraction_obstructed")}
        if hist is not None:
            try:
                out["proto"] = _clean(MessageToDict(
                    hist.obstruction_stats, preserving_proto_field_name=True))
            except Exception:
                pass
        return {"section": section, "data": out}
    if section == "diagnostics":
        tests = db.recent_tests(10) if _db_ready else []
        return {"section": section, "data": {"tests": tests}}
    raise ValueError("unknown raw section: %s" % section)


def _op_control(args):
    action = args.get("action")
    if _mode != "real":
        return {"action": action, "accepted": True,
                "noteAr": "وضع العرض التجريبي — الأمر غير مُرسل فعلياً"}
    ctx = _get_ctx()
    if action == "reboot":
        reboot(context=ctx)
    elif action == "stow":
        set_stow_state(unstow=False, context=ctx)
    elif action == "unstow":
        set_stow_state(unstow=True, context=ctx)
    elif action == "gps_enable":
        set_gps_inhibit(False, context=ctx)
    elif action == "gps_inhibit":
        set_gps_inhibit(True, context=ctx)
    elif action == "power_save":
        start_min = int(args.get("startMinutes") or 0)
        dur_min = int(args.get("durationMinutes") or 0)
        enable = bool(args.get("enable", True))
        set_power_save(start_min, dur_min, enable, context=ctx)
    else:
        raise ValueError("unknown control action: %s" % action)
    return {"action": action, "accepted": True}


# ── v42 additions: obstruction map / speedtest / router ──────────────────
def _op_obstruction_map(_args):
    if _mode == "real":
        try:
            data = get_obstruction_map(context=_get_ctx())
            return {"map": data, "source": "real"}
        except (grpc.RpcError, GrpcError) as exc:
            # Some older dishes reject the RPC; degrade gracefully.
            return {"map": None, "source": "real",
                    "errorAr": "الطبق لم يستجب لطلب خريطة العرقلة (قد يكون الإصدار قديماً)",
                    "error": "%s: %s" % (type(exc).__name__, exc)}
    if _mode == "demo":
        return {"map": demo_sim.demo_obstruction_map(), "source": "demo"}
    return {"map": demo_sim.sample_obstruction_map(), "source": "sample"}


def _op_speedtest_start(args):
    if _mode != "real":
        demo_sim.demo_speedtest_start(int(args.get("durationS") or 15))
        return {"started": True, "demo": True,
                "noteAr": "وضع العرض — محاكاة اختبار السرعة قيد التشغيل"}
    duration = int(args.get("durationS") or 15)
    start_speedtest(context=_get_ctx(), duration_s=duration)
    return {"started": True, "durationS": duration}


def _op_speedtest_status(_args):
    if _mode != "real":
        return demo_sim.demo_speedtest_status()
    return get_speedtest_status(context=_get_ctx())


def _op_router_probe(args):
    """gRPC diagnostics on the Starlink router itself (192.168.1.1:9000)."""
    host = args.get("host") or "192.168.1.1"
    port = int(args.get("port") or 9000)
    rctx = ChannelContext(target="%s:%d" % (host, port))
    try:
        st = router_status(rctx)
        try:
            clients = router_clients(rctx)
        except (grpc.RpcError, GrpcError, Exception):
            clients = []
        st["reachable"] = True
        st["host"] = host
        st["port"] = port
        st["clients"] = clients
        return st
    except (grpc.RpcError, GrpcError, Exception) as exc:
        return {
            "reachable": False,
            "tried": True,
            "host": host,
            "port": port,
            "errorAr": "لا استجابة gRPC من الراوتر على %s:%d — قد يكون الراوتر غير ستارلينك أو المنفذ محجوباً" % (host, port),
            "error": "%s: %s" % (type(exc).__name__, exc),
        }
    finally:
        rctx.close()


def _op_dish_ping(_args):
    """Device-level ping targets reported by the dish itself (get_ping=1009)."""
    if _mode != "real":
        return {"targets": demo_sim.demo_ping_targets()}
    try:
        return get_ping(context=_get_ctx())
    except (grpc.RpcError, GrpcError) as exc:
        return {"targets": [],
                "errorAr": "الطبق لا يدعم فحص أهداف الـ ping (قد يكون الإصدار قديماً)",
                "error": "%s: %s" % (type(exc).__name__, exc)}


def _op_demo_set(args):
    global _mode, _last_sample_t, _last_counter
    with _lock:
        want = bool(args.get("enabled"))
        _mode = "demo" if want else "real"
        _last_sample_t = 0
        _last_counter = -1
        if not want:
            _reset_ctx()
        return {"mode": _mode}


def _op_demo_load_sample(args):
    global _mode, _last_sample_t, _last_counter
    with _lock:
        _mode = "sample"
        _last_sample_t = 0
        _last_counter = -1
        return {"mode": _mode, "sample": args.get("name") or "gps14"}


def _op_db_summary(_args):
    return db.summary() if _db_ready else {"samples": 0}


def _op_history_query(args):
    if not _db_ready:
        return {"series": []}
    from_ts = int(args.get("fromTs") or 0)
    to_ts = args.get("toTs")
    return {"series": db.series(from_ts=from_ts, to_ts=to_ts)}


def _op_tests(_args):
    return {"tests": db.recent_tests(30) if _db_ready else []}


def _op_alerts_log(_args):
    return {"alerts": db.recent_alerts(80) if _db_ready else []}


def _op_trim(_args):
    return {"deleted": db.trim() if _db_ready else 0}


def _op_shutdown(_args):
    global _db_ready
    with _lock:
        _reset_ctx()
        db.close_db()
        _db_ready = False
        return {"bye": True}


def _op_net_verdict(args):
    return dx.network_verdict(args.get("net") or {})


# ── V2.2: long-range trends + CSV export (from the local DB) ─────────────
TREND_WINDOWS = (("6h", 6 * 3600), ("24h", 24 * 3600), ("7d", 7 * 86400))
OUTAGE_LOSS = 0.9      # samples at/above this drop rate count as outage


def _trend_direction(first_avg, second_avg, tol_pct, higher_is_better=False):
    """Compare the first-half mean to the second-half mean of a window."""
    if first_avg is None or second_avg is None or first_avg <= 0:
        return "stable"
    delta = (second_avg - first_avg) / first_avg * 100.0
    improved = delta < -tol_pct
    degraded = delta > tol_pct
    if higher_is_better:
        improved, degraded = degraded, improved
    if degraded:
        return "degrading"
    if improved:
        return "improving"
    return "stable"


def _window_trend(rows):
    """Aggregate one window of stored samples into trend metrics."""
    n = len(rows)
    if n == 0:
        return {"samples": 0}
    lat = [r.get("latency") for r in rows]
    loss = [r.get("packetLoss") if r.get("packetLoss") is not None else 0.0 for r in rows]
    down = [r.get("download") or 0.0 for r in rows]
    up = [r.get("upload") or 0.0 for r in rows]
    outages = 0
    outage_s = 0
    in_outage = False
    for v in loss:
        if v >= OUTAGE_LOSS:
            if not in_outage:
                outages += 1
                in_outage = True
            outage_s += 1
        else:
            in_outage = False
    conn_lat = sorted(l for l, v in zip(lat, loss) if v < OUTAGE_LOSS and l is not None)
    half = n // 2
    lat_trend = down_trend = "stable"
    if half >= 5:
        def _mean(vals):
            clean = [v for v in vals if v is not None]
            return sum(clean) / len(clean) if clean else None
        lat_trend = _trend_direction(
            _mean(lat[:half]), _mean(lat[half:]), 10.0,
        )
        down_trend = _trend_direction(
            _mean(down[:half]), _mean(down[half:]), 10.0, higher_is_better=True,
        )
    return {
        "samples": n,
        "availabilityPct": round(100.0 * (n - outage_s) / n, 2),
        "outages": outages,
        "outageSamplesS": outage_s,
        "p50Ms": round(_pct(conn_lat, 50), 1) if conn_lat else None,
        "p95Ms": round(_pct(conn_lat, 95), 1) if conn_lat else None,
        "downAvgMbps": round(sum(down) / n, 2),
        "upAvgMbps": round(sum(up) / n, 2),
        "latencyTrend": lat_trend,
        "downloadTrend": down_trend,
    }


def _op_trends(_args):
    now = int(time.time())
    out = {}
    for key, seconds in TREND_WINDOWS:
        rows = db.series(from_ts=now - seconds) if _db_ready else []
        w = _window_trend(rows)
        w["windowS"] = seconds
        out[key] = w
    return {"trends": out, "dbReady": _db_ready}


def _op_export_csv(args):
    hours = max(1, int(args.get("hours") or 24))
    path = args.get("path")
    if not path:
        raise ValueError("export_csv requires 'path'")
    now = int(time.time())
    rows = db.series(from_ts=now - hours * 3600) if _db_ready else []
    header = ("timestamp,datetime_iso,download_mbps,upload_mbps,"
              "latency_ms,packet_loss_pct,gps,obstruction_pct,state")
    lines = [header]
    for r in rows:
        ts = r.get("ts") or 0
        obstr = r.get("obstruction")
        lines.append(",".join([
            str(ts),
            time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime(ts)) if ts else "",
            str(r.get("download") if r.get("download") is not None else ""),
            str(r.get("upload") if r.get("upload") is not None else ""),
            str(r.get("latency") if r.get("latency") is not None else ""),
            str(round(100.0 * (r.get("packetLoss") or 0.0), 3)),
            str(r.get("gps") or ""),
            str(round(100.0 * obstr, 2)) if obstr is not None else "",
            str(r.get("state") or ""),
        ]))
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write("\n".join(lines) + "\n")
    return {"path": path, "rows": len(rows), "hours": hours}


_OPS = {
    "init": _op_init,
    "set_target": _op_set_target,
    "get_status": _op_get_status,
    "poll": _op_poll,
    "live_series": _op_live_series,
    "full_diagnostic": _op_full_diagnostic,
    "raw": _op_raw,
    "control": _op_control,
    "obstruction_map": _op_obstruction_map,
    "speedtest_start": _op_speedtest_start,
    "speedtest_status": _op_speedtest_status,
    "router_probe": _op_router_probe,
    "dish_ping": _op_dish_ping,
    "demo_set": _op_demo_set,
    "demo_load_sample": _op_demo_load_sample,
    "db_summary": _op_db_summary,
    "history_query": _op_history_query,
    "tests": _op_tests,
    "alerts_log": _op_alerts_log,
    "trim": _op_trim,
    "net_verdict": _op_net_verdict,
    "trends": _op_trends,
    "export_csv": _op_export_csv,
    "shutdown": _op_shutdown,
}


def rpc(payload_json: str) -> str:
    """Single entry point from Kotlin. Never raises; errors come back as JSON."""
    try:
        req = json.loads(payload_json)
        op = req.get("op")
        args = req.get("args") or {}
        handler = _OPS.get(op)
        if handler is None:
            return json.dumps({"ok": False, "errorAr": "عملية غير معروفة",
                               "error": "unknown op: %s" % op})
        data = handler(args)
        return json.dumps({"ok": True, "data": _clean(data)},
                          separators=(",", ":"), allow_nan=False)
    except Exception as exc:  # noqa: BLE001 - bridge must never raise into Kotlin
        return json.dumps(_err_payload(exc), separators=(",", ":"))
