---
id: autoscaling
title: Autoscaling pools
---

A pool has a fixed number of nodes until its owner declares an **autoscale band**: a minimum and a maximum node count. Inside that band the manager adds read nodes when the pool is busy and removes them when it goes quiet, without anyone calling `pool/scale`. Outside the band nothing happens: the band is the whole permission, and a pool that has none never moves on its own.

This page covers declaring a band, the load signal behind the decisions, the timing rules, and how the band interacts with suspend and resume. For roles, sizing, and manual scaling see [Pools and cohorts](/operating/pools-cohorts).

Autoscaling behaves identically on both deployment shapes. On the local backend the added nodes are child processes of the manager (a single container grows and shrinks in place); on Kubernetes they are node pods. Nothing about the feature is Kubernetes-only.

## The band

| Field | Meaning |
|---|---|
| `minNodes` | The floor. The pool never shrinks below it, and it is the size the pool returns to when idle. |
| `maxNodes` | The ceiling. The pool never grows past it, whatever the load. |

Both bounds are set together or not at all. Anyone who can manage the pool can set them: at creation time on `POST /api/pool/create`, or later with `POST /api/pool/setAutoscale`. Omitting both bounds on `setAutoscale` clears the band and returns the pool to a fixed size.

Setting `minNodes` equal to `maxNodes` is legal and means "hold exactly this size, never scale". It is the per-pool way to freeze a pool without giving up the band: the sweep can move the pool neither up nor down, and manual scaling is constrained to that one size. Use it when you want the size pinned on the record; clear the band instead when you want manual scaling free again.

Five rules are enforced when a band is declared, each returning `400 invalid_band`:

1. `1 <= minNodes <= maxNodes`. Equal bounds pin the pool at that size.
2. `maxNodes` may not exceed `autoscale.hardCap` (default 16), the manager-wide ceiling an operator sets once.
3. `minNodes` must cover the pool's write-capable nodes (`writeonly + dual`). Scaling only ever touches read nodes, so the floor has to leave room for the writers.
4. The pool's current size must lie inside the band. A stopped pool has size 0, which is outside every band, so scale it up first and then declare the band.
5. A pool with authored [cohorts](/operating/pools-cohorts) cannot be elastic. Scaling rewrites the role distribution, which clears cohorts, so the two cannot be declared together.

Setting a band moves no nodes. The next sweep converges the pool.

### When to use one

Declare a band when the pool's load is bursty and you would otherwise size it for the peak: a BI pool that is busy during business hours, a pool behind a scheduled ETL window, a shared analytics pool with an unpredictable number of concurrent dashboards. Set `minNodes` to the size that carries the quiet baseline (including your write nodes) and `maxNodes` to what you are willing to pay for at peak.

Leave the band off for pools whose size is a deliberate constant: single-node development pools, pools pinned to specific hardware through cohorts, and pools whose cost must not vary. If you want that constant enforced rather than merely conventional, declare the band with `minNodes == maxNodes` instead of omitting it.

## The load signal

Every statement the edge serves is recorded against its pool with its duration, accumulated into per-pool one-minute buckets in memory. Each manager replica flushes its own closed buckets into the `qodstate_pool_load` table (adding to whatever is already there for that minute), because a replica only sees the statements it served itself. Buckets are purged after an hour.

From that table the manager estimates the pool's **utilization** over a five-minute window:

- **Demand** is the average concurrency implied by the work done: the sum of statement durations in the window divided by the length of the window. Five minutes of wall clock carrying ten minutes of statement time means an average of two statements in flight.
- **Capacity** is the sum of `maxConcurrentPerNode` over the pool's routable read-capable nodes (`readonly` and `dual`, healthy and not draining). A node with no cap set (`0`, meaning unbounded) counts as `autoscale.assumedConcurrencyPerNode`, 4 by default.
- **Utilization** is demand divided by capacity.

If capacity is zero, because no read-capable node is currently routable, utilization is unknown and no decision is taken for that pool on that sweep. Guessing there would be the one case where a wrong guess cannot be corrected.

Decisions are made off the pool's *desired* role distribution, not a live node census, so a node that is briefly missing while it restarts does not read as a shortfall and trigger a scale-out.

## Watermarks, streaks, and cooldowns

