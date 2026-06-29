---
id: manage-by-manifest
title: Manage by manifest
---

The whole control plane is one declarative YAML document: every tenant, database, pool, role, group, user, grant, and federated source. The `Manifest (YAML)` blocks throughout this section are fragments of that document. Edit it, keep it under version control, and apply it by exporting the current state, changing the YAML, and re-importing. The field reference is on the [Manifest backup and restore](/operating/manifest) page.

## Manifest shape

```yaml
apiVersion: quack-on-demand/v1
kind: ConfigManifest
tenants:
  - name: acme
    tenantDbs:
      - { name: acme_tpch, kind: ducklake, defaultSchema: tpch1 }
    pools:
      - name: bi
        tenantDb: acme_tpch
        roleDistribution: { writeonly: 1, readonly: 1, dual: 1 }
roles:
  - tenant: acme
    name: analyst
    permissions:
      - { catalog: acme_tpch, schema: tpch1, table: customer, verb: SELECT }
groups:
  - { tenant: acme, name: analysts, roles: [analyst] }
users:
  - { tenant: acme, username: alice, groups: [analysts], poolGrants: [{ pool: bi }] }
```

The fields `exportedAt` (an ISO-8601 instant) and `exportedFrom` (`{managerVersion, hostname}`) are required by the decoder and written automatically on export; when authoring a manifest from scratch, use any valid ISO-8601 instant and any `managerVersion`/`hostname` string, or start from `src/main/resources/bootstrap-demo.yaml`, which is a complete importable example.

## Export the current state

**Goal:** capture the live control-plane configuration as YAML.

**Prerequisites:** superuser session (export is cross-tenant; a tenant session is rejected with `403 superuser_required`).

**Steps (UI):** open **Config** -> **Manifest** -> **Download YAML**.

**REST equivalent**

```bash
curl -sS -H "X-API-Key: $TOKEN" http://localhost:20900/api/manifest/export > manifest.yaml
```

User passwords are omitted and secret values are redacted on export, so a downloaded manifest is safe to commit. Re-supply them on import.

**Related:** [Manifest backup and restore](/operating/manifest).

## Edit and re-import

**Goal:** apply an edited manifest.

**Prerequisites:** superuser session.

**Steps (UI):** edit the YAML, then **Config** -> **Manifest** -> **Import**.

**REST equivalent**

```bash
curl -sS -X POST -H "X-API-Key: $TOKEN" -H 'Content-Type: text/plain' --data-binary @manifest.yaml http://localhost:20900/api/manifest/import
```

Import uses two different strategies depending on nesting level. At the top level the import is purely additive (upsert only): tenants, roles, groups, and users present in the YAML are created or updated; top-level resources absent from the manifest are left untouched - importing only `tenant: acme` does not delete `tenant: widgets`, and an omitted user is never deleted. Within a parent that IS in the manifest, nested collections are replaced (delete-then-upsert): the child collection in the database is made to match the manifest exactly, so a child absent from the YAML is deleted. For example, listing a tenant with only two of its three pools drops the third; a role's `permissions` and a user's `poolGrants` are fully replaced on import. Re-supply any secret values, and set a `password` on a user to (re)set it.

**Verify:** re-export and diff, or confirm the change on the relevant Administration page (Nodes board, Users tab, and so on).

**Related:** [Manifest backup and restore](/operating/manifest), [Back up and restore](/administration/lifecycle-config#back-up-and-restore).
