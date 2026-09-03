"""Starlink dish gRPC layer for V2.1 — aligned with the CURRENT firmware
protocol (API v42, verified against joshuasing/starlink_exporter generated
descriptors, firmware 2026.06.22) while keeping the call signatures and data
dict keys of sparky8512/starlink-grpc-tools v1.2.5 wherever they still apply.

v42 layout essentials (see docs/spacex_api_device.proto for the full map):
- History: 1001..1004 arrays unchanged; 1005-1007 GONE; 1009 = repeated
  DishOutage; 1010 = power_in (W per sample); alert_bitmask gone.
- Status: state/snr removed -> state is derived from the outage message
  exactly like current starlink-grpc-tools; gps_stats=1015 (inhibit_gps is
  a REAL field now), eth_speed=1016, snr flags 1018/1022, outage=1014,
  software_update_state=1021, disablement_code=1024, alignment_stats=1027,
  upsu_stats=1043 (power draw), dl/ul_bandwidth_restricted_reason=1044/1045.
- alerts_hardware (old repeated Alert enum) is GONE in v42 -> V2.1 evidence
  comes from disablement_code + DishAlerts booleans + outage causes.
- New RPCs used here: get_ping=1009, start_speedtest=1027,
  get_speedtest_status=1028, dish_get_obstruction_map=2008,
  dish_power_save=2013, dish_inhibit_gps=2014, wifi_get_status=3004 and
  wifi_get_clients=3002 (router shares the same Device.Handle service).
"""

import math
import statistics
from itertools import chain
from typing import Iterable, List, Optional, Tuple

import grpc

import spacex_api_device_pb2 as device
import spacex_api_device_pb2_grpc as device_grpc

REQUEST_TIMEOUT = 10

DEFAULT_TARGET = "192.168.100.1:9200"
DEFAULT_ROUTER_TARGET = "192.168.1.1:9000"


class GrpcError(Exception):
    """Provides error info when something went wrong with a gRPC call."""

    def __init__(self, e, *args, **kwargs):
        if isinstance(e, grpc.Call):
            msg = e.details()
        elif isinstance(e, grpc.RpcError):
            msg = "Unknown communication or service error"
        elif isinstance(e, (AttributeError, IndexError, TypeError, ValueError)):
            msg = "Protocol error"
        else:
            msg = str(e)
        super().__init__(msg, *args, **kwargs)


class ChannelContext:
    """A wrapper for reusing an open grpc Channel across calls."""

    def __init__(self, target: Optional[str] = None) -> None:
        self.channel = None
        self.target = DEFAULT_TARGET if target is None else target

    def get_channel(self) -> Tuple[grpc.Channel, bool]:
        reused = True
        if self.channel is None:
            options = [
                ("grpc.keepalive_time_ms", 15000),
                ("grpc.keepalive_timeout_ms", 5000),
                ("grpc.keepalive_permit_without_calls", 1),
                ("grpc.enable_retries", 0),
            ]
            self.channel = grpc.insecure_channel(self.target, options=options)
            reused = False
        return self.channel, reused

    def close(self) -> None:
        if self.channel is not None:
            self.channel.close()
        self.channel = None


def call_with_channel(function, *args, context: Optional[ChannelContext] = None, **kwargs):
    """Call a function with a grpc.Channel object (same retry semantics as upstream)."""
    if context is None:
        temp = ChannelContext()
        channel, _ = temp.get_channel()
        try:
            return function(channel, *args, **kwargs)
        finally:
            temp.close()
    while True:
        channel, reused = context.get_channel()
        try:
            return function(channel, *args, **kwargs)
        except grpc.RpcError:
            context.close()
            if not reused:
                raise


# The pb2 classes are module-level here; upstream assigns them in
# resolve_imports() after reflection. Same call shapes otherwise.
DeviceStub = device_grpc.DeviceStub
Request = device.Request


def _handle(channel, request, timeout=REQUEST_TIMEOUT):
    stub = DeviceStub(channel)
    return stub.Handle(request, timeout=timeout)


def get_status(context: Optional[ChannelContext] = None):
    """Fetch status and return it in grpc structure format (upstream signature)."""

    def grpc_call(channel):
        return _handle(channel, Request(get_status={})).dish_get_status

    return call_with_channel(grpc_call, context=context)


