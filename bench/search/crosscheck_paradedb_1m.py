#!/usr/bin/env python3
"""Result equivalence of ParadeDB at 1M against reference_bm25.sql (plan Task 4 Step 5).

Usage:
  uv run --no-project --with 'psycopg[binary]' python -I crosscheck_paradedb_1m.py <p2>

For each `where` cell of size 1m: ParadeDB's top 10 (id, score) on 127.0.0.1:27432 (password
from <p2>/secrets/paradedb.txt, never printed) against the reference in its table-population
form, computed by the DuckDB v2.0.0 CLI on <p2>/corpus/pages_1m.parquet, with dl exact (the
reference proper) and dl quantized to Tantivy's fieldnorm (a diagnostic). Prints, per cell and
per dl mode, whether the 10 ids agree up to ties at the 10th score (scores within 1e-6 relative
count as tied, ParadeDB's scores being f32), each ParadeDB id's rank in the reference, and the
largest relative score gap, raw and net of the filter's own score: ParadeDB pushes the WHERE
filter into its Tantivy query as a `term` clause of the boolean `must`, whose score is added to
every matching doc's, so a filtered cell's raw scores sit one constant above the reference's. The
constant printed is the filter term's idf over the table, ln(1 + (N - df + 0.5) / (df + 0.5)) with
N = 1000000 and df = N / 10 (f10 = 0) or N / 100 (f100 = 0) (ids 0..N-1, f = id mod 10 or 100).
Exit 0 always: the result is evidence, not a test.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import json  # noqa: E402
import math  # noqa: E402
import subprocess  # noqa: E402

import psycopg  # noqa: E402

from cells import CELLS, FILTERS, QUERIES, paradedb_sql  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
DUCKDB = "/Users/hayssams/git/public/quack-on-demand/.duckdb/2.0.0/bin/duckdb"
FCOL = {"100%": "none", "10%": "f10", "1%": "f100"}
TIE = 1e-6
N = 1_000_000
FILTER_DF = {"100%": None, "10%": N // 10, "1%": N // 100}


def filter_offset(cell):
    df = FILTER_DF[cell.input]
    return 0.0 if df is None else math.log(1 + (N - df + 0.5) / (df + 0.5))


def reference(parquet, cell, dlmode, limit=2000):
    terms = "[" + ", ".join(f"'{t}'" for t in QUERIES[cell.query].split()) + "]"
    cmd = (f"SET VARIABLE src = '{parquet}'; SET VARIABLE terms = {terms}; "
           f"SET VARIABLE fcol = '{FCOL[cell.input]}'; SET VARIABLE pop = 'table'; "
           f"SET VARIABLE dlmode = '{dlmode}'; SET VARIABLE nrows = {limit}")
    env = {k: v for k, v in os.environ.items() if k != "AI_AGENT"}
    with open(os.path.join(HERE, "reference_bm25.sql")) as f:
        out = subprocess.run([DUCKDB, "-no-agent", "-json", "-cmd", cmd], stdin=f,
                             capture_output=True, text=True, env=env, check=True).stdout
    return [(r["id"], r["score"]) for r in json.loads(out)]


def rel(a, b):
    return abs(a - b) / max(abs(b), 1e-30)


def agree_up_to_ties(pdb, ref):
    """pdb ids equal ref's first 10 ids, except that docs tied with the 10th reference score may
    be any of the docs holding that score."""
    tenth = ref[9][1]
    above = [i for i, s in ref[:10] if rel(s, tenth) > TIE]
    tied_pool = {i for i, s in ref if rel(s, tenth) <= TIE}
    ids = [i for i, _ in pdb]
    return ids[:len(above)] == above and all(i in tied_pool for i in ids[len(above):])


def main():
    p2 = sys.argv[1]
    parquet = os.path.join(p2, "corpus", "pages_1m.parquet")
    with open(os.path.join(p2, "secrets", "paradedb.txt")) as f:
        password = f.read().strip()
    conn = psycopg.connect(host="127.0.0.1", port=27432, dbname="postgres", user="postgres",
                           password=password, sslmode="require", autocommit=True)
    del password
    cur = conn.cursor()
    for cell in CELLS:
        if cell.size != "1m" or cell.form != "where":
            continue
        cur.execute(paradedb_sql(cell))
        pdb = [(r[0], float(r[2])) for r in cur.fetchall()]
        off = filter_offset(cell)
        print(f"cell 1m {cell.input} {cell.query} ({FILTERS[cell.input] or 'no filter'}): "
              f"paradedb ids {[i for i, _ in pdb]} filter_offset={off:.7f}")
        for mode in ("exact", "quantized"):
            ref = reference(parquet, cell, mode)
            pos = {i: (k + 1, s) for k, (i, s) in enumerate(ref)}
            ranks = [pos[i][0] if i in pos else None for i, _ in pdb]
            gaps = [rel(s, pos[i][1]) for i, s in pdb if i in pos]
            net = [rel(s - off, pos[i][1]) for i, s in pdb if i in pos]
            print(f"  ref {mode:9s} top10 {[i for i, _ in ref[:10]]}")
            print(f"  ref {mode:9s} agree_up_to_ties={agree_up_to_ties(pdb, ref)} "
                  f"paradedb ranks in ref {ranks} max_rel_score_gap raw={max(gaps):.3e} "
                  f"net_of_filter={max(net):.3e} "
                  f"paradedb top score {pdb[0][1]:.7f} ref top score {ref[0][1]:.7f}")
    conn.close()


if __name__ == "__main__":
    main()
