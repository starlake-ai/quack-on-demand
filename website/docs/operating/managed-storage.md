---
id: managed-storage
title: Managed object storage
---

A DuckLake database normally points at a `dataPath` you choose, authenticated either by the manager-wide object-store credentials or by that database's own `objectStore` keys (see [Tenants and databases](/operating/tenants-databases#per-database-object-store-credentials)). Managed object storage is the other option: the operator configures **one** root bucket once, and each database created with `managedStorage` gets its own prefix carved out of it, provisioned and reclaimed by the manager.

It is off by default. Nothing in this page applies until `QOD_MANAGED_STORE_ENABLED=true`.

## Configuration

The `quack-on-demand.managedObjectStore` block. As always, prefer the environment variables over editing the bundled `application.conf`.

| Key | Env var | Default | Meaning |
|---|---|---|---|
| `enabled` | `QOD_MANAGED_STORE_ENABLED` | `false` | Master switch. While off, a managed create is refused with `400`. |
| `endpoint` | `QOD_MANAGED_STORE_ENDPOINT` | _(empty)_ | S3-compatible endpoint URL. Empty means AWS default endpoint resolution. |
| `region` | `QOD_MANAGED_STORE_REGION` | `us-east-1` | Region passed to the S3 client. |
| `bucket` | `QOD_MANAGED_STORE_BUCKET` | `qod-managed` | The operator root bucket every managed prefix lives in. |
| `accessKeyId` | `QOD_MANAGED_STORE_ACCESS_KEY_ID` | _(empty)_ | Operator access key id. |
| `secretAccessKey` | `QOD_MANAGED_STORE_SECRET_ACCESS_KEY` | _(empty)_ | Operator secret access key. Redacted in config output. |
| `urlStyle` | `QOD_MANAGED_STORE_URL_STYLE` | `path` | `path` for S3-compatible stores, `vhost` for AWS. Any other value is refused at config load. |
| `retainDays` | `QOD_MANAGED_STORE_RETAIN_DAYS` | `7` | Days a deleted database's objects are retained before the purge worker removes them. `0` makes every delete immediately eligible. |
| `purgeSweepSec` | `QOD_MANAGED_STORE_PURGE_SWEEP_SEC` | `300` | Purge worker cadence, clamped to a 60 second floor. |

The manager creates the root bucket at boot if it does not exist and logs `managed object store ready: bucket '<name>'`. The probe is advisory: an unreachable or mis-credentialed store logs `managed object store unreachable: managed creates still succeed at the control plane, but their nodes will fail to ATTACH until the store recovers` and never blocks boot. It does not gate managed creates either - a `database/create` with `managedStorage: true` succeeds at the control plane regardless of store reachability; the failure only surfaces later, when a node tries to ATTACH against the missing bucket.

:::warning Versioning must be OFF on the managed bucket
On a versioned bucket a delete does not remove the object, it writes a delete marker. The purge worker's next listing then comes back empty, so it stamps the prefix as purged while every non-current version survives and keeps costing money, invisible to the inventory query below. Create the root bucket with versioning disabled, and do not enable it later.
:::

## Creating a managed database

Send `managedStorage: true` and no location of your own:

```bash
qod database create --tenant acme --name sales --kind ducklake --managed-storage
```

```bash
curl -sS -X POST "$MGR/api/database/create" \
  -H "X-API-Key: $QOD_API_KEY" -H 'Content-Type: application/json' \
  -d '{"tenant":"acme","name":"sales","kind":"ducklake","managedStorage":true}'
```

In the admin UI, pick the storage mode **Managed (QoD-provisioned)** in the database form; it takes no fields, and the resolved path shows up on the database after create.

The manager resolves:

```
dataPath = s3://<bucket>/<tenant>_<dbname>-<id8>/
```

where `id8` is the first 8 characters of the database's surrogate id. Prefixes are keyed by the incarnation, not by the name: delete `acme_sales` and create it again and the new database gets a fresh, empty prefix instead of inheriting its predecessor's files. The response carries the effective `dataPath`.

The database's `objectStore` map is filled from the config block (`s3_endpoint`, `s3_region`, `s3_url_style`, `s3_access_key_id`, `s3_secret_access_key`), so everything downstream behaves exactly as it does for a bring-your-own bucket: the path-scoped `CREATE SECRET` at node spawn, secret redaction on GET/list, the Kubernetes per-node Secret, and manifest export redaction.

Three refusals, all `400` with an actionable message:

- managed storage is not enabled on this deployment (the message names `QOD_MANAGED_STORE_ENABLED`);
- `managedStorage` sent together with a `dataPath` or an `objectStore` (one intent per call);
- `managedStorage` on a kind other than `ducklake`.

:::note No migration between the two models
`database/update` does not accept `managedStorage`. The field does not exist on the update request at all, so there is no bring-your-own-to-managed conversion and no managed-to-bring-your-own conversion. Moving a database between storage models means creating a new database and reloading the data.
:::

## Lifecycle: create, tombstone, retain, purge