A sweep runs every `autoscale.sweepSeconds` (60 by default). In a manager cluster only the HA leader decides and acts; every replica still flushes its buckets.

| Direction | Trigger | Consecutive sweeps needed | Cooldown after any action |
|---|---|---|---|
| Scale out (add one `readonly` node) | utilization at or above `highWatermark` (0.8) | `outStreak`, 2 | `scaleOutCooldownSec`, 180s |
| Scale in (remove one `readonly` node) | utilization at or below `lowWatermark` (0.3) | `inStreak`, 10 | `scaleInCooldownSec`, 600s |

Four independent brakes keep the pool from oscillating: the gap between the two watermarks (nothing at all happens between 0.3 and 0.8), the streak requirement, the per-pool cooldown, and a step size of exactly one node per action. Any sweep that does not confirm the trend resets the streak to zero.

The asymmetry is deliberate. Scaling out is cheap and reversible, and a queue that is already backing up is not helped by waiting, so two sweeps of sustained pressure (about two minutes) is enough. Scaling in is disruptive and easy to regret: a node is a warm cache, and reclaiming it during a lull only to need it back three minutes later is worse than keeping it. Ten sweeps below the low watermark (about ten minutes) plus a ten-minute cooldown means a pool sheds nodes slowly, one at a time.

Two invariants bound scale-in beyond `minNodes`:

- Only `readonly` nodes are ever removed. `writeonly` and `dual` counts are invariant, so write capacity never changes underneath you.
- The last read-capable node is never removed. A pool with no `readonly` node left, or with only one reader remaining, is not scaled in even when the arithmetic would allow it, because a pool that cannot serve a SELECT can no longer report the capacity it would need to recover.

If a scale action fails three times in a row for one pool, the pool is skipped for `failureBackoffSweeps` sweeps (5 by default) so a broken pool does not retry itself in a loop. Any success resets the counter.

## Interaction with suspend and resume

Autoscaling never touches the zero boundary. Scale-in stops at `minNodes`; going to zero is the job of pool suspension, which keeps the role distribution and wakes the pool on the next FlightSQL statement.

The two features stack into a staircase for an idle pool:

1. Peak: the pool has grown toward `maxNodes` under load.
2. Load drops: scale-in walks it down one node per action until it sits at `minNodes`.
3. The pool stays idle: a suspend policy scales it to zero, keeping its role distribution.
4. A statement arrives: the pool resumes at `minNodes` and serves the query after the wake.

Steps 1 and 2 are this feature. Step 3 is a policy decision, and the automatic idle-suspend sweep ships in the hosted service; in a self-managed deployment you suspend and resume explicitly through `POST /api/pool/suspend` and `POST /api/pool/resume`, or leave the pool sitting at `minNodes`.

Resuming a pool starts a scale-out cooldown. The burst of queries that arrives right after a wake is not evidence of sustained demand, and the pool's baseline nodes have not warmed their caches yet, so the manager gives them time before considering the pool overloaded.

## Manual scaling and `outside_band`

`POST /api/pool/scale` on a pool that has a band refuses a `targetSize` outside it:

```json
{"code":"outside_band",
 "message":"targetSize 8 is outside the autoscale band [2, 6]; adjust the band first via pool/setAutoscale"}
```

This is not a permission problem. A manual scale outside the band would simply be undone by the next sweep, so the manager refuses it and points at the setting that actually decides the range. Widen the band (or clear it) first, then scale. Inside the band, manual scaling works exactly as before; the next sweep takes over from wherever you left the pool.

## Observability

Every action taken by the sweep leaves three traces:

- One manager log line, for example `autoscale: acme/acme_sales/bi out 2 -> 3 util=0.91`. Failures log a warning with the error and the backoff decision.
- One [audit log](/administration/audit-log) row with actor `autoscale`, action `pool.scale`, and detail carrying the direction, target size, and the utilization that triggered it.
- One `PoolScaled` manager event carrying `fromSize`, `toSize`, and the reason, for any module listening on the SPI.

Autoscale actions go through the same mutation gate as a human-driven scale, so where quotas apply they apply here too: an action that would exceed a tenant's node quota is refused and recorded as a failure.

## Configuration

Global settings live under `quack-on-demand.autoscale`. They apply to every pool; the per-pool decision is the band. Every setting below is inert until at least one pool declares a band.

