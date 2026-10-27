# Delegating data-access authorization to Open Policy Agent

QoD can hand the data-access decisions of a tenant to that tenant's own
[Open Policy Agent](https://www.openpolicyagent.org/) server. QoD stays the enforcement point; the
tenant's Rego policy is the source of truth for:

1. **pool access**, decided once at session handshake (the `connect` rule), and
2. **table and file access**, decided per statement for every table and file the statement touches
   (the `statement` rule): once by the gateway on the tables QoD's SQL parser extracts, and once by
   the node on what DuckDB resolved (see "statement input from the node").

Row filters and column masks are not decided by OPA in this version (see "Result" below).

## Modes

Each tenant is in exactly one mode:

| Mode | Pool access | Table access |
|---|---|---|
| `qod` | QoD pool grants (`qodstate_pool_permission`) | QoD role permissions (`qodstate_role_permission`) |
| `opa` | the tenant's OPA, `connect` rule | the tenant's OPA, `statement` rule |

On an `opa` tenant QoD's grant tables are ignored. Role and group **memberships** still resolve
(QoD RBAC graph plus JWT/OIDC claim names) and are sent to OPA as identity attributes. OPA
authorizes; it does not create users: users of an `opa` tenant are provisioned as usual (SCIM,
OIDC, CLI, REST).

`opa` mode always validates statements, even when the manager-wide `QOD_ACL_ENABLED` is false: a
policy you configured is never silently ignored by a global flag. Any stored mode other than exactly
`qod` is treated as `opa` (fail closed).

Superusers (users with no tenant) never reach OPA, in either mode.

## Configuration

### Manager-wide (environment)

| Env | Default | Meaning |
|---|---|---|
| `QOD_ACL_MODE` | `qod` | Mode of a tenant that sets none. |
| `QOD_OPA_URL` | empty | OPA base URL for `opa` tenants that set none (an operator-run sidecar). |
| `QOD_OPA_TIMEOUT_MS` | `2000` | Bound on one whole OPA exchange (connect, headers and body). |
| `QOD_OPA_CACHE_TTL_SEC` | `5` | Seconds an allow / deny is cached. `0` disables the cache. |

### Per tenant

```bash
qod tenant set-acl acme --mode opa --opa-url https://opa.acme.internal:8181 \
    --opa-policy-path qod/authz --opa-token "$OPA_TOKEN"
qod tenant set-acl acme --send-statement-text      # include the SQL text in the input
qod tenant set-acl acme --mode qod                 # back to QoD grants
```

- Omitted options keep their stored value; an empty string clears one (`--mode ''` = manager
  default, `--opa-url ''` = fall back to `QOD_OPA_URL`, `--opa-token ''` = no token).
- `--opa-policy-path` defaults to `qod/authz`; it is slash-separated identifiers only.
- Switching a tenant to `opa` with neither a tenant URL nor `QOD_OPA_URL` is refused
  (`400 opa_url_required`).
- The token is write-only: sent as `Authorization: Bearer <token>`, never returned (responses only
  say whether one is set), redacted in exported manifests.
- The same settings are available in the admin UI (tenant page, Access control section) and the
  MCP admin tool `set_tenant_acl`.

**Manifest re-import.** An exported manifest carries the token redacted. Re-importing it onto a
tenant that still has a stored token keeps that token. Importing it where no token is stored (a
fresh control plane) is an import error, not a silent drop: supply the real value in the manifest,
or import without `opaToken` and set it afterwards with `qod tenant set-acl`.

## The contract

QoD calls `POST {opaUrl}/v1/data/{policyPath}/connect` and
`POST {opaUrl}/v1/data/{policyPath}/statement`.

### connect input

```json
{"input": {
  "kind": "connect",
  "tenant": "acme",
  "database": "tpch",
  "pool": "bi",
  "parentPool": null,
  "user": {"name": "alice", "roles": ["analyst"], "groups": ["emea"], "claims": {"dept": "fin"}},
  "client": {"edge": "flightsql"}
}}
```

- `pool` is the addressed pool. For a branch pool (`__br_<id8>`) `parentPool` is an array of the
  parent database's pool names, so a policy can let a branch inherit access from its parent; it is
  `null` for a regular pool.
- `roles` and `groups` are the resolved closure, sorted. `claims` are the verified token claims
  when the session was authenticated by a token, `{}` otherwise.
- `client.edge` is `flightsql`, `quack` (native Quack front door), `mcp` (MCP tools and the REST
  SQL preview), `rest` (branch creation access probe) or `dry-run` (`qod tenant opa-test`).

### statement input

```json
{"input": {
  "kind": "statement",
  "tenant": "acme",
  "database": "tpch",
  "pool": "bi",
  "parentPool": null,
  "user": {"name": "alice", "roles": ["analyst"], "groups": ["emea"], "claims": {"dept": "fin"}},
  "client": {"edge": "mcp"},
  "statement": {"class": "WRITE"},
  "accesses": [
    {"catalog": "tpch", "schema": "main", "table": "lineitem", "verb": "write"},
    {"catalog": "tpch", "schema": "main", "table": "orders", "verb": "read"}
  ]
}}
```

- `statement.class` is `READ`, `WRITE` or `DDL`. It is advisory: it reflects only the first
  statement of a batch, judged by its first keyword, so `SELECT 1; DELETE FROM t` (or
  `(SELECT 1); DELETE FROM t`) arrives as `READ` while `accesses` carries the `write`. Never
  authorize on it: decide on the `accesses` verbs, as the starter policy does.
- `accesses` is the exact table set QoD's SQL parser extracts (the same parser and catalog
  qualification as QoD's own grant check), fully qualified, sorted, deduplicated, with lowercase
  verbs `read | write | ddl`.
- `statement.text` (the SQL) is present only when the tenant enables `--send-statement-text`, so
  SQL literals do not land in your decision logs by default.
- A statement with no table references (`COMMIT`, `SET`, `USE`, `SHOW`) is admitted without
  calling OPA, so the `statement` rule never sees an empty `accesses`.

### statement input from the node

Every statement of a non-superuser is also decided on the node that runs it, on what DuckDB
resolved for it (in-engine authorization). The node asks the manager, which asks the same
`statement` rule with this input:

```json
{"input": {
  "kind": "statement",
  "tenant": "acme",
  "database": "tpch",
  "pool": "bi",
  "parentPool": null,
  "user": {"name": "alice", "roles": ["analyst"], "groups": ["emea"], "claims": {"dept": "fin"}},
  "client": {"edge": "node"},
  "statement": {"class": "WRITE"},
  "accesses": [
    {"catalog": "memory", "schema": "main", "table": "base", "verb": "read", "kind": "table",
     "columns": ["id"], "via": "memory.main.v_secure"},
    {"catalog": "memory", "schema": "main", "table": "v_secure", "verb": "read",
     "kind": "secure_view", "columns": ["id"], "via": null},
    {"catalog": "tpch", "schema": "main", "table": "orders", "verb": "read", "kind": "table",
     "columns": ["o_orderkey", "o_totalprice"], "via": null}
  ],
  "locations": [
    {"access": "write", "location": "s3://acme-etl/out/orders.parquet", "scope": "s3://acme-etl/",
     "via": "copy_to"}
  ]
}}
```

- `accesses` are the objects of the bound plan, lowercase, sorted: tables and views read or written,
  and schemas, sequences, indexes, types created, dropped or altered. `kind` is `table`, `view`,
  `secure_view`, `schema`, `sequence`, `index`, `macro`, `type` or `trigger`; a schema-wide access
  (`kind: schema`) has `table: "*"`. `columns` lists the columns read (empty for a write or DDL).
  `via` names the outermost secure view the access is read through (`catalog.schema.name`), `null`
  otherwise: a secure view runs with its creator's rights, so QoD covers such a read by a read grant
  on the view, and the starter policy does the same. The view itself is listed too (`kind:
  secure_view`, `via: null`). Secure views live in DuckDB catalogs (a `memory` or `duckdb-file`
  database); a DuckLake catalog holds none.
- `locations` lists the files the statement reads (a reader function such as `read_parquet`, or
  `COPY ... FROM`) or writes (`COPY ... TO`), already admitted by QoD: under one of the user's own S3
  credential scopes (`qod profile s3 add`) or the tenant's authorized folders, and outside every
  database data folder. `scope` names the credential scope or folder that admitted it. A policy can
  only narrow them.
- `statement.class` is computed from the accesses (any `ddl` verb: `DDL`; any `write` or a
  `copy_to`: `WRITE`; else `READ`). There is never `statement.text` (the node describes plans, not
  SQL), whatever `--send-statement-text` says.
- The node input is sent only when there is something to judge: a statement whose plan touches no
  object outside the session's `temp` catalog and DuckDB's `system` views, and names no file, is
  allowed without calling OPA. Rules QoD always enforces (below) are applied first, so OPA is never
  asked about a statement QoD refuses anyway.
- A node decision is cached by the node for `QOD_OPA_CACHE_TTL_SEC` (at least 5 s) per user,
  session context and plan.

**Upgrading a policy.** The new fields are additive, but a policy that ignores `via` judges a read
through a secure view as a direct read of the underlying table, which the user usually holds no
grant on: such reads are then denied. Add a `via` rule like the starter policy's, and decide on
`locations` if your users read or write files. Test with `opa eval` on the node input above.

### Result

```json
{"result": {"allow": true}}
{"result": {"allow": false, "reason": "emea analysts cannot write lineitem",
            "denied": [{"catalog": "tpch", "schema": "main", "table": "lineitem", "verb": "write"}]}}
```

- `reason` reaches the client after truncation to 256 characters and removal of control
  characters.
- `denied` is advisory: it narrows the error message to the listed accesses. Entries that do not
  match an input access are ignored; a deny with no usable `denied` names every access.
- `decision_id` (top level of OPA's response, present when OPA decision logging is on) is recorded
  on QoD's denial audit row as `opa_decision_id`, next to `authz_source=opa`, so you can join the
  two logs.

### Fail closed

Only a literal JSON `true` at `result.allow` allows.

| OPA response | Outcome |
|---|---|
| `allow: true` | allow |
| `allow: false` | deny, with `reason` and `denied` |
| `allow` missing, `"true"`, `1`, `null`; `result` missing (rule undefined); non-JSON body | deny, "policy error: ..." naming the problem |
| `row_filter` or `masks` present (any value) | deny: reserved for a future version, rejected now |
| timeout, connection refused, non-2xx status, body larger than 1 MiB, no URL configured | error |

How each outcome reaches the client:

| Outcome | Handshake | Statement |
|---|---|---|
| allow | admitted | executed |
| deny | permission refusal, "pool access denied by policy: <reason>" | permission refusal naming the denied tables and the reason |
| error | `UNAVAILABLE`, "authorization service unavailable: OPA unreachable or returned an error (see the manager log)" (retryable) | same |

An outage is reported as `UNAVAILABLE`, not as a permission error, so clients retry and nobody
chases a phantom grant problem. The client never sees the cause: the tenant admin chooses the OPA
URL, so the exception, host, port or HTTP status would let anyone probe what the manager can reach.
The manager logs it at WARN as `OPA <connect|statement> error for tenant=<id>: <cause>`. There is
no circuit breaker: during an outage every decision waits up to `QOD_OPA_TIMEOUT_MS` and is
refused. The timeout bounds the whole exchange, including the response body, and the body is
capped at 1 MiB.

## What QoD still enforces on an `opa` tenant

Independently of OPA:

- superuser bypass (superusers never reach OPA);
- file locations: a file a statement names must lie under one of the user's own S3 credential
  scopes or the tenant's authorized folders, and outside every database data folder (checked before
  OPA is asked; `locations` only lists files that passed, each with its `scope`);
- DuckLake catalog metadata, node-state functions and settings, attaching, loading and installing
  extensions, secrets, and macros, triggers and secure views: superuser-only, whatever the policy
  says;
- user existence, `enabled`, lock, forced password change, session revocation;
- pool lockdown and bucket denial, the protected-write guard, filtered metadata, catalog write
  screening;
- row and column policies (RLS / CLS) from QoD's own policy tables;
- statements QoD cannot authorize precisely (parse errors, unsupported constructs, unqualifiable
  table references) are refused before OPA is called, with no wildcard escape.

## Revocation and caching

- Allow and deny decisions are cached for `QOD_OPA_CACHE_TTL_SEC` seconds. The cache key covers
  every field of the input (and the tenant's OPA settings), so a different question is never
  answered from a cached decision; errors are never cached.
- A Rego or data change applies to new decisions within the TTL. It does **not** kill running
  sessions or statements: pool access is decided at handshake, so a session admitted before the
  change stays connected until it reconnects. Statement decisions re-evaluate within the TTL.
- OPA cannot push a revocation to QoD. QoD-side events (user disabled, locked, deleted, logged out)
  keep their usual behaviour.
- Cost of `QOD_OPA_CACHE_TTL_SEC=0`: the Flight edge authenticates on every RPC and validates a
  statement twice, once in the schema probe (`GetFlightInfo`) and once in the fetch (`DoGet`). With
  the cache off, one FlightSQL statement therefore costs **two statement decisions plus one connect
  decision per RPC**, so OPA sees roughly three calls per query instead of one. The native Quack
  front door and MCP validate once per statement. Keep a small positive TTL unless you need every
  decision re-evaluated on the spot.

## Dry run

```bash
qod tenant opa-test acme --pool bi --user alice                                  # connect
qod tenant opa-test acme --pool bi --user alice --sql "SELECT * FROM orders"     # statement
```

Prints the exact input document and the tenant OPA's answer (`allow`, `deny` or `error`). An
`error` shows the transport cause to a superuser only; a tenant admin sees "OPA unreachable or
returned an error (see the manager log)". Nothing is executed and the decision cache is neither
read nor filled. Differences from a live session: `client.edge` is `dry-run`, `claims` is `{}`,
and unqualified tables resolve to the pool's database and its `main` schema (no session `USE`
state).

## Starter policy

[`qod-authz.rego`](qod-authz.rego) (package `qod.authz`) reproduces QoD's own grant semantics
from data you load into OPA, so a migrating tenant starts from equivalence and edits from there:

```json
{"qod": {
  "pool_grants":  {"acme": {"analyst": ["bi"], "admin": ["*"]}},
  "table_grants": {"acme": {"analyst": [{"catalog": "tpch", "schema": "*", "table": "*", "verb": "RO"}]}},
  "blocked_locations": {"acme": ["s3://acme-etl/restricted/"]}
}}
```

- `pool_grants[tenant][role]`: pool names the role may connect to, `"*"` for every pool. A
  branch pool is allowed when the role holds any of its parent pools.
- `table_grants[tenant][role]`: `catalog` / `schema` / `table` patterns (`"*"` matches anything)
  with a verb: `RO` covers read, `RW` read and write, `DDL` ddl, `ALL` everything. A read through
  a secure view (`via`) is covered by a read grant on the view; a schema-wide access needs a grant
  whose `table` is `"*"`.
- `blocked_locations[tenant]` (optional): location prefixes the tenant's statements may never
  read or write, on top of QoD's own location checks. Absent, every location QoD admitted is
  allowed. A prefix matches as plain text: end it with `/`, or `s3://acme-etl/restricted` also
  blocks `s3://acme-etl/restricted2/`.
- Every other input is denied by default (`default statement := {"allow": false, ...}`).

Run it locally:

```bash
opa run --server --addr 127.0.0.1:8181 qod-authz.rego grants.json
QOD_OPA_URL=http://127.0.0.1:8181 ...           # or per tenant: qod tenant set-acl acme --opa-url ...
qod tenant set-acl acme --mode opa
qod tenant opa-test acme --pool bi --user alice --sql "SELECT * FROM tpch.main.orders"
```

## Known limitation: FlightSQL denials in the schema probe are not audited

A FlightSQL client asks for the result schema (`GetFlightInfo`) before it fetches rows (`DoGet`).
QoD validates the statement at both steps, but the schema probe runs without journaling, so a
statement OPA denies is refused at the probe and **no denial audit row** is written for it; the
`authz_source=opa` / `opa_decision_id` row only appears for denials reached through the fetch, the
native Quack front door, MCP or the REST preview. This is pre-existing behaviour of the Flight edge
and applies to QoD-mode denials alike. Enable OPA decision logging if you need a complete record of
FlightSQL denials.

## Known limitation: catalog listings

With filtered metadata on (`QOD_ACL_FILTERED_METADATA`, default true), `information_schema` and
`duckdb_*` listings are narrowed by **QoD grants**, not by OPA. An `opa` tenant whose users hold
no QoD role permissions therefore sees empty catalog listings: this fails closed (nothing leaks),
but BI tools that browse the catalog show no tables, even though queries OPA allows run normally.
