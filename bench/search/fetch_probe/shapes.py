"""Statement shapes of the P5a fetch probe (QoD Search Phase 0 part 2): the 10-rowid fetch of
drain.py and the one-statement join-back shapes of spec 7.7, over acme_lake.main.pages_10m.
Shared by engine_fetch.py (DuckDB in-process) and flight_fetch.py (ADBC Flight SQL)."""
import math

TABLE = "acme_lake.main.pages_10m"
ROWS = 10_000_000
K = 10
# A fixed rowid set for the "same text repeated" shape (drawn once, kept constant).
SAME = [1234567, 2345678, 3456789, 4567890, 5678901, 6789012, 7890123, 8901234, 9012345, 123456]


def in_list(ids):
    return f"SELECT id, title FROM {TABLE} WHERE rowid IN ({', '.join(map(str, ids))})"


def cte_join(ids):
    values = ", ".join(f"({i})" for i in ids)
    return (f"WITH hits(rowid) AS (VALUES {values}) "
            f"SELECT p.id, p.title FROM hits JOIN {TABLE} p ON p.rowid = hits.rowid")


def in_subq(ids):
    values = ", ".join(f"({i})" for i in ids)
    return (f"SELECT id, title FROM {TABLE} "
            f"WHERE rowid IN (SELECT rowid FROM (VALUES {values}) v(rowid))")


def probe(ids):
    """The gateway's GetFlightInfo schema probe of in_list (PrepareStrategy.ProbeWrap)."""
    return f"SELECT * FROM ({in_list(ids)}) AS _qod_probe LIMIT 0"


PREPARED = f"SELECT id, title FROM {TABLE} WHERE rowid IN ({', '.join('?' * K)})"
PREPARED_DOLLAR = f"SELECT id, title FROM {TABLE} WHERE rowid IN ({', '.join(f'${i + 1}' for i in range(K))})"


def percentile(samples, q):
    """Nearest rank: the ceil(q n)-th smallest sample (Q12's convention)."""
    s = sorted(samples)
    return s[max(0, math.ceil(q * len(s)) - 1)]


def summary(samples):
    return {"p50_ms": round(percentile(samples, 0.50), 3),
            "p95_ms": round(percentile(samples, 0.95), 3),
            "n": len(samples)}
