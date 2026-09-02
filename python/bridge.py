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
    ChannelContext,
    GrpcError,
    get_history,
    get_status,
    history_bulk_data,
    history_stats,
    reboot,
    set_stow_state,
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
    return {
        "state": status.get("state"),
        "uptimeS": status.get("uptime"),
        "deviceInfo": {
            "id": status.get("id"),
            "hardwareVersion": status.get("hardware_version"),
            "softwareVersion": status.get("software_version"),
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
        },
        "obstruction": {
            "currentlyObstructed": status.get("currently_obstructed"),
            "fractionObstructed": round(status.get("fraction_obstructed"), 4)
            if status.get("fraction_obstructed") is not None else None,
            "last24hObstructedS": round(obstruction.get("last_24h_obstructed_s") or 0),
            "validS": round(obstruction.get("valid_s") or 0),
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
    status, obstruction, alerts, _ = _current_status_real()
    hist = get_history(context=ctx)
    start = _last_counter if _last_counter >= 0 else None
    gen, bulk = history_bulk_data(parse_samples=-1, start=start, context=ctx, history=hist)
    n = len(bulk["pop_ping_drop_rate"])
    end_counter = gen.get("end_counter") or 0
    new_samples = []
    if n > 0:
        end_time = int(time.time())
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
    if end_counter and end_counter != _last_counter:
        _last_counter = end_counter
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
            "endCounter": end_counter, "mode": _mode}


def _poll_demo(_args):
    global _last_sample_t
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
    stored = db.store_samples(new_samples) if _db_ready else 0
    if _db_ready and new_samples:
        db.set_meta_bulk({
            "firmware": status.get("software_version") or "",
            "hardware": status.get("hardware_version") or "",
            "dish_id": status.get("id") or "",
            "last_uptime_s": status.get("uptime") or 0,
        })
    return {"status": _clean(norm), "newSamples": new_samples, "stored": stored,
            "endCounter": 0, "mode": _mode}


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
    if _mode == "real":
        ctx = _get_ctx()
        stats = history_stats(parse_samples=-1, context=ctx, history=hist)
    else:
        stats = _demo_stats()
    net = args.get("net")
    assessment = dx.run(status, obstruction, alerts, stats, net=net)
    assessment["ts"] = time.time()
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
    else:
        raise ValueError("unknown control action: %s" % action)
    return {"action": action, "accepted": True}


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


_OPS = {
    "init": _op_init,
    "set_target": _op_set_target,
    "get_status": _op_get_status,
    "poll": _op_poll,
    "live_series": _op_live_series,
    "full_diagnostic": _op_full_diagnostic,
    "raw": _op_raw,
    "control": _op_control,
    "demo_set": _op_demo_set,
    "demo_load_sample": _op_demo_load_sample,
    "db_summary": _op_db_summary,
    "history_query": _op_history_query,
    "tests": _op_tests,
    "alerts_log": _op_alerts_log,
    "trim": _op_trim,
    "net_verdict": _op_net_verdict,
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
