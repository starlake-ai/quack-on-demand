---
name: quack-on-demand
description: Operate a quack-on-demand FlightSQL gateway - boot/stop the manager, manage tenants/pools/ACLs, inspect nodes, run SQL
---

# Quack on Demand

Quack on Demand is a multi-tenant FlightSQL gateway in front of DuckDB Quack + DuckLake. The manager exposes a REST control plane (`/api/...`) and a React admin UI (`/ui/...`) on the same port (default `:20900`), and a FlightSQL edge on a separate port (default `:31338`). Pools of Quack nodes are spawned as local subprocesses or K8s pods.

Use this skill when the user wants to:
- Boot, restart, or stop the manager
- Create / scale / delete tenants and pools
- Grant / revoke ACLs
- Inspect node health, throughput, latency
- See what SQL recently ran and where
- Run ad-hoc SQL against the FlightSQL edge
- Diagnose typical failure modes (dead nodes, expired sessions, ACL denials)

## Tooling

Everything here runs through the `qod` CLI (PyPI package `qod`) against a live
manager - no source checkout is needed. The pieces:

- `qod start` / `qod stop` / `qod status` - run a manager from the released
  uber-jar (auto-downloaded and cached) against your Postgres; `qod setup`
  persists the `QOD_*` settings it needs
- `qod start --demo` - fully self-contained evaluation stack (embedded Postgres)
- `qod <noun> <verb>` - the REST control plane (tenants, databases, pools,
  nodes, RBAC, policies, telemetry)
- `qod sql` - run SQL against the FlightSQL edge (one statement, a script, or a REPL)
- Docs: https://docs.starlake.ai/qod (guides, configuration reference, REST API)

Every manager config scalar has a `QOD_*` env-var override (or `PROXY_*` for
FlightSQL edge keys); prefer env vars - the bundled `application.conf` is baked
into the jar.

## CLI setup (do this before CLI-driven operations)

The `qod` CLI is the primary way to drive the manager. Before operating
through it, make sure it is installed, current, and logged in.

**1. Installed and current?**

```bash
qod --version                # prints: qod X.Y.Z; command not found = not installed
curl -s https://pypi.org/pypi/qod/json | python3 -c "import sys,json; print(json.load(sys.stdin)['info']['version'])"
```

- Not installed - install it: `uv tool install qod` when `uv` is available,
  else `pip install qod`. (`uvx qod ...` also works for one-off runs with no
  install.)
- Versions equal, or the installed one ends in `.dev0` (a source checkout) -
  proceed.
- Installed older than PyPI - upgrade with the command matching the install
  method: `uv tool upgrade qod` (uv tool installs), `pip install -U qod`
  (pip); `uvx qod` users resolve the latest on a fresh cache (`uvx qod@latest`
  forces it).
- PyPI unreachable (offline, proxy) - skip the check silently; never block
  operations on it.
- After an upgrade, `qod skill install` refreshes the locally installed copy
  of this skill; it prompts for the target LLM (claude, copilot, gemini, all)
  and `--platform <name>` skips the prompt. No-op for plugin installs, which
  update via `/plugin marketplace update`.

**2. Logged in?** `qod whoami` verifies the current session. If it errors,
log in first - every non-public command needs a session:

```bash
qod login --username admin                  # prompts for the password; system realm
qod login --username alice --tenant acme    # tenant-scoped principal
```

`qod login` mints a session and stores the token plus the FlightSQL edge
settings in the active CLI profile file (mode 0600; `QOD_CONFIG_FILE`
overrides the path), so subsequent commands need no flags. Non-interactive
alternative: set `QOD_API_KEY` (static key) or `QOD_TOKEN` (session or PAT
token) in the environment - each command sends it as `X-API-Key`. Use
`--profile <name>` (or `QOD_PROFILE`) to keep several managers side by side,
`QOD_MANAGER_URL` for a non-default manager URL, and `qod --json ...`
anywhere you need raw JSON for scripting.

## Booting

