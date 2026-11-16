#!/usr/bin/env python3
"""Lists the Task 15a bucket and checks it against the bundle builds (Step 2).

Usage: uv run --no-project --with boto3 python -I s3_list.py <p2> [label]

Reads the store's keys from <p2>/secrets/weed.json (never printed) and the last build answer of
the s3 table from <p2>/t15a/build-acme_lakes3.json (written by s3_db.py and s3_e2e.py), lists
s3://qod-p2-search/acme_lakes3/ and checks:
  - one bundle object under acme_lakes3/.qod_search/<index_id>/ per file the build reported;
  - each bundle's size in the listing equals the `bytes` the build endpoint reported for it;
  - at least as many Parquet objects as bundles;
  - no `.tmp-` object anywhere in the bucket.
Prints every object's key (under the bucket) and size, one PASS or FAIL line per check, and
appends the listing to <p2>/t15a/s3-listing.json under `label`. Exit 0 when every check passes.
"""
import json
import os
import sys

import boto3
from botocore.config import Config

BUCKET = "qod-p2-search"
P2 = sys.argv[1]
LABEL = sys.argv[2] if len(sys.argv) > 2 else "listing"

with open(os.path.join(P2, "secrets", "weed.json"), encoding="utf-8") as f:
    keys = json.load(f)
s3 = boto3.client("s3", endpoint_url="http://127.0.0.1:27460", aws_access_key_id=keys["key"],
                  aws_secret_access_key=keys["secret"], region_name="us-east-1",
                  config=Config(s3={"addressing_style": "path"}))
del keys
objs = [(o["Key"], o["Size"]) for p in s3.get_paginator("list_objects_v2").paginate(Bucket=BUCKET)
        for o in p.get("Contents", [])]
for k, size in objs:
    print(f"  {k} {size}", flush=True)
bundles = {k: size for k, size in objs if k.startswith("acme_lakes3/.qod_search/")}
parquet = [k for k, _ in objs if k.endswith(".parquet")]
tmp = [k for k, _ in objs if ".tmp-" in k]
with open(os.path.join(P2, "t15a", "build-acme_lakes3.json"), encoding="utf-8") as f:
    built = json.load(f)["files"]
prefix = f"s3://{BUCKET}/"
reported = {b["bundle"][len(prefix):]: b["bytes"] for b in built if b["bundle"].startswith(prefix)}
fails = 0


def check(label, ok, observed):
    global fails
    fails += 0 if ok else 1
    print(f"{'PASS' if ok else 'FAIL'} {label}: {observed}", flush=True)


print(f"{len(bundles)} bundles {len(parquet)} parquet {len(objs)} objects", flush=True)
check("every bundle the build reported is in the bucket, and nothing else under .qod_search",
      set(reported) == set(bundles) and len(reported) == len(built),
      f"reported {len(built)}, listed {len(bundles)}")
check("each bundle's listed size equals the size the build reported",
      all(bundles.get(k) == v for k, v in reported.items()),
      {k.rsplit("/", 1)[-1]: (v, bundles.get(k)) for k, v in reported.items()})
check("at least as many Parquet objects as bundles", len(parquet) >= len(bundles),
      f"{len(parquet)} parquet, {len(bundles)} bundles")
check("no .tmp- object in the bucket", not tmp, tmp)
path = os.path.join(P2, "t15a", "s3-listing.json")
data = json.load(open(path, encoding="utf-8")) if os.path.exists(path) else {}
data[LABEL] = {"objects": objs, "reported": reported}
with open(path, "w", encoding="utf-8") as f:
    json.dump(data, f, indent=1)
sys.exit(1 if fails else 0)