def get_id(context: Optional[ChannelContext] = None) -> str:
    """Fetch dish ID and return it."""
    try:
        return get_status(context).device_info.id
    except (AttributeError, ValueError, grpc.RpcError) as e:
        raise GrpcError(e) from e


def _enum_name(enum_type, value, fallback=None):
    """Enum value name with graceful fallback for unknown codes."""
    try:
        return enum_type.Name(value)
    except (ValueError, AttributeError):
        return fallback if fallback is not None else str(int(value))


def outage_dict(outage):
    """DishOutage message -> plain dict (V2.1 outage attribution)."""
    if outage is None:
        return None
    try:
        has = outage.SerializeToString() != b""
    except Exception:  # noqa: BLE001
        has = False
    if not has:
        return None
    return {
        "cause": int(outage.cause),
        "causeName": _enum_name(device.DishOutage.Cause, outage.cause, "CAUSE_UNKNOWN"),
        "ongoing": outage.duration_ns == 0,
        "startTsNs": int(outage.start_timestamp_ns),
        "durationNs": int(outage.duration_ns),
        "didSwitch": bool(outage.did_switch),
    }


def _scan_unknown_varint(msg, min_field: int = 3):
    """Scan unknown varint fields of a message (protobuf unknown field set).

    Kept as a transparency aid for newer firmware; with the v42 schema the
    fields V2.1 needs are declared, so this is no longer on the critical path.
    """
    out = []
    try:
        from google.protobuf.unknown_fields import UnknownFieldSet

        for f in UnknownFieldSet(msg):
            if f.wire_type == 0 and f.field_number >= min_field:
                out.append((f.field_number, int(f.data)))
    except Exception:  # noqa: BLE001 - diagnostics must never crash on this
        pass
    return out


