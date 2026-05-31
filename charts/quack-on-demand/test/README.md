# Local test rig (kind + Helm)

End-to-end smoke for the chart on a local [kind](https://kind.sigs.k8s.io/) cluster. The rig is intentionally self-contained — it does not require an external Postgres or a published image.

## Prerequisites

- `kind` 0.20+
- `kubectl`
- `helm` 3.12+
- `docker`
- ~8 GB free RAM (the manager image + Postgres + kind nodes)

## One command

```bash
./charts/quack-on-demand/test/install-local.sh
```

This:

1. Creates a kind cluster named `qod-test` (reused if it already exists).
2. Builds the manager image (`quack-on-demand:local`) from the current source tree.
3. Loads the image into the kind cluster.
4. Applies a minimal in-cluster Postgres ([`local-postgres.yaml`](local-postgres.yaml)): single Pod + Service, ephemeral `emptyDir`. **Not production-grade** — that's the point. The chart itself expects an external Postgres; this manifest exists only to satisfy the smoke.
5. `helm install`s the chart pointing at that Postgres + the local image, with TLS off and an inline admin password.
6. Waits for the manager pod to be `Ready` and `/health` to return OK.
7. Verifies the manager spawned the bootstrap Quack node pods (3 by default — one each of WriteOnly / ReadOnly / Dual).

## Env knobs

| Var | Default | Purpose |
|---|---|---|
| `KIND_CLUSTER` | `qod-test` | kind cluster name |
| `IMAGE` | `quack-on-demand:local` | manager image ref |
| `NAMESPACE` | `qod` | install namespace |
| `RELEASE` | `qod` | helm release name |
| `BUILD` | `1` | `1` runs `docker build`; `0` reuses an already-loaded image. Same convention as `scripts/run-jar.sh`. |
| `NUKE` | `0` | `1` deletes the namespace before reinstalling — wipes the Postgres `emptyDir`, the helm release, and every Quack node pod. Mirrors `NUKE` in `scripts/run-jar.sh`. |
| `SF` | unset | TPC-H scale factor. When set, seeds TPC-H into the in-cluster Postgres before the manager boots — same `scripts/load-tpch-dbgen.sh` flow `run-jar.sh` uses. Requires `duckdb` on the host. `SF=1` ≈ 6M lineitem rows. |

```bash
# Fresh boot from a clean Postgres + TPC-H SF=1 seeded:
NUKE=1 SF=1 ./charts/quack-on-demand/test/install-local.sh

# Just nuke and reinstall without seeding:
NUKE=1 ./charts/quack-on-demand/test/install-local.sh

# Iterate on the chart without rebuilding the image:
BUILD=0 ./charts/quack-on-demand/test/install-local.sh
```

The script also auto-cleans orphan `managed-by=quack-on-demand` pods + services from a prior failed bootstrap before installing — so reruns without `NUKE=1` still recover cleanly (the manager's bootstrap would otherwise 409 on the leftover pod name).

## Verify by hand

After the script reports `smoke OK` (the script prints the exact port-forward commands at the end). The full names that Helm renders for the default release/chart combo are:

```bash
# Port-forward the admin UI (REST + UI on :20900)
kubectl -n qod port-forward svc/qod-quack-on-demand 20900:20900
# Open http://localhost:20900/ui/  (login admin / admin)

# Port-forward FlightSQL (gRPC on :31338)
kubectl -n qod port-forward svc/qod-quack-on-demand-flightsql 31338:31338
# JDBC: jdbc:arrow-flight-sql://localhost:31338?useEncryption=false&user=acme/sales/<user>&password=<password>

# Watch the quack node pods the manager spawns
kubectl -n qod get pods -l managed-by=quack-on-demand -w
```

Note: Helm prepends the chart name (`quack-on-demand`) to the release name (`qod`) unless the release name already contains it. So services land at `qod-quack-on-demand`, not `qod`. The script's tail message resolves this from the cluster so its copy-paste lines always match what's actually there.

## Tear down

```bash
kind delete cluster --name qod-test
```

## Known limitations

- Postgres data is ephemeral (`emptyDir`). Recreating the cluster wipes all state — that's a feature for a smoke rig.
- The manager image is built on every run unless `SKIP_BUILD=1`. The Dockerfile uses cached layers, so reruns are fast once the JDK + sbt deps are cached.
- TLS is disabled for the smoke; production deploys should keep `flightsql.tls.enabled=true` and either let the manager auto-generate or mount a CA-signed cert.