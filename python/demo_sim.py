"""Demo mode for V2.

1) A live simulator producing dish-like data (throughput, latency, drops,
   outages, obstruction events) — ported from the previous project's
   demo-sim, now emitting the SAME dict shape as starlink_grpc.status_data()
   so the bridge has a single normalization path for real and demo data.

2) A bundled static sample reproducing the user's real device capture
   (GPS self-test failure, code 14) so the diagnostics engine can be
   exercised without a dish.
"""

import math
import random
import time

BUFFER_SIZE = 9000  # 2.5 h at 1 Hz (same as the real dish history buffer)


def _rand(a, b):
    return a + random.random() * (b - a)


def _clamp(v, a, b):
    return min(b, max(a, v))


class _SimEvent:
    __slots__ = ("kind", "remaining", "total")

    def __init__(self, kind, remaining):
        self.kind = kind
        self.remaining = remaining
        self.total = remaining


class _DemoState:
    def __init__(self):
        self.buffer = [None] * BUFFER_SIZE
        self.head = 0
        self.count = 0
        self.last_t = 0
        self.event = None
        self.next_outage_in = int(_rand(30, 180))
        self.next_obstruction_in = int(_rand(20, 120))
        self.throttled = False
        self.base_down = _rand(130, 220)
        self.base_up = _rand(12, 22)
        self.base_latency = _rand(30, 38)
        self.uptime = int(_rand(2 * 86400, 40 * 86400))
        self.mast_alert = False
        self.slow_eth = False
        self.current = 0  # sample counter, like the real dish
        suffix = format(int(_rand(0x1000, 0xFFFF)), "04X")
        self.device = {
            "id": "ut0{}-{}-000000".format(int(_rand(1, 6)), suffix),
            "hw": "rev3-pre4_prod4-v2",
            "sw": "20{:d}.0{:d}.0{:d}.mr{:d}".format(
                int(_rand(23, 25)), int(_rand(1, 9)), int(_rand(1, 9)),
                int(_rand(28000, 48000)),
            ),
        }


_state = None


def _new_sample(t, st):
    st.base_down = _clamp(st.base_down + _rand(-3, 3), 90, 300)
    st.base_up = _clamp(st.base_up + _rand(-0.4, 0.4), 8, 26)
    st.base_latency = _clamp(st.base_latency + _rand(-1, 1), 26, 44)

    if st.next_outage_in <= 0 and st.event is None:
        st.event = _SimEvent("outage", int(_rand(3, 90)))
        st.next_outage_in = int(_rand(480, 1200))
    if st.next_obstruction_in <= 0 and st.event is None:
        st.event = _SimEvent("obstruction", int(_rand(5, 60)))
        st.next_obstruction_in = int(_rand(300, 900))

    down = st.base_down + math.sin(t / 90.0) * 25 + _rand(-12, 12)
    up = st.base_up + math.sin(t / 70.0) * 2.5 + _rand(-1.5, 1.5)
    latency = st.base_latency + _rand(-4, 6)
    drop = 0.0
    obstructed = False

    if st.event is not None:
        st.event.remaining -= 1
        if st.event.remaining <= 0:
            st.event = None

    if st.event is not None and st.event.kind == "outage":
        drop = 1.0
        down = 0.0
        up = 0.0
        latency = 0.0
    elif st.event is not None and st.event.kind == "obstruction":
        obstructed = True
        latency += _rand(8, 30)
        down *= _rand(0.6, 0.95)
        drop = _rand(0, 0.15)
    elif st.throttled:
        down = min(down, 55)
        latency += _rand(2, 8)

    if random.random() < 0.012:
        latency += _rand(20, 90)
    if random.random() < 0.004:
        drop = min(1.0, _rand(0.5, 0.9))

    return {
        "t": t,
        "downMbps": max(0.0, round(down * 10) / 10),
        "upMbps": max(0.0, round(up * 10) / 10),
        "latencyMs": max(0.0, round(latency * 10) / 10),
        "dropRate": round(_clamp(drop, 0, 1) * 1000) / 1000,
        "obstructed": obstructed,
        "scheduled": random.random() < 0.93,
    }