def status_data(
    context: Optional[ChannelContext] = None,
) -> Tuple[dict, dict, dict]:
    """Fetch current status data (same keys as upstream v1.2.5, v42 semantics)."""
    try:
        status = get_status(context)
    except (AttributeError, ValueError, grpc.RpcError) as e:
        raise GrpcError(e) from e

    # State: the v42 service carries no DishState enum, so derive from the
    # outage message. An outage with duration_ns > 0 is a COMPLETED outage
    # the dish still attaches to status — the dish is connected again; only
    # duration_ns == 0 means the outage is ongoing.
    outage = outage_dict(status.outage) if status.HasField("outage") else None
    if outage is not None and outage["ongoing"]:
        if outage["causeName"] == "CAUSE_NO_SCHEDULE":
            # Special case translate this to equivalent old name (upstream)
            state = "SEARCHING"
        else:
            state = outage["causeName"].replace("CAUSE_", "")
    else:
        state = "CONNECTED"

    # Alerts: bit field based on field numbers of the DishAlerts message
    # (same approach as upstream) + the expanded v42 boolean set.
    alerts = {}
    alert_bits = 0
    try:
        for field in status.alerts.DESCRIPTOR.fields:
            value = getattr(status.alerts, field.name, False)
            alerts["alert_" + field.name] = value
            if field.number < 65:
                alert_bits |= (1 if value else 0) << (field.number - 1)
    except AttributeError:
        pass

    obstruction_stats = getattr(status, "obstruction_stats", None)
    device_info = getattr(status, "device_info", None)
    gps_stats = getattr(status, "gps_stats", None)
    alignment = getattr(status, "alignment_stats", None)
    swu_stats = getattr(status, "software_update_stats", None)
    upsu = getattr(status, "upsu_stats", None)
    battery = getattr(status, "battery_stats", None)

    def _f(msg, key):
        v = getattr(msg, key, None) if msg is not None else None
        if isinstance(v, float) and (math.isnan(v) or math.isinf(v)):
            return None
        return v

    # Power draw (Gen2/3 UPSU). None when the dish does not publish it.
    dish_power = _f(upsu, "dish_power")
    router_power = _f(upsu, "router_power")

    alignment_d = None
    if alignment is not None and alignment.SerializeToString() != b"":
        alignment_d = {
            "hasActuators": _enum_name(device.HasActuators, alignment.has_actuators),
            "actuatorState": _enum_name(device.ActuatorState, alignment.actuator_state),
            "tiltAngleDeg": _f(alignment, "tilt_angle_deg"),
            "boresightAzimuthDeg": _f(alignment, "boresight_azimuth_deg"),
            "boresightElevationDeg": _f(alignment, "boresight_elevation_deg"),
            "attitudeState": _enum_name(
                device.AttitudeEstimationState, alignment.attitude_estimation_state
            ),
            "attitudeUncertaintyDeg": _f(alignment, "attitude_uncertainty_deg"),
            "desiredBoresightAzimuthDeg": _f(alignment, "desired_boresight_azimuth_deg"),
            "desiredBoresightElevationDeg": _f(alignment, "desired_boresight_elevation_deg"),
        }

    swu_d = None
    if swu_stats is not None and swu_stats.SerializeToString() != b"":
        swu_d = {
            "state": int(swu_stats.software_update_state),
            "stateName": _enum_name(
                device.SoftwareUpdateState, swu_stats.software_update_state
            ),
            "progress": _f(swu_stats, "software_update_progress"),
            "requiresReboot": bool(swu_stats.update_requires_reboot),
            "rebootScheduledUtc": int(swu_stats.reboot_scheduled_utc_time),
        }

    swu_state = int(getattr(status, "software_update_state", 0))
    disablement = int(getattr(status, "disablement_code", 0))
    dl_restricted = int(getattr(status, "dl_bandwidth_restricted_reason", 0))
    ul_restricted = int(getattr(status, "ul_bandwidth_restricted_reason", 0))

    return {
        "id": getattr(device_info, "id", None),
        "hardware_version": getattr(device_info, "hardware_version", None),
        "software_version": getattr(device_info, "software_version", None),
        "country_code": getattr(device_info, "country_code", None),
        "bootcount": getattr(device_info, "bootcount", None),
        "build_id": getattr(device_info, "build_id", None),
        "state": state,
        "uptime": getattr(getattr(status, "device_state", None), "uptime_s", None),
        "snr": None,  # obsoleted in the v42 service (upstream keeps the key)
        "seconds_to_first_nonempty_slot": _f(status, "seconds_to_first_nonempty_slot"),
        "pop_ping_drop_rate": _f(status, "pop_ping_drop_rate"),
        "downlink_throughput_bps": _f(status, "downlink_throughput_bps"),
        "uplink_throughput_bps": _f(status, "uplink_throughput_bps"),
        "pop_ping_latency_ms": _f(status, "pop_ping_latency_ms"),
        "alerts": alert_bits,
        "alert_hw_codes": [],  # alerts_hardware removed in v42 (kept for key compat)
        "fraction_obstructed": _f(obstruction_stats, "fraction_obstructed"),
        "currently_obstructed": getattr(obstruction_stats, "currently_obstructed", None),
        "seconds_obstructed": None,  # obsoleted
        "obstruction_duration": _f(obstruction_stats, "avg_prolonged_obstruction_duration_s"),
        "obstruction_interval": _f(obstruction_stats, "avg_prolonged_obstruction_interval_s"),
        "direction_azimuth": _f(status, "boresight_azimuth_deg"),
        "direction_elevation": _f(status, "boresight_elevation_deg"),
        "is_snr_above_noise_floor": getattr(status, "is_snr_above_noise_floor", None),
        "is_snr_persistently_low": getattr(status, "is_snr_persistently_low", None),
        "eth_speed_mbps": getattr(status, "eth_speed_mbps", None),
        "stow_requested": getattr(status, "stow_requested", None),
        # v42 evidence surface (V2.1):
        "outage": outage,
        "disablement_code": disablement,
        "software_update_state": swu_state,
        "swupdate_reboot_ready": getattr(status, "swupdate_reboot_ready", None),
        "software_update_stats": swu_d,
        "dl_restricted_reason": dl_restricted,
        "ul_restricted_reason": ul_restricted,
        "mobility_class": _enum_name(
            device.UserMobilityClass, getattr(status, "mobility_class", 0)
        ),
        "class_of_service": _enum_name(
            device.UserClassOfService, getattr(status, "class_of_service", 0)
        ),
        "ready_states": {
            k: bool(getattr(getattr(status, "ready_states", None), k, False))
            for k in ("cady", "scp", "l1l2", "xphy", "aap", "rf")
        },
        "alignment": alignment_d,
        "dish_power_w": dish_power,
        "router_power_w": router_power,
        "upsu_uptime_s": getattr(upsu, "uptime", None) if upsu is not None else None,
        "battery": {
            "stateOfCharge": getattr(battery, "state_of_charge", None),
            "isCharging": getattr(battery, "is_charging", None),
        } if battery is not None and battery.SerializeToString() != b"" else None,
        "reboot_reason": _enum_name(
            device.RebootReason, getattr(status, "reboot_reason", 0)
        ),
        "gps_ready": getattr(gps_stats, "gps_valid", None),
        "gps_sats": getattr(gps_stats, "gps_sats", None),
        "gps_no_sats_after_ttff": getattr(gps_stats, "no_sats_after_ttff", None),
        "gps_enabled": (
            None if gps_stats is None or not gps_stats.HasField("inhibit_gps")
            else not bool(gps_stats.inhibit_gps)
        ),
    }, {
        # v42 carries no 24h counters / wedges / direction — keys stay for
        # compatibility and read as None on modern dishes.
        "valid_s": _f(obstruction_stats, "valid_s"),
        "last_24h_obstructed_s": None,
        "last_24h_valid_s": None,
        "avg_prolonged_obstruction_duration_s": _f(
            obstruction_stats, "avg_prolonged_obstruction_duration_s"
        ),
        "avg_prolonged_obstruction_interval_s": _f(
            obstruction_stats, "avg_prolonged_obstruction_interval_s"
        ),
        "avg_prolonged_obstruction_valid": getattr(
            obstruction_stats, "avg_prolonged_obstruction_valid", None
        ),
        "time_obstructed": _f(obstruction_stats, "time_obstructed"),
        "patches_valid": getattr(obstruction_stats, "patches_valid", None),
        "direction_azimuth": None,
        "direction_elevation": None,
    }, alerts


