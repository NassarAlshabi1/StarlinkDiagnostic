"""starlink_grpc — the Starlink dish gRPC connection layer for V2.

Derived from sparky8512/starlink-grpc-tools v1.2.5 (the module
`starlink_grpc.py`), which is the part of that project explicitly designed
to be called from other applications. Function names, signatures and the
data dict keys mirror the original so behavior is verifiable against it.

Differences from upstream (all documented in docs/GRPC.md):
- Protocol classes come from a bundled, protoset-verified
  spacex_api_device.proto (compiled pb2) instead of runtime gRPC
  reflection. Same wire protocol, same request ids
  (get_status=1004, get_history=1007, reboot=1001, dish_stow=2002).
- Only the surface V2 needs is ported (no location, sleep config,
  obstruction map, or Influx/SQLite scripts).
- Small, clearly-marked extensions for fields our bundled schema carries
  (scheduled/obstructed sample flags) and for firmware fields that may
  arrive as unknown fields (e.g. gps inhibit), which upstream reads via
  reflection.
"""

from .core import (
    REQUEST_TIMEOUT,
    ChannelContext,
    GrpcError,
    call_with_channel,
    get_history,
    get_id,
    get_status,
    history_bulk_data,
    history_ping_stats,
    history_stats,
    reboot,
    set_stow_state,
    status_data,
)

__all__ = [
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
    "reboot",
    "set_stow_state",
]
