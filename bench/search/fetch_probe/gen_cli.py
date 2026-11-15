#!/usr/bin/env python3
"""P5a: writes the DuckDB CLI scripts (run through cli_run.sh): <out>/cli_timer.sql (.timer on, for
in_list, cte_join, in_subq and probe: 5 warm-ups then 50 timed statements each, a fresh random
rowid set per statement, interleaved) and <out>/cli_explain.sql (EXPLAIN ANALYZE of one statement
of each shape). Usage: python3 -I gen_cli.py <out dir>"""
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shapes as S  # noqa: E402

out = sys.argv[1]
rng = random.Random(20261118)
SHAPES = [("in_list", S.in_list), ("cte_join", S.cte_join), ("in_subq", S.in_subq), ("probe", S.probe)]
with open(os.path.join(out, "cli_timer.sql"), "w") as f:
    f.write(".mode csv\n.output /dev/null\n")
    for i in range(55):
        for name, make in SHAPES:
            if i == 5 and name == "in_list":
                f.write(".timer on\n")
            f.write(f"-- {name}\n{make(rng.sample(range(S.ROWS), S.K))};\n")
with open(os.path.join(out, "cli_explain.sql"), "w") as f:
    f.write(".mode line\n")
    for name, make in SHAPES:
        f.write(f"EXPLAIN ANALYZE {make(rng.sample(range(S.ROWS), S.K))};\n")