def get_history(context: Optional[ChannelContext] = None):
    """Fetch history data and return it in grpc structure format (upstream signature)."""

    def grpc_call(channel):
        return _handle(channel, Request(get_history={})).dish_get_history

    return call_with_channel(grpc_call, context=context)


def _compute_sample_range(
    history,
    parse_samples: int,
    start: Optional[int] = None,
    verbose: bool = False,
):
    """Ring-buffer sample range resolution — exact port of upstream v1.2.5."""
    try:
        current = int(history.current)
        samples = len(history.pop_ping_drop_rate)
    except (AttributeError, TypeError):
        # Without current and pop_ping_drop_rate, history is unusable.
        return range(0), 0, None

    if verbose:
        print("current counter:       " + str(current))
        print("All samples:           " + str(samples))

    samples = min(samples, current)

    if verbose:
        print("Valid samples:         " + str(samples))

    if parse_samples < 0 or samples < parse_samples:
        parse_samples = samples

    if start is not None and start > current:
        if verbose:
            print("Counter reset detected, ignoring requested start count")
        start = None

    if start is None or start < current - parse_samples:
        start = current - parse_samples

    if start == current:
        return range(0), 0, current

    # Ring buffer offset, so both index to oldest data sample and index to
    # next data sample after the newest one.
    end_offset = current % samples
    start_offset = start % samples

    sample_range: Iterable[int]
    if start_offset < end_offset:
        sample_range = range(start_offset, end_offset)
    else:
        sample_range = chain(range(start_offset, samples), range(0, end_offset))

    return sample_range, current - start, current


def outages_from_history(history) -> List[dict]:
    """Outage records overlapping the history buffer (v42 field 1009)."""
    out = []
    try:
        for o in history.outages:
            d = outage_dict(o)
            if d is not None:
                out.append(d)
    except (AttributeError, TypeError):
        pass
    return out


def history_power_stats(
    parse_samples: int,
    context: Optional[ChannelContext] = None,
    history=None,
) -> dict:
    """Power statistics from the v42 power_in array (watts per sample)."""
    if history is None:
        try:
            history = get_history(context)
        except (AttributeError, ValueError, grpc.RpcError) as e:
            raise GrpcError(e) from e

    sample_range, parsed_samples, current = _compute_sample_range(history, parse_samples)
    watts = []
    for i in sample_range:
        try:
            w = float(history.power_in[i])
            if not math.isnan(w) and not math.isinf(w):
                watts.append(w)
        except (AttributeError, IndexError, TypeError):
            pass
    avg = sum(watts) / len(watts) if watts else None
    return {
        "samples": len(watts),
        "avgPowerW": round(avg, 1) if avg is not None else None,
        "minPowerW": round(min(watts), 1) if watts else None,
        "maxPowerW": round(max(watts), 1) if watts else None,
        # kWh over the parsed window (each sample = 1 s)
        "kWh": round(sum(watts) / 3600.0 / 1000.0, 4) if watts else None,
    }


