---
id: mcp
title: MCP server (AI agents)
---

The manager embeds an MCP (Model Context Protocol) server at `POST /mcp` on the REST port (default `:20900`), so AI agents such as Claude Code, Claude Desktop, or Cursor can discover schemas, run SQL with full RBAC/RLS/CLS enforcement, use DuckLake time travel, and (for admin credentials) operate pools and nodes.

The transport is stateless Streamable HTTP: each POST carries one JSON-RPC message and the response is plain JSON. There is no SSE, no server push, and no session id, so any HA replica answers any request. `GET /mcp` returns 405.

## Authentication

`/mcp` accepts exactly two credentials in the `Authorization: Bearer <token>` header:

| Credential | Principal |
|---|---|
| Personal access token (`qod_pat_...`) | The owning user, with that user's live tenant, role, and grants |
| The static API key (`QOD_API_KEY`) | Superuser-equivalent; only when the key is set non-empty |

Session JWTs, passwords, and missing headers are all 401. `/mcp` never admits unauthenticated requests.

Create a PAT from the profile page in the UI, or with the CLI:

```bash
qod auth pat create --name claude-code
# the token is printed ONCE; store it now
```

A PAT acts with exactly its owner's permissions and can be revoked at any time (`qod auth pat revoke --id <id>`, or the profile page). A revoked or expired token stays visible in the listing until you discard it with `qod auth pat delete --id <id>` (or the profile page's Delete button); a live token must be revoked before it can be deleted. Tenant-scoped principals get their tenant inferred from the PAT owner; superuser and static-key callers pass an explicit `tenant` argument on tools that need one.

## Client configuration

Claude Code:

```bash
claude mcp add --transport http qod http://localhost:20900/mcp \
  --header "Authorization: Bearer qod_pat_..."
```

Claude Desktop or any client that takes a JSON server entry:

```json
{
  "url": "https://your-manager:20900/mcp",
  "headers": { "Authorization": "Bearer qod_pat_..." }
}
```

## Tools: data tier (every authenticated principal)

| Tool | Arguments | Returns |
|---|---|---|
| `run_sql` | `sql`, `database`, `pool?`, `max_rows?` | Columns and rows as JSON, a `truncated` flag, rows affected for writes |
| `list_databases` | superuser: `tenant?` | Tenant databases with kind and pools |
| `list_tables` | `database`, `schema?` | Schemas and tables |
| `describe_table` | `database`, `schema`, `table` | Columns and types plus a few sample rows |
| `table_history` | `database`, `schema`, `table`, `limit?` | Snapshot history with change verbs |
| `list_snapshots` | `database`, `limit?` | Snapshots and tags, for time-travel queries (`AT (VERSION => n)`) |
| `my_usage` | none | Own usage counters and recent statements (PAT principals only) |

`run_sql` executes through the same in-process path as the FlightSQL edge: statement validation (ACL), classification, routing, then the node. RBAC verbs decide whether writes are allowed, RLS/CLS apply, and suspended pools wake on the first statement exactly as they do for FlightSQL clients. Results are capped server-side by `QOD_MCP_MAX_ROWS` (default 500); the tool's `max_rows` argument can only lower the cap, and truncated results carry `truncated: true` so the agent aggregates or filters instead of paginating blindly.

## Tools: admin tier (admin or superuser principals)

| Tool | Notes |
|---|---|
| `list_pools`, `get_pool_status` | Nodes, health, served counts, suspended flag, autoscale band |
| `scale_pool` | Band refusals (`outside_band`) surface as tool errors with the reason |
| `suspend_pool`, `resume_pool` | Scale-to-zero and wake |
| `restart_node`, `quarantine_node`, `unquarantine_node` | Node lifecycle |
| `active_statements`, `kill_statement` | Inspect and kill running statements |
| `run_maintenance`, `maintenance_runs` | Trigger and inspect managed maintenance |
| `create_tag`, `protect_tag` | Protect only; there is no unprotect and no tag delete |
| `audit_search` | Filtered read over the audit log |

`tools/list` is computed per principal: a `role=user` PAT sees the data tier only; a tenant admin sees both tiers scoped to their tenant; superuser and static-key callers see both cross-tenant. `tools/call` re-checks the tier server-side. Tool calls land in the audit trail as the acting user.

## Deny-list

Some operations exist in no tier and have no code path from `/mcp`, regardless of credential:

- Protection-weakening operations: tag unprotect, tag delete, lockdown off, any guardrail loosening
- Irreversible destruction: tenant delete, database delete or purge, user delete, manifest import
- Credential and secret operations: password set/reset, PAT create/list/revoke, federated secrets
- RBAC mutations: grants, revokes, memberships, role/group/user create or update

In particular, an agent holding a PAT can never mint, enumerate, or revoke tokens: PAT management requires a logged-in session (UI or CLI).

## Errors

Protocol failures (bad token, malformed JSON-RPC, unknown method) come back as HTTP 401 or JSON-RPC error objects. Everything that happens inside a tool (an ACL denial, a SQL error, an `outside_band` refusal, a "pool is resuming" timeout) returns a normal `tools/call` result with `isError: true` and a message written for the agent to act on. Internal exceptions are sanitized to a generic message with a correlation id in the server log.

## Configuration

| Key | Default | Env | Meaning |
|---|---|---|---|
| `quack-on-demand.mcp.enabled` | `true` | `QOD_MCP_ENABLED` | Serve `POST /mcp` at all |
| `quack-on-demand.mcp.maxRows` | `500` | `QOD_MCP_MAX_ROWS` | Hard cap on rows returned by `run_sql` |

Statement execution inherits the edge's existing timeouts.

## Troubleshooting

- **401 on every call**: the bearer is not a live PAT (revoked, expired, owner disabled) or is a session JWT, which `/mcp` refuses by design. Mint a fresh PAT.
- **A tool is missing from `tools/list`**: the credential's tier does not include it; admin tools need an admin-owned PAT or the static key.
- **`run_sql` returns an ACL error**: the message names the table and missing verb; grant the owning role `RO`/`RW`/`DDL` as needed.
- **"pool is resuming"**: the target pool was suspended and is waking; retry in a few seconds.