def _push(st, sample):
    st.buffer[st.head] = sample
    st.head = (st.head + 1) % BUFFER_SIZE
    st.count = min(st.count + 1, BUFFER_SIZE)
    st.last_t = sample["t"]
    st.uptime += 1
    st.current += 1


def _rewrite_range(st, start_t, dur_s):
    for i in range(st.count):
        idx = (st.head - 1 - i + BUFFER_SIZE * 2) % BUFFER_SIZE
        s = st.buffer[idx]
        if s is not None and start_t <= s["t"] < start_t + dur_s:
            s["downMbps"] = 0.0
            s["upMbps"] = 0.0
            s["latencyMs"] = 0.0
            s["dropRate"] = 1.0


def _ensure_state():
    global _state
    if _state is not None:
        return _state
    st = _DemoState()
    now = int(time.time())
    prefill = min(1200, BUFFER_SIZE)
    for i in range(prefill, 0, -1):
        _push(st, _new_sample(now - i, st))
    outage_start = now - int(_rand(120, 400))
    outage_dur = int(_rand(8, 45))
    _rewrite_range(st, outage_start, outage_dur)
    _state = st
    return st


def _advance(st):
    now = int(time.time())
    while st.last_t < now:
        st.next_outage_in -= 1
        st.next_obstruction_in -= 1
        if random.random() < 0.00008:
            st.throttled = True
        if st.throttled and st.event is None:
            st.event = _SimEvent("throttle", int(_rand(120, 400)))
        _push(st, _new_sample(st.last_t + 1, st))


def _ordered_samples(st, window_seconds):
    n = min(st.count, window_seconds, BUFFER_SIZE)
    out = []
    for i in range(n, 0, -1):
        idx = (st.head - i + BUFFER_SIZE * 2) % BUFFER_SIZE
        s = st.buffer[idx]
        if s is not None:
            out.append(s)
    return out


def demo_get_status_data():
    """Return (status, obstruction, alerts) with the same keys as
    starlink_grpc.status_data() for the live simulator."""
    st = _ensure_state()
    _advance(st)
    latest = st.buffer[(st.head - 1 + BUFFER_SIZE) % BUFFER_SIZE] if st.count > 0 else None
    state = "SEARCHING" if (st.event is not None and st.event.kind == "outage") else "CONNECTED"
    throttled = bool(st.throttled or (st.event is not None and st.event.kind == "throttle"))
    obstructed_now = bool(latest["obstructed"]) if latest else False

    status = {
        "id": st.device["id"],
        "hardware_version": st.device["hw"],
        "software_version": st.device["sw"],
        "state": state,
        "uptime": st.uptime,
        "snr": None,
        "seconds_to_first_nonempty_slot": round(_rand(0.0, 2.0), 1),
        "pop_ping_drop_rate": latest["dropRate"] if latest else 0.0,
        "downlink_throughput_bps": (latest["downMbps"] if latest else 0.0) * 1e6,
        "uplink_throughput_bps": (latest["upMbps"] if latest else 0.0) * 1e6,
        "pop_ping_latency_ms": latest["latencyMs"] if latest else 0.0,
        "alerts": 0,
        "alert_hw_codes": [2] if throttled else [],
        "currently_obstructed": obstructed_now,
        "seconds_obstructed": None,
        "obstruction_duration": round(_rand(6, 35) * 10) / 10,
        "obstruction_interval": round(_rand(240, 900)),
        "direction_azimuth": round(_rand(120, 240)),
        "direction_elevation": round(_rand(35, 60)),
        "is_snr_above_noise_floor": state == "CONNECTED",
        "is_snr_persistently_low": False,
        "eth_speed_mbps": 100 if st.slow_eth else 1000,
        "stow_requested": False,
        "gps_ready": True,
        "gps_enabled": True,
        "gps_sats": int(_rand(9, 14)),
        "gps_inhibit_raw": None,
    }
    obstruction = {
        "valid_s": round(_rand(82000, 86400)),
        "last_24h_obstructed_s": round(_rand(180, 900)),
        "last_24h_valid_s": round(_rand(81000, 86000)),
        "avg_prolonged_obstruction_duration_s": round(_rand(6, 35) * 10) / 10,
        "avg_prolonged_obstruction_interval_s": round(_rand(240, 900)),
        "direction_azimuth": round(_rand(0, 359)),
        "direction_elevation": round(_rand(20, 65)),
    }
    alerts = {
        "alert_motors_stuck": False,
        "alert_thermal_shutdown": False,
        "alert_thermal_throttle": throttled,
        "alert_unexpected_location": False,
        "alert_mast_not_near_vertical": st.mast_alert,
        "alert_slow_ethernet_speeds": st.slow_eth,
        "alert_obstructed": obstructed_now,
    }
    return status, obstruction, alerts