| Key | Env var | Default | Meaning |
|---|---|---|---|
| `enabled` | `QOD_AUTOSCALE_ENABLED` | `true` | Manager-wide kill switch for the sweep. Bands stay recorded and validated; they are simply not acted on. |
| `sweepSeconds` | `QOD_AUTOSCALE_SWEEP_SEC` | `60` | Sweep interval, clamped to a 30s floor. |
| `windowMinutes` | `QOD_AUTOSCALE_WINDOW_MINUTES` | `5` | Length of the load window used for the utilization estimate. |
| `highWatermark` | `QOD_AUTOSCALE_HIGH_WATERMARK` | `0.8` | Utilization at or above which scale-out is considered. |
| `lowWatermark` | `QOD_AUTOSCALE_LOW_WATERMARK` | `0.3` | Utilization at or below which scale-in is considered. Must be below `highWatermark`. |
| `outStreak` | `QOD_AUTOSCALE_OUT_STREAK` | `2` | Consecutive sweeps above the high watermark before a node is added. |
| `inStreak` | `QOD_AUTOSCALE_IN_STREAK` | `10` | Consecutive sweeps below the low watermark before a node is removed. |
| `scaleOutCooldownSec` | `QOD_AUTOSCALE_OUT_COOLDOWN_SEC` | `180` | Per-pool quiet period after any action, or a resume, before scaling out. |
| `scaleInCooldownSec` | `QOD_AUTOSCALE_IN_COOLDOWN_SEC` | `600` | Per-pool quiet period after any action before scaling in. |
| `assumedConcurrencyPerNode` | `QOD_AUTOSCALE_ASSUMED_CONCURRENCY` | `4` | Capacity credited to a node with no `maxConcurrentPerNode` cap. |
| `hardCap` | `QOD_AUTOSCALE_HARD_CAP` | `16` | Manager-wide ceiling on any pool's `maxNodes`. |
| `failureBackoffSweeps` | `QOD_AUTOSCALE_FAILURE_BACKOFF_SWEEPS` | `5` | Sweeps to skip a pool after three consecutive failed actions. |

`enabled = false` is the operator-wide brake, for a manager where elasticity has to stop moving right now regardless of what pool owners declared. Stopping a *single* pool is a per-pool decision instead: set `minNodes == maxNodes` to hold it at its current size, or clear the band to take it out of the sweep entirely. See the [Configuration reference](/reference/configuration) for the full list of keys.

## Worked example

A BI pool that needs one writer at all times, one reader at the quiet baseline, and up to five readers at peak. The floor is 2 (the writer plus one reader) and the ceiling is 6.

```bash
# Create the pool at its baseline size with the band declared
curl -sS -X POST http://localhost:20900/api/pool/create \
  -H "X-API-Key: $QOD_API_KEY" -H 'Content-Type: application/json' \
  -d '{"tenant":"acme","tenantDb":"acme_sales","pool":"bi","size":2,
       "roleDistribution":{"writeonly":1,"readonly":1,"dual":0},
       "minNodes":2,"maxNodes":6}'

# Widen the ceiling later
curl -sS -X POST http://localhost:20900/api/pool/setAutoscale \
  -H "X-API-Key: $QOD_API_KEY" -H 'Content-Type: application/json' \
  -d '{"tenant":"acme","tenantDb":"acme_sales","pool":"bi","minNodes":2,"maxNodes":8}'

# Clear the band: omit both bounds. The pool keeps its current size, fixed.
curl -sS -X POST http://localhost:20900/api/pool/setAutoscale \
  -H "X-API-Key: $QOD_API_KEY" -H 'Content-Type: application/json' \
  -d '{"tenant":"acme","tenantDb":"acme_sales","pool":"bi"}'
```

The same three operations with the [qod CLI](/cli/):

```bash
qod pool create --tenant acme --db acme_sales --pool bi --size 2 \
  --writeonly 1 --readonly 1 --min-nodes 2 --max-nodes 6

qod pool set-autoscale --tenant acme --db acme_sales --pool bi --min-nodes 2 --max-nodes 8

qod pool set-autoscale --tenant acme --db acme_sales --pool bi
```

`qod pool list` reports the band alongside the pool's size, so you can see at a glance which pools are elastic and where they currently sit inside their range.