def history_bulk_data(
    parse_samples: int,
    start: Optional[int] = None,
    verbose: bool = False,
    context: Optional[ChannelContext] = None,
    history=None,
) -> Tuple[dict, dict]:
    """Fetch history data for a range of samples (upstream signature/keys)."""
    if history is None:
        try:
            history = get_history(context)
        except (AttributeError, ValueError, grpc.RpcError) as e:
            raise GrpcError(e) from e

    sample_range, parsed_samples, current = _compute_sample_range(
        history, parse_samples, start=start, verbose=verbose
    )

    pop_ping_drop_rate = []
    pop_ping_latency_ms = []
    downlink_throughput_bps = []
    uplink_throughput_bps = []
    scheduled = []
    obstructed = []

    # scheduled/obstructed arrays were removed in v42; tolerate both worlds.
    has_sched = (
        getattr(history, "scheduled", None) is not None
        and len(getattr(history, "scheduled", [])) > 0
    )
    has_obstruct = (
        getattr(history, "obstructed", None) is not None
        and len(getattr(history, "obstructed", [])) > 0
    )

    for i in sample_range:
        # pop_ping_drop_rate is checked in _compute_sample_range
        pop_ping_drop_rate.append(history.pop_ping_drop_rate[i])

        latency = None
        try:
            if history.pop_ping_drop_rate[i] < 1:
                latency = history.pop_ping_latency_ms[i]
        except (AttributeError, IndexError, TypeError):
            pass
        pop_ping_latency_ms.append(latency)

        downlink = None
        try:
            downlink = history.downlink_throughput_bps[i]
        except (AttributeError, IndexError, TypeError):
            pass
        downlink_throughput_bps.append(downlink)

        uplink = None
        try:
            uplink = history.uplink_throughput_bps[i]
        except (AttributeError, IndexError, TypeError):
            pass
        uplink_throughput_bps.append(uplink)

        val_sched = None
        val_obstruct = None
        if has_sched:
            try:
                val_sched = bool(history.scheduled[i])
            except (IndexError, TypeError):
                val_sched = None
        if has_obstruct:
            try:
                val_obstruct = bool(history.obstructed[i])
            except (IndexError, TypeError):
                val_obstruct = None
        scheduled.append(val_sched)
        obstructed.append(val_obstruct)

    snr = []
    try:
        snr = [float(v) for v in history.snr]
        if len(snr) != parsed_samples:
            snr = [None] * parsed_samples
    except (AttributeError, IndexError, TypeError):
        snr = [None] * parsed_samples

    return {
        "samples": parsed_samples,
        "end_counter": current,
    }, {
        "pop_ping_drop_rate": pop_ping_drop_rate,
        "pop_ping_latency_ms": pop_ping_latency_ms,
        "downlink_throughput_bps": downlink_throughput_bps,
        "uplink_throughput_bps": uplink_throughput_bps,
        "snr": snr,
        "scheduled": scheduled,
        "obstructed": obstructed,
    }


def history_ping_stats(parse_samples: int, context: Optional[ChannelContext] = None) -> dict:
    """Fetch ping drop and latency stats (upstream signature)."""
    return history_stats(parse_samples, context=context)[1:4]


