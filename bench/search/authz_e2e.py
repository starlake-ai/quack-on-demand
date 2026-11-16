#!/usr/bin/env python3
"""Authorization end to end through Flight SQL (QoD Search Phase 0 part 2, plan Task 12 Step 3).

Usage:
  uv run --no-project --with adbc-driver-flightsql --with pyarrow python -I authz_e2e.py <p2>

Runs the P12 matrix on the part 2 stack (Flight SQL with TLS on 127.0.0.1:27338, the edge's
self-signed certificate not verified, as the qod CLI does; tenant acme and pool bi as call headers;
REST on 127.0.0.1:27900) as the users of qod_users.py, each password read from
<p2>/secrets/<user>.txt and never printed. Needs the bundles of build_segments.py (pages_1m:body,
pages_10m:body, pages_1m:title). The references are reference_bm25.sql on
<p2>/corpus/pages_1m.parquet (the DuckDB v2.0.0 CLI) with dl quantized to Tantivy's fieldnorm, the
form P4's review fixed for Tasks 12 and 13 (ids rank for rank up to ties pooled at 1e-4 relative,
scores within 1e-4): rls10's policy cell against the allowed population, and also farther than 1e-4
from the table population's, so the row shows which population the verdict gave; plain's WHERE cell
against the table population. The exact-dl form is printed as a diagnostic only. defaultDatabase is
never set.

The verdict-cache rows run first, on shapes no other row reads, each pair 11 s readings apart, so
inside the node's 60 s verdict lifetime: statements differing only in `table` (pages_1m then
pages_10m), only in `columns` (body then title), and, as a control, only in the projection of the
search's output (which is not in the plan description: one decision for both). Each statement is
followed by a reading of the replica's qod_authz_decide_duration_ms{kind="plan"} count on /metrics
(11 s after it, as /metrics shares one scrape for 4 s; Q12's method; no series yet reads 0).

Prints one PASS or FAIL line per row with what was observed, then the load average. Appends one
JSON object per row to <p2>/results/authz-e2e.jsonl. Exit 0 when every row passes, else 1.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import json  # noqa: E402
import re  # noqa: E402
import subprocess  # noqa: E402
import time  # noqa: E402
import urllib.error  # noqa: E402
import urllib.request  # noqa: E402

import adbc_driver_flightsql.dbapi as flight  # noqa: E402
from adbc_driver_flightsql import DatabaseOptions  # noqa: E402

from cells import QUERIES, Cell, qod_sql  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
DUCKDB = "/Users/hayssams/git/public/quack-on-demand/.duckdb/2.0.0/bin/duckdb"
FLIGHT = "grpc+tls://127.0.0.1:27338"
REST = "http://127.0.0.1:27900"
TENANT, POOL = "acme", "bi"
T1M = "acme_lake.main.pages_1m"
T10M = "acme_lake.main.pages_10m"
HDR = DatabaseOptions.RPC_CALL_HEADER_PREFIX.value
HIDDEN_ROWID = 1  # id 1: f10 = 1, hidden by rls10's policy f10 = 0
WAIT = 11
TIE = 1e-4  # Task 4's tolerance for QoD against the quantized reference (f32 against f64)

P2 = sys.argv[1]
results = []


def secret(name):
    with open(os.path.join(P2, "secrets", f"{name}.txt"), encoding="utf-8") as f:
        return f.read().strip()


def connect(user, superuser=False):
    kw = {
        "username": user,
        "password": secret(user),
        DatabaseOptions.TLS_SKIP_VERIFY.value: "true",
        HDR + "tenant": TENANT,
        HDR + "pool": POOL,
    }
    if superuser:
        kw[HDR + "superuser"] = "true"
    return flight.connect(FLIGHT, db_kwargs=kw, autocommit=True)


def run(conn, sql):
    """(rows, None) or (None, the error's first line)."""
    try:
        with conn.cursor() as cur:
            cur.execute(sql)
            return cur.fetchall(), None
    except Exception as exc:  # noqa: BLE001 - the error is the observation
        return None, " ".join(str(exc).split())[:600]


def row(user, statement, expected, ok, observed):
    results.append({"user": user, "statement": statement, "expected": expected,
                    "pass": bool(ok), "observed": observed})
    print(f"{'PASS' if ok else 'FAIL'} [{user}] {statement}: expected {expected}; observed "
          f"{observed}", flush=True)


def expect_error(user, conn, label, sql, needles, expected):
    rows, err = run(conn, sql)
    ok = rows is None and all(n.lower() in err.lower() for n in needles)
    row(user, label, expected, ok, err if rows is None else f"{len(rows)} rows")


def rest(method, path, body=None, token=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(REST + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("X-API-Key", token)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def login(user, tenant=None):
    body = {"username": user, "password": secret(user)}
    if tenant:
        body["tenant"] = tenant
    status, text = rest("POST", "/api/auth/login", body)
    return json.loads(text).get("token") if status == 200 else None


ADMIN_TOKEN = None


def decides():
    """The replica's plan decide count, read WAIT seconds from now."""
    time.sleep(WAIT)
    status, text = rest("GET", "/metrics")
    if status != 200:
        status, text = rest("GET", "/metrics", token=ADMIN_TOKEN)
    if status != 200:
        return None
    m = re.search(r'qod_authz_decide_duration_ms_count\{[^}]*kind="plan"[^}]*\} ([0-9.eE+]+)', text)
    # No series yet: the replica has made no plan decision since it started.
    return float(m.group(1)) if m else 0.0


def reference(pop, dlmode, nrows=2000):
    """reference_bm25.sql for the 1M 10% OR cell: [(rank, id, score)]."""
    terms = "[" + ", ".join(f"'{t}'" for t in QUERIES["or"].split()) + "]"
    parquet = os.path.join(P2, "corpus", "pages_1m.parquet")
    cmd = (f"SET VARIABLE src = '{parquet}'; SET VARIABLE terms = {terms}; "
           f"SET VARIABLE fcol = 'f10'; SET VARIABLE pop = '{pop}'; "
           f"SET VARIABLE dlmode = '{dlmode}'; SET VARIABLE nrows = {nrows}")
    env = {k: v for k, v in os.environ.items() if k != "AI_AGENT"}
    with open(os.path.join(HERE, "reference_bm25.sql"), encoding="utf-8") as f:
        out = subprocess.run([DUCKDB, "-no-agent", "-json", "-cmd", cmd], stdin=f,
                             capture_output=True, text=True, env=env, check=True).stdout
    return [(r["rank"], r["id"], r["score"]) for r in json.loads(out)]


def search(table, column, query, where, select="h.rowid, h.rank"):
    return (f"SELECT {select} FROM qod_search((SELECT rowid FROM {table} WHERE {where}), "
            f"'{table}', ['{column}'], '{query}', k := 5) h")


def rel(a, b):
    return abs(a - b) / max(abs(b), 1e-30)


def agree(got, ref):
    """Task 4's comparison (P4, "What Tasks 12 and 13 compare"): ids rank for rank up to ties
    pooled at TIE relative, and every score within TIE of the reference's score for that id.
    got: [(id, score)], ref: [(rank, id, score)] (more rows than got, for the tie pool)."""
    by_id = {i: sc for _, i, sc in ref}
    tenth = ref[len(got) - 1][2]
    above = [i for _, i, sc in ref[:len(got)] if rel(sc, tenth) > TIE]
    pool = {i for _, i, sc in ref if rel(sc, tenth) <= TIE}
    ids = [i for i, _ in got]
    ranks = ids[:len(above)] == above and all(i in pool for i in ids[len(above):])
    gaps = [rel(sc, by_id[i]) for i, sc in got if i in by_id]
    worst = max(gaps) if len(gaps) == len(got) else None
    return ranks, worst


def main():
    global ADMIN_TOKEN
    ADMIN_TOKEN = login("admin")
    conns = {u: connect(u) for u in ("plain", "rls10", "rls1", "other")}
    conns["admin"] = connect("admin", superuser=True)
    plain = conns["plain"]
    print(f"load average at start: {[round(x, 2) for x in os.getloadavg()]}", flush=True)

    # ---- the verdict cache: fresh shapes first ---------------------------------------------------
    # A plan verdict lives 60 s in the node's cache, keyed by principal, policy version, context and
    # fingerprint, and the fingerprint is the plan description: the tables and columns read (the
    # input's included) and the search calls' table and columns; constants and the projection of
    # the search's own output are not in it. So each pair reads an input column no other pair and no
    # other row of this script reads, and the third pair pins the sharing: two statements differing
    # only in the projection of the search's output share one verdict.
    pairs = [
        ("table", search(T1M, "body", "w3", "id >= 0"), search(T10M, "body", "w3", "id >= 0"), True),
        ("columns", search(T1M, "body", "w3", "title IS NOT NULL"),
         search(T1M, "title", "t5", "title IS NOT NULL"), True),
        ("the projection of the search's output",
         search(T1M, "body", "w3", "f100 >= 0", "h.rowid"),
         search(T1M, "body", "w3", "f100 >= 0", "h.score"), False),
    ]
    for what, first, second, second_decided in pairs:
        c0 = decides()
        r1, e1 = run(plain, first)
        c1 = decides()
        r2, e2 = run(plain, second)
        c2 = decides()
        if None in (c0, c1, c2):
            moved = False
        elif second_decided:
            moved = c1 - c0 >= 1 and c2 - c1 >= 1
        else:
            moved = c1 - c0 >= 1 and c2 == c1
        expected = ("each decided on its own (the plan decide count moves for both)" if second_decided
                    else "one decision for both (the count moves for the first only)")
        row("plain", f"two statements differing only in {what}, back to back", expected,
            moved and e1 is None and e2 is None,
            f"decide count {c0} -> {c1} -> {c2}; first {e1 or str(len(r1)) + ' rows'}; second "
            f"{e2 or str(len(r2)) + ' rows'}")

    # ---- plain ------------------------------------------------------------------------------------
    cell100 = qod_sql(Cell("1m", "100%", "where", "or"))
    pr, pe = run(plain, cell100)
    sr, se = run(conns["admin"], cell100)
    row("plain", "qod_sql(Cell('1m','100%','where','or'))", "10 rows, equal to the superuser's run",
        pe is None and se is None and len(pr) == 10 and pr == sr,
        pe or se or f"{len(pr)} rows, equal to the superuser's: {pr == sr}; top {pr[:2]}")
    cell10 = qod_sql(Cell("1m", "10%", "where", "or"))
    r, e = run(plain, cell10)
    if e is None:
        ranks, worst = agree([(x[0], x[2]) for x in r], reference("table", "quantized"))
        _, worst_allowed = agree([(x[0], x[2]) for x in r], reference("allowed", "quantized"))
        row("plain", "the same with WHERE f10 = 0",
            "10 rows, every id % 10 == 0; scored over the table population (quantized-dl "
            "reference, ranks up to ties and scores within 1e-4)",
            len(r) == 10 and all(x[0] % 10 == 0 for x in r) and ranks and worst is not None
            and worst <= TIE,
            f"ids {[x[0] for x in r]}; table population: ranks {ranks}, max rel score gap {worst}; "
            f"allowed population: max rel score gap {worst_allowed}")
    else:
        row("plain", "the same with WHERE f10 = 0", "10 rows of the filter", False, e)

    # ---- rls10 ------------------------------------------------------------------------------------
    rls10 = conns["rls10"]
    pol10 = qod_sql(Cell("1m", "10%", "policy", "or"))
    r, e = run(rls10, pol10)
    if e is None:
        got = [(x[0], x[2]) for x in r]
        ranks, worst = agree(got, reference("allowed", "quantized"))
        _, worst_table = agree(got, reference("table", "quantized"))
        exact_ranks, worst_exact = agree(got, reference("allowed", "exact"))
        # The allowed population, not the table's: within 1e-4 of the allowed reference and farther
        # than 1e-4 from the table population's (the two differ by about 7.6e-3 here).
        ok = (len(r) == 10 and all(i % 10 == 0 for i, _ in got) and ranks and worst is not None
              and worst <= TIE and worst_table is not None and worst_table > TIE)
        row("rls10", "qod_sql(Cell('1m','10%','policy','or'))",
            "10 rows, every id % 10 == 0; ids and ranks equal to the allowed-population reference "
            "(quantized dl, ties at 1e-4), scores within 1e-4, and not the table population's", ok,
            f"ids {[i for i, _ in got]}; allowed population: ranks {ranks}, max rel score gap "
            f"{worst}; table population: max rel score gap {worst_table}; exact-dl reference "
            f"(diagnostic): ranks {exact_ranks}, max rel score gap {worst_exact}")
    else:
        row("rls10", "qod_sql(Cell('1m','10%','policy','or'))", "10 rows of the policy", False, e)
    expect_error("rls10", rls10, f"SELECT p.title FROM {T1M} p WHERE p.rowid = {HIDDEN_ROWID}",
                 f"SELECT p.title FROM {T1M} p WHERE p.rowid = {HIDDEN_ROWID}",
                 ['Values list "p" does not have a column named "rowid"'],
                 'Values list "p" does not have a column named "rowid" (change 43)')
    narrowed = (f"SELECT p.id, p.title, h.score FROM qod_search((SELECT rowid FROM {T1M} WHERE "
                f"rowid = {HIDDEN_ROWID}), '{T1M}', ['body'], '{QUERIES['or']}', k := 10) h JOIN "
                f"{T1M} p ON p.rowid = h.rowid ORDER BY h.score DESC, h.rowid")
    r, e = run(rls10, narrowed)
    row("rls10", f"the policy statement with its input narrowed to rowid {HIDDEN_ROWID}", "0 rows",
        e is None and len(r) == 0, e or f"{len(r)} rows")
    expect_error("rls10", rls10, f"SELECT rowid FROM {T1M}", f"SELECT rowid FROM {T1M}",
                 ['Referenced column "rowid" not found'],
                 'Referenced column "rowid" not found (change 43, unchanged)')

    # ---- rls1 -------------------------------------------------------------------------------------
    r, e = run(conns["rls1"], qod_sql(Cell("1m", "1%", "policy", "or")))
    row("rls1", "qod_sql(Cell('1m','1%','policy','or'))", "10 rows, every id % 100 == 0",
        e is None and len(r) == 10 and all(x[0] % 100 == 0 for x in r),
        e or f"{len(r)} rows, ids {[x[0] for x in r]}")

    # ---- other ------------------------------------------------------------------------------------
    expect_error("other", conns["other"], "the plain 100% statement", cell100,
                 ["denied", "pages_1m"], "refused, access denied naming pages_1m (rule 5)")

    # ---- plain refusals ---------------------------------------------------------------------------
    field = (f"SELECT p.id, h.score FROM qod_search((SELECT rowid FROM {T1M}), '{T1M}', ['body'], "
             f"'title:w0', k := 10) h JOIN {T1M} p ON p.rowid = h.rowid")
    expect_error("plain", plain, "the statement with 'title:w0' as query", field,
                 ["search_field_not_allowed"], "refused, search_field_not_allowed (rule 6)")
    expect_error("plain", plain, "qod_search((VALUES (1)), ...)",
                 f"SELECT h.rowid FROM qod_search((VALUES (1)), '{T1M}', ['body'], 'w0') h",
                 ["search_input_not_governed"],
                 "refused, search_input_not_governed (an INTEGER column: not a BIGINT rowid)")
    expect_error("plain", plain, "qod_search((VALUES (1::BIGINT)), ...)",
                 f"SELECT h.rowid FROM qod_search((VALUES (1::BIGINT)), '{T1M}', ['body'], 'w0') h",
                 ["search_input_not_governed"], "refused, search_input_not_governed (rule 1)")
    build = ("SELECT path, docs, bytes FROM qod_search_build_segment((SELECT 0::BIGINT, 0::BIGINT, "
             f"NULL::BIGINT, 'x'), '{T1M}', 'body', '/nowhere/', '/nowhere', 1::UBIGINT, "
             "1::UBIGINT)")
    expect_error("plain", plain, "SELECT ... FROM qod_search_build_segment(...)", build,
                 ["qod_search_build_segment"], "refused (superuser class)")
    expect_error("admin", conns["admin"], "SELECT ... FROM qod_search_build_segment(...)", build,
                 ["QoD: function qod_search_build_segment is superuser-only"],
                 "refused, QoD: function qod_search_build_segment is superuser-only")

    # ---- REST -------------------------------------------------------------------------------------
    token = login("plain", TENANT)
    status, text = rest("POST", "/api/search/prototype/build-segments",
                        {"tenant": TENANT, "database": "acme_lake", "pool": POOL,
                         "table": "pages_1m", "column": "body"}, token=token)
    try:
        code = json.loads(text).get("error")
    except ValueError:
        code = None
    row("plain", "POST /api/search/prototype/build-segments",
        "403 (the guard's admin_required for a profile session; superuser_required past it)",
        token is not None and status == 403 and code in ("admin_required", "superuser_required"),
        f"{status} {code}" if token else "plain could not log in")

    for c in conns.values():
        c.close()
    print(f"load average at end: {[round(x, 2) for x in os.getloadavg()]}", flush=True)
    os.makedirs(os.path.join(P2, "results"), exist_ok=True)
    with open(os.path.join(P2, "results", "authz-e2e.jsonl"), "a", encoding="utf-8") as f:
        for r_ in results:
            f.write(json.dumps(r_) + "\n")
    failed = [r_ for r_ in results if not r_["pass"]]
    print(f"{len(results) - len(failed)} PASS, {len(failed)} FAIL", flush=True)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
