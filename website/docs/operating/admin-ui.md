---
id: admin-ui
title: Admin UI guide
---

The manager serves a React admin console at `http://<host>:20900/ui/`. It is an operator console: it manages tenants, databases, pools, users, and access control, and surfaces live node and statement telemetry. It is not an end-user query tool; clients run SQL over the FlightSQL edge, not through this UI.

Every screen here is backed by a REST endpoint documented in the [REST API reference](pathname:///api/); the UI is a thin front end over the same `/api/*` calls shown on the operator pages. Where a screen maps to a task already covered in prose, this guide links there rather than repeating it.

The screenshots below are from a local demo deployment with the bootstrap TPC-H tenant.

## Signing in

The login screen takes a username, password, and an optional **Tenant ID**. The tenant field is the realm signal:

- **Leave it blank** to log in as a system superuser. Credentials are validated against the manager's global auth providers (`quack-flightsql.auth.*`) and the matching `qodstate_user` row must have `tenant IS NULL`.
- **Fill it in** (e.g. `t-02d0e86e`) to log in as a tenant user. Credentials are validated against THAT tenant's configured provider, and the matching `qodstate_user` row must have `tenant = <id>`.

There is no separate superuser checkbox; the field's presence is the only signal. Pre-existing scripts that called `/api/auth/login` with a `tenant` value for the bootstrap admin need to drop the field (or send an empty string) after upgrading.

When the server has no auth providers configured (the no-auth dev mode), the login screen is skipped and the session is a synthetic anonymous superuser. The signed-in user and role appear in the top-right pill; the no-auth mode shows `anonymous / no-auth`.

![The Quack on Demand admin login screen](/img/ui/login.png)

For how credentials are validated and how to wire an external provider, see [Authentication](/operating/authentication) and [Authentication providers](/operating/auth-providers).

## Navigation

The top navigation bar has **Nodes**, **Tenants**, **Users**, **Audit** (hidden when telemetry is disabled), **History** (hidden when telemetry is disabled), **Usage** (hidden when telemetry is disabled), and (for a superuser admin only) **Config**, plus the user pill and Sign out. The Config tab is hidden for non-superusers, and its backend endpoints reject them as well, so a deep link does not leak it. The Audit, History, and Usage tabs are hidden when `QOD_TELEMETRY_STORE=none`; all three are visible to superusers and tenant admins.

![The top navigation bar](/img/ui/nav.png)

## Nodes

`Nodes` is the landing page and the at-a-glance operational view: a live table of every Quack node across all tenants, grouped by tenant and pool. Each row shows the node's in-flight count, total served, and average latency, the same telemetry the router uses to place statements. Use it to spot a hot pool, a draining node, or an idle one.

![The Nodes overview with live per-node counters](/img/ui/nodes.png)

## Tenants

`Tenants` lists every tenant and offers a **New tenant** form to create one. Selecting a tenant opens its detail page.

The tenant detail page has three tabs:

- **Databases** - create and manage the tenant's databases (tenant-dbs): kind, data path, default schema. This is the UI form for the calls on the "Tenants and databases" page.
- **Pools** - create pools bound to a database (a **New pool** form with the role distribution and a create-disabled toggle), and per-pool **Scale**, **Drain**, and **Force** actions. Drain is the graceful stop (stop accepting new queries, then shut down); Force stops immediately and fails outstanding queries.
- **Auth provider** - the tenant's auth provider and its configuration.

The page also shows the tenant's live nodes and recent statements.

![The tenant detail page on the Pools tab](/img/ui/tenant-pools.png)

### Databases and federation

The **Databases** tab lists the tenant's databases with their kind, schema, and data path, plus a per-database **Federation** link and a **New database** form.

![The Databases tab](/img/ui/databases.png)

Clicking **Federation** on a database opens its federated-source panel, where DuckDB `ATTACH` / extension catalogs are registered (with their secrets) and injected at session start. See [Federation](/operating/federation).

![The federation panel for a database](/img/ui/federation.png)

### Pool detail

Opening an individual pool shows its detail page, with tabs for **Nodes** (the per-node table with role, host, port, and a per-node max-concurrency control), **Connections**, **Storage**, and **Placement**, plus **Scale** and **Delete** actions. The concepts behind role distribution, scaling, and graceful drain are covered on the "Pools and cohorts" page.

![The pool detail page on the Nodes tab](/img/ui/pool-detail.png)

The **Connections** tab gives ready-made JDBC, ODBC, and ADBC connection strings pre-filled with the pool's `tenant` and `pool`, plus direct per-node `ATTACH` URIs (see [Connecting clients](/connecting/clients)). The **Storage** tab shows the pool's data path, catalog database, schema, and Postgres endpoint.

![The pool Connections tab with client connection strings](/img/ui/pool-connections.png)

![The pool Storage tab](/img/ui/pool-storage.png)

### Node placement

When you create a pool, the **New pool** form has a **Node placement** section. Enabling "Pin nodes to Kubernetes node labels (cohorts)" opens the cohort editor: per-cohort read-only / write-only / dual counts and a `nodeSelector` of `key=value` Kubernetes node labels, with **+ add cohort** for additional cohorts. On a manager that is not running on Kubernetes, the form shows a warning that placement is saved with the pool (and survives a manifest export to a K8s cluster) but ignored at runtime, where the local backend spawns every node regardless. The model is described on the [Pools and cohorts](/operating/pools-cohorts) page.

![The New pool form with the cohort placement editor open](/img/ui/placement.png)

## Users and access control

`Users` is the RBAC console, titled **Users & access control**. A tenant selector at the top scopes the view, and three tabs cover the graph:

- **Users** - create users, set their role, and assign roles, groups, and pool grants.
- **Groups** - define groups and the roles they carry (needs a concrete tenant scope).
- **Roles** - define roles and their table permissions (needs a concrete tenant scope).

The **Users** tab lists each user with their assigned roles, groups, and pool grants.

![The Users tab](/img/ui/rbac-users.png)

The **Groups** tab shows each group with its user, role, and pool-grant counts.

![The Groups tab](/img/ui/rbac-groups.png)

The **Roles** tab lists each role with a per-verb count (SELECT / INSERT / UPDATE / DELETE / ALL) of its table permissions, and **+ New role** plus per-role edit and delete actions.

![The Roles tab](/img/ui/rbac-roles.png)

The model these screens edit (the EffectiveSet, verbs, wildcards, the two gates) is described in the [Access control model](/operating/rbac-model); the step-by-step grant flows are on the "Administering access" page.

## Catalog browser

The catalog browser lists the schemas and tables of a database (the DuckLake catalog) and drills into a table for its columns. It is reached contextually from table links rather than the top nav. Use it to confirm what a pool actually exposes, including federated catalogs attached to the database.

![The catalog browser with a schema's tables listed](/img/ui/catalog.png)

## Audit

The `Audit` page shows a tenant-scoped, newest-first table of administrative and data-plane events. It is available to superusers and tenant admins. It is hidden from the navigation when `QOD_TELEMETRY_STORE=none`; a deep link to the page shows an empty state with a "telemetry is disabled" message.

The page has a filter bar with:

- **Family** - dropdown select for `control-plane`, `auth`, `data-denial`, and `data-write`.
- **Tenant** - a select populated from the live tenant list, visible to superusers only. Includes a "(no tenant)" option to show only tenant-less events (anonymous authentication failures, node operations, and manifest imports). Tenant admins are pinned to their own tenant and do not see this filter.
- **Actor** - filter by username.
- **Action** - a select populated from the exhaustive action vocabulary served by `GET /api/audit/actions`.
- **Time range** - from / to ISO-8601 instant fields.

The event table shows timestamp, family badge, actor, action, target, and an outcome badge (`ok`, `denied`, `error`). Each row is expandable to reveal the `detail` key-value map as a formatted list.

Pagination uses a keyset "Load more" button rather than numbered pages. There is no live polling; audit is a forensic view, so a manual refresh button is provided.

The four event families, the full action taxonomy, tenant scoping rules, retention, and the sanitization guarantee are documented on the [Audit log](/administration/audit-log) page.

## History

The `History` page gives a time-series view of FlightSQL activity. It is available to superusers and tenant admins. It is hidden from the navigation when `QOD_TELEMETRY_STORE=none`; a deep link to the page shows an empty state with a "telemetry is disabled" message.

The page has a range picker at the top that sets the time window shared by all three charts and the statement table. Narrowing the window to a few hours switches the chart granularity to hourly; widening it to weeks uses daily buckets automatically.

The three charts are:

- **Statement volume** - total statements per bucket, with an error overlay.
- **Latency percentiles** - p50, p95, and p99 as separate lines. Percentile data comes from the hourly rollup and is shown only when the granularity is hourly; daily buckets do not carry percentile aggregates.
- **Error rate** - percentage of statements that completed with denied status or a non-ok failure status (transient, permanent, no-node, no-pool, or pin-lost).

Below the charts, a searchable statement table shows the raw rows for the selected window. Columns include timestamp, user, pool, status badge, duration, and a truncated SQL preview. Clicking a row expands the full SQL text (up to the 500-character recording cap). Pagination uses a keyset "Load more" button.

The filter bar above the table accepts a free-text `q` search (substring match on SQL), a `status` filter, a user filter, and a pool selector. Superusers also have a tenant selector; tenant admins are pinned to their own tenant.

The storage model, watermark semantics, retention knobs, and the curl API for both endpoints are covered on the [Statement history and trends](/operating/history-trends) page.

## Usage

The `Usage` page is the durable metering ledger for FlightSQL activity, aggregated per tenant, pool, or user. It is available to superusers and tenant admins. It is hidden from the navigation when `QOD_TELEMETRY_STORE=none`; a deep link to the page shows a "telemetry is disabled" message.

The page has a filter bar at the top with:

- **Period picker** - a month input (defaults to the current calendar month) or a custom date range, toggled by the "custom range" button. Custom ranges translate to a half-open `[from, to)` interval in UTC before being sent to the API.
- **Group-by selector** - `by tenant` (superusers only), `by pool`, or `by user`. Tenant admins land on the `by pool` grouping; the `by tenant` option is hidden for them because the API pins them to their own tenant.
- **Metric toggle** - `statements` (total statement count) or `engine-ms` (summed execution time in milliseconds). Switching updates the chart; the totals table always shows all four measures.
- **Tenant and pool filters** - the tenant filter (visible to superusers only) is a select populated from the live tenant list; the pool select narrows its options to the chosen tenant.

Below the filters, a stacked per-day bar chart shows each group's contribution over the period. The top 8 groups by `engineMs` receive distinct colors; all remaining groups are merged into a single gray "other" segment. Periods with no activity show an empty chart.

Below the chart, a totals table shows one row per group, sorted by `engineMs` descending, with columns for tenant, pool (when `groupBy=pool`), user (when `groupBy=user`), statements, errors, denied, and engine-ms. A **Download CSV** button above the table produces a client-side CSV using the column contract described on the [Usage and accounting](/administration/usage-accounting) page.

The `dataStart` field in the API response drives a notice below the filter bar when the requested period starts before the retention horizon: "Data starts YYYY-MM-DD (older buckets purged)."

The full API reference, CSV column contract, retention knob, and scoping rules are documented on the [Usage and accounting](/administration/usage-accounting) page.

## Config (superuser only)

`Config` is a cross-tenant view of the whole deployment, so it is restricted to a superuser admin. It has two parts:

- **Configuration** - the resolved `application.conf` rendered as a table of each key, its `QOD_*` env var, description, and effective value. Sensitive values are masked. This is the live equivalent of the [Configuration reference](/reference/configuration).
- **Manifest** - export the entire control-plane configuration as YAML (**Download YAML**) and re-import an edited file (**Import**). The semantics, redaction, and apply rules are on the [Manifest backup and restore](/operating/manifest) page.

![The Config page resolved-configuration table](/img/ui/config.png)