def history_stats(
    parse_samples: int,
    start: Optional[int] = None,
    verbose: bool = False,
    context: Optional[ChannelContext] = None,
    history=None,
):
    """Fetch, parse, and compute ping and usage stats (upstream keys)."""
    if history is None:
        try:
            history = get_history(context)
        except (AttributeError, ValueError, grpc.RpcError) as e:
            raise GrpcError(e) from e

    sample_range, parsed_samples, current = _compute_sample_range(
        history, parse_samples, start=start, verbose=verbose
    )

    tot = 0.0
    count_full_drop = 0
    count_unsched = 0
    total_unsched_drop = 0.0
    count_full_unsched = 0
    count_obstruct = 0
    total_obstruct_drop = 0.0
    count_full_obstruct = 0

    second_runs = [0] * 60
    minute_runs = [0] * 60
    run_length = 0
    init_run_length = None

    usage_down = 0.0
    usage_up = 0.0

    rtt_full: List[float] = []
    rtt_all: List[Tuple[float, float]] = []
    rtt_buckets: List[List[float]] = [[] for _ in range(15)]

    has_sched = (
        getattr(history, "scheduled", None) is not None
        and len(getattr(history, "scheduled", [])) > 0
    )
    has_obstruct = (
        getattr(history, "obstructed", None) is not None
        and len(getattr(history, "obstructed", [])) > 0
    )

    for i in sample_range:
        d = history.pop_ping_drop_rate[i]
        if d >= 1:
            # just in case...
            d = 1
            count_full_drop += 1
            run_length += 1
        elif run_length > 0:
            if init_run_length is None:
                init_run_length = run_length
            else:
                if run_length <= 60:
                    second_runs[run_length - 1] += run_length
                else:
                    minute_runs[min((run_length - 1) // 60 - 1, 59)] += run_length
            run_length = 0
        elif init_run_length is None:
            init_run_length = 0
        tot += d

        if has_obstruct:
            try:
                if history.obstructed[i]:
                    count_obstruct += 1
                    total_obstruct_drop += d
                    if d >= 1:
                        count_full_obstruct += 1
            except (IndexError, TypeError):
                pass
        if has_sched:
            try:
                if not history.scheduled[i]:
                    count_unsched += 1
                    total_unsched_drop += d
                    if d >= 1:
                        count_full_unsched += 1
            except (IndexError, TypeError):
                pass

        down = 0.0
        try:
            down = history.downlink_throughput_bps[i]
        except (AttributeError, IndexError, TypeError):
            pass
        usage_down += down

        up = 0.0
        try:
            up = history.uplink_throughput_bps[i]
        except (AttributeError, IndexError, TypeError):
            pass
        usage_up += up

        rtt = 0.0
        try:
            rtt = history.pop_ping_latency_ms[i]
        except (AttributeError, IndexError, TypeError):
            pass
        # note that "full" here means the opposite of ping drop full
        if d == 0.0:
            rtt_full.append(rtt)
            if down + up > 500000:
                rtt_buckets[min(14, int(math.log2((down + up) / 500000)))].append(rtt)
            else:
                rtt_buckets[0].append(rtt)
        if d < 1.0:
            rtt_all.append((rtt, 1.0 - d))

    # If the entire sample set is one big drop run, it will be both initial
    # fragment (continued from prior sample range) and final one (continued
    # to next sample range), but to avoid double-reporting, just call it
    # the initial run. (Same as upstream.)
    if init_run_length is None:
        init_run_length = run_length
        run_length = 0

    def weighted_mean_and_quantiles(data, n):
        if not data:
            return None, [None] * (n + 1)
        total_weight = sum(x[1] for x in data)
        result = []
        items = iter(data)
        value, accum_weight = next(items)
        accum_value = value * accum_weight
        for boundary in (total_weight * x / n for x in range(n)):
            while accum_weight < boundary:
                try:
                    value, weight = next(items)
                    accum_value += value * weight
                    accum_weight += weight
                except StopIteration:
                    # shouldn't happen, but in case of float precision weirdness...
                    break
            result.append(value)
        result.append(data[-1][0])
        accum_value += sum(x[0] for x in items)
        return accum_value / total_weight, result

    bucket_samples: List[int] = []
    bucket_min: List[Optional[float]] = []
    bucket_median: List[Optional[float]] = []
    bucket_max: List[Optional[float]] = []
    for bucket in rtt_buckets:
        if bucket:
            bucket_samples.append(len(bucket))
            bucket_min.append(min(bucket))
            bucket_median.append(statistics.median(bucket))
            bucket_max.append(max(bucket))
        else:
            bucket_samples.append(0)
            bucket_min.append(None)
            bucket_median.append(None)
            bucket_max.append(None)

    rtt_all.sort(key=lambda x: x[0])
    wmean_all, wdeciles_all = weighted_mean_and_quantiles(rtt_all, 10)
    rtt_full.sort()
    mean_full, deciles_full = weighted_mean_and_quantiles(
        tuple((x, 1.0) for x in rtt_full), 10
    )

    return {
        "samples": parsed_samples,
        "end_counter": current,
    }, {
        "total_ping_drop": tot,
        "count_full_ping_drop": count_full_drop,
        "count_obstructed": count_obstruct,
        "total_obstructed_ping_drop": total_obstruct_drop,
        "count_full_obstructed_ping_drop": count_full_obstruct,
        "count_unscheduled": count_unsched,
        "total_unscheduled_ping_drop": total_unsched_drop,
        "count_full_unscheduled_ping_drop": count_full_unsched,
    }, {
        "init_run_fragment": init_run_length,
        "final_run_fragment": run_length,
        "run_seconds[1,]": second_runs,
        "run_minutes[1,]": minute_runs,
    }, {
        "mean_all_ping_latency": wmean_all,
        "deciles_all_ping_latency[]": wdeciles_all,
        "mean_full_ping_latency": mean_full,
        "deciles_full_ping_latency[]": deciles_full,
        "stdev_full_ping_latency": statistics.pstdev(rtt_full) if rtt_full else None,
    }, {
        "load_bucket_samples[]": bucket_samples,
        "load_bucket_min_latency[]": bucket_min,
        "load_bucket_median_latency[]": bucket_median,
        "load_bucket_max_latency[]": bucket_max,
    }, {
        "download_usage": int(round(usage_down / 8)),
        "upload_usage": int(round(usage_up / 8)),
    }


def get_obstruction_map(context: Optional[ChannelContext] = None) -> dict:
    """Fetch the polar obstruction map (RPC dish_get_obstruction_map=2008).

    Returns num_rows x num_cols SNR matrix + geometry. Row 0 is the lowest
    elevation ring (min_elevation_deg), last row is zenith; columns sweep
    azimuth 0..360.
    """

    def grpc_call(channel):
        resp = _handle(channel, Request(dish_get_obstruction_map={}))
        m = resp.dish_get_obstruction_map
        return {
            "numRows": int(m.num_rows),
            "numCols": int(m.num_cols),
            "snr": [float(v) for v in m.snr],
            "minElevationDeg": float(m.min_elevation_deg),
            "maxThetaDeg": float(m.max_theta_deg),
            "referenceFrame": _enum_name(
                device.ObstructionMapReferenceFrame, m.map_reference_frame
            ),
        }

    try:
        return call_with_channel(grpc_call, context=context)
    except (AttributeError, ValueError, grpc.RpcError) as e:
        raise GrpcError(e) from e


def get_ping(context: Optional[ChannelContext] = None) -> dict:
    """Device-level ping to its configured targets (RPC get_ping=1009)."""

    def grpc_call(channel):
        resp = _handle(channel, Request(get_ping={}))
        out = []
        results = getattr(resp.get_ping, "results", None) or {}
        for name, res in results.items():
            out.append({
                "target": name,
                "dropRate": float(res.drop_rate),
                "latencyMs": float(res.latency_ms),
                "service": res.target.service,
                "location": res.target.location,
                "address": res.target.address,
            })
        return {"targets": out}

    try:
        return call_with_channel(grpc_call, context=context)
    except (AttributeError, ValueError, grpc.RpcError) as e:
        raise GrpcError(e) from e


def start_speedtest(context: Optional[ChannelContext] = None, duration_s: int = 15) -> dict:
    """Ask the dish to run its own speed test (RPC start_speedtest=1027)."""

    def grpc_call(channel):
        _handle(
            channel,
            Request(start_speedtest={"duration_s": duration_s, "send_telemetry": False}),
        )
        return {"started": True, "durationS": duration_s}

    try:
        return call_with_channel(grpc_call, context=context)
    except (AttributeError, ValueError, grpc.RpcError) as e:
        raise GrpcError(e) from e


def get_speedtest_status(context: Optional[ChannelContext] = None) -> dict:
    """Speed test status/results (RPC get_speedtest_status=1028)."""

    def grpc_call(channel):
        resp = _handle(channel, Request(get_speedtest_status={}))
        st = resp.get_speedtest_status.status

        def direction(d):
            if d is None or d.SerializeToString() == b"":
                return None
            tps = [float(v) for v in d.throughputs_mbps]
            return {
                "throughputsMbps": tps,
                "peakMbps": max(tps) if tps else None,
                "err": int(d.err),
            }

        return {
            "running": bool(st.running),
            "id": int(st.id),
            "down": direction(getattr(st, "down", None)),
            "up": direction(getattr(st, "up", None)),
        }

    try:
        return call_with_channel(grpc_call, context=context)
    except (AttributeError, ValueError, grpc.RpcError) as e:
        raise GrpcError(e) from e


def set_power_save(
    start_minutes: int,
    duration_minutes: int,
    enable: bool,
    context: Optional[ChannelContext] = None,
) -> None:
    """Configure dish power save / sleep window (RPC dish_power_save=2013)."""

    def grpc_call(channel):
        _handle(
            channel,
            Request(dish_power_save={
                "power_save_start_minutes": int(start_minutes),
                "power_save_duration_minutes": int(duration_minutes),
                "enable_power_save": bool(enable),
            }),
        )

    try:
        call_with_channel(grpc_call, context=context)
    except (AttributeError, ValueError, grpc.RpcError) as e:
        raise GrpcError(e) from e


def set_gps_inhibit(inhibit: bool, context: Optional[ChannelContext] = None) -> None:
    """Inhibit or re-enable the dish GPS (RPC dish_inhibit_gps=2014).

    Re-enabling GPS is exactly the remedy the diagnostics engine prescribes
    for an inhibited-GPS device that cannot be diagnosed conclusively.
    """

    def grpc_call(channel):
        _handle(channel, Request(dish_inhibit_gps={"inhibit_gps": bool(inhibit)}))

    try:
        call_with_channel(grpc_call, context=context)
    except (AttributeError, ValueError, grpc.RpcError) as e:
        raise GrpcError(e) from e


def reboot(context: Optional[ChannelContext] = None) -> None:
    """Request dish reboot operation (upstream signature)."""

    def grpc_call(channel: grpc.Channel) -> None:
        _handle(channel, Request(reboot={}))
        # response is empty message in this case, so just ignore it

    try:
        call_with_channel(grpc_call, context=context)
    except (AttributeError, ValueError, grpc.RpcError) as e:
        raise GrpcError(e) from e


def set_stow_state(unstow: bool = False, context: Optional[ChannelContext] = None) -> None:
    """Request dish stow or unstow operation (upstream signature)."""

    def grpc_call(channel: grpc.Channel) -> None:
        _handle(channel, Request(dish_stow={"unstow": unstow}))

    try:
        call_with_channel(grpc_call, context=context)
    except (AttributeError, ValueError, grpc.RpcError) as e:
        raise GrpcError(e) from e


# ── router (same Device.Handle service, different host) ─────────────────

def router_status(context: Optional[ChannelContext]) -> dict:
    """Router status + ping stats (RPC wifi_get_status=3004)."""

    def grpc_call(channel):
        resp = _handle(channel, Request(wifi_get_status={}))
        w = resp.wifi_get_status
        return {
            "id": getattr(w.device_info, "id", None),
            "hardwareVersion": getattr(w.device_info, "hardware_version", None),
            "softwareVersion": getattr(w.device_info, "software_version", None),
            "uptimeS": getattr(getattr(w, "device_state", None), "uptime_s", None),
            "wanIp": w.ipv4_wan_address,
            "pingLatencyMs": float(w.ping_latency_ms) if w.HasField("ping_latency_ms") else None,
            "pingDropRate": float(w.ping_drop_rate) if w.HasField("ping_drop_rate") else None,
            "dishPingLatencyMs": (
                float(w.dish_ping_latency_ms) if w.HasField("dish_ping_latency_ms") else None
            ),
            "dishPingDropRate": (
                float(w.dish_ping_drop_rate) if w.HasField("dish_ping_drop_rate") else None
            ),
            "popPingLatencyMs": (
                float(w.pop_ping_latency_ms) if w.HasField("pop_ping_latency_ms") else None
            ),
            "popPingDropRate": (
                float(w.pop_ping_drop_rate) if w.HasField("pop_ping_drop_rate") else None
            ),
        }

    try:
        return call_with_channel(grpc_call, context=context)
    except (AttributeError, ValueError, grpc.RpcError) as e:
        raise GrpcError(e) from e


def router_clients(context: Optional[ChannelContext]) -> list:
    """Connected router clients (RPC wifi_get_clients=3002)."""

    def grpc_call(channel):
        resp = _handle(channel, Request(wifi_get_clients={}))
        out = []
        for c in resp.wifi_get_clients.clients:
            out.append({
                "name": c.given_name or c.name,
                "mac": c.mac_address,
                "ip": c.ip_address,
                "signalDbm": float(c.signal_strength) if c.signal_strength != 0.0 else None,
            })
        return out

    try:
        return call_with_channel(grpc_call, context=context)
    except (AttributeError, ValueError, grpc.RpcError) as e:
        raise GrpcError(e) from e
