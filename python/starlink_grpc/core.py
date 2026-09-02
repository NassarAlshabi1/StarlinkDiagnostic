"""Faithful mini-port of sparky8512/starlink-grpc-tools v1.2.5 (starlink_grpc.py).

Only the pieces V2 uses are ported, with identical call signatures and data
dict keys wherever the original defines them:

    ChannelContext, call_with_channel, GrpcError
    get_status / get_id / status_data
    get_history / _compute_sample_range / history_bulk_data / history_ping_stats
    history_stats
    reboot / set_stow_state

Upstream uses gRPC reflection (yagrc) to load protocol classes at runtime.
V2 bundles a protoset-verified spacex_api_device.proto instead (compiled to
pb2 with grpcio-tools 1.59.3, matching the pinned grpcio 1.59.3 runtime), so
the wire protocol and request ids are identical without a reflection round
trip on every cold start. See docs/GRPC.md for the full rationale.
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
    """A wrapper for reusing an open grpc Channel across calls.

    Mirrors upstream v1.2.5: `close()` should be called on the object when
    it is no longer in use.
    """

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
    """Call a function with a grpc.Channel object (same retry semantics as upstream).

    If no context is given, a one-shot channel is used and closed afterwards.
    With a context, a failed reused channel gets exactly one retry on a fresh
    channel, since connectivity may have been lost and restored since last use.
    """
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


def get_status(context: Optional[ChannelContext] = None):
    """Fetch status and return it in grpc structure format (upstream signature)."""

    def grpc_call(channel):
        stub = DeviceStub(channel)
        response = stub.Handle(Request(get_status={}), timeout=REQUEST_TIMEOUT)
        return response.dish_get_status

    return call_with_channel(grpc_call, context=context)


def get_id(context: Optional[ChannelContext] = None) -> str:
    """Fetch dish ID and return it."""

    def grpc_call(channel):
        return get_status(context).device_info.id

    try:
        return grpc_call(None if context is None else context.get_channel()[0])
    except (AttributeError, ValueError, grpc.RpcError) as e:
        raise GrpcError(e) from e


def _scan_unknown_varint(msg, min_field: int = 3):
    """Scan unknown varint fields of a message (protobuf unknown field set).

    Newer firmware may add fields (e.g. DishGpsStats.inhibit_gps) that our
    bundled schema does not know. Upstream sees them via reflection; we see
    them here. Returns a list of (field_number, value) tuples.
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
    """Fetch current status data (same keys as upstream v1.2.5)."""
    try:
        status = get_status(context)
    except (AttributeError, ValueError, grpc.RpcError) as e:
        raise GrpcError(e) from e

    # Upstream derives state from the outage message (read via reflection).
    # Our bundled schema instead carries the DishState enum field, so derive
    # from it; if a firmware outage message ever shows up as an unknown
    # field, we still fall back to the enum.
    try:
        state = device.DishState.Name(status.state)
    except ValueError:
        state = "UNKNOWN"

    # More alerts may be added in future, so in addition to listing them
    # individually, provide a bit field based on field numbers of the
    # DishAlerts message. (Same as upstream.)
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

    # Hardware alert codes (repeated Alert enum). Extended enum values from
    # newer firmware decode fine as ints, which is exactly what the
    # diagnostics engine wants (e.g. code 14 observed for GPS self-test).
    try:
        alert_hw = [int(v) for v in status.alerts_hardware]
    except AttributeError:
        alert_hw = []

    obstruction_duration = None
    obstruction_interval = None
    obstruction_stats = getattr(status, "obstruction_stats", None)
    if obstruction_stats is not None:
        try:
            if (
                obstruction_stats.avg_prolonged_obstruction_duration_s > 0.0
                and not math.isnan(obstruction_stats.avg_prolonged_obstruction_interval_s)
            ):
                obstruction_duration = obstruction_stats.avg_prolonged_obstruction_duration_s
                obstruction_interval = obstruction_stats.avg_prolonged_obstruction_interval_s
        except AttributeError:
            pass

    device_info = getattr(status, "device_info", None)
    gps_stats = getattr(status, "gps_stats", None)
    inhibit_gps = getattr(gps_stats, "inhibit_gps", None)
    inhibit_raw = None
    if inhibit_gps is None and gps_stats is not None:
        # Firmware newer than our bundled schema may send inhibit_gps as an
        # unknown varint field. First unknown 0/1 varint >= field 3 is the
        # best candidate; reported transparently to the UI.
        for fnum, val in _scan_unknown_varint(gps_stats, min_field=3):
            if val in (0, 1):
                inhibit_raw = {"field": fnum, "value": bool(val)}
                inhibit_gps = bool(val)
                break

    eth_speed = getattr(status, "eth_speed_mbps", None)

    return {
        "id": getattr(device_info, "id", None),
        "hardware_version": getattr(device_info, "hardware_version", None),
        "software_version": getattr(device_info, "software_version", None),
        "state": state,
        "uptime": getattr(getattr(status, "device_state", None), "uptime_s", None),
        "snr": None,  # obsoleted in grpc service (upstream keeps the key)
        "seconds_to_first_nonempty_slot": getattr(status, "seconds_to_first_nonempty_slot", None),
        "pop_ping_drop_rate": getattr(status, "pop_ping_drop_rate", None),
        "downlink_throughput_bps": getattr(status, "downlink_throughput_bps", None),
        "uplink_throughput_bps": getattr(status, "uplink_throughput_bps", None),
        "pop_ping_latency_ms": getattr(status, "pop_ping_latency_ms", None),
        "alerts": alert_bits,
        "alert_hw_codes": alert_hw,  # V2 extension: repeated Alert enum values
        "fraction_obstructed": getattr(obstruction_stats, "fraction_obstructed", None),
        "currently_obstructed": getattr(obstruction_stats, "currently_obstructed", None),
        "seconds_obstructed": None,  # obsoleted in grpc service
        "obstruction_duration": obstruction_duration,
        "obstruction_interval": obstruction_interval,
        "direction_azimuth": getattr(status, "boresight_azimuth_deg", None),
        "direction_elevation": getattr(status, "boresight_elevation_deg", None),
        "is_snr_above_noise_floor": getattr(status, "is_snr_above_noise_floor", None),
        "is_snr_persistently_low": getattr(status, "is_snr_persistently_low", None),
        "eth_speed_mbps": eth_speed,
        "stow_requested": getattr(status, "stow_requested", None),
        "gps_ready": getattr(gps_stats, "gps_valid", None),
        "gps_enabled": None if inhibit_gps is None else not inhibit_gps,
        "gps_sats": getattr(gps_stats, "gps_sats", None),
        "gps_inhibit_raw": inhibit_raw,  # V2 extension: unknown-field evidence
    }, {
        "valid_s": getattr(obstruction_stats, "valid_s", None),
        "last_24h_obstructed_s": getattr(obstruction_stats, "last_24h_obstructed_s", None),
        "last_24h_valid_s": getattr(obstruction_stats, "last_24h_valid_s", None),
        "avg_prolonged_obstruction_duration_s": getattr(
            obstruction_stats, "avg_prolonged_obstruction_duration_s", None
        ),
        "avg_prolonged_obstruction_interval_s": getattr(
            obstruction_stats, "avg_prolonged_obstruction_interval_s", None
        ),
        "direction_azimuth": getattr(obstruction_stats, "direction_azimuth", None),
        "direction_elevation": getattr(obstruction_stats, "direction_elevation", None),
    }, alerts


