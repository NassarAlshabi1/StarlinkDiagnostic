"""StarlinkDiagnostic.db — local history store (stdlib sqlite3 only).

Schema follows the V2 spec columns: timestamp, download, upload, latency,
packet_loss, snr, gps, obstruction — plus alerts / hardware_test records and
device metadata, stored in their own tables (cleaner than wide rows).

Everything is stored in Mbps / ms / fractions; timestamps are unix seconds.
No Internet or Cloud involved: the DB file lives in the app-private dir.
"""

import json
import sqlite3
import threading

_lock = threading.RLock()
_conn = None
_path = None

_SCHEMA = """
CREATE TABLE IF NOT EXISTS samples (
    ts          INTEGER PRIMARY KEY,
    download    REAL NOT NULL,   -- Mbps
    upload      REAL NOT NULL,   -- Mbps
    latency     REAL NOT NULL,   -- ms (pop ping)
    packet_loss REAL NOT NULL,   -- 0..1
    snr         REAL,            -- reserved: obsoleted in grpc service
    gps         TEXT,            -- 'ok' | 'no_fix' | 'inhibited' | NULL
    obstruction REAL,            -- 0..1 fraction snapshot
    state       TEXT             -- DishState name
);
CREATE INDEX IF NOT EXISTS idx_samples_ts ON samples(ts);

CREATE TABLE IF NOT EXISTS alerts (
    ts     INTEGER NOT NULL,
    kind   TEXT NOT NULL,        -- alert key or hw code name
    detail TEXT
);
CREATE INDEX IF NOT EXISTS idx_alerts_ts ON alerts(ts);

CREATE TABLE IF NOT EXISTS tests (
    ts     INTEGER NOT NULL,
    kind   TEXT NOT NULL,        -- 'hardware_test' | 'net_test' | 'full_diagnostic'
    result TEXT NOT NULL,        -- 'PASSED' | 'FAILED' | 'WARN' | 'UNKNOWN'
    detail TEXT                  -- JSON blob of the assessment
);
CREATE INDEX IF NOT EXISTS idx_tests_ts ON tests(ts);

CREATE TABLE IF NOT EXISTS meta (
    key   TEXT PRIMARY KEY,
    value TEXT
);
"""


def open_db(path):
    """Open (and create) the database. Safe to call again with same path."""
    global _conn, _path
    with _lock:
        if _conn is not None and _path == path:
            return _path
        if _conn is not None:
            try:
                _conn.close()
            except Exception:
                pass
        _conn = sqlite3.connect(path, check_same_thread=False)
        _conn.execute("PRAGMA journal_mode=WAL")
        _conn.executescript(_SCHEMA)
        _conn.commit()
        _path = path
        return _path


def close_db():
    global _conn, _path
    with _lock:
        if _conn is not None:
            try:
                _conn.close()
            except Exception:
                pass
        _conn = None
        _path = None


def _require():
    if _conn is None:
        raise RuntimeError("DB not initialized — call open_db() first")
    return _conn


def store_samples(rows):
    """rows: list of dicts with keys ts, download, upload, latency,
    packet_loss, snr, gps, obstruction, state."""
    if not rows:
        return 0
    with _lock:
        c = _require()
        c.executemany(
            "INSERT OR REPLACE INTO samples"
            "(ts, download, upload, latency, packet_loss, snr, gps, obstruction, state)"
            " VALUES (?,?,?,?,?,?,?,?,?)",
            [
                (
                    int(r["ts"]),
                    float(r.get("download") or 0.0),
                    float(r.get("upload") or 0.0),
                    float(r.get("latency") or 0.0),
                    float(r.get("packet_loss") or 0.0),
                    r.get("snr"),
                    r.get("gps"),
                    r.get("obstruction"),
                    r.get("state"),
                )
                for r in rows
            ],
        )
        c.commit()
        return len(rows)


def store_alerts(ts, items):
    """items: list of (kind, detail) tuples or dicts {kind, detail}."""
    if not items:
        return 0
    with _lock:
        c = _require()
        c.executemany(
            "INSERT INTO alerts(ts, kind, detail) VALUES (?,?,?)",
            [
                (
                    int(ts),
                    str(it["kind"]),
                    json.dumps(it["detail"], ensure_ascii=False) if not isinstance(it["detail"], str) else it["detail"],
                )
                for it in items
            ],
        )
        c.commit()
        return len(items)


