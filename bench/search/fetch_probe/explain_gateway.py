#!/usr/bin/env python3
"""P5a: the node's own execution time of each fetch shape, read from EXPLAIN ANALYZE through the
gateway as the superuser (no decide call), fresh rowids per statement; prints the plan's Total Time
p50 / p95 and the Data Read values seen, per shape. EXPLAIN ANALYZE is Unprobed (no schema probe).
Usage: env -u VIRTUAL_ENV uv run --no-project --with adbc-driver-flightsql --with pyarrow \
         python -I explain_gateway.py <p2> [runs]"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import json  # noqa: E402
import random  # noqa: E402
import re  # noqa: E402

import shapes as S  # noqa: E402
from flight_fetch import connect  # noqa: E402

p2 = sys.argv[1]
runs = int(sys.argv[2]) if len(sys.argv) > 2 else 50
rng = random.Random(20261119)
shapes = {"in_list": S.in_list, "cte_join": S.cte_join, "in_subq": S.in_subq, "probe": S.probe}
with connect(p2, "admin", "127.0.0.1", 27338) as conn:
    cur = conn.cursor()
    out = {k: [] for k in shapes}
    reads = {k: set() for k in shapes}
    for i in range(5 + runs):
        for name, make in shapes.items():
            cur.execute("EXPLAIN ANALYZE " + make(rng.sample(range(S.ROWS), S.K)))
            text = "\n".join(str(c) for row in cur.fetchall() for c in row)
            m = re.search(r"Total Time: ([0-9.]+)s", text)
            d = re.search(r"Data Read:\s+([0-9.]+ [KMG]?B)", text)
            if i >= 5:
                out[name].append(float(m.group(1)) * 1000)
                reads[name].add(d.group(1) if d else "none")
    for name, samples in out.items():
        print(json.dumps({"path": "gateway-explain-analyze", "user": "admin", "shape": name,
                          "data_read": sorted(reads[name]), **S.summary(samples)}), flush=True)
