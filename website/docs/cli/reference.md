---
id: reference
title: Command reference
---

Settings resolve flag > `QOD_*` env var > active profile > built-in default; see [The qod CLI](/cli/) for the full table. `--profile NAME` and `--json` are top-level flags that go before the noun, e.g. `qod --json tenant list`.

Purposes below are one line each; run `qod <noun> <verb> --help` for the full flag list of any command.

## auth

| Command | Purpose |
|---|---|
| `qod login` | Mint a session, store it and the edge settings in the active profile. |
| `qod logout` | Revoke the current session token. |
| `qod whoami` | Verify the current session. |
| `qod auth mode` | Show the auth mode (db or oidc) the manager expects. |
| `qod health` | Liveness plus pool/node counts (open endpoint). |

## tenant

| Command | Purpose |
|---|---|
| `qod tenant list` | List tenants. |
| `qod tenant create` | Create a tenant. |
| `qod tenant delete` | Delete a tenant (must have no pools). |
| `qod tenant set-disabled` | Enable or disable a tenant. |
| `qod tenant set-auth` | Change a tenant's auth provider/config. |

## database

| Command | Purpose |
|---|---|
| `qod database list` | List databases for a tenant. |
| `qod database create` | Create a tenant database. |
| `qod database update` | Update a tenant database's metastore/object-store/init settings. |
| `qod database delete` | Delete a tenant database. |

## pool

| Command | Purpose |
|---|---|
| `qod pool list` | List pools. |
| `qod pool create` | Create a pool. |
| `qod pool scale` | Change a pool's target size and role distribution. |
| `qod pool stop` | Stop a pool's nodes. |
| `qod pool delete` | Delete a pool. |
| `qod pool set-disabled` | Enable or disable a pool. |
| `qod pool set-resources` | Set CPU/memory requests for a pool's nodes. |
| `qod pool set-pod-template` | Set the Kubernetes pod template YAML for a pool. |

## pool permission

| Command | Purpose |
|---|---|
| `qod pool permission list` | List pool access grants. |
| `qod pool permission grant` | Grant pool access to a user or group. |
| `qod pool permission revoke` | Revoke a pool access grant. |

## node

| Command | Purpose |
|---|---|
| `qod node quarantine` | Take a node out of routing rotation. |
| `qod node unquarantine` | Return a node to routing rotation. |
| `qod node restart` | Restart a node. |
| `qod node set-max-concurrent` | Set a node's max concurrent statement limit. |
| `qod node statements` | Recent statement history, newest first. |
| `qod node active-statements` | Currently running statements. |

## statement

| Command | Purpose |
|---|---|
| `qod statement kill` | Kill a running statement by id. |

## user

| Command | Purpose |
|---|---|
| `qod user list` | List users. |
| `qod user create` | Create a user (tenant-scoped, or `--superuser` for tenant-less). |
| `qod user update` | Update a user's tenant, password, or role. |
| `qod user delete` | Delete a user. |
| `qod user effective` | Closure of roles, groups, table permissions, and pool grants. |

## role

| Command | Purpose |
|---|---|
| `qod role list` | List roles for a tenant. |
| `qod role create` | Create a role. |
| `qod role delete` | Delete a role. |

## role permission

| Command | Purpose |
|---|---|
| `qod role permission list` | List table permissions attached to a role. |
| `qod role permission grant` | Grant a table permission to a role. |
| `qod role permission revoke` | Revoke a table permission from a role. |

## role row-policy

| Command | Purpose |
|---|---|
| `qod role row-policy list` | List row-level security predicates on a role. |
| `qod role row-policy create` | Add a row-level security predicate. |
| `qod role row-policy update` | Update a row-level security predicate. |
| `qod role row-policy delete` | Delete a row-level security predicate. |

## role column-policy

| Command | Purpose |
|---|---|
| `qod role column-policy list` | List column-level security policies on a role. |
| `qod role column-policy create` | Add a column-level security policy. |
| `qod role column-policy update` | Update a column-level security policy. |
| `qod role column-policy delete` | Delete a column-level security policy. |

## group