def store_test(ts, kind, result, detail):
    with _lock:
        c = _require()
        c.execute(
            "INSERT INTO tests(ts, kind, result, detail) VALUES (?,?,?,?)",
            (int(ts), kind, result, json.dumps(detail, ensure_ascii=False)),
        )
        c.commit()


def set_meta(key, value):
    with _lock:
        c = _require()
        c.execute(
            "INSERT OR REPLACE INTO meta(key, value) VALUES (?,?)",
            (str(key), str(value)),
        )
        c.commit()


def set_meta_bulk(pairs):
    with _lock:
        c = _require()
        c.executemany(
            "INSERT OR REPLACE INTO meta(key, value) VALUES (?,?)",
            [(str(k), str(v)) for k, v in pairs.items()],
        )
        c.commit()


def get_meta(key, default=None):
    with _lock:
        c = _require()
        row = c.execute("SELECT value FROM meta WHERE key=?", (str(key),)).fetchone()
        return row[0] if row else default


def series(from_ts=0, to_ts=None):
    """Time-ordered samples for charting."""
    with _lock:
        c = _require()
        if to_ts is None:
            rows = c.execute(
                "SELECT ts, download, upload, latency, packet_loss, snr, gps,"
                " obstruction, state FROM samples WHERE ts >= ? ORDER BY ts ASC",
                (int(from_ts),),
            ).fetchall()
        else:
            rows = c.execute(
                "SELECT ts, download, upload, latency, packet_loss, snr, gps,"
                " obstruction, state FROM samples WHERE ts >= ? AND ts <= ?"
                " ORDER BY ts ASC",
                (int(from_ts), int(to_ts)),
            ).fetchall()
    return [
        {
            "ts": r[0],
            "download": r[1],
            "upload": r[2],
            "latency": r[3],
            "packetLoss": r[4],
            "snr": r[5],
            "gps": r[6],
            "obstruction": r[7],
            "state": r[8],
        }
        for r in rows
    ]


def recent_tests(limit=20):
    with _lock:
        c = _require()
        rows = c.execute(
            "SELECT ts, kind, result, detail FROM tests ORDER BY ts DESC LIMIT ?",
            (int(limit),),
        ).fetchall()
    out = []
    for r in rows:
        try:
            detail = json.loads(r[3])
        except Exception:
            detail = r[3]
        out.append({"ts": r[0], "kind": r[1], "result": r[2], "detail": detail})
    return out


def recent_alerts(limit=50):
    with _lock:
        c = _require()
        rows = c.execute(
            "SELECT ts, kind, detail FROM alerts ORDER BY ts DESC LIMIT ?",
            (int(limit),),
        ).fetchall()
    out = []
    for r in rows:
        try:
            detail = json.loads(r[2])
        except Exception:
            detail = r[2]
        out.append({"ts": r[0], "kind": r[1], "detail": detail})
    return out


def summary():
    with _lock:
        c = _require()
        n, lo, hi = c.execute(
            "SELECT COUNT(*), MIN(ts), MAX(ts) FROM samples"
        ).fetchone()
        ntests = c.execute("SELECT COUNT(*) FROM tests").fetchone()[0]
        nalerts = c.execute("SELECT COUNT(*) FROM alerts").fetchone()[0]
    return {
        "samples": n or 0,
        "firstTs": lo,
        "lastTs": hi,
        "tests": ntests,
        "alerts": nalerts,
    }


def trim(keep_seconds=7 * 86400):
    """Delete samples older than keep_seconds. Returns deleted row count."""
    with _lock:
        c = _require()
        hi = c.execute("SELECT MAX(ts) FROM samples").fetchone()[0]
        if hi is None:
            return 0
        cutoff = int(hi) - int(keep_seconds)
        cur = c.execute("DELETE FROM samples WHERE ts < ?", (cutoff,))
        c.commit()
        return cur.rowcount if cur.rowcount and cur.rowcount > 0 else 0