1. **Create.** The prefix is resolved and a row is written to the control-plane table `qodstate_managed_prefix` (id, tenant, database name, prefix, `created_at`).
2. **Delete.** `database/delete` stamps `deleted_at` and `purge_eligible_at` on that row and returns immediately. `purge_eligible_at` is `deleted_at + retainDays`, or `deleted_at` itself when the caller asked for an immediate purge. The objects are untouched at this point. Deleting the owning tenant cascades the same stamp with the normal retention window, never immediate.
3. **Retain.** Until `purge_eligible_at`, the data is still in the bucket and still billed. That window doubles as the undrop window: the files are recoverable by hand while it lasts.
4. **Purge.** A background worker sweeps every `purgeSweepSec`, picks up every row whose `purge_eligible_at` has passed and whose `purged_at` is null, lists and batch-deletes the objects under the prefix, and stamps `purged_at` once a listing comes back empty. It logs one `managed purge:` line per outcome.

The worker is gated on HA leadership, so exactly one replica purges. It bounds the number of list-and-delete rounds per prefix per sweep, so a very large prefix drains over several sweeps instead of holding the sweep open; the tombstone row simply stays due until it is empty. A failure on one prefix logs a warning and moves on to the next row, and is retried on the following sweep.

### Purging immediately

```bash
qod database delete --tenant acme --name acme_sales --purge-managed-data
```

```bash
curl -sS -X POST "$MGR/api/database/delete" \
  -H "X-API-Key: $QOD_API_KEY" -H 'Content-Type: application/json' \
  -d '{"tenant":"acme","name":"acme_sales","purgeManagedData":true}'
```

The admin UI asks a second time on a `ducklake` delete ("Also purge managed object storage now?"); confirming sets the same flag. "Immediately" means immediately *eligible*: the delete call still returns as fast as a plain delete, and the worker removes the objects on its next sweep. The flag is ignored, with a warning in the log, on a database that has no managed prefix.

### Inventory

```sql
SELECT id, prefix, deleted_at, purge_eligible_at, purged_at
FROM qodstate_managed_prefix
ORDER BY created_at;
```

- `deleted_at IS NULL` - a live database.
- `deleted_at` set, `purged_at` null - retained. The objects still exist and still cost money; `purge_eligible_at` tells you when the worker will take them.
- `purged_at` set - the objects are gone. The row is kept as an audit trail, one row per database incarnation.

## Operational notes

### Changing the bucket strands existing tombstones

`QOD_MANAGED_STORE_BUCKET` is not a value you can swap in place. Tombstone rows hold the full `s3://bucket/...` prefix they were written with; after a bucket change those rows no longer sit under the configured root, so the purge worker refuses to list them (it will not risk enumerating a whole bucket from a mismatched prefix), warns `skipping <id>, prefix ... is not under ...` on every sweep, and the old bucket's objects leak indefinitely. Before switching buckets, purge the outstanding prefixes or migrate them, and confirm the sweep log is quiet afterwards.

### First-boot warning in HA

With several manager replicas starting together, they all probe the store and race to create the root bucket. The replicas that lose the race can log one `managed object store unreachable` warning at first boot. It is a false alarm, it self-heals on the next probe, and the store is fine. A warning that repeats on a settled cluster is real.

### Isolation posture: shared credential, prefix by convention

All managed databases share the operator credential. Isolation between tenants' managed data is prefix-by-convention, enforced by QoD's node configuration (the per-database `CREATE SECRET` scoped to that database's own `dataPath`) plus [per-pool lockdown](/operating/hardening), not by store-level ACLs. A principal who can run arbitrary configuration statements on a node with those credentials is not confined by the bucket itself. Keep node lockdown on for managed deployments, and treat the operator credential as a manager-level secret.

Per-database credential minting (per-database keys issued by the store's admin API or by IAM) is the designed follow-up; it would change only which credentials land in a database's `objectStore`, not the prefix layout or the lifecycle above.

### Credential rotation

A managed create snapshots the operator credential (`QOD_MANAGED_STORE_ACCESS_KEY_ID` / `QOD_MANAGED_STORE_SECRET_ACCESS_KEY`) into that database's own `objectStore` at create time; it is a copy, not a live reference to the config block. Rotating `QOD_MANAGED_STORE_SECRET_ACCESS_KEY` on the manager therefore only affects *future* managed creates - every managed database that already exists keeps signing requests with the old key until something rewrites its `objectStore`, and it breaks at its next node respawn once the old key is revoked at the store.

Today's remediation is manual, per database: `database/update` with a fresh `objectStore` pointing at the new secret, then recycle that database's pools so nodes pick it up on their next spawn. Per-database credential minting (above) is the designed follow-up that would make rotation blast-radius-free by construction.

## See also

- [Tenants and databases](/operating/tenants-databases) - the bring-your-own-bucket alternative and the rest of the database fields.
- [Configuration reference](/reference/configuration) - the generated table for every `managedObjectStore` key.
- [Managed DuckLake maintenance](/operating/maintenance) - compaction and snapshot expiry, which bound what a managed prefix actually stores.
