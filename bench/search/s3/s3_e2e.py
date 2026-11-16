#!/usr/bin/env python3
"""Search end to end on an s3:// data path (QoD Search Phase 0 part 2, Task 15a, Steps 3 to 5).

Usage:
  uv run --no-project --with adbc-driver-flightsql --with pyarrow python -I s3_e2e.py <p2> [part ...]

Parts (all, in this order, by default): isolation, probe, phases.
  isolation  Step 5. On the bis3 node, as the superuser: the name, type and scope of every secret
             (never secret_string, never a setting). As plain and rls10 through Flight SQL: the
             read_blob and glob forms on the s3 table's bundle folder and data path, and on the local
             lake's bundle folder; each must be refused, never answered.
  probe      The HTTP log and the store's metrics on a superuser read of the s3 table: checks that
             both counters work before the phases rely on them.
  phases     Step 3: P0 indexed, P3 fetch, P1 fallback, P2 liveness, each on the s3 table
             (acme_lakes3.main.pages_1m, pool bis3) and its local copy
             (acme_lake.main.pages_1m_s3cmp, pool bi), for plain, rls10 and other, w0 and w1 w10;
             a difference between the two tables stops the run (status 3). With it, Step 4 at
             each phase's state: HTTP requests per URL class, cold (after a restart of the bis3
             node) and warm, for plain and w0, from DuckDB's HTTP log on the bis3 node (method, URL
             path and the response's Content-Length only: no request header, no query string) and
             the store's SeaweedFS_s3_request_total; then the labelled estimate (summary).

Flight SQL with TLS on 127.0.0.1:27338 (the edge's self-signed certificate not verified, as the qod
CLI does), one reused connection per user and pool, tenant and pool as call headers (the q12
pattern). Passwords from <p2>/secrets/<user>.txt, never printed. Writes every observation to
<p2>/t15a/s3-e2e.json and prints one PASS or FAIL line per check. Exit 0 when every check passes.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import json  # noqa: E402
import re  # noqa: E402
import time  # noqa: E402
import urllib.error  # noqa: E402
import urllib.request  # noqa: E402

import adbc_driver_flightsql.dbapi as flight  # noqa: E402
from adbc_driver_flightsql import DatabaseOptions  # noqa: E402

from cells import QUERIES, Cell, qod_sql  # noqa: E402

FLIGHT = "grpc+tls://127.0.0.1:27338"
REST = "http://127.0.0.1:27900"
METRICS = "http://127.0.0.1:27468/metrics"
TENANT = "acme"
HDR = DatabaseOptions.RPC_CALL_HEADER_PREFIX.value
BUCKET = "qod-p2-search"
S3 = {"pool": "bis3", "db": "acme_lakes3", "cat": "acme_lakes3", "size": "1m",
      "table": "acme_lakes3.main.pages_1m", "name": "pages_1m"}
LOCAL = {"pool": "bi", "db": "acme_lake", "cat": "acme_lake", "size": "1m_s3cmp",
         "table": "acme_lake.main.pages_1m_s3cmp", "name": "pages_1m_s3cmp"}
DATA_PATH = f"s3://{BUCKET}/acme_lakes3/"
RTT_MS = 20  # a stated assumption (same-region S3 GET time to first byte), not measured here

P2 = sys.argv[1]
PARTS = sys.argv[2:] or ["isolation", "probe", "phases"]
OUT = os.path.join(P2, "t15a", "s3-e2e.json")
record = {"checks": [], "isolation": {}, "phases": {}, "requests": []}
conns = {}


def secret(name):
    with open(os.path.join(P2, "secrets", f"{name}.txt"), encoding="utf-8") as f:
        return f.read().strip()


def conn(user, pool, superuser=False):
    key = (user, pool, superuser)
    if key not in conns:
        kw = {"username": user, "password": secret(user),
              DatabaseOptions.TLS_SKIP_VERIFY.value: "true",
              HDR + "tenant": TENANT, HDR + "pool": pool}
        if superuser:
            kw[HDR + "superuser"] = "true"
        conns[key] = flight.connect(FLIGHT, db_kwargs=kw, autocommit=True)
    return conns[key]


def su(pool):
    return conn("admin", pool, True)


def run(c, sql):
    """(rows, None) or (None, the error on one line)."""
    try:
        with c.cursor() as cur:
            cur.execute(sql)
            try:
                return cur.fetchall(), None
            except Exception as exc:  # noqa: BLE001 - no result set, or an error while fetching
                if cur.description is None:
                    return [], None
                return None, " ".join(str(exc).split())[:700]
    except Exception as exc:  # noqa: BLE001 - the error is the observation
        return None, " ".join(str(exc).split())[:700]


def must(c, sql):
    rows, err = run(c, sql)
    if err:
        raise RuntimeError(f"{sql[:120]}: {err}")
    return rows


def check(label, ok, observed):
    record["checks"].append({"check": label, "pass": bool(ok), "observed": observed})
    print(f"{'PASS' if ok else 'FAIL'} {label}: {observed}", flush=True)
    return ok


def save():
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(record, f, indent=1, default=str)


def rest(method, path, body=None, token=None, timeout=120):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(REST + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("X-API-Key", token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, {"error": f"http_{e.code}", "message": raw[:300]}


_token = []


def token():
    if not _token:
        status, body = rest("POST", "/api/auth/login",
                            {"username": "admin", "password": secret("admin")})
        if status != 200:
            raise RuntimeError(f"login failed: {status}")
        _token.append(body["token"])
    return _token[0]


# ----- statements --------------------------------------------------------------------------


def search_sql(t, user, qkey):
    form, inp = ("policy", "10%") if user == "rls10" else ("where", "100%")
    return qod_sql(Cell(t["size"], inp, form, qkey), t["cat"])


def hits_sql(t, qkey):
    """The search with the join-back removed (plain, no filter)."""
    table = t["table"]
    return (f"SELECT h.rowid, h.score FROM qod_search((SELECT rowid FROM {table}), '{table}', "
            f"['body'], '{QUERIES[qkey]}', k := 10) h ORDER BY h.score DESC, h.rowid")


def fetch_sql(t, rowids):
    return f"SELECT id, title FROM {t['table']} WHERE rowid IN ({', '.join(map(str, rowids))})"


# ----- counters ----------------------------------------------------------------------------


def log_enable():
    must(su(S3["pool"]), "CALL enable_logging('HTTP')")


def log_mark():
    return must(su(S3["pool"]), "SELECT epoch_us(get_current_timestamp())")[0][0]


def log_read(t0, t1):
    """The HTTP requests the bis3 node logged between two marks: method, URL path (no query
    string), status, the response's Content-Length, start time (us) and duration. No request header
    is read, so no signature."""
    rows = must(su(S3["pool"]), (
        "SELECT request.type, split_part(request.url, '?', 1), response.status, "
        "list_filter(map_entries(response.headers), lambda e: lower(e.key) = 'content-length')[1].value, "
        "epoch_us(request.start_time), request.duration_ms "
        "FROM duckdb_logs_parsed('HTTP') "
        f"WHERE epoch_us(timestamp) BETWEEN {t0} AND {t1} ORDER BY request.start_time"))
    out = []
    for method, url, status, length, start, dur in rows:
        path = re.sub(r"^https?://[^/]+", "", url or "")
        out.append({"method": method, "path": path, "status": status,
                    "bytes": int(length) if length and str(length).isdigit() else 0,
                    "start_us": start, "ms": dur})
    return out


def store_total():
    try:
        with urllib.request.urlopen(METRICS, timeout=10) as r:
            text = r.read().decode()
    except Exception:  # noqa: BLE001
        return None
    total = 0.0
    for line in text.splitlines():
        if line.startswith("SeaweedFS_s3_request_total"):
            total += float(line.rsplit(" ", 1)[1])
    return total


def measured(c, sql):
    """Runs `sql` on connection `c` with both counters around it."""
    m0, t0 = store_total(), log_mark()
    started = time.perf_counter()
    rows, err = run(c, sql)
    ms = round((time.perf_counter() - started) * 1000, 1)
    t1, m1 = log_mark(), store_total()
    reqs = log_read(t0, t1)
    return {"rows": rows, "error": err, "ms": ms, "requests": reqs,
            "store_delta": None if m0 is None or m1 is None else m1 - m0}


def waves(reqs):
    """Groups of requests started within 1 ms of the previous one."""
    starts = sorted(r["start_us"] for r in reqs if r["start_us"] is not None)
    n, last = 0, None
    for s in starts:
        if last is None or s - last > 1000:
            n += 1
        last = s
    return n


def classify(reqs, fallback_file=None):
    out = {"bundle": [0, 0], "fallback": [0, 0], "data": [0, 0], "other": [0, 0]}
    for r in reqs:
        p = r["path"]
        if "/.qod_search/" in p:
            k = "bundle"
        elif fallback_file and p.endswith(fallback_file):
            k = "fallback"
        elif p.endswith(".parquet"):
            k = "data"
        else:
            k = "other"
        out[k][0] += 1
        out[k][1] += r["bytes"]
    return out


# ----- node restart (the cold runs) ----------------------------------------------------------


def s3_node():
    status, body = rest("GET", "/api/pool/list", token=token())
    mine = [p for p in body["pools"] if p["tenantDb"] == S3["db"] and p["pool"] == S3["pool"]]
    return mine[0]["nodes"][0] if mine and mine[0]["nodes"] else None


def restart_s3_node():
    node = s3_node()
    status, body = rest("POST", "/api/node/restart",
                        {"tenant": TENANT, "tenantDb": S3["db"], "pool": S3["pool"],
                         "nodeId": node["nodeId"]}, token=token(), timeout=300)
    if status not in (200, 204):
        raise RuntimeError(f"node restart: {status} {body}")
    # The old process has HTTP logging on; a fresh one does not, which tells the two apart.
    deadline = time.time() + 180
    started = time.time()
    while time.time() < deadline:
        time.sleep(2)
        n = s3_node()
        if n and n.get("healthy"):
            rows, err = run(su(S3["pool"]), "SELECT current_setting('enabled_log_types')")
            if rows and "HTTP" not in str(rows[0][0] or ""):
                log_enable()
                record.setdefault("restarts_s", []).append(round(time.time() - started, 1))
                return
    raise RuntimeError("the bis3 node did not come back within 180 s")


# ----- Step 5 ------------------------------------------------------------------------------


def isolation():
    rows = must(su(S3["pool"]), "SELECT name, type, scope FROM duckdb_secrets() ORDER BY name")
    secrets_ = [{"name": n, "type": t, "scope": list(s or [])} for n, t, s in rows]
    record["isolation"]["secrets"] = secrets_
    for s in secrets_:
        print(f"  secret {s['name']} type={s['type']} scope={s['scope']}", flush=True)
    store = [s for s in secrets_ if s["name"] == "qod_db_store"]
    want = {DATA_PATH, f"s3://{BUCKET}/acme_lakes3__br_"}
    check("qod_db_store scoped to the data path and its branch prefix",
          len(store) == 1 and set(store[0]["scope"]) == want, store)
    wide = [s for s in secrets_ if s["type"] == "s3" and (
        not s["scope"] or any(x.rstrip("/") in ("s3:/", "s3:", f"s3://{BUCKET}") for x in s["scope"]))]
    check("no s3 secret unscoped or scoped to the bucket root", not wide, wide)
    data = [s for s in secrets_ if s["name"] == "qod_data_s3"
            and any(x.startswith(f"s3://{BUCKET}") for x in s["scope"])]
    check("no qod_data_s3 covers the bucket", not data, data)

    status, dbs = rest("GET", f"/api/database/list?tenant={TENANT}", token=token())
    local_path = [d for d in dbs["tenantDbs"] if d["name"] == LOCAL["db"]][0]["effectiveDataPath"]
    local_bundles = local_path.rstrip("/") + "/.qod_search"
    forms = [
        (S3, f"SELECT count(*) FROM read_blob('{DATA_PATH}.qod_search/*/*')"),
        (S3, f"SELECT * FROM glob('{DATA_PATH}**')"),
        (LOCAL, f"SELECT count(*) FROM read_blob('{local_bundles}/*/*')"),
        (LOCAL, f"SELECT * FROM glob('{local_bundles}/**')"),
    ]
    record["isolation"]["reads"] = []
    for user in ("plain", "rls10"):
        for t, sql in forms:
            rows, err = run(conn(user, t["pool"]), sql)
            shown = sql.replace(P2, "<p2>")
            refused = rows is None and (
                "authorized locations" in err or "Permission Error" in err)
            record["isolation"]["reads"].append(
                {"user": user, "pool": t["pool"], "sql": shown,
                 "answer": f"{len(rows)} rows" if rows is not None else err.replace(P2, "<p2>")})
            check(f"[{user}@{t['pool']}] refused: {shown}", refused,
                  f"{len(rows)} rows ANSWERED" if rows is not None else err.replace(P2, "<p2>"))


# ----- probe -------------------------------------------------------------------------------


def probe():
    log_enable()
    m = measured(su(S3["pool"]), f"SELECT count(*), sum(f10) FROM {S3['table']} WHERE id < 100")
    reqs = m["requests"]
    record["probe"] = {"error": m["error"], "rows": m["rows"], "log_requests": len(reqs),
                       "store_delta": m["store_delta"], "classes": classify(reqs)}
    for r in reqs[:20]:
        print(f"  {r['method']} {r['path']} {r['status']} {r['bytes']} B {r['ms']} ms", flush=True)
    check("probe: the HTTP log counts the read's requests",
          m["error"] is None and len(reqs) > 0, f"{len(reqs)} logged, store {m['store_delta']}")
    check("probe: the store's request total moved by the logged count",
          m["store_delta"] == len(reqs), f"log {len(reqs)}, store {m['store_delta']}")


# ----- Step 3 ------------------------------------------------------------------------------


def pairs(rows):
    return [(int(r[0]), float(r[2])) for r in rows]


def compare_all(phase, extra=None):
    """Every user and query on both tables; returns {(user, q): pairs} of the s3 table."""
    got = {}
    for user in ("plain", "rls10", "other"):
        for q in QUERIES:
            a, ea = run(conn(user, S3["pool"]), search_sql(S3, user, q))
            b, eb = run(conn(user, LOCAL["pool"]), search_sql(LOCAL, user, q))
            label = f"{phase} [{user}] {QUERIES[q]}"
            entry = {"phase": phase, "user": user, "query": QUERIES[q],
                     "s3": pairs(a) if a is not None else ea,
                     "local": pairs(b) if b is not None else eb}
            record["phases"].setdefault(phase, []).append(entry)
            if user == "other":
                check(label + ": refused on both", a is None and b is None
                      and "access denied" in ea.lower() and S3["name"] in ea
                      and "access denied" in eb.lower() and LOCAL["name"] in eb,
                      f"s3: {ea or len(a)}; local: {eb or len(b)}")
                continue
            if a is None or b is None:
                check(label + ": answered on both", False, f"s3: {ea}; local: {eb}")
                continue
            pa, pb = pairs(a), pairs(b)
            ok = len(pa) == 10 and pa == pb
            detail = f"s3 {pa[:3]}... local {pb[:3]}..." if not ok else f"ids {[i for i, _ in pa]}"
            check(label + ": 10 rows, identical (id, score) on both tables", ok, detail)
            if user == "rls10":
                check(label + ": every id % 10 == 0", all(i % 10 == 0 for i, _ in pa),
                      [i for i, _ in pa])
            if extra:
                extra(label, pa)
            got[(user, q)] = pa
    return got


def list_files(t):
    rows = must(su(t["pool"]), f"SELECT data_file FROM ducklake_list_files('{t['cat']}', "
                               f"'{t['name']}', schema => 'main') ORDER BY data_file")
    return [r[0] for r in rows]


def build(t):
    status, body = rest("POST", "/api/search/prototype/build-segments",
                        {"tenant": TENANT, "database": t["db"], "pool": t["pool"],
                         "table": t["name"], "column": "body"}, token=token(), timeout=1800)
    if status == 200:
        with open(os.path.join(P2, "t15a", f"build-{t['db']}.json"), "w", encoding="utf-8") as f:
            json.dump(body, f, indent=1)
    return check(f"build-segments {t['table']}", status == 200,
                 f"{status} files={len(body.get('files', []))}" if status == 200
                 else f"{status} {body.get('error')}: {body.get('message')}")


def liveness_count(m, fallback=None):
    c = classify(m["requests"], fallback)
    return c["data"][0]


class Difference(Exception):
    """A difference between the two tables: stop, record, tear down (lead's rule)."""


def stop_on_difference(phase):
    bad = [c for c in record["checks"] if not c["pass"] and c["check"].startswith(phase + " ")]
    if bad:
        raise Difference(f"{phase}: {len(bad)} check(s) failed, first: {bad[0]['check']}")


def measure_state(phase, fallback=None, fetch_rowids=None):
    """Step 4 at the current state of the s3 table, for plain and w0: the search, the search with
    the join-back removed, and (on P0's state) P3's fetch, each cold (first statement after a
    restart of the bis3 node, so the open-segment, liveness and verdict caches are empty) and warm
    (the same statement again)."""
    c = conn("plain", S3["pool"])
    shapes = [("search", search_sql(S3, "plain", "common")),
              ("search without join-back", hits_sql(S3, "common"))]
    if fetch_rowids:
        shapes.append(("fetch", fetch_sql(S3, fetch_rowids)))
    for shape, sql in shapes:
        restart_s3_node()
        for temp in ("cold", "warm"):
            m = measured(c, sql)
            reqs = m["requests"]
            entry = {"phase": phase, "shape": shape, "temp": temp, "ms": m["ms"],
                     "error": m["error"], "rows": len(m["rows"]) if m["rows"] is not None else None,
                     "requests": len(reqs), "store_delta": m["store_delta"], "waves": waves(reqs),
                     "classes": classify(reqs, fallback), "bytes": sum(r["bytes"] for r in reqs),
                     "detail": [(r["method"], r["path"].replace(f"/{BUCKET}/acme_lakes3/", ""),
                                 r["status"], r["bytes"], r["start_us"], r["ms"]) for r in reqs]}
            record["requests"].append(entry)
            print(f"  [{phase}] {shape} {temp}: {len(reqs)} requests ({entry['bytes']} B) in "
                  f"{entry['waves']} waves, store {m['store_delta']}, {m['ms']} ms, "
                  f"classes {entry['classes']}{', ERROR ' + m['error'] if m['error'] else ''}",
                  flush=True)
            check(f"Step 4 [{phase}] {shape} {temp}: answered, log and store agree",
                  m["error"] is None and m["store_delta"] == len(reqs),
                  f"rows {entry['rows']}, log {len(reqs)}, store {m['store_delta']}")
            save()


def phases():
    corpus = os.path.join(P2, "corpus", "pages_1m.parquet")
    log_enable()
    # P0 indexed.
    compare_all("P0")
    stop_on_difference("P0")
    rows = must(conn("plain", S3["pool"]), hits_sql(S3, "common"))
    rowids = [int(r[0]) for r in rows]
    record["p3_rowids"] = rowids

    # P3 fetch (on P0's state, before any write).
    a, ea = run(conn("plain", S3["pool"]), fetch_sql(S3, rowids))
    b, eb = run(conn("plain", LOCAL["pool"]), fetch_sql(LOCAL, rowids))
    record["phases"]["P3"] = {"rowids": rowids, "s3": a if a is not None else ea,
                              "local": b if b is not None else eb}
    check("P3 [plain] fetch of P0's 10 rowids: 10 rows, identical on both tables",
          a is not None and b is not None and len(a) == 10 and sorted(a) == sorted(b),
          f"s3 {len(a) if a is not None else ea}; local {len(b) if b is not None else eb}")
    stop_on_difference("P3")
    measure_state("P0", fetch_rowids=rowids)

    # P1 fallback: one unbundled 20,000-row file on each table.
    before = {n: list_files(t) for n, t in (("s3", S3), ("local", LOCAL))}
    for t in (S3, LOCAL):
        must(su(t["pool"]), "SET threads = 1")
        try:
            must(su(t["pool"]), f"INSERT INTO {t['table']} SELECT * FROM read_parquet('{corpus}') "
                                f"WHERE id < 20000 ORDER BY id")
        finally:
            must(su(t["pool"]), "SET threads = 16")
    new = {n: [f for f in list_files(t) if f not in before[n]]
           for n, t in (("s3", S3), ("local", LOCAL))}
    record["p1_new_files"] = {k: [os.path.basename(f) for f in v] for k, v in new.items()}
    check("Setup P1: one new data file on each table", all(len(v) == 1 for v in new.values()),
          record["p1_new_files"])
    fallback = os.path.basename(new["s3"][0])
    record["p1_fallback_file"] = fallback
    fb = compare_all("P1 fallback")
    stop_on_difference("P1 fallback")
    measure_state("P1 fallback", fallback)
    build(S3)
    build(LOCAL)
    ix = compare_all("P1 indexed")
    check("P1 indexed: the fallback's answers equal the indexed path's", fb == ix,
          "equal" if fb == ix else
          {str(k): (fb.get(k), ix.get(k)) for k in fb if fb.get(k) != ix.get(k)})
    stop_on_difference("P1 indexed")
    measure_state("P1 indexed", fallback)

    # P2 liveness: a delete file on each table, then the cache hit, then a new S.
    for t in (S3, LOCAL):
        must(su(t["pool"]), f"DELETE FROM {t['table']} WHERE id % 100 = 7")
    c = conn("plain", S3["pool"])
    m1 = measured(c, hits_sql(S3, "common"))
    m2 = measured(c, hits_sql(S3, "common"))
    l1, l2 = liveness_count(m1, fallback), liveness_count(m2, fallback)
    record["p2_liveness"] = {"first": classify(m1["requests"], fallback),
                             "second": classify(m2["requests"], fallback)}
    check("Liveness P2: the first search after the delete reads liveness",
          m1["error"] is None and l1 > 0, f"{l1} data or delete file requests {m1['error'] or ''}")
    check("Liveness P2: the second search reads no liveness (3c cache hit)",
          m2["error"] is None and l2 == 0, f"{l2} data or delete file requests")

    def no_deleted(label, pa):
        check(label + ": no deleted id", all(i % 100 != 7 for i, _ in pa),
              [i for i, _ in pa if i % 100 == 7])

    compare_all("P2", no_deleted)
    stop_on_difference("P2")
    measure_state("P2", fallback)
    for t in (S3, LOCAL):
        must(su(t["pool"]), f"CREATE TABLE {t['cat']}.main.bump AS SELECT 1 AS x")
    m3 = measured(c, hits_sql(S3, "common"))
    m4 = measured(c, hits_sql(S3, "common"))
    l3, l4 = liveness_count(m3, fallback), liveness_count(m4, fallback)
    record["p2_liveness"]["after_bump"] = classify(m3["requests"], fallback)
    record["p2_liveness"]["after_bump_again"] = classify(m4["requests"], fallback)
    check("Liveness P2 bump: after a commit to another table the search reads liveness again (a new S)",
          m3["error"] is None and l3 > 0, f"{l3} data or delete file requests")
    check("Liveness P2 bump: and the next search hits the cache again",
          m4["error"] is None and l4 == 0, f"{l4} data or delete file requests")
    compare_all("P2 bump", no_deleted)
    stop_on_difference("P2 bump")
    summarize()


def summarize():
    """Step 4's table: per phase and temperature, requests (bytes) per class. Bundle reads and
    liveness from the search without the join-back; fetch = the search's data-file requests less
    the search without join-back's; the fallback scan = the P1 file's requests in the search
    without join-back at P1 fallback, less the same at P1 indexed; P3 = the fetch statement."""
    by = {(e["phase"], e["shape"], e["temp"]): e for e in record["requests"]}
    rows = []
    for phase in ("P0", "P1 fallback", "P1 indexed", "P2"):
        for temp in ("cold", "warm"):
            full, hits = by.get((phase, "search", temp)), by.get((phase, "search without join-back", temp))
            if not full or not hits:
                continue
            hc, fc = hits["classes"], full["classes"]
            fetch = [fc["data"][0] + fc["fallback"][0] - hc["data"][0] - hc["fallback"][0],
                     fc["data"][1] + fc["fallback"][1] - hc["data"][1] - hc["fallback"][1]]
            row = {"phase": phase, "temp": temp, "bundle": hc["bundle"], "liveness": hc["data"],
                   "fallback_file": hc["fallback"], "fetch": fetch, "other": hc["other"],
                   "search_total": [full["requests"], full["bytes"]], "search_waves": full["waves"],
                   "search_ms": full["ms"], "hits_ms": hits["ms"]}
            rows.append(row)
    for temp in ("cold", "warm"):
        f = by.get(("P0", "fetch", temp))
        if f:
            rows.append({"phase": "P3 fetch", "temp": temp, "fetch": [f["requests"], f["bytes"]],
                         "search_total": [f["requests"], f["bytes"]], "search_waves": f["waves"],
                         "search_ms": f["ms"]})
    for r in rows:
        n, w = r["search_total"][0], r["search_waves"]
        r["estimate_serial_ms"] = n * RTT_MS
        r["estimate_waves_ms"] = w * RTT_MS
        print(f"  {r}", flush=True)
    record["summary"] = rows


def main():
    status = 0
    try:
        for part in PARTS:
            print(f"== {part}", flush=True)
            {"isolation": isolation, "probe": probe, "phases": phases}[part]()
            save()
            if part == "isolation" and not all(c["pass"] for c in record["checks"]):
                print("STOP: an isolation check failed", flush=True)
                status = 2
                break
    except Difference as exc:
        print(f"STOP: a difference between the two tables: {exc}", flush=True)
        status = 3
    except Exception as exc:  # noqa: BLE001 - reported, then teardown runs regardless
        print(f"ERROR {type(exc).__name__}: {' '.join(str(exc).split())[:700]}", flush=True)
        status = 1
    finally:
        save()
        for c in conns.values():
            try:
                c.close()
            except Exception:  # noqa: BLE001
                pass
    failed = [c for c in record["checks"] if not c["pass"]]
    print(f"DONE s3_e2e {len(record['checks']) - len(failed)} pass / {len(failed)} fail", flush=True)
    return status or (1 if failed else 0)


if __name__ == "__main__":
    sys.exit(main())
