#!/usr/bin/env python3
"""The s3:// database of QoD Search Phase 0 part 2, Task 15a: create and load it, or tear it down.

Usage: uv run --no-project python -I s3_db.py <p2> up|down

`up`, as the part 2 stack's superuser (REST on 127.0.0.1:27900, the password read from
<p2>/secrets/admin.txt, never printed):
  1. POST /api/database/create: tenant acme, database lakes3 (catalog acme_lakes3), DuckLake, data
     path s3://qod-p2-search/acme_lakes3/ on the loopback SeaweedFS of weed_up.py, with its own
     objectStore keys read from <p2>/secrets/weed.json inside this process (never printed or
     logged). The answer's objectStore must hold neither key.
  2. POST /api/pool/create: pool bis3, one dual node; waits until /api/pool/list shows it ready.
  3. Through `qod sql --superuser` (one call per statement, as bench/search/stack/qod_load.sh
     does, since a multi-statement submission binds every statement under one 60 s handle): the
     1M load of acme_lakes3.main.pages_1m on pool bis3 and of acme_lake.main.pages_1m_s3cmp on pool
     bi, each under SET threads = 1 (DuckLake's parallel insert writes row groups in completion
     order; Task 3 loads the same way so that rowid = id) and back to 16, then the grants to
     roles reader and reader_rls10 and reader_rls10's row policy f10 = 0.
  4. POST /api/search/prototype/build-segments for both tables, column body.

`down` reports each step `ok` or its error and goes on: drops the bump tables of s3_e2e.py, the
row policies and grants, the comparison table, then deletes pool bis3 and database lakes3 (which
drops its catalog database on the embedded Postgres), and checks that the database and pool lists
and the roles' permissions and row policies no longer name either table.

Standard library only.
"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
WT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
STACK = os.path.join(WT, "bench", "search", "stack")
BASE = "http://127.0.0.1:27900"
TENANT = "acme"
S3DB, S3POOL, S3CAT = "lakes3", "bis3", "acme_lakes3"
LOCALDB, LOCALPOOL, LOCALCAT = "acme_lake", "bi", "acme_lake"
DATA_PATH = "s3://qod-p2-search/acme_lakes3/"
S3T = f"{S3CAT}.main.pages_1m"
LOCALT = f"{LOCALCAT}.main.pages_1m_s3cmp"

P2 = sys.argv[1]


def call(method, path, body=None, token=None, timeout=120):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("X-API-Key", token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw else None), raw
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw), raw
        except ValueError:
            return e.code, {"error": f"http_{e.code}", "message": raw[:300]}, raw


def login():
    with open(os.path.join(P2, "secrets", "admin.txt"), encoding="utf-8") as f:
        password = f.read().strip()
    status, body, _ = call("POST", "/api/auth/login", {"username": "admin", "password": password})
    del password
    if status != 200 or not (body or {}).get("token"):
        sys.exit(f"login failed: {status} {(body or {}).get('error')}")
    return body["token"]


def qod_sql(pool, sql, timeout=900):
    """Runs one statement as the superuser through `qod sql`; (ok, last output lines)."""
    script = (
        '. "$1/qod_env.sh" "$2" >/dev/null; '
        'QOD_USER=admin QOD_SUPERUSER=true QOD_PASSWORD="$(cat "$2/secrets/admin.txt")" '
        'exec "$3/cli/.venv/bin/qod" sql --tenant acme --pool "$4" --superuser --csv "$5"'
    )
    env = {k: v for k, v in os.environ.items() if k not in ("AI_AGENT", "CLAUDECODE")}
    p = subprocess.run(["bash", "-c", script, "qod_sql", STACK, P2, WT, pool, sql],
                       stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=timeout,
                       env=env)
    out = [ln for ln in (p.stdout + p.stderr).splitlines()
           if ln.strip() and not ln.startswith("qod: profile")]
    return p.returncode == 0, out


def report(label, ok, detail=""):
    print(f"{'ok' if ok else 'ERROR'} {label}{(': ' + detail) if detail else ''}", flush=True)
    return ok


def pools(token):
    status, body, _ = call("GET", "/api/pool/list", token=token)
    return (body or {}).get("pools", []) if status == 200 else None


def load_statements(cat, table):
    t = f"{cat}.main.{table}"
    corpus = os.path.join(P2, "corpus", "pages_1m.parquet")
    return [
        f"CREATE TABLE {t} (id BIGINT, title VARCHAR, body VARCHAR, f10 INTEGER, f100 INTEGER)",
        f"CALL {cat}.set_option('parquet_row_group_size', 16384, table_name => '{table}')",
        "SET threads = 1",
        f"INSERT INTO {t} SELECT * FROM read_parquet('{corpus}') ORDER BY id",
        "SET threads = 16",
        f"GRANT SELECT ON {t} TO ROLE reader",
        f"GRANT SELECT ON {t} TO ROLE reader_rls10",
        f"CREATE ROW POLICY ON {t} FOR ROLE reader_rls10 USING (f10 = 0)",
    ]


def run_all(pool, statements):
    for sql in statements:
        t0 = time.time()
        ok, out = qod_sql(pool, sql)
        report(f"[{pool}] {sql[:100]} ({time.time() - t0:.1f} s)", ok,
               "" if ok else " | ".join(out[-3:])[:400])
        if not ok:
            if "threads" not in sql:
                qod_sql(pool, "SET threads = 16")
            return False
    ok, out = qod_sql(pool, "SELECT current_setting('threads')")
    report(f"[{pool}] threads back to {out[-1] if out else '?'}", ok and out[-1:] == ["16"])
    return True


def build(token, database, pool, table):
    t0 = time.time()
    status, body, _ = call("POST", "/api/search/prototype/build-segments",
                           {"tenant": TENANT, "database": database, "pool": pool,
                            "table": table, "column": "body"}, token=token, timeout=1800)
    if status != 200:
        return report(f"build-segments {database}.{table}", False,
                      f"{status} {body.get('error')}: {body.get('message')}")
    files = body["files"]
    report(f"build-segments {database}.{table} ({time.time() - t0:.1f} s)", True,
           f"files={len(files)} skipped={len(body['skipped'])} "
           f"docs={sum(f['docs'] for f in files)} bytes={sum(f['bytes'] for f in files)}")
    for f in files:
        print(f"   {f['path'].replace(P2, '<p2>')} -> {f['bundle'].replace(P2, '<p2>')} "
              f"docs={f['docs']} bytes={f['bytes']} ms={f['ms']}", flush=True)
    os.makedirs(os.path.join(P2, "t15a"), exist_ok=True)
    with open(os.path.join(P2, "t15a", f"build-{database}.json"), "w", encoding="utf-8") as out:
        json.dump(body, out, indent=1)
    return True


def up():
    token = login()
    with open(os.path.join(P2, "secrets", "weed.json"), encoding="utf-8") as f:
        keys = json.load(f)
    body = {"tenant": TENANT, "name": S3DB, "kind": "ducklake", "dataPath": DATA_PATH,
            "objectStore": {"s3_access_key_id": keys["key"], "s3_secret_access_key": keys["secret"],
                            "s3_endpoint": "http://127.0.0.1:27460", "s3_url_style": "path",
                            "s3_region": "us-east-1"}}
    status, listed, _ = call("GET", f"/api/database/list?tenant={TENANT}", token=token)
    existing = [d for d in (listed or {}).get("tenantDbs", []) if d["name"] == S3CAT]
    if existing:
        # A rerun after a later step failed: the row is there, so only check what it shows.
        status, resp, raw = 200, existing[0], json.dumps(existing[0])
        label = "database/create (exists, kept)"
    else:
        status, resp, raw = call("POST", "/api/database/create", body, token=token, timeout=300)
        label = "database/create"
    # Checked by field name, never by value: no key id or secret is compared or printed. The
    # secret field must not come back (TenantDb.SecretKeys); the other fields are listed by name.
    del keys, body
    if status not in (200, 201):
        return report(label, False, f"{status} {resp.get('error')}: {resp.get('message')}")
    fields = sorted(resp.get("objectStore", {}))
    secret_back = "s3_secret_access_key" in fields or "s3_secret_access_key" in raw
    report(label, not secret_back,
           f"name={resp['name']} kind={resp['kind']} dataPath={resp['dataPath']} "
           f"objectStore fields={fields} encrypted={resp.get('encrypted')} "
           f"{'SECRET FIELD IN THE ANSWER' if secret_back else 'no secret field in the answer'}")
    if secret_back:
        return False

    status, resp, _ = call("POST", "/api/pool/create",
                           {"tenant": TENANT, "tenantDb": S3CAT, "pool": S3POOL, "size": 1,
                            "roleDistribution": {"writeonly": 0, "readonly": 0, "dual": 1}},
                           token=token, timeout=300)
    if status not in (200, 201):
        return report("pool/create", False, f"{status} {resp.get('error')}: {resp.get('message')}")
    report("pool/create", True)
    deadline = time.time() + 300
    state = None
    while time.time() < deadline:
        mine = [p for p in (pools(token) or []) if p["tenantDb"] == S3CAT and p["pool"] == S3POOL]
        if mine:
            state = mine[0]
            healthy = [n for n in state.get("nodes", []) if n.get("healthy")]
            if state["status"] == "ready" and healthy:
                break
        time.sleep(2)
    else:
        return report("pool bis3 ready", False,
                      f"status={state and state['status']} detail={state and state.get('statusDetail')}")
    report("pool bis3 ready", True,
           f"node={state['nodes'][0]['nodeId']} port={state['nodes'][0]['port']}")

    if not run_all(S3POOL, load_statements(S3CAT, "pages_1m")):
        return False
    if not run_all(LOCALPOOL, load_statements(LOCALCAT, "pages_1m_s3cmp")):
        return False
    if not build(token, S3CAT, S3POOL, "pages_1m"):
        return False
    return build(token, LOCALDB, LOCALPOOL, "pages_1m_s3cmp")


def down():
    token = login()
    allok = True
    steps = [
        (S3POOL, f"DROP TABLE IF EXISTS {S3CAT}.main.bump"),
        (LOCALPOOL, f"DROP TABLE IF EXISTS {LOCALCAT}.main.bump"),
        (S3POOL, f"DROP ROW POLICY IF EXISTS ON {S3T} FOR ROLE reader_rls10"),
        (LOCALPOOL, f"DROP ROW POLICY IF EXISTS ON {LOCALT} FOR ROLE reader_rls10"),
        (S3POOL, f"REVOKE SELECT ON {S3T} FROM ROLE reader"),
        (S3POOL, f"REVOKE SELECT ON {S3T} FROM ROLE reader_rls10"),
        (LOCALPOOL, f"REVOKE SELECT ON {LOCALT} FROM ROLE reader"),
        (LOCALPOOL, f"REVOKE SELECT ON {LOCALT} FROM ROLE reader_rls10"),
        (LOCALPOOL, f"DROP TABLE IF EXISTS {LOCALT}"),
    ]
    live = {p["pool"] for p in (pools(token) or []) if p["tenant"] == TENANT}
    for pool, sql in steps:
        if pool not in live:
            report(f"[{pool}] {sql}", True, "skipped: the pool is gone (an earlier down ran)")
            continue
        ok, out = qod_sql(pool, sql, timeout=300)
        allok &= report(f"[{pool}] {sql}", ok, "" if ok else " | ".join(out[-3:])[:400])

    status, resp, _ = call("POST", "/api/pool/delete",
                           {"tenant": TENANT, "tenantDb": S3CAT, "pool": S3POOL},
                           token=token, timeout=300)
    if status == 404:
        status = 204  # already gone: an earlier down deleted it
    allok &= report("pool/delete bis3", status in (200, 204),
                    "" if status in (200, 204) else f"{status} {(resp or {}).get('error')}: "
                    f"{(resp or {}).get('message')}")
    for attempt in range(30):
        status, resp, _ = call("POST", "/api/database/delete", {"tenant": TENANT, "name": S3CAT},
                               token=token, timeout=300)
        if status in (200, 204) or status == 404 or attempt == 29:
            break
        time.sleep(2)
    allok &= report("database/delete lakes3", status in (200, 204),
                    "" if status in (200, 204) else f"{status} {(resp or {}).get('error')}: "
                    f"{(resp or {}).get('message')}")

    status, dbs, _ = call("GET", f"/api/database/list?tenant={TENANT}", token=token)
    names = [d["name"] for d in (dbs or {}).get("tenantDbs", [])]
    allok &= report("database list names neither", status == 200 and S3CAT not in names,
                    f"databases={names}")
    pl = pools(token) or []
    allok &= report("pool list names neither",
                    not any(p["pool"] == S3POOL or p["tenantDb"] == S3CAT for p in pl),
                    f"pools={[(p['tenantDb'], p['pool']) for p in pl]}")
    ok, out = qod_sql(LOCALPOOL, f"SELECT count(*) FROM duckdb_tables() WHERE database_name = "
                      f"'{LOCALCAT}' AND table_name IN ('pages_1m_s3cmp', 'bump')")
    allok &= report("acme_lake holds neither pages_1m_s3cmp nor bump", ok and out[-1:] == ["0"],
                    f"count={out[-1] if out else '?'}")
    status, roles, _ = call("GET", f"/api/role/list?tenant={TENANT}", token=token)
    for name in ("reader", "reader_rls10"):
        role = [r for r in (roles or {}).get("roles", []) if r.get("name") == name]
        if not role:
            allok &= report(f"role {name}", False, f"not found ({status})")
            continue
        rid = role[0]["id"]
        s1, perms, raw1 = call("GET", f"/api/role/permission/list?roleId={rid}", token=token)
        s2, pols, raw2 = call("GET", f"/api/role/row-policy/list?roleId={rid}", token=token)
        named = [t for t in ("pages_1m_s3cmp", S3CAT) if t in raw1 or t in raw2]
        allok &= report(f"role {name} names neither table", s1 == 200 and s2 == 200 and not named,
                        f"permission rows={len((perms or {}).get('permissions', perms or []))} "
                        f"named={named}")
    return allok


if __name__ == "__main__":
    action = sys.argv[2]
    ok = up() if action == "up" else down() if action == "down" else sys.exit("up or down")
    print(f"DONE s3_db {action} {'ok' if ok else 'with errors'}", flush=True)
    sys.exit(0 if ok else 1)