def get_history(context: Optional[ChannelContext] = None):
    """Fetch history data and return it in grpc structure format (upstream signature)."""

    def grpc_call(channel):
        stub = DeviceStub(channel)
        response = stub.Handle(Request(get_history={}), timeout=REQUEST_TIMEOUT)
        return response.dish_get_history

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

        # Upstream marks scheduled/obstructed as obsoleted and returns Nones.
        # Our bundled schema still carries both arrays, so populate them when
        # the dish actually sends them (marked V2 extension).
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

        # Obstructed/unscheduled accounting: upstream v1.2.5 leaves these at
        # zero because the fields were obsoleted upstream; our bundled schema
        # still carries them, so count when the dish sends them (V2 extension
        # matching the pre-obsoletion upstream behavior).
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


def reboot(context: Optional[ChannelContext] = None) -> None:
    """Request dish reboot operation (upstream signature)."""

    def grpc_call(channel: grpc.Channel) -> None:
        stub = DeviceStub(channel)
        stub.Handle(Request(reboot={}), timeout=REQUEST_TIMEOUT)
        # response is empty message in this case, so just ignore it

    try:
        call_with_channel(grpc_call, context=context)
    except (AttributeError, ValueError, grpc.RpcError) as e:
        raise GrpcError(e) from e


def set_stow_state(unstow: bool = False, context: Optional[ChannelContext] = None) -> None:
    """Request dish stow or unstow operation (upstream signature)."""

    def grpc_call(channel: grpc.Channel) -> None:
        stub = DeviceStub(channel)
        stub.Handle(Request(dish_stow={"unstow": unstow}), timeout=REQUEST_TIMEOUT)
        # response is empty message in this case, so just ignore it

    try:
        call_with_channel(grpc_call, context=context)
    except (AttributeError, ValueError, grpc.RpcError) as e:
        raise GrpcError(e) from e
