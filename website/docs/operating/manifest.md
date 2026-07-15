---
id: manifest
title: Manifest backup and restore
---

The config manifest is the entire control-plane configuration serialized as one YAML document: every tenant, database, pool, federated source, role, group, user, and grant. Export it to back up or version-control your configuration, then import it to restore, clone an environment, or apply a reviewed change. The manifest holds configuration only; it does not contain table data or the DuckLake catalogs.

Manifest export and import are cross-tenant operations and are **restricted to superusers**. A tenant-scoped session is rejected with `403 superuser_required`; a static `QOD_API_KEY` caller is admitted. The examples below use the [qod CLI](/cli/); they assume `qod login` has stored a superuser session, or `QOD_API_KEY` is set for CI scripts.

## Export

```bash
qod manifest export --out manifest.yaml
```

`GET /api/manifest/export` returns the manifest as `application/yaml`. The top of the document records provenance:

```yaml
apiVersion: quack-on-demand/v1
kind: ConfigManifest
exportedAt: 2026-06-10T12:00:00Z
exportedFrom:
  managerVersion: 0.3.2
  hostname: qod-1
```

### What the export contains

The document mirrors the object hierarchy:

- `tenants[]` - each with its `authProvider` / `authConfig`, and nested:
  - `tenantDbs[]` - `kind`, `metastore`, `dataPath`, `objectStore`, `defaultDatabase`, `defaultSchema`, and nested `federatedSources[]` (with their `secrets[]`).
  - `pools[]` - `tenantDb`, `roleDistribution`, `maxConcurrentPerNode`, `disabled`, and optional `cohorts[]` placement.
  - `identities[]` - external identity mappings for the tenant.
- `roles[]` - each `(tenant, name)` with its `permissions[]` (catalog/schema/table/verb).
- `groups[]` - each `(tenant, name)` with its assigned `roles[]`.
- `users[]` - `tenant` (omitted/null for a superuser), `username`, `passwordHash`, `role`, `enabled`, and the user's `roles[]`, `groups[]`, and `poolGrants[]`.

### Sensitivity of an export

Plaintext credentials are never written on export, but the file is NOT free of credential material:

- User `password` (plaintext) is **omitted** entirely from exported users; there is no plaintext to export, only bcrypt hashes are stored.
- User `passwordHash` carries the user's **real bcrypt hash verbatim**. This is what lets a backup restore the same credential without anyone re-typing passwords.
- Federated secret `value`s are written as `***REDACTED***`; an `externalRef` is written verbatim.

Because bcrypt hashes of weak passwords are subject to offline cracking, **treat an exported manifest as sensitive**: do not commit it to a public repository, and store it with the same care as any other credential material (private repository, secret store, or encrypted backup).

A plain export-then-import round-trip preserves credentials without rotation: exported hashes are applied verbatim on import, an absent hash falls back to the existing row, and redacted secret values are reused from the existing rows (see below).

## Import

```bash
qod manifest import manifest.yaml
```

`POST /api/manifest/import` takes the YAML body and returns a summary of how many top-level resources were in the manifest:

```json
{"tenants":2,"tenantDbs":3,"pools":4,"roles":5,"groups":2,"users":7}
```

Import validates the whole document before writing anything. On failure it returns `400` and changes nothing:

- `invalid-yaml` - the body did not parse as YAML.
- `invalid-manifest` - validation failed: a wrong `apiVersion` (must be `quack-on-demand/v1`), duplicate keys (tenant name, `(tenant, role)`, `(tenant, group)`, `(tenant, user)`, or a nested duplicate), or a user/role/group that references a tenant not present in the manifest or already in the database.

After a successful import the manager reloads its in-memory caches (tenants, databases, pools, RBAC effective sets) immediately. No restart is needed for the new configuration to take effect.

### Apply semantics

This is the part to understand before importing a hand-edited file. The rules differ between top-level and nested resources:

- **Top level is additive (upsert only).** Tenants, roles, groups, and users that appear in the manifest are created or updated. Top-level resources that are **absent** from the manifest are left untouched: importing a manifest that lists only `tenant: acme` does not delete `tenant: widgets`. None of the top-level lists are required, so a manifest may omit `users`, `roles`, or `groups` entirely. An omitted user is ignored, never deleted; the importer only ever upserts the users it finds in the file.
- **Nested collections under a present parent are replace (delete-then-upsert).** For any parent that *is* in the manifest, its child collections are made to match the manifest exactly. Children present in the manifest are upserted; children that exist in the database but are missing from the manifest are deleted.

:::caution Nested omissions delete
Because nested collections are replaced, omitting a child under a parent you do include removes it. If you export, then hand-edit a tenant to drop one pool from its `pools` list and re-import, that pool is deleted. A role's `permissions` are fully replaced; a user's `poolGrants` are fully replaced. To change one tenant safely, keep all of its databases, pools, roles, and grants in the file, or edit a full export rather than a partial document.
:::

### Passwords and secrets on import

- A user with a `passwordHash` value has it applied **verbatim** (no re-hashing): this is the field an export populates, so a round-trip restores the exact credential the user had at export time.
- Otherwise, a user with a `password` value sets it (the value is treated as a bcrypt hash if it looks like one, otherwise as plaintext to be hashed). A user with **neither** field reuses the bcrypt hash captured from the existing row at the start of the import, so hand-written manifests that omit both keep existing users' passwords unchanged.
- A federated secret left as `value: "***REDACTED***"` (and no `externalRef`) reuses the existing stored value. This is why a redacted export re-imports without re-typing credentials.

## Typical workflows

- **Backup.** Export on a schedule and store `manifest.yaml` somewhere access-controlled (private repository, secret store, or encrypted backup). The file carries no plaintext credentials, but it DOES carry each user's bcrypt password hash, so treat it as sensitive and keep it out of public repositories.
- **Promote a change.** Export, edit in a reviewed pull request, re-import. Validation plus all-or-nothing apply makes a bad edit fail before it touches the store.
- **Clone an environment.** Import a source environment's manifest into a fresh manager. Exported `passwordHash` values carry user credentials over as-is; provide real `password` / secret `value`s only for anything the manifest does not carry, since there is nothing to reuse on a first import.

For the objects the manifest carries, see "Tenants and databases", "Pools and cohorts", [Federation](/operating/federation), and the [Access control model](/operating/rbac-model).