| Command | Purpose |
|---|---|
| `qod group list` | List groups for a tenant. |
| `qod group create` | Create a group. |
| `qod group delete` | Delete a group. |

## membership

| Command | Purpose |
|---|---|
| `qod membership user-role add` | Add a user to a role. |
| `qod membership user-role remove` | Remove a user from a role. |
| `qod membership user-group add` | Add a user to a group. |
| `qod membership user-group remove` | Remove a user from a group. |
| `qod membership group-role add` | Add a role to a group. |
| `qod membership group-role remove` | Remove a role from a group. |
| `qod membership group-role list` | List roles held by a group. |

## catalog

| Command | Purpose |
|---|---|
| `qod catalog schemas` | List schemas in a tenant database. |
| `qod catalog tables` | List tables in a schema. |
| `qod catalog describe` | Describe a table's columns, optionally as of a snapshot/tag/timestamp. |
| `qod catalog snapshots` | List snapshots for a tenant database, optionally filtered to a table. |
| `qod catalog history` | Snapshot history for a table (operation, author, time range filters). |
| `qod catalog preview` | Preview table rows, optionally as of a snapshot/tag/timestamp. |
| `qod catalog data-diff` | Row-level diff of a table between two snapshot selectors. |
| `qod catalog schema-diff` | Schema diff of a table between two snapshot selectors. |
| `qod catalog recoverable` | Dropped tables still recoverable via undrop. |
| `qod catalog undrop` | Recover a dropped table, optionally under a different name. |
| `qod catalog restore` | Roll a live table back to a prior snapshot or tag, as a new forward snapshot. |

```bash
qod catalog restore --tenant acme --db acme_tpch --schema tpch1 --table orders --to 480 --dry-run
```

`--to` takes a snapshot id or a tag name. `qod catalog restore` is non-destructive: it runs the
dry run first and prints the change summary, then prompts to confirm before writing the new
snapshot (skip the prompt with `--yes`, or stop after the preview with `--dry-run`). Requires an
ALL grant, or DDL plus RO/RW, on the table. For dropped tables, use `qod catalog undrop` instead.

## tag

| Command | Purpose |
|---|---|
| `qod tag create` | Tag a snapshot, optionally marking it protected. |
| `qod tag delete` | Delete a tag. |
| `qod tag protect` | Change a tag's protected flag. |

## maintenance

| Command | Purpose |
|---|---|
| `qod maintenance policy` | Show the maintenance policy for a scope. |
| `qod maintenance policy-upsert` | Create or update a maintenance policy. |
| `qod maintenance policy-delete` | Delete a maintenance policy. |
| `qod maintenance run` | Trigger a maintenance run. |
| `qod maintenance runs` | List past maintenance runs. |

## manifest

| Command | Purpose |
|---|---|
| `qod manifest export` | Export the whole control-plane topology as YAML. |
| `qod manifest import` | Import a topology manifest (YAML) into the control plane. |

## federation

| Command | Purpose |
|---|---|
| `qod federation list` | List federated sources. |
| `qod federation get` | Show one federated source. |
| `qod federation create` | Create a federated source. |
| `qod federation delete` | Delete a federated source. |
| `qod federation secret list` | List secrets referenced by a federated source's setup SQL. |
| `qod federation secret set` | Set a secret (inline value or external reference). |
| `qod federation secret delete` | Delete a secret. |

## audit

| Command | Purpose |
|---|---|
| `qod audit list` | List audit log entries with filters. |
| `qod audit actions` | Distinct audit action names, for filter values. |

## usage

| Command | Purpose |
|---|---|
| `qod usage` | Usage accounting rollups. |

## history

| Command | Purpose |
|---|---|
| `qod history statements` | List past statements with filters. |
| `qod history trends` | Aggregate statement trends over time. |

## config

| Command | Purpose |
|---|---|
| `qod config client` | Edge host/port/TLS for client bootstrapping (open endpoint). |
| `qod config server` | Effective manager configuration. |

## sql

| Command | Purpose |
|---|---|
| `qod sql` | Run SQL against the FlightSQL edge; one-shot or interactive REPL. |

`qod <noun> <verb> --help` prints every flag for any command above.