On Kubernetes, the Helm chart is published as an OCI artifact per release:
`helm install qod oci://ghcr.io/starlake-ai/charts/quack-on-demand --version <release>`
(external Postgres required; see https://docs.starlake.ai/qod). Locally,
`qod start` supervises the released uber-jar, downloaded and cached on first
use (Java 21+ required; the `duckdb` CLI and node spawn scripts are
provisioned automatically):

```bash
# One-time: persist Postgres coordinates, admin password, API key, TLS prefs
# so a bare `qod start` works afterwards (a real env var still wins)
qod setup

# Default: TLS edge, DB auth on, Postgres state, admin user seeded
qod start

# Pin a release, or run a jar you already have
qod start --version 0.8.3
qod start --jar /path/to/quack-on-demand-assembly.jar

# Disable DB auth (UI then skips the login screen)
QOD_AUTH_DB_ENABLED=false qod start

# Is anything running, and what is it serving?
qod status

# Stop everything (manager + quack nodes)
qod stop
```

`qod start` runs the manager in the foreground; Ctrl-C tears the manager and
its nodes down gracefully (same as `qod stop` from another terminal - never
kill the JVM directly, or DuckDB node processes are orphaned holding ports
`21900+`). Durable state (`certs/`, DuckLake data, node state) lives under the
platform user-data dir (`~/.local/share/qod` on Linux,
`~/Library/Application Support/qod` on macOS); jars and the provisioned duckdb
CLI cache under the user-cache dir. Default credentials:
`admin@localhost.local` / `admin` (rotate via `QOD_ADMIN_PASSWORD`). The
manager logs `auth: providers configured` when DB auth is on, and
`auth: OPEN` otherwise.

**Self-contained demo.** For evaluation with no external Postgres and no
Docker, `qod start --demo` boots everything against an embedded, ephemeral
Postgres (zonky), seeds the minimal demo, and tears it all down on exit
(`qod setup`'s stored config is deliberately not applied):

```bash
qod start --demo
```

It creates a demo home under `/tmp/qod-demo` (override `QOD_DEMO_HOME`) holding the embedded PG data dir + the DuckLake data path, runs the whole demo config overlay (TLS off, REST open, ACL/RLS/CLS on) - a posture produced ONLY on this code path, never on a normal `qod start` boot - seeds tenant `acme` (`acme_tpch.tpch1`) with TPC-H at SF 0.1, and prints a connect banner. Seeded principals: `alice`/`demo-alice` (analyst - sees `c_phone` masked + only `BUILDING` rows), `acme-admin`/`demo-acme-admin` (full), and any ungranted table is denied. Ctrl-C stops the manager, stops the embedded PG, and deletes the demo home. Insecure by design; not for production.

Bootstrap is driven by `QOD_BOOTSTRAP_YAML` - a path (or `classpath:` reference) to a YAML manifest. Bootstrap runs only when you request demo data: pass `LOAD_TPCH=1` or `LOAD_TPCDS=1` (or `LOAD_SSB=1`, or `LOAD_TPC=1` for all) in the environment of `qod start`, which seeds the benchmark in the background and sets `QOD_BOOTSTRAP_YAML` to the bundled demo manifest (`classpath:bootstrap-demo.yaml`). A bare `qod start` does NOT bootstrap. The demo manifest imports two tenants (`acme` with pools `bi` and `etl`, `globex` with pool `bi`), 2 nodes per pool, and a starter RBAC role graph. The import is idempotent: it is skipped when the demo tenants already exist, so restarting the manager is safe. (`LOAD_*` seeding is not yet supported on Windows through `qod start` - the bundled loaders are bash.)

A second profile targets fronting a single DuckDB instance: `DEMO=minimal` (with any
`LOAD_*` flag) imports `bootstrap-demo-minimal.yaml` instead: tenant `acme` only, one pool
`bi` with a single dual node serving reads and writes, and the analyst RLS/CLS demo. Use
`DEMO=full` (the default) for the multi-tenant demo. The profile is only
consulted when `QOD_BOOTSTRAP_YAML` is unset, and bootstrap only imports into a fresh
control plane, so switch profiles with `NUKE=1`:

    NUKE=1 DEMO=minimal LOAD_TPCH=1 qod start

On a terminal, `NUKE=1` asks you to type the control-plane database's name
before proceeding (it drops the control plane and the demo tenant-dbs and
wipes the local state dirs); non-tty runs skip the prompt (a script that
wants no prompt redirects stdin, e.g. `< /dev/null`).

`DEMO=minimal` plus `LOAD_TPCDS` warns and skips the TPC-DS loader (no globex tenant in
this profile).

## Auth flow (REST + UI)

The REST API has three acceptable credentials:
1. **Static** `X-API-Key` header matching `QOD_API_KEY` (if set; not set by default)
2. **UI session token** minted via `POST /api/auth/login`
3. **Personal access token** (`qod_pat_...`), sent the same way as a session token
   (see "Personal access tokens and the MCP server" below)

```bash
# Mint a session and store it in the CLI profile (admin role required)
qod login --username admin        # prompts for the password

# Every subsequent command rides that session
qod pool list
```

If `QOD_API_KEY` is unset (or empty), only the static-key arm is disabled: every non-public `/api/...` call still requires a session or PAT, and a keyless call answers 401. There is no open mode; keyless dev scripts must log in first.

### Regular-user login (profile only)

The admin UI isn't admin-exclusive: a tenant-scoped `role=user` principal can log in too, with the same `/api/auth/login` call plus a `tenant` (the blank/system login and OIDC SSO still require an admin grant). The resulting session is demoted to a fixed allowlist - `whoami`, `logout`, `/api/profile/usage`, `/api/profile/statements` - and gets `403 admin_required` on everything else; the UI lands such a session straight on `/profile` (change own password; view own usage + recent statements).

```bash
# Log in as a regular tenant user (demo credentials from the bootstrap manifest)
qod login --username alice --tenant acme    # prompts for the password (demo-alice)

# Own usage and recent statements - the only data endpoints this session can reach
qod profile usage --days 7
qod profile statements --limit 20
```

### Personal access tokens and the MCP server

PATs are long-lived bearer credentials for agents and scripts. A PAT acts with exactly
its owner's permissions (admin PATs reach the admin surface, `role=user` PATs are
demoted to the profile allowlist) and is accepted wherever a session token is, on both
`/api/*` and the MCP endpoint. Management is no longer session-only: a session can
mint, list, revoke and delete any of the caller's own tokens, and a PAT may now mint a
scoped child of itself and may list, revoke and delete within its own subtree only -
never a sibling, its own parent, or any other token of its owner. Revoking a token
cascades to its whole subtree in the same statement, so a stolen token cannot be rolled
forward past its own revocation by minting a successor first. The revoke also kills the
in-flight statements of every token in that subtree (locally at once, across HA replicas
via NOTIFY moments later); the response reports `killedStatements` for the serving
replica, and killed statements show in statement history with status `killed`.

A PAT can also be **scoped** narrower than its owner's own grants, so an AI agent holds
a credential it cannot exceed. Scope axes are `roles` / `databases` / `pools` / `tools`
(each a JSON array of strings), `verbCeiling` (`RO` / `RW` / `DDL` / `ALL`), `dropAdmin`
(boolean, strips superuser/admin standing), and `stmtTimeoutMs` / `maxRows` (integer
caps). Every axis is optional: an axis left out of the request is unrestricted (inherits
the owner's reach); an axis sent as `[]` restricts the token to nothing on that axis.
A PAT credential can mint a further-scoped child PAT of its own (`parentId` / `depth` on
the listing row show the chain), but any axis narrowed at mint time can only be narrowed
further down the chain, never widened back out - the server refuses a widening attempt
with `400 pat_scope_widens`.

**`roles` and `verbCeiling` are inert for a token whose owner is a tenant-less
superuser.** The ACL validator returns `Allowed` for a superuser before it ever looks at
a permission list, so attenuating an empty permission set changes nothing, and the
example below (`verbCeiling: "RO"`, `dropAdmin: true`) minted by the default
`admin@localhost.local` superuser still produces a token that can `DROP TABLE`. For a
tenant-scoped admin the ceiling does bite, which is exactly what makes this easy to miss
in testing: it only fails silently for a superuser owner. Scope a superuser-owned agent
token with `databases`, `tools` and `dropAdmin` instead, or - better - mint the token
from a tenant-scoped service user in the first place, where `roles` and `verbCeiling`
are load-bearing.

```bash
# Mint an unscoped token (session required; the token is printed ONCE - store it now)
qod auth pat create --name claude-code [--expires-at 2027-01-01T00:00:00Z]
# {"id":"pat-...","name":"claude-code","token":"qod_pat_..."}

# Mint a scoped token for an agent: read-only, one database, two tools, no admin standing
qod auth pat create --name claude-agent \
  --database acme_db --tool run_sql --tool list_tables \
  --verb-ceiling RO --drop-admin --max-rows 500

# List (metadata only; the raw token is unrecoverable after mint; the scope summary
# and parentId/depth on each row show what an agent's own PAT has minted)
qod auth pat list
# Revoke
qod auth pat revoke --id pat-...
```

`qod auth pat create --help` lists the full scope flag set (`--role`, `--database`,
`--pool`, `--tool` are repeatable; `--verb-ceiling`; `--drop-admin`; `--stmt-timeout-ms`;
`--max-rows`). A flag left off the command line is unrestricted on that axis, not empty -
same rule as the REST body above.

The MCP server lives at `POST /mcp` on the manager port (`QOD_MCP_ENABLED`, default
true). Auth is `Authorization: Bearer <PAT or QOD_API_KEY>`; session JWTs are refused
there. Point Claude Code at it with:

```bash
claude mcp add --transport http qod http://localhost:20900/mcp \
  --header "Authorization: Bearer qod_pat_..."
```

MCP troubleshooting:
- **401 on every call**: the bearer is a session JWT (refused by design) or a dead PAT
  (revoked, expired, owner disabled). Mint a fresh PAT.
- **Tool missing from tools/list**: wrong tier - admin tools need an admin-owned PAT or
  the static key.
- **run_sql denied**: the ACL message names the table and missing verb; fix the grant.
- **"pool is resuming"**: the suspended pool is waking; retry in a few seconds.
- Row caps: `run_sql` is truncated server-side at `QOD_MCP_MAX_ROWS` (default 500).

### Account lockout and self-service password reset

Lockout is opt-in and off by default. Turning it on requires SMTP to be configured first - boot refuses to start otherwise (the error names `QOD_SMTP_HOST`), because a locked-out user with no mail path would have no way back in.

```bash
# Enable lockout: SMTP relay + the lockout switch + the public link base
export QOD_SMTP_HOST=smtp.example.com
export QOD_SMTP_USER=apikey
export QOD_SMTP_PASSWORD=secret
export QOD_SMTP_FROM=no-reply@example.com
export QOD_PUBLIC_BASE_URL=https://qod.example.com
export QOD_AUTH_LOCKOUT_ENABLED=true
export QOD_AUTH_LOCKOUT_MAX_FAILURES=10   # default; locks after this many consecutive bad passwords

# A locked-out user (or anyone who forgot their password) self-serves a reset -
# the endpoint is public (no session needed) and always answers 200, even for
# an unknown username or an account without an email, to avoid leaking existence
qod auth forgot-password --username alice --tenant acme

# The mailed link carries a single-use, 1-hour token; the command prompts for
# the token and the new password:
qod auth reset-password
```

Lockout only ever applies to rows with an `email` set (`qod user create/update --email`). A user with a non-email username and no email is never locked and has no self-service path - recover it with an admin password reset instead:

An email-format username is its own email and cannot be set separately: `qod user create/update --email` with a conflicting value 400s `invalid_email`, and pre-existing such rows were backfilled automatically. This includes the seeded admin (`admin@localhost.local` by default): because its username is email-format, it is auto-assigned `email = username`, so it IS eligible for lockout when lockout is on, and for self-service reset. A locked superuser is still recoverable without the email flow: restarting the manager re-seeds the admin (resetting the password to `QOD_ADMIN_PASSWORD` and clearing `failed_attempts` / `locked_at` in the same statement), and the static `X-API-Key` bypasses login lockout entirely. Note that `admin@localhost.local` is not a routable mailbox, so the seeded admin's self-service email reset will not deliver by default - set `QOD_ADMIN_USERNAME` to a real deliverable address if you want the admin to self-recover by email, otherwise use restart or the API key.

```bash
qod user update <user-id> --password a-new-password
```

An admin password reset also unconditionally clears the lock (`failed_attempts` and `locked_at`), same as the self-service reset.

### SCIM 2.0 provisioning (IdP-driven user + group lifecycle)

For workforce identity, point an enterprise IdP's SCIM connector (Okta, Entra, Google Workspace) at the per-tenant base URL so users and groups are provisioned, updated and deprovisioned automatically instead of by hand. This complements per-tenant OIDC SSO: SSO authenticates the login, SCIM manages the accounts.

- **Base URL**: `https://qod.example.com/api/scim/v2/<tenant>` (the tenant id or its display name both work).
- **Auth**: `Authorization: Bearer <token>`, where the token is the static `QOD_API_KEY` or a tenant-admin personal access token. A tenant-scoped token can only touch its own tenant.
- **Resources**: Users and Groups, plus the discovery endpoints (`ServiceProviderConfig`, `ResourceTypes`, `Schemas`) the connector reads on setup.

```bash
SCIM=https://qod.example.com/api/scim/v2/acme

# Provision a user (no password needed - the user signs in through the tenant's OIDC SSO;
# QoD assigns an unguessable random password). active:false creates the row disabled.
curl -sS -H "Authorization: Bearer $QOD_API_KEY"   -H 'Content-Type: application/scim+json'   -X POST "$SCIM/Users"   -d '{"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
       "userName":"carol@acme.com","externalId":"okta-123",
       "emails":[{"value":"carol@acme.com","primary":true}],"active":true}'

# Find by the IdP's identifier or by userName (filter: attr eq "value")
curl -sS -H "Authorization: Bearer $QOD_API_KEY"   "$SCIM/Users?filter=externalId%20eq%20%22okta-123%22"

# Deactivate (cuts BOTH the REST login and the FlightSQL handshake, not just the console)
curl -sS -H "Authorization: Bearer $QOD_API_KEY"   -H 'Content-Type: application/scim+json'   -X PATCH "$SCIM/Users/<id>"   -d '{"Operations":[{"op":"replace","path":"active","value":false}]}'

# Group with members; PATCH add/remove members drives RBAC group membership
curl -sS -H "Authorization: Bearer $QOD_API_KEY"   -H 'Content-Type: application/scim+json'   -X POST "$SCIM/Groups"   -d '{"displayName":"analysts","members":[{"value":"<user-id>"}]}'
```

Semantics worth knowing: `userName` and a group's `displayName` are immutable (a rename is refused with scimType `mutability`); `active` maps to the account `enabled` flag; the superuser realm is invisible to SCIM; creates are insert-only, so a connector retry can never rotate an existing user's password. SCIM is machine-to-machine, so it has no `qod` CLI command - manage users and groups by hand with `qod user`/`qod group`/`qod membership` when you are not driving them from an IdP.

## Tenant + pool management

```bash
# List tenants
qod tenant list

# Create a tenant, then its database (metastore keys omitted here resolve from
# the manager's defaultMetastore at spawn time; --metastore KEY=VALUE overrides)
qod tenant create acme
qod database create --tenant acme --name tpch \
  --metastore dbName=tpch --metastore schemaName=tpch1

# Create a pool (1 WriteOnly + 1 ReadOnly + 1 Dual = 3 nodes)
qod pool create --tenant acme --db acme_tpch --pool bi --size 3 \
  --writeonly 1 --readonly 1 --dual 1

# Scale up
qod pool scale --tenant acme --db acme_tpch --pool bi --target-size 6 \
  --writeonly 1 --readonly 2 --dual 3

# Stop a pool: scales it down to 0 nodes but KEEPS the pool (--force skips graceful drain)
qod pool stop --tenant acme --db acme_tpch --pool bi --force

# Suspend a pool (scale-to-zero, keeps the role distribution for resume)
qod pool suspend --tenant acme --db acme_tpch --pool bi

# Resume a suspended pool
qod pool resume --tenant acme --db acme_tpch --pool bi

# A suspended pool also wakes automatically on the first FlightSQL statement
# (bounded by PROXY_RESUME_HOLD_TIMEOUT_SEC, default 60s). A stopped pool
# (pool stop) stays down; a disabled pool is never auto-woken.

# Autoscale band: declare min/max nodes and the manager adds/removes READONLY
# nodes with demand, never leaving the band. Both bounds together or neither.
# Rules: 1 <= min <= max <= QOD_AUTOSCALE_HARD_CAP (16); min must cover the
# write-capable nodes (writeonly + dual); the CURRENT size must sit inside the
# band (so a stopped pool, size 0, cannot take one - scale it up first); a pool
# with authored cohorts cannot be elastic. Violations return 400 invalid_band.
# Create a pool with a band:
qod pool create --tenant acme --db acme_tpch --pool bi --size 2 \
  --writeonly 1 --readonly 1 --min-nodes 2 --max-nodes 6

# Set (or change) the band on an existing pool
qod pool set-autoscale --tenant acme --db acme_tpch --pool bi --min-nodes 2 --max-nodes 6

# Clear the band (omit BOTH bounds) - the pool goes back to a fixed size
qod pool set-autoscale --tenant acme --db acme_tpch --pool bi

# pool scale on a banded pool refuses a target size outside [min, max] with
# 400 outside_band ("adjust the band first via pool/setAutoscale") - the next
# sweep would just undo it. Widen or clear the band, then scale.
# To pin a pool: set --min-nodes == --max-nodes. That is a legal band meaning
# "hold exactly this size, never scale", and it keeps manual scaling constrained
# to that size. To stop the sweep manager-wide: QOD_AUTOSCALE_ENABLED=false
# (bands stay recorded and are simply not acted on). Actions land in the audit
# log with actor "autoscale" and in the manager log as
# "autoscale: acme/acme_tpch/bi out 2 -> 3 util=0.91".

# Delete a pool: stops nodes AND removes the pool from the registry
qod pool delete --tenant acme --db acme_tpch --pool bi --force

# Delete a tenant (must have no pools first)
qod tenant delete acme

# Size a pool's k8s node pods: cpu and memory are each applied as request AND
# limit on the quack container (Guaranteed QoS when both set). Applies on the
# next node spawn; restart the pool's nodes to apply now. Empty clears.
# Set DuckDB memory (database/pool init SQL, SET memory_limit) to ~80% of pod
# memory so the engine spills before the kernel OOM-kills the pod.
qod pool set-resources --tenant acme --db acme_tpch --pool bi --cpu 2 --memory 8Gi

# Supply a full Pod-manifest template from a YAML file (superuser only; requires
# QOD_POD_TEMPLATE_ENABLED=true). The manager overlays the pod name, its
# identity labels, and the quack container's env contract and resources; a
# container named 'quack' is required. Use for sidecars, volumes, affinity.
qod pool set-pod-template --tenant acme --db acme_tpch --pool bi --file pod-template.yaml
```

The local backend ignores cpu/memory/template; use database or pool `initSql` (`SET memory_limit='...'`) for local memory control. The Helm chart's `resources` block sizes the MANAGER container, not node pods; use `qod pool set-resources` for node-pod sizing.

`pool/setResources` is mutation-gated as of 2026-08-13, like `pool/create` and `pool/scale`: a module gate may refuse it, and the refusal surfaces as **HTTP 429 `quota_exceeded`** with the reason in the body. Superuser sessions and static-`X-API-Key` callers bypass the gate, as everywhere. Zero-module (plain OSS) boots have no gates registered, so nothing changes there.

Hosted deployments can cap a tenant's *cumulated* cores and memory across all its pools (dimensions `maxCores` / `maxMemoryGib`, `0` = unlimited). What an operator hitting a 429 needs to know:

- The charge is computed from **declared** pool shapes, not live nodes: `sum over pools of (desired nodes) * cpu` and likewise for memory. **Suspended and disabled pools still count** (they keep their reservation); a stopped pool whose distribution is zeroed charges nothing.
- Under a finite cap, a pool with an **undeclared** (empty) cpu or memory is refused rather than counted as zero. The message is `declare cpu/memory on this pool: resource caps are active for this tenant`; the fix is to `pool/setResources` a real shape on it (which is itself cap-checked). Pre-cap pools are not retroactively broken: they charge nothing until a gated mutation touches them.
- An unparseable quantity is refused naming the offending string. Kubernetes quantity syntax: cpu `"2"` / `"1.5"` / `"500m"` (millicores are the only cpu suffix), memory `"8Gi"` / `"1024Mi"` / `Ki` / `Ti`, the decimal `k`/`M`/`G`/`T` (lowercase `k`, matching what the manager's own quantity validator admits), or a plain byte count.
- **Shrinks always pass.** A scale-down, and any shape swap whose per-dimension delta is `<= 0`, is admitted without consulting the cap, so a tenant that is already over (caps lowered under it, shapes backfilled past them) can always shrink back into compliance.
- Autoscale scale-outs route through the same gate, so caps bound autoscale with no extra config: a band whose ceiling exceeds the cap simply stops growing at the cap, and the refusal feeds the sweep's normal failure backoff.

## RBAC grants

Grants live in the normalized `qodstate_*` tables in Postgres. The endpoints are always mounted (Postgres is the only control-plane store since 2026-06-12).

### Command reference

```bash
# Roles
qod role list --tenant acme
qod role create --tenant acme --name analyst --description "..."
qod role delete <roleId>

# Role table permissions (verb: RO | RW | DDL | ALL)
qod role permission list --role-id <roleId>
qod role permission grant --role-id <roleId> --catalog acme_tpch --schema tpch1 --table customer --verb RO
qod role permission revoke <permissionId>

# Users
qod user create --tenant acme --username alice --role user   # prompts for the password

# Groups
qod group create --tenant acme --name analysts

# Memberships (each has a matching remove)
qod membership group-role add --group-id <groupId> --role-id <roleId>
qod membership user-group add --user-id <userId> --group-id <groupId>
qod membership user-role add --user-id <userId> --role-id <roleId>

# Pool access - governs which pools a principal can reach
qod pool permission list --tenant acme
qod pool permission grant --tenant acme --pool-id <poolId> --group-id <groupId>
qod pool permission revoke <id>
```

### Grant a team read access (6-step flow)

```bash
# 1. Create a role (qod --json prints the raw response so the id can be captured)
ROLE_ID=$(qod --json role create --tenant acme --name analyst --description "Read-only analyst" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')

# 2. Grant RO on acme_tpch.tpch1.customer (repeat per table, or use "*" to wildcard any field)
qod role permission grant --role-id "$ROLE_ID" \
  --catalog acme_tpch --schema tpch1 --table customer --verb RO

# 3. Create a group
GROUP_ID=$(qod --json group create --tenant acme --name analysts \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')

# 4. Attach the role to the group
qod membership group-role add --group-id "$GROUP_ID" --role-id "$ROLE_ID"

# 5. Add a user to the group (or use membership user-role add to attach the role directly to a user)
qod membership user-group add --user-id <userId> --group-id "$GROUP_ID"

# 6. Grant the group access to the pool (REQUIRED - without this the group cannot reach the pool)
qod pool permission grant --tenant acme --pool-id <poolId> --group-id "$GROUP_ID"
```

Retrieve `<userId>` and `<poolId>` from `qod user list --tenant acme` and `qod pool list` respectively.

### DML and DDL grants

Use the same `qod role permission grant` command with `--verb RW` for DML writes (covers reads too), `--verb DDL` for CREATE / DROP / ALTER, or `--verb ALL` to cover everything on a table at once. The verb vocabulary is deliberately coarse (`RO` / `RW` / `DDL` / `ALL`, matching what the validator enforces per table); granular SQL keywords like `SELECT` or `INSERT` are only accepted by the SQL admin dialect (below), which maps them onto these four at parse time.

### Metadata browsing (information_schema)

Since the filtered-metadata feature, **no grant is needed to browse metadata**. Any
authenticated principal may read the session database's `information_schema`
(`schemata` / `tables` / `columns` / `views`); the manager rewrites the query so the
rows come back filtered to the objects the principal already holds at least RO on. A
user granted only `acme_tpch.tpch1.customer` sees that one table plus the system rows,
never the rest of the schema. This is what makes a JDBC/ADBC client's table tree
(DBeaver, ADBC `GetTables`) work for a grantless-on-`information_schema` principal:
those catalog RPCs are `information_schema` queries underneath and used to come back
denied.

An **explicit** `information_schema` grant is still meaningful: it is the escape hatch
that turns the filter off for that principal and restores the unfiltered read (useful
for a tooling or admin account that must see the whole catalog):

```bash
qod role permission grant --role-id "$ROLE_ID" \
  --catalog acme_tpch --schema information_schema --table '*' --verb RO
```

The grant must name `information_schema` literally (a wildcard schema does not count as
the escape hatch) and its catalog must be the session database or `*`.

Superusers and holders of a wildcard `ALL` grant are never filtered either. To turn the
whole feature off manager-wide and go back to the pre-0.6.7 grant-required posture, set
`QOD_ACL_FILTERED_METADATA=false`.

**Denial semantics** (all fail-closed, and all only when ACL is on):

- The filter rewrites `information_schema` references it finds in `FROM` position. A
  reference sitting in `ORDER BY`, `GROUP BY` or a window clause, or a filterable name
  merely **mentioned inside a string literal**, is *denied* with a message ending
  `query it directly in the FROM clause instead`. The remedy is to move the reference
  into the `FROM` clause (or drop the literal mention) and re-run.
- `DESCRIBE <t>`, `SHOW <t>` and `SHOW COLUMNS FROM <t>` now require RO on the target
  table. They previously bypassed the ACL entirely. This applies whenever ACL is on,
  independent of `QOD_ACL_FILTERED_METADATA`.
- Plain `SHOW TABLES` answers the filtered listing. The `FROM` / `IN` / `LIKE` variants
  are denied while the filter is active: query `information_schema.tables` instead.
  `SHOW ALL TABLES` stays denied under ACL, as before.
- Known limitation: when a statement is metadata-rewritten, a DuckLake time-travel
  clause (`AT (VERSION => n)` / `AT (TIMESTAMP => ...)`) on a co-referenced table in the
  same statement is dropped, so that table reads at the current version. This only
  affects the unusual shape of joining `information_schema` with a time-traveled table
  in one query; a standalone `SELECT ... FROM t AT (VERSION => n)` is unaffected. Not a
  data-exposure issue (the metadata is still filtered); run the time-travel read as its
  own statement if you need the historical snapshot.

### Revoking access

```bash
# Remove a table permission from a role
qod role permission revoke <permissionId>

# Detach a role from a group
qod membership group-role remove --group-id <groupId> --role-id <roleId>

# Remove pool access
qod pool permission revoke <poolPermissionId>
```

The EffectiveSet cache is invalidated on every RBAC mutation, so changes take effect on the next handshake - no TTL window to wait for.

ACL is *off* by default (`acl.enabled=false`). Flip with `QOD_ACL_ENABLED=true` to actually enforce.

### Force a password change at next login

Create with a temporary password (or reset one) that only works against
`POST /api/auth/change-password`:

```bash
qod user create --tenant acme --username alice --password Temp123 --role user \
  --must-change-password

# reset an existing password as temporary
qod user update <userId> --password Temp123 --must-change-password
```

Until changed, REST login answers `401 password_change_required` and the FlightSQL
handshake fails `UNAUTHENTICATED` with "password change required". The user swaps it
(no session needed; also available anytime for voluntary rotation; prompts for the
current and new passwords):

```bash
qod auth change-password --username alice --tenant acme
```

## SQL administration (FlightSQL)

An admin SQL dialect is answered directly at the FlightSQL edge: GRANT/REVOKE,
CREATE/DROP ROLE, CREATE/ALTER/DROP USER, ROW/COLUMN POLICY, ALTER GROUP, and
admin SHOW forms are claimed by `FlightSqlRouter` before a statement would
otherwise be forwarded to a node, executed against the same `PoolSupervisor`
mutators the REST RBAC endpoints call, and answered as a small in-memory
Arrow result set. This is a second way to reach the RBAC/policy machinery
described above - REST and the SQL dialect are two doors onto the same rows,
and either one invalidates the same `EffectiveSet` cache.

Flag: `quack-on-demand.sqlAdmin.enabled` (env `QOD_SQL_ADMIN_ENABLED`, default
`true`). Off restores the pre-feature behavior exactly: these statements fall
through to the normal fail-closed denial instead of being claimed.

**Who may run them**: a superuser session, or a tenant admin session acting
within its own tenant. A non-admin session gets `PERMISSION_DENIED
admin_required`. Admin authority is evaluated against the handshake-time
principal snapshot cached on the connection - demoting an admin does not cut
off dialect authority on an already-open connection until that connection's
context TTL (`sessionTtlSec`, default 3600s) expires, matching the existing
handshake-cache behavior for every other authorization check on the wire.

**One example per statement family** (the semantics bullets below cover the
sharp edges of the grammar):

```sql
-- Roles and membership
CREATE ROLE analyst;
GRANT ROLE analyst TO USER alice;
ALTER GROUP finance ADD USER alice;

-- Users
CREATE USER alice PASSWORD 'secret';
CREATE USER ops PASSWORD 'secret' ADMIN;
ALTER USER alice PASSWORD 'newsecret';
ALTER USER alice REQUIRE PASSWORD CHANGE;
ALTER USER alice DISABLE;
ALTER USER alice ENABLE;
DROP USER IF EXISTS alice;

-- Table ACLs
GRANT SELECT ON tpch.main.orders TO ROLE analyst;
REVOKE ALL ON tpch.main.orders FROM ROLE analyst;

-- Row policy (RLS)
CREATE ROW POLICY ON tpch.main.orders FOR ROLE analyst
  USING (region = ${tenantId} OR owner = ${user});

-- Column policy (CLS mask/deny)
CREATE COLUMN POLICY ON tpch.main.customers COLUMN email FOR ROLE analyst
  MASK USING (SHA256(CAST(email AS VARCHAR)));

-- Pool access
GRANT CONNECT ON POOL tpch.bi TO USER alice;

-- Introspection
SHOW GRANTS FOR ROLE analyst;
SHOW GRANTS FOR USER alice;
SHOW USERS;
```

The prepared-statement path advertises the correct `(status, detail)` schema
for a mutating admin statement (dispatch lives inside `execute`, which both
the immediate and prepared paths call), so ADBC/JDBC clients - the dialect's
primary clients - work through Prepare/Execute exactly as they do for any
other DDL. Nothing is executed at Prepare time; only the schema advertised at
that step differs from an ordinary DDL statement's `Count: int64`, matching
what the later Execute actually delivers.

**Semantics worth knowing before you rely on them**:

- `REVOKE <verb> ON obj FROM ROLE r` on a grant that does not exist is not an
  error - it succeeds with `detail = "revoked 0"` in the result row (there is
  no warning channel over FlightSQL to distinguish "revoked something" from
  "revoked nothing").
- `REVOKE ALL ON *.*.*` removes only a grant row stored **literally** as
  `(*, *, *)` - it is not a shorthand for "every grant this role holds." Wildcards
  in the `ON` clause match the stored tuple exactly, wildcards included; they
  are not glob patterns over existing rows.
- `SHOW ... ON <table>` and `SHOW GRANTS FOR ROLE r` match the stored tuple
  exactly too, same caveat.
- `SHOW ROLES`, `SHOW GRANTS`, and `SHOW USERS` are claimed by the dialect and
  will **shadow** a real table literally named `roles`, `grants`, or `users`
  in your schema. Quote the identifier (`SHOW "roles"`) to bypass the dialect
  and reach DuckDB's normal describe-table behavior instead - the claim check
  only fires on an unquoted keyword token.
- The `ON POOL db.pool` qualifier in `GRANT/REVOKE CONNECT ON POOL ...` is
  optional and never disambiguates: pool names are unique per tenant already,
  so a bare `ON POOL sales` and a qualified `ON POOL tpch.sales` resolve
  identically. A *wrong* qualifier does not fall back to name-only resolution -
  it fails `unknown_pool`, it does not silently pick a different pool.
- Admin SQL is FlightSQL-edge only. It is **not** available over the MCP
  server or the REST catalog-preview/restore/undrop endpoints: those share one
  routed-execution choke point that explicitly disables the dialect claim, so
  a claimed statement reaching them still falls through to the ordinary
  fail-closed denial rather than the dialect's own admin check (which does not
  know about PAT scope attenuation the way the routed ACL path does). Use the
  FlightSQL wire (or the REST RBAC endpoints above) for SQL administration.
- Admin statements are control-plane writes, not data-connection writes: a
  surrounding `BEGIN` on the data connection does not cover them. They commit
  immediately regardless of an open transaction, and are not rolled back by a
  later `ROLLBACK` on that connection.
- `CREATE USER` / `DROP USER` always target a **tenant** user in the session
  tenant - the dialect cannot mint superusers (tenant-NULL rows are
  unreachable by construction), consistent with the standing
  no-privilege-escalation rule that only superusers mint superusers, via REST.
  `WITH` before `PASSWORD` is optional Postgres-style noise; `ADMIN` sets the
  tenant-admin role label, not RBAC superuser status. `CREATE USER` is a true
  create (`failIfExists = true`): an existing `(tenant, username)` is refused
  with `ALREADY_EXISTS`, never upserted. `DROP USER` refuses to drop the
  session's own username (self-drop guard, mirroring the REST posture). The
  password literal is excluded from statement history (the executor logs only
  the command kind) AND redacted from the edge's DEBUG statement logging - a
  claim-shaped statement is logged as a constant redacted placeholder there,
  never a substring of the raw text, and the FlightSQL wire is TLS.
- `ALTER USER ... PASSWORD` rotates a tenant user's password through the same
  per-(tenant, username) path REST `user/update` uses, so lockout counters
  (`failed_attempts` / `locked_at`) are cleared as part of the write, same as
  the REST reset. Unlike `DROP USER`, self-rotation is allowed - there is no
  session-user guard on this one. An unknown username 404s before the
  rotation path ever runs. Same password-literal-excluded-and-redacted note as
  `CREATE USER` above. `ALTER USER ... ENABLE` / `DISABLE` and `ALTER USER ...
  REQUIRE PASSWORD CHANGE` (below) go through the same underlying upsert and
  clear the same lockout counters as a side effect, even though neither
  touches the password.
- `SHOW USERS` lists the session tenant's users only (`id`, `username`,
  `role`, `enabled`, `email`) - never any credential material, since
  `RbacUser` carries no password hash. The superuser realm is invisible, as
  everywhere else in the dialect. `users` is a much likelier real table name
  than `roles` or `grants`, so the shadowing caveat above bites harder here:
  if your schema has its own `users` table, `SHOW USERS` claims the statement
  and never reaches it - quote the identifier (`SHOW "users"`) to get DuckDB's
  normal describe-table behavior instead.
- `ALTER USER ... REQUIRE PASSWORD CHANGE` flags the account so it must set a
  new password on its next login, WITHOUT rotating the credential - it takes
  no password argument. There is no self-guard: the session user may flag its
  own account. An unknown username 404s before anything is written. Surprising
  consequence: the write goes through the same upsert that unconditionally
  clears `failed_attempts` / `locked_at`, so flagging a **locked-out** user for
  a required password change also unlocks them - the same side effect an
  admin password reset already has, just reached from a statement that never
  mentions lockout at all.
- `ALTER USER ... ENABLE` / `DISABLE` flips the account's login/handshake gate
  (the same `enabled` column REST `user/update` locks/unlocks). `DISABLE`
  refuses to target the session's own username (self-disable guard, mirroring
  `DROP USER`'s self-drop guard); `ENABLE` has no such guard - re-enabling
  your own account is allowed. An unknown username 404s before anything is
  written. `ENABLE` also clears lockout counters (see above) - re-enabling an
  admin-disabled account also lifts any failed-login lock it had accumulated.
- `SHOW GRANTS FOR USER u` lists the flattened effective table-grant closure
  for a tenant user - direct role grants plus grants reached through group
  membership - as `(role, id, catalog, schema, table, verb)`, where `role`
  names the specific role each row was granted through ("-" if it cannot be
  resolved). Tenant-scoped by construction (the username is resolved within
  the session tenant, same as every other user-targeting form); an unknown
  username 404s. A resolved user holding no grants lists as an empty result,
  not an error.

## Federation - external catalogs via DuckDB extensions

Quack-on-Demand supports per-tenant-db federated catalogs that attach external sources (Postgres, S3, Iceberg, any DuckDB extension) under DuckDB catalog aliases. Existing RBAC covers federated tables - a `RolePermission(catalog='fedpg', schema='public', table='orders', verb='RO')` grants read access to a federated alias just like a DuckLake table.

### Tenant-db kinds

When creating a tenant-db, pick a `kind`:

- `ducklake` (default) - Postgres metastore + object-store dataPath. Production multi-node persistence.
- `duckdb-file` - Local `.duckdb` file at `dataPath`. Single-node only (file must exist on every node).
- `memory` - No persistent default catalog. Use with `defaultDatabase` pointing at a federated alias.

For `ducklake` creates, Postgres connection keys are optional: anything omitted resolves from the manager's `defaultMetastore` (`QOD_PG_*`) at spawn time, and the row follows later config changes.

Example: create an in-memory tenant-db that only serves federated sources.

```bash
qod database create --tenant acme --name fed --kind memory \
  --default-database fedpg --default-schema public
```

### Update a database

```bash
# Update a database: any subset of metastore, objectStore, defaultDatabase,
# defaultSchema, initSql. Absent fields stay unchanged; empty clears. Editing
# metastore/objectStore/initSql restarts ALL the database's nodes immediately
# (in-flight statements on them fail); default database/schema edits do not.
# pgPassword is preserved unless you send it: pgPassword=new rotates. Removing a key
# the database's kind requires (incl. pgPassword on ducklake) is rejected.
# Engine defaults only in initSql, never credentials: the value is stored
# unredacted and inlined in pod specs; secrets belong in federation sources.
qod database update --tenant acme --name acme_tpch --init-sql "SET memory_limit = '8GB';"

# Rotate the metastore password (restarts the db's nodes):
# Send the FULL metastore map when editing it (minus pgPassword to keep it): the map is replaced, and dropping a required key is rejected.
qod database update --tenant acme --name acme_tpch \
  --metastore dbName=acme_tpch --metastore pgHost=localhost --metastore pgPort=5432 \
  --metastore pgUser=postgres --metastore schemaName=main --metastore pgPassword=newpass
```

### Per-database object-store credentials

`objectStore` on a database takes effect at node spawn: it authors a DuckDB
secret scoped to that database's `dataPath`, so this database authenticates
its own bucket with its own keys, coexisting with the process-global
`QOD_S3_*` / `QOD_AZURE_*` / `QOD_GCS_*` default secret (DuckDB picks the most
specific scope per path). Keys, by `dataPath` scheme:

- `s3://` / `s3a://` / `r2://`: `s3_region`, `s3_access_key_id`,
  `s3_secret_access_key`, `s3_endpoint`, `s3_url_style`.
- `gs://`: `gcs_hmac_key_id`, `gcs_hmac_secret`.
- `az://` / `azure://` / `abfss://`: `azure_account`, `azure_account_key`.

```bash
# Create a database that authenticates its own bucket, distinct from the
# manager-wide default credentials.
qod database create --tenant acme --name coldstore --kind ducklake \
  --data-path s3://acme-coldstore/ducklake \
  --object-store s3_region=us-east-1 \
  --object-store s3_access_key_id=AKIA... \
  --object-store s3_secret_access_key=...
```

An empty (or absent) `objectStore` falls back to the global env credentials -
exact back-compat for every deployment that only ever used one set of keys.
On Kubernetes the resolved SQL is never inlined in the pod spec: it lands in a
per-node Secret `qod-store-${nodeId}` injected via `env.valueFrom.secretKeyRef`
(same pattern as the per-pod token and per-pool federation secrets); `kubectl
describe pod` shows the ref, not the values. On the local backend it rides the
node's process env like the other spawn-time SQL blocks. GET/list responses
redact `s3_secret_access_key`, `azure_account_key`, and `gcs_hmac_secret`
(alongside `pgPassword`). The secret is authored only for `kind=ducklake`
databases - a `duckdb-file` database on a remote `dataPath` gets no per-db
object-store secret today (that spawn arm doesn't install `httpfs`/`azure` at
all, a separate pre-existing gap). Editing `objectStore` restarts the
database's nodes so the new secret takes effect immediately; there is no
in-place rotation on an already-running node.

### Managed object storage (QoD provisions the bucket prefix)

Instead of bringing a bucket, a `ducklake` database can ask QoD to carve its
data path out of ONE operator-owned root bucket. Off by default; enable it on
the manager with:

```bash
export QOD_MANAGED_STORE_ENABLED=true
export QOD_MANAGED_STORE_ENDPOINT=http://seaweedfs:8333    # empty = AWS default resolution
export QOD_MANAGED_STORE_REGION=us-east-1
export QOD_MANAGED_STORE_BUCKET=qod-managed
export QOD_MANAGED_STORE_ACCESS_KEY_ID=...
export QOD_MANAGED_STORE_SECRET_ACCESS_KEY=...
export QOD_MANAGED_STORE_URL_STYLE=path                   # path (S3-compatible) | vhost (AWS)
export QOD_MANAGED_STORE_RETAIN_DAYS=7                    # retention after delete, 0 = immediate
export QOD_MANAGED_STORE_PURGE_SWEEP_SEC=300              # purge worker cadence, 60s floor
```

**The root bucket must have versioning OFF.** On a versioned bucket a delete
writes a delete marker instead of removing the object: the purge worker's next
listing comes back empty, it stamps the prefix purged, and the non-current
versions keep billing forever. Boot creates the bucket if missing; an
unreachable store only WARNs (`managed object store unreachable`) and never
blocks a managed create at the control plane - the create still succeeds, and
the failure surfaces later, at node spawn/ATTACH against the missing bucket.
In HA, replicas race that first create, so the losing ones can log one false
"unreachable" WARN at first boot; it self-heals.

```bash
# Create a managed database. No data path, no object store: the server resolves
# both. The response's dataPath is s3://<bucket>/<tenant>_<name>-<id8>/ where
# id8 is the first 8 chars of the tenant-db surrogate id, so recreating a
# deleted name always lands on a fresh empty prefix.
qod database create --tenant acme --name sales --kind ducklake --managed-storage
```

400s, all actionable: managed storage not enabled on this deployment
(`QOD_MANAGED_STORE_ENABLED`); `managedStorage` sent together with a
`dataPath`/`objectStore` (one intent per call); `managedStorage` on a
non-`ducklake` kind. `database/update` has NO `managedStorage` field: there is
no BYO-to-managed (or managed-to-BYO) migration, recreate instead.

- **Credential rotation**: a managed create snapshots the operator credential
  (`QOD_MANAGED_STORE_SECRET_ACCESS_KEY`) into that database's own
  `objectStore` at create time. Rotating the env var only affects *future*
  managed creates; existing managed databases keep signing with the old key
  and break at their next node respawn once it is revoked. Remediation today:
  `database/update` each managed database's `objectStore` with the new
  secret, then recycle its pools. Per-database credential minting is the
  designed follow-up.

```bash
# Delete: tombstone now, objects purged after retainDays (7 by default).
qod database delete --tenant acme --name acme_sales

# Delete and make the storage purge-eligible immediately (the worker drains it
# on its next sweep; the call itself still returns straight away).
qod database delete --tenant acme --name acme_sales --purge-managed-data
```

`purgeManagedData` on a BYO / default-path database is ignored with a WARN.
Deleting the whole TENANT cascades tombstones with the normal retention window,
never immediate.

Inventory / audit the prefixes in the control-plane Postgres:

```sql
SELECT id, prefix, deleted_at, purge_eligible_at, purged_at
FROM qodstate_managed_prefix
ORDER BY created_at;
```

- `deleted_at IS NULL` - live database.
- `deleted_at` set, `purged_at NULL` - retained; the objects are still there and
  still billed. Until `purge_eligible_at`, this window doubles as the undrop
  window (the data is recoverable by hand).
- `purged_at` set - objects gone, row kept as the audit trail.

The purge worker is HA-leader-gated, sweeps every `purgeSweepSec`, drains each
due prefix in bounded list+delete batches (resuming across sweeps for large
prefixes), isolates failures per prefix, and stamps `purged_at` only when a
listing comes back empty. Grep the manager log for `managed purge:`.

Two operator cautions:

- **Changing `QOD_MANAGED_STORE_BUCKET` strands existing tombstones.** Rows
  written under the old bucket no longer match the configured root, so the
  worker logs `skipping <id>, prefix ... is not under ...` on every sweep and
  the old bucket's objects leak. Purge or migrate before switching buckets.
- **All managed databases share the operator credential.** Isolation between
  tenants' managed data is prefix-by-convention, enforced by the per-db
  `CREATE SECRET` scoped to that database's own `dataPath` plus per-pool
  lockdown, not by store-level ACLs. Per-database credential minting is the
  designed follow-up.

### Register a federated source

```bash
qod federation create acme acme_fed --alias fedpg \
  --description "Prod warehouse Postgres" \
  --setup-sql "INSTALL postgres; LOAD postgres; CREATE OR REPLACE SECRET fedpg_sec (TYPE POSTGRES, HOST 'pg.prod', PORT 5432, DATABASE 'warehouse', USER 'svc_qod', PASSWORD '{{secret.PG_PWD}}'); ATTACH '' AS {{alias}} (TYPE POSTGRES, SECRET fedpg_sec, READ_ONLY);"
```

Placeholders:
- `{{alias}}` - replaced with the source's `alias` field.
- `{{secret.NAME}}` - replaced with the resolved value of the secret named `NAME`.

### Add a Postgres-backed secret

```bash
qod federation secret set acme acme_fed fedpg --name PG_PWD --value hunter2
```

Or a secret backed by an external store (env var, AWS Secrets Manager, etc.):

```bash
qod federation secret set acme acme_fed fedpg --name PG_PWD \
  --external-ref "vault:secret/data/qod/fedpg#password"
```

### Switch the secret resolver

`secretStore = postgres | env | aws-sm | gcp-sm | azure-kv | vault`. Set via `QOD_FEDERATION_SECRET_STORE=env` (and per-backend config keys). The four KMS backends are stubbed in v1; calling `resolve()` raises `NotImplementedError`. To enable one, fill in the corresponding resolver class with the real SDK call.

`externalRef` formats:

| Backend  | Format                                                       |
| -------- | ------------------------------------------------------------ |
| env      | `env:SL_QOD_SECRET_FOO`                                      |
| aws-sm   | `aws-sm:arn:aws:secretsmanager:...` or `aws-sm:name#jsonKey` |
| gcp-sm   | `gcp-sm:projects/<p>/secrets/<name>/versions/latest`         |
| azure-kv | `azure-kv:<secretName>` (vault URL from config)              |
| vault    | `vault:secret/data/<path>#<key>`                             |

### Export / import as YAML

These two endpoints have no `qod` command yet - call them over REST (the
static `QOD_API_KEY` or a session/PAT token goes in `X-API-Key`).

Export (`***REDACTED***` replaces every value-backed secret; `externalRef` is left as-is):

```bash
curl -H "X-API-Key: $QOD_API_KEY" \
  "http://localhost:20900/api/tenants/acme/tenant-dbs/acme_fed/federated-sources/yaml/export" > fed.yaml
```

Re-import after editing. Secrets with `value: "***REDACTED***"` (and no `externalRef`) reuse the existing row's value, so a round-trip never requires re-typing passwords:

```bash
curl -X POST -H "X-API-Key: $QOD_API_KEY" -H 'Content-Type: text/plain' \
  "http://localhost:20900/api/tenants/acme/tenant-dbs/acme_fed/federated-sources/yaml/import" --data-binary @fed.yaml
```

Import semantics: replace-by-alias inside the tenant-db. Sources absent from the YAML are deleted; secrets absent from a source are deleted.

### Lifecycle

- **Edits take effect on the next spawn.** Editing or disabling a source affects every Quack node spawned after the commit: idle-timeout replacement, manual restart, scale-up additions, pool recreation. Already-running nodes keep their attached catalogs until they exit.
- **Boot-time failure is fatal.** If a source's `setupSql` errors at node startup (extension missing, bad credentials, DNS), the spawn script exits 91 and the supervisor surfaces the last lines of stderr.
- **Disabled sources** are filtered out at blob assembly. No live DETACH.
- **Deleting a federated source** removes its alias from the ACL ambiguity guard immediately, but running nodes keep the catalog attached until the pool recycles. Recycle the pool right after deleting a source.

### Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `unresolved secret 'X' in source '<alias>'` in supervisor log | Source's setupSql references `{{secret.X}}` but no matching row | Add the secret row via `qod federation secret set` |
| `unsubstituted placeholder` at boot | Typo in setupSql like `{{secret.X}` (missing brace) | Fix setupSql via re-create (POST upserts); recycle the pool |
| `catalog 'fedpg' does not exist` from the client | Pool was not recycled after editing the source | Drop and recreate the pool, or wait for idle-timeout recycle |
| `missing RO grant on fedpg.public.X` | ACL not granted on the federated alias | `qod role permission grant --role-id <roleId> --catalog fedpg --schema public --table X --verb RO` |
| `kind env var is required` from spawn script | Manager invoked the script without setting `kind` | Manager release too old for this feature - upgrade (`qod start` with a current release) and restart |
| `secret '<name>' for source '<alias>' has no existing value to reuse` on YAML import | Imported `***REDACTED***` for a new source that didn't exist before | Provide the actual `value` or `externalRef` for that secret in the YAML |
| YAML import HTTP 400 `duplicate alias '<X>' in payload` | Two sources in the imported YAML have the same alias | Dedupe in the YAML before re-importing |

### What does NOT need an ACL change

The existing RBAC graph covers federated tables with zero changes:
- Grant `RO` on `fedpg.public.orders` to role `analyst` via `qod role permission grant` (verb `RO`), exactly like a DuckLake table.
- Federated writes (INSERT/UPDATE/DELETE on a federated alias) require an `RW` grant on the same triple; otherwise they are denied.
- Read-only is enforced at ATTACH time (the user's `setupSql` should include `READ_ONLY`), not in the validator.

## Node status + metrics

```bash
# Live node table (used by the UI)
qod pool list

# Compact one-line-per-node view of the same data
qod --json pool list \
  | python3 -c "import sys,json; d=json.load(sys.stdin); \
    [print(f'{n[\"nodeId\"]:28s} role={n[\"role\"]:9s} healthy={n[\"healthy\"]} \
served={n[\"totalServed\"]:5d} p50={n[\"p50Ms\"]:4.0f} p95={n[\"p95Ms\"]:4.0f} p99={n[\"p99Ms\"]:4.0f}') \
     for p in d['pools'] for n in p['nodes']]"

# Recent statement history (newest first)
qod node statements --limit 20
```

Per-node fields surfaced via `/api/pool/list`:
- `inFlight` - currently executing statements
- `totalServed` - lifetime counter since manager start
- `avgDurationMs` - EWMA latency
- `p50Ms`/`p95Ms`/`p99Ms` - rolling 256-sample window
- `healthy` / `draining` - tracker flags

On object-store pools the router biases toward a node that already has the query's tables cached (cache-aware placement, on by default); local-path pools stay pure least-loaded. `QOD_ROUTING_CACHE_AWARE=false` instantly reverts routing decisions to least-loaded everywhere while the locality metrics keep running.

## Incident response

Quarantine is durable operator state, separate from node health. Check the quarantined flag in `pool/list` before debugging a node that shows unhealthy-like symptoms.

```bash
# Quarantine a node: stop routing new statements to it (running ones finish).
# Durable: survives manager restarts; only unquarantine clears it. Superuser only.
qod node quarantine --tenant acme --db acme_tpch --pool bi --node-id bi-1

qod node unquarantine --tenant acme --db acme_tpch --pool bi --node-id bi-1

# Restart a node: kills everything running on it, respawns with the same id, and clears any quarantine. Superuser only.
qod node restart --tenant acme --db acme_tpch --pool bi --node-id bi-1

# In-flight statements (tenant admins see only their tenant).
qod node active-statements

# Best-effort kill by statement id from the list above. "accepted" is not a guarantee:
# the manager closes the stream; a node that ignores disconnect keeps executing.
# Response is "accepted" (stream closed, best-effort) or "already-completed" (statement finished before the kill arrived).
# Escalate with node restart when the statement must die.
qod statement kill <statement-id>
```

## Audit log

The audit log records control-plane mutations, auth events, data-plane denials, and data-plane writes. It is backed by the `qodstate_audit` Postgres table when `QOD_TELEMETRY_STORE=postgres` (the default).

```bash
# Most recent 50 control-plane events
qod audit list --family control-plane --limit 50

# Page through with the keyset cursor (use nextBefore from the previous response)
qod audit list --before <nextBefore-from-previous-page>

# Failed logins in a time window
qod audit list --action auth.login.failure --from 2026-07-01T00:00:00Z

# Only no-tenant rows (anonymous auth failures, node ops, manifest imports; superuser only)
qod audit list --no-tenant

# Exhaustive action vocabulary for exact --action filters
qod audit actions
```

Filters: `family`, `tenant` (superuser: returns only that tenant's rows; null-tenant rows not included when this is set), `noTenant=true` (superuser: only null-tenant rows; wins over `tenant`), `actor`, `action` (exact), `q` (substring on action/target), `from`, `to` (ISO-8601), `limit` (max 500), `before` (keyset cursor). Results are newest-first.

Tenant admins see only their own tenant's rows. Superusers and static-key callers see everything, including null-tenant rows (anonymous failures, node ops, manifest imports).

**Retention and the off switch:**

| Env var | Default | Effect |
|---|---|---|
| `QOD_TELEMETRY_JOURNAL_CAPACITY` | `8192` | Bounded in-process telemetry journal queue depth. Overflow drops events and increments `qod_journal_dropped_total`. Increase to buffer higher statement throughput under Postgres write latency spikes |
| `QOD_AUDIT_RETENTION_DAYS` | `90` | Delete rows older than N days (hourly purge); set to `0` to keep forever |
| `QOD_TELEMETRY_STORE` | `postgres` | `none` disables all recording, hides the Audit UI page, and keeps the drop counter at zero |

## Statement history and trends

Statement history records every FlightSQL statement (including reads). Raw rows are searchable for a short window; aggregated rollups drive trend charts over a longer period. Both are tenant-scoped: superusers see all tenants, tenant admins see only their own.

```bash
# Most recent 50 statements for a tenant
qod history statements --tenant acme --limit 50

# Page through with the keyset cursor (use nextBefore from the previous response)
qod history statements --before <nextBefore-from-previous-page>

# Find yesterday's slow statements: fetch the window, filter durationMs locally
# (there is no duration filter parameter on the endpoint)
qod --json history statements --tenant acme --pool bi \
  --from 2026-07-05T00:00:00Z --to 2026-07-06T00:00:00Z --limit 500 \
  | python3 -c "
import sys, json
rows = json.load(sys.stdin).get('statements', [])
slow = [r for r in rows if r.get('durationMs', 0) > 5000]
for r in sorted(slow, key=lambda x: -x.get('durationMs', 0)):
    print(r.get('durationMs'), r.get('username'), r.get('sql', '')[:80])
"

# Hourly trend for the last 7 days
qod history trends --granularity hour --tenant acme --pool bi \
  --from 2026-06-29T00:00:00Z --to 2026-07-06T00:00:00Z

# Daily trend for the last 30 days
qod history trends --granularity day --tenant acme \
  --from 2026-06-06T00:00:00Z --to 2026-07-06T00:00:00Z
```

Statement filters: `tenant`, `pool`, `user`, `status` (`ok`, `denied`, `transient`, `permanent`, `no-node`, `no-pool`, or `pin-lost`), `q` (substring on SQL), `from`, `to` (ISO-8601), `limit` (max 500), `before` (keyset cursor). Results are newest-first.

Trend filters: `granularity` (required: `hour` or `day`), `tenant`, `pool`, `from`, `to`. Hourly buckets include p50/p95/p99; daily buckets have null percentiles.

**Retention env vars:**

| Env var | Default | Effect |
|---|---|---|
| `QOD_STMT_HISTORY_RETENTION_DAYS` | `7` | Delete raw statement rows older than N days (hourly purge); set to `0` to keep forever |
| `QOD_HOURLY_ROLLUP_RETENTION_DAYS` | `90` | Delete hourly rollup rows older than N days (hourly purge); set to `0` to keep forever |
| `QOD_ROLLUP_INTERVAL_SEC` | `300` | How often the rollup job recomputes touched buckets (leader-gated in HA mode) |

`QOD_TELEMETRY_STORE=none` disables all recording, hides the History UI page, and keeps the drop counter at zero. Raw retention must stay at least 2 days so the daily recompute can rebuild its whole-day bucket.

## Usage and accounting

Durable per-tenant / per-pool / per-user metering over daily rollups. Tenant-scoped like the
history endpoints: superusers see all tenants, tenant admins are pinned to their own.

```bash
# Month-to-date per tenant (defaults: current calendar month UTC, group-by tenant)
qod usage

# A closed month per pool, for billing
qod usage --from 2026-06-01T00:00:00Z --to 2026-07-01T00:00:00Z --group-by pool --tenant acme

# CSV extraction for billing (columns per the spec contract)
qod --json usage --from 2026-06-01T00:00:00Z --to 2026-07-01T00:00:00Z \
  | jq -r '["tenant","statements","errors","denied","engine_ms"],
           (.groups[] | [.tenant, .statements, .errors, .denied, .engineMs]) | @csv'
```

Params: `from` / `to` (ISO-8601 instants, half-open, default = current calendar month),
`groupBy` (`tenant` default, `pool`, `user`), `tenant`, `pool`. Groups are sorted by
`engineMs` descending; each group carries a per-day `days` array. `dataStart` marks the
oldest daily bucket still retained.

| Env var | Default | Effect |
|---|---|---|
| `QOD_USAGE_RETENTION_DAYS` | `400` | Delete daily rollup buckets older than N days (hourly purge); `0` = keep forever |

## Ad-hoc queries (qod sql)

`qod sql` runs SQL against the FlightSQL edge and prints a terminal table
(`--csv`, or the global `--json`, for machine output). `qod login` stores the
edge host/port/TLS and the SQL username in the active CLI profile, so after a
login it needs no flags beyond the routing target. It's the quickest way to
confirm what a given user actually sees - handy for spot-checking ACL,
column-, and row-level policies.

```bash
# Query as the profile's user (prompts once for the SQL password)
qod sql --tenant acme --pool bi \
  "SELECT c_mktsegment, count(*) FROM tpch1.customer GROUP BY 1 ORDER BY 1"

# Same query as a superuser (system realm) - bypasses RLS/CLS, so diffing the
# two outputs shows exactly what a policy filtered or masked. Keep one CLI
# profile per principal and pick one with --profile.
qod --profile root sql --tenant acme --pool bi --superuser \
  "SELECT c_mktsegment, count(*) FROM tpch1.customer GROUP BY 1 ORDER BY 1"

# Interactive REPL (\q quits), or a script file (first error aborts, exit 1)
qod sql
qod sql --file setup.sql
echo "SELECT 1" | qod sql --file -
```

Connection settings resolve like every other CLI setting: flags > `QOD_HOST` /
`QOD_PORT` / `QOD_TLS` / `QOD_USER` / `QOD_PASSWORD` / `QOD_TENANT` /
`QOD_POOL` / `QOD_SUPERUSER` env vars > the profile written by `qod login`.
Unqualified table names resolve against the pool's default schema, but the
FlightSQL prepare-time probe needs a real table - schema-qualify
(`tpch1.customer`) if you hit "Table … does not exist" at prepare.

Two-part names are only unambiguous when the head is a schema in the pool's default catalog, as in `tpch1.customer` above. When the head instead names an attached catalog (the tenant-db itself, e.g. `acme_tpch`, or a federation alias) under ACL, it's rejected as ambiguous - the engine would bind it catalog-first while the ACL check can't tell which catalog you meant. Write the full three-part form instead: `acme_tpch.tpch1.customer`.

## Hardening (lockdown, pod security, network policy, reader eviction)

Self-serve / hosted deployments need a tighter isolation posture than the OSS
default (single trusted-operator assumption). Four independent knobs, all
opt-in except pod security:

- **Node lockdown** - `QOD_NODE_LOCKDOWN=true` (config
  `quack-on-demand.nodeLockdown.enabled`, default false). Two layers, both
  gated by the same flag:
  - Edge denial (first line): for non-superuser sessions, denies `ATTACH` /
    `DETACH`, `INSTALL` / `LOAD`, `SET`/`RESET`/`PRAGMA` against a
    protected-settings list (`disabled_filesystems`, `allow_*_extensions`,
    `autoinstall_known_extensions`, `autoload_known_extensions`,
    `enable_external_access`, `lock_configuration`, `temp_directory`,
    `extension_directory`, `secret_directory`, plus the resource settings
    `memory_limit`, `max_memory`, `threads`, `worker_threads`,
    `max_temp_directory_size` - a tenant raising them past the spawn-time
    defaults could OOM the node or pressure the host), and local-machine read
    functions (`read_text`, `read_blob`, `glob`, `read_csv[_auto]`,
    `read_parquet`, `read_json[_auto]`, `read_ndjson[_auto]`, `parquet_scan`,
    `getenv`) unless every path argument is an object-store URL literal
    (`s3://`, `gs://`, `az://`, `r2://`, `http(s)://`). Denials use the same
    wire shape as ACL denials ("lockdown: ... is disabled on this
    deployment") and go through the existing audit path. Superuser sessions
    bypass the edge arm (operator escape hatch).
  - Engine lockdown (second line, value-sets only): a lockdown SQL block
    runs BEFORE `CALL quack_serve(...)` in node init (after catalog ATTACH,
    pool initSql, and the federation blob) setting `autoinstall_known_extensions
    = false`, `allow_community_extensions = false`, `allow_unsigned_extensions
    = false`, and `disabled_filesystems = 'LocalFileSystem'` (only when the
    tenant-db dataPath is an object store). `autoload_known_extensions` is left
    ON: quack_serve itself lazily autoloads signed built-in extensions to
    handle incoming connections, and disabling autoload marks every node
    unhealthy (the SELECT 1 probe fails). `SET lock_configuration = true` was
    tried and dropped: it freezes DuckDB's global config outright and is
    incompatible with quack_serve regardless of which side of quack_serve it
    runs on, so nodes never come up healthy. It is also redundant - the edge
    LockdownScreen already denies every protected-setting SET/RESET/PRAGMA for
    tenant sessions, so nothing short of a superuser bypass could change these
    values anyway. The real threats (autoinstall fetching arbitrary extensions
    over the network, community/unsigned extensions) stay blocked; autoloading
    a signed built-in already on disk is benign.
  - LOCAL-dataPath lockdown is best-effort: the engine
    `disabled_filesystems = 'LocalFileSystem'` restriction is NOT applied for
    a local dataPath (DuckLake data lives there, so the filesystem must stay
    enabled). The edge screen also denies COPY over local paths and bare
    filesystem-path FROM (replacement scans), but this protection is
    best-effort: dollar-quoted (`$$...$$`) and identifier-quoted path forms
    remain unhandled and may still read local files in LOCAL mode. Production
    hosted deploys MUST use an object-store dataPath, where the engine
    `disabled_filesystems` layer is the hard guard; the edge screen alone is
    not a hard guard for LOCAL mode.
  - Per-pool override: the global `QOD_NODE_LOCKDOWN` default stays OFF
    (unchanged by this feature) - it is the fallback for pools that don't set
    their own value. `POST /api/pool/setLockdown` layers a tri-state
    `inherit | on | off` on top, superuser only, and restarts the pool's
    nodes immediately so the new posture takes effect:
    ```bash
    # Per-pool override (superuser only; restarts the pool's nodes immediately).
    # --lockdown: inherit (default, follow QOD_NODE_LOCKDOWN) | on | off
    qod pool set-lockdown --tenant acme --db acme_tpch --pool bi --lockdown off
    ```
  - Verify: with the flag on, `SELECT * FROM read_text('/etc/passwd')` and
    `ATTACH ':memory:' AS x` are denied for a tenant user, `SET
    autoinstall_known_extensions=true` is denied, nodes come up healthy, and
    ordinary TPCH queries still work. With the flag off, behavior is
    unchanged from pre-lockdown.
  - `POST /api/pool/create`'s `lockdown` field is gated the same
    superuser-only way; a tenant admin sending ANY non-`"inherit"` value
    (including an invalid one like `"banana"`) gets `403 superuser_required`
    rather than `400 invalid`, because the gate compares the raw string
    before it is parsed - deliberate fail-closed behavior, not a bug.

- **Pod security defaults** (K8s backend only, no flag, always on) - spawned
  node pods get `runAsNonRoot: true`, `runAsUser`/`fsGroup` from
  `k8s.runAsUser` (default 1000), `seccompProfile: RuntimeDefault` on the pod,
  and `allowPrivilegeEscalation: false`, `capabilities: drop [ALL]`,
  `readOnlyRootFilesystem: true` on the quack container, with writable
  `emptyDir` mounts for `/tmp` and the DuckDB temp directory. A pod-template
  override merges field-by-field rather than clobbering, so an operator
  template can relax individual fields if a workload needs it.

- **NetworkPolicy** (helm, opt-in) - `--set networkPolicy.enabled=true`
  (default false) renders `networkpolicy-nodes.yaml` (default-deny; ingress
  from manager pods only on the node port range, egress to DNS, Postgres,
  and the object store) and `networkpolicy-manager.yaml` (ingress on
  `:20900`/`:31338` from `networkPolicy.ingressFrom`, egress to node pods,
  Postgres, DNS, optional SMTP). Tune `networkPolicy.postgres.cidr`,
  `networkPolicy.objectStore.cidrs`, and `networkPolicy.nodePortRange` in the
  chart values (`helm show values oci://ghcr.io/starlake-ai/charts/quack-on-demand`).
  Sanity-check a rendered policy with
  `helm template --set networkPolicy.enabled=true` before applying.

- **Catalog-reader idle eviction** - each cached `DuckLakeCatalogReader`
  (one per browsed tenant-db, in `Main`'s `catalogReaderCache`) owns a small
  HikariCP pool; without bounding, thousands of self-serve tenant-dbs would
  each pin a pool forever. A daemon sweeper (process-local - every manager
  replica sweeps its own cache, no HA coordination) runs every
  `QOD_CATALOG_READER_SWEEP_MIN` minutes (config
  `quack-on-demand.catalogReader.sweepIntervalMin`, default 10) and closes +
  evicts any reader idle past `QOD_CATALOG_READER_IDLE_EVICT_MIN` minutes
  (config `catalogReader.idleEvictMin`, default 30). Each reader's Hikari
  pool also sets `minimumIdle=0` / `idleTimeout=60s`, so an idle-but-not-yet-
  evicted reader already holds zero live Postgres connections. Delete/rotate
  of a tenant-db still evicts immediately regardless of the sweep cadence.
  To watch it fire without waiting 30 minutes, set
  `QOD_CATALOG_READER_IDLE_EVICT_MIN=1`, browse a tenant-db's catalog, wait a
  couple minutes, and grep the manager log for `reader-cache sweep: evicted`.

## Typical failure modes

| Symptom | Cause | Fix |
|---|---|---|
| `/api/*` returns 401 | No valid credential on the call (missing/wrong key, expired session) | `qod login`, or pass `X-API-Key: <key>` on raw REST calls |
| `no node with role READONLY or DUAL` | All nodes flipped unhealthy (port unreachable) | Check `pgrep -fl spawn-quack-node`; if 0, run `qod stop` + `qod start` (reconcile respawns) |
| `access denied: missing RO grant on ...` | ACL is enabled and the user has no matching grant | Add the grant via `qod role permission grant` or set `QOD_ACL_ENABLED=false` |
| `session expired; please reconnect` | Bearer token unknown (manager restarted between calls) | Re-login or pass Basic credentials |
| `Could not connect to server` for `http://127.0.0.1:21NNN/quack` | Quack child died after manager restart | Reconcile respawns on next boot; until then `qod pool delete` + `qod pool create` |
| Manager (or spawned node) hangs at startup right after `BaseAllocator` log line, java pegged at 100% CPU | `INSTALL quack` is blocked by a corporate proxy - DuckDB is silently retrying to fetch the extension from `extensions.duckdb.org` | Pass `HTTP_PROXY` / `HTTPS_PROXY` / `NO_PROXY` env vars to the process (container `-e` or shell env). See "Behind a corporate proxy" in the project README on GitHub. |

## Where state lives

- **Tenants, tenant-dbs, pools, nodes** - normalized `qodstate_tenant` / `qodstate_tenant_db` / `qodstate_pool` / `qodstate_node` tables (Liquibase-managed) in the dedicated control-plane Postgres database (`qod` by default, override with `QOD_PG_DBNAME`). The legacy single-JSONB-row `slkstate_pool_state` blob was dropped.
- **Users + RBAC** - `qodstate_user` (bcrypt-hashed passwords), plus `qodstate_role`, `qodstate_group`, `qodstate_role_permission`, `qodstate_user_role`, `qodstate_user_group`, `qodstate_group_role`, `qodstate_pool_permission`. The old `slkstate_user` and `slkstate_acl_grant` tables were dropped in the RBAC cutover.
- **Federation** - `qodstate_federated_source`, `qodstate_federated_secret`
- **DuckLake catalog metadata** - `ducklake_*` tables in each managed tenant-db's own Postgres database (`${tenant}_${suffix}`), separate from the control plane
- **DuckLake data files** - `defaultMetastore.dataPath` on disk (or s3://, gs://, az://)
- **Self-signed TLS cert** - `certs/server-{cert,key}.pem` under `qod start`'s state dir (the platform user-data dir), auto-generated on first boot if missing
- **Manager log** - `qod start` runs the manager in the foreground; its log is the terminal output

## When operating

- The default admin password is `admin`. Rotate via `QOD_ADMIN_PASSWORD` before exposing the edge.
- With no `QOD_API_KEY` pinned, boot generates a random one and prints it in a startup banner; it changes on every restart, so pin `QOD_API_KEY` (and `QOD_SESSION_JWT_SECRET`) before any non-localhost deploy.
- All config scalars have matching `QOD_*` env-var overrides. Prefer env vars over editing `application.conf` (it is bundled into the jar at build time).

## Common UI URLs

- `http://localhost:20900/ui/` → Nodes dashboard (landing page)
- `http://localhost:20900/ui/tenants` → Tenants list
- `http://localhost:20900/ui/tenant/<tenant>` → tenant detail + ACL editor
- `http://localhost:20900/ui/pool/<tenant>/<pool>` → per-pool nodes + JDBC URLs