---
id: admin
title: Administering with the CLI
---

This page is one continuous walkthrough: provision a tenant end to end with `qod` alone, from the first `qod login` to a user who can run a query under row-level security. It mirrors the REST walkthrough in [Onboarding](/administration/onboarding) but drives everything through the CLI instead of `curl`. Every command below is runnable as shown against a manager booted with the demo bootstrap (`admin` / `admin` at `http://localhost:20900`); swap `initech` for whatever tenant you are actually creating.

Ids returned by `create` commands (roles, users, groups, pool permission grants, ...) are opaque and generated server-side - you cannot pick them yourself, so every step that needs one either captures it with `--json` and `jq`, or is written with a `<PLACEHOLDER>` you substitute from the previous step's output.

## Log in

```bash
qod login --url http://localhost:20900 --username admin
```

Prompts for the password. This mints an admin session and stores it, plus the FlightSQL edge settings, in the `default` profile - later commands in this walkthrough need no `--url` or credentials.

## Create the tenant

```bash
qod tenant create initech --display-name "Initech"
```

`initech` is the tenant id (a lowercase slug); `--display-name` is the human-readable label shown in the UI.

## Create a database under the tenant

```bash
qod database create --tenant initech --name analytics
```

`--name` is a suffix, not the full database name - the manager composes it as `${tenant}_${name}`, so this creates the tenant-db `initech_analytics`. Every later command that takes `--db` (or a positional `DB`) wants that full composed name, not the suffix you passed here.

## Create a pool

```bash
qod pool create --tenant initech --db initech_analytics --pool bi \
  --size 2 --writeonly 1 --readonly 1
```

This starts 2 nodes for the `bi` pool: 1 writeonly and 1 readonly (the role distribution flags must sum to `--size`). Clients connect against this pool once RBAC below grants them access to it.

## Create a user

```bash
qod user create --tenant initech --username peter --role user
```

Prompts for (and confirms) a password. `--role` here is the coarse REST-level role (`user` vs `admin`), not a table-permission role - those come from `qod role create` below. Capture the new user's id for the steps that need it:

```bash
USER_ID=$(qod --json user create --tenant initech --username peter --role user | jq -r .id)
```

`--json` only changes how the response is printed - the password prompt still happens interactively either way, and the captured id comes from the create response.

## Create a role and grant it a table permission

```bash
ROLE_ID=$(qod --json role create --tenant initech --name analyst --description "Read-only" | jq -r .id)
qod role permission grant --role-id "$ROLE_ID" --verb SELECT --schema main
```

The first line creates the `analyst` role and captures its id in one shot with `--json` + `jq -r .id` - the pattern to reach for any time a later step needs an id a `create` command just minted. The second grants `SELECT` on every table in schema `main` (the omitted `--catalog` / `--table` default to `*`).

## Wire the role to the user

```bash
qod membership user-role add --user-id "$USER_ID" --role-id "$ROLE_ID"
```

`peter` now inherits the `analyst` role's table permissions.

## Grant pool access

```bash
qod pool permission grant --tenant initech --user-id "$USER_ID"
```

Table permissions alone do not let a user connect - `pool permission grant` is the separate, coarser gate that admits a principal to a pool's FlightSQL handshake. Omitting `--pool-id` grants every pool in the tenant; pass `--pool-id <ID>` to scope it to one pool.

## Verify the effective grant

```bash
qod user effective "$USER_ID"
```

Prints the closure of roles, groups, table permissions, and pool grants `peter` actually holds - the fastest way to confirm the wiring above landed before handing over credentials.

## Day 2: scaling, quarantine, maintenance, audit

Once a tenant is live, these are the one-liners reached for most often:

```bash
# Grow the pool and rebalance its role distribution
qod pool scale --tenant initech --db initech_analytics --pool bi \
  --target-size 4 --writeonly 1 --readonly 2 --dual 1

# Pull one misbehaving node out of routing rotation without stopping it
qod node quarantine --tenant initech --db initech_analytics --pool bi --node-id <NODE_ID>

# Trigger an out-of-band maintenance run (compaction, retention, orphan cleanup)
qod maintenance run --tenant initech --db initech_analytics

# Recent admin activity for the tenant
qod audit list --tenant initech --limit 20
```

See the [Command reference](/cli/reference) for every noun and verb, including federation, manifest export/import, and time-travel catalog browsing.
