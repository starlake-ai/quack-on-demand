---
id: index
title: The qod CLI
---

`qod` is a single command-line client for both planes of quack-on-demand: the manager's admin REST surface on `:20900` (tenants, databases, pools, nodes, RBAC, catalog, federation, audit, usage) and the FlightSQL query plane on `:31338` (`qod sql`, one-shot or interactive). Every REST path has a matching noun-verb command - `POST /api/tenant/create` is `qod tenant create`, `GET /api/pool/list` is `qod pool list`, and so on - so if you know the API you already know most of the CLI.

## Install

```bash
pip install qod-cli
```

`qod-cli` is released on PyPI on the same version number as the manager itself, so pin the CLI to the manager you are talking to. Requires Python 3.10 or later.

To install from a source checkout instead (for example while developing the CLI itself):

```bash
pip install ./cli
```

Both routes install the `qod` console script on `$PATH`.

**Windows.** The same `pip install qod-cli` installs a `qod.exe` entry point; there is no separate Windows package. The profile file lives at `%APPDATA%\qod\config.toml` instead of the Linux/macOS XDG path, and the interactive `qod sql` REPL gets line editing and history from `pyreadline3` (installed automatically as a Windows-only dependency of `qod-cli`).

## Login and profiles

```bash
qod login --url http://localhost:20900 --username admin
```

Against the demo bootstrap this prompts for the `admin` / `admin` dev password (the password is always a prompt, never a flag, so it never lands in shell history). A successful login mints a session and writes it to the active profile together with the FlightSQL edge settings the manager reports back (host, port, TLS) - one `qod login` configures both the REST plane and the SQL plane in one shot.

Profiles are named sections of a TOML file at a platform config directory: `~/.config/qod/config.toml` on Linux, `~/Library/Application Support/qod/config.toml` on macOS, `%APPDATA%\qod\config.toml` on Windows (override the whole path with `QOD_CONFIG_FILE`). The file is written with mode `0600` because it can hold a session token and, opt-in, a SQL password. The default profile is named `default`; switch profiles with `--profile prod` (a top-level flag, before the noun) or the `QOD_PROFILE` environment variable, so one machine can hold a `default` profile against a local dev manager and a `prod` profile against a real deployment without either clobbering the other.

For non-interactive callers such as CI, skip `qod login` entirely and set `QOD_API_KEY` to the manager's static REST key - every command resolves credentials the same way, so a static key works everywhere a session token would.

## Settings resolution

Every setting resolves in this order, highest priority first:

1. Command-line flag (`--tenant`, `--url`, ...)
2. `QOD_*` environment variable
3. Value saved in the active profile
4. Built-in default

| Setting | Env var | Notes |
|---|---|---|
| `manager_url` | `QOD_MANAGER_URL` | REST plane base URL. |
| `api_key` | `QOD_API_KEY` | Static API key; wins over `token` when both are set. |
| `token` | `QOD_TOKEN` | Session JWT, normally written by `qod login`, not set by hand. |
| `edge_host` | `QOD_HOST` | FlightSQL edge host. |
| `edge_port` | `QOD_PORT` | FlightSQL edge port. |
| `edge_tls` | `QOD_TLS` | TLS to the edge. |
| `edge_tls_verify` | `QOD_TLS_VERIFY` | Verify the edge TLS cert; off by default since the edge ships a self-signed cert. |
| `tenant` | `QOD_TENANT` | Default tenant for `qod sql`. |
| `pool` | `QOD_POOL` | Default pool for `qod sql`. |
| `sql_user` | `QOD_USER` | FlightSQL username; set by `qod login`. |
| `sql_password` | `QOD_PASSWORD` | FlightSQL password; opt-in storage, prompted when unset. |
| `superuser` | `QOD_SUPERUSER` | Send the superuser call header on `qod sql`. |

## Output

Rich tables are the default, sized to the terminal. Pass `--json` - a top-level flag, before the noun - for the raw JSON response body, the stable interface for scripting:

```bash
qod --json tenant list | jq -r '.[].id'
```

`qod sql` additionally supports `--csv` for spreadsheet-friendly output. Exit codes follow the Unix convention: `0` success, `1` a server or API error (the server's error body is printed verbatim, e.g. `tenant_forbidden`), `2` a usage error (missing or invalid arguments).

## Where to go next

- [Administering with the CLI](/cli/admin) - a full walkthrough provisioning a tenant, database, pool, users, and RBAC.
- [Running SQL](/cli/sql) - one-shot queries, the REPL, and time travel.
- [Command reference](/cli/reference) - every noun and verb.
