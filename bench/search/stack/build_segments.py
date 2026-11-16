#!/usr/bin/env python3
"""Builds the full-text bundles of the part 2 tables on the QoD stack through the manager (plan
Task 12 Step 2), since qod_search_build_segment runs for the system principal only.

Usage: uv run --no-project python -I build_segments.py <p2> [table:column ...]

Logs in as the stack's superuser (POST /api/auth/login on 127.0.0.1:27900, the password read from
<p2>/secrets/admin.txt, never printed), finds pool bi of tenant acme in /api/pool/list, and calls
POST /api/search/prototype/build-segments (prototype only) for each table:column, by default
pages_1m:body, pages_10m:body and pages_1m:title (the last for P12's verdict-cache row, which runs
two statements differing only in their column). The endpoint flushes the table's inlined rows and
builds one bundle per live data file from the table's own scan.

Prints, per table and column, every file's docs, bundle bytes and build wall time, then the total
build time (the request's wall time and the sum of the per-file times) and the total index size,
with the load average before and after. Appends one JSON object per table and column to
<p2>/results/build-segments.jsonl. Exit 1 on the first refusal (its code and message printed).
No dependency beyond the standard library.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:27900"
TENANT, POOL = "acme", "bi"
DEFAULT = ["pages_1m:body", "pages_10m:body", "pages_1m:title"]


def call(method, path, body=None, token=None, timeout=60):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("X-API-Key", token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode() or "null")
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, {"error": "http_" + str(e.code), "message": raw[:300]}


def load():
    return [round(x, 2) for x in os.getloadavg()]


def main():
    p2 = sys.argv[1]
    targets = sys.argv[2:] or DEFAULT
    with open(os.path.join(p2, "secrets", "admin.txt"), encoding="utf-8") as f:
        password = f.read().strip()
    status, body = call("POST", "/api/auth/login", {"username": "admin", "password": password})
    del password
    if status != 200 or not body.get("token"):
        print(f"login failed: {status} {(body or {}).get('error')}", flush=True)
        return 1
    token = body["token"]
    status, pools = call("GET", "/api/pool/list", token=token)
    dbs = [p["tenantDb"] for p in (pools or {}).get("pools", [])
           if p["tenant"] == TENANT and p["pool"] == POOL]
    if status != 200 or len(dbs) != 1:
        print(f"pool {TENANT}/{POOL} not found: {status}", flush=True)
        return 1
    database = dbs[0]
    results = os.path.join(p2, "results")
    os.makedirs(results, exist_ok=True)
    for target in targets:
        table, column = target.split(":", 1)
        before = load()
        t0 = time.perf_counter()
        status, out = call("POST", "/api/search/prototype/build-segments",
                           {"tenant": TENANT, "database": database, "pool": POOL,
                            "table": table, "column": column}, token=token, timeout=7200)
        wall_ms = round((time.perf_counter() - t0) * 1000, 1)
        after = load()
        if status != 200:
            print(f"{table}.{column}: refused {status} {out.get('error')}: {out.get('message')}",
                  flush=True)
            return 1
        for f in out["files"]:
            print(f"  {table}.{column} {os.path.basename(f['path'])}: docs={f['docs']} "
                  f"bytes={f['bytes']} ms={f['ms']}", flush=True)
        summary = {
            "table": table, "column": column, "database": database,
            "files": len(out["files"]), "skipped": len(out["skipped"]),
            "docs": sum(f["docs"] for f in out["files"]),
            "index_bytes": sum(f["bytes"] for f in out["files"]),
            "build_ms_sum": sum(f["ms"] for f in out["files"]),
            "request_ms": wall_ms, "load_before": before, "load_after": after,
            "per_file": out["files"],
        }
        print(f"{table}.{column}: files={summary['files']} skipped={summary['skipped']} "
              f"docs={summary['docs']} index_bytes={summary['index_bytes']} "
              f"build_ms_sum={summary['build_ms_sum']} request_ms={wall_ms} "
              f"load {before} -> {after}", flush=True)
        with open(os.path.join(results, "build-segments.jsonl"), "a", encoding="utf-8") as f:
            f.write(json.dumps(summary) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
