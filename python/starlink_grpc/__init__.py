"""starlink_grpc — the Starlink dish gRPC connection layer for V2.1.

Call signatures and data dict keys follow sparky8512/starlink-grpc-tools
(the module `starlink_grpc.py`) wherever they still apply; the protocol
schema is aligned with the CURRENT firmware (API v42).

Differences from upstream (all documented in docs/GRPC.md):
- Protocol classes come from a bundled v42-verified spacex_api_device.proto
  (compiled pb2) instead of runtime gRPC reflection. Same wire protocol,
  same request ids.
- Only the surface V2 needs is ported (no location, no Influx/SQLite).
- v42 additions: obstruction map (2008), device ping targets (1009),
  dish-side speed test (1027/1028), power save schedule (2013),
  GPS inhibit control (2014), router status/clients (3004/3002),
  history outages + per-sample power.
"""

from .core import (
    DEFAULT_ROUTER_TARGET,
    REQUEST_TIMEOUT,
    ChannelContext,
    GrpcError,
    call_with_channel,
    get_history,
    get_id,
    get_obstruction_map,
    get_ping,
    get_speedtest_status,
    get_status,
    history_bulk_data,
    history_ping_stats,
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

__all__ = [
    "DEFAULT_ROUTER_TARGET",
    "REQUEST_TIMEOUT",
    "ChannelContext",
    "GrpcError",
    "call_with_channel",
    "get_status",
    "get_id",
    "status_data",
    "get_history",
    "history_bulk_data",
    "history_ping_stats",
    "history_stats",
    "history_power_stats",
    "outages_from_history",
    "get_obstruction_map",
    "get_ping",
    "start_speedtest",
    "get_speedtest_status",
    "set_power_save",
    "set_gps_inhibit",
    "reboot",
    "set_stow_state",
    "router_status",
    "router_clients",
]
