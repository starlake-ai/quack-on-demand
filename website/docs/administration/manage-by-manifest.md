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

`src/main/resources/bootstrap-demo.yaml` is a complete worked example.

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
curl -sS -H "X-API-Key: $TOKEN" -H 'Content-Type: text/plain' \
  --data-binary @manifest.yaml http://localhost:20900/api/manifest/import
```

Import is upsert plus prune-within-parent: resources present in the YAML are created or updated; siblings omitted under a parent that IS in the YAML are deleted. For example, listing a tenant with two of its three pools deletes the third; a tenant absent from the YAML is left untouched. Re-supply any secret values, and set a `password` on a user to (re)set it.

**Verify:** re-export and diff, or confirm the change on the relevant Administration page (Nodes board, Users tab, and so on).

**Related:** [Manifest backup and restore](/operating/manifest), [Back up and restore](/administration/lifecycle-config#back-up-and-restore).