def demo_latest_sample():
    """The newest 1 Hz sample (used by the poll loop to feed the DB)."""
    st = _ensure_state()
    _advance(st)
    latest = st.buffer[(st.head - 1 + BUFFER_SIZE) % BUFFER_SIZE] if st.count > 0 else None
    return latest


def demo_history_window(window_seconds):
    """Ordered samples for the demo live charts."""
    st = _ensure_state()
    _advance(st)
    return _ordered_samples(st, window_seconds)


def demo_poll_new(last_t):
    """Samples newer than last_t (used to feed the DB incrementally)."""
    st = _ensure_state()
    _advance(st)
    out = []
    for i in range(st.count, 0, -1):
        idx = (st.head - i + BUFFER_SIZE * 2) % BUFFER_SIZE
        s = st.buffer[idx]
        if s is not None and s["t"] > last_t:
            out.append(s)
    return out


# ── Bundled static sample: the user's real device capture ────────────────
# Hardware rev3_proto2, firmware 2026.08.20.mr85023.1, GPS self-test FAILED
# with code 14, GPS valid=false, satellites=0, GPS inhibited=true.
SAMPLE_GPS14 = {
    "id": "ut01-field-sample-000000",
    "hardware_version": "rev3_proto2",
    "software_version": "2026.08.20.mr85023.1",
    "state": "CONNECTED",
    "uptime": 988,
    "snr": None,
    "seconds_to_first_nonempty_slot": 0.0,
    "pop_ping_drop_rate": 0.0,
    "downlink_throughput_bps": 9.67e6,
    "uplink_throughput_bps": 1.24e6,
    "pop_ping_latency_ms": 48.2,
    "alerts": 0,
    "alert_hw_codes": [14],
    "currently_obstructed": False,
    "seconds_obstructed": None,
    "obstruction_duration": 0.0,
    "obstruction_interval": 0.0,
    "direction_azimuth": 0.0,
    "direction_elevation": 0.0,
    "is_snr_above_noise_floor": True,
    "is_snr_persistently_low": False,
    "eth_speed_mbps": 1000,
    "stow_requested": False,
    "gps_ready": False,
    "gps_enabled": False,   # inhibited = YES
    "gps_sats": 0,
    "gps_inhibit_raw": {"field": 3, "value": True},
}

SAMPLE_GPS14_OBSTRUCTION = {
    "valid_s": 86000,
    "last_24h_obstructed_s": 0.0,
    "last_24h_valid_s": 85500.0,
    "avg_prolonged_obstruction_duration_s": 0.0,
    "avg_prolonged_obstruction_interval_s": 0.0,
    "direction_azimuth": 0.0,
    "direction_elevation": 0.0,
}

SAMPLE_GPS14_ALERTS = {
    "alert_motors_stuck": False,
    "alert_thermal_shutdown": False,
    "alert_thermal_throttle": False,
    "alert_unexpected_location": False,
    "alert_mast_not_near_vertical": False,
    "alert_slow_ethernet_speeds": False,
    "alert_obstructed": False,
}


def sample_get_status_data():
    return dict(SAMPLE_GPS14), dict(SAMPLE_GPS14_OBSTRUCTION), dict(SAMPLE_GPS14_ALERTS)
