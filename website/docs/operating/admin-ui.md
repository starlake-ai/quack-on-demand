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

The top navigation bar has **Nodes**, **Tenants**, **Users**, an **Audit** dropdown menu, and (for a superuser admin only) **Config** last, plus the user pill and Sign out. The Audit menu groups the three telemetry pages: **Control Plane** (the audit log at `/audit`), **Statements** (statement history and trends at `/history`), and **Usage** (the metering ledger at `/usage`). The menu closes on selection, on a click elsewhere, or with Escape, and its trigger stays highlighted while any of the three pages is open.

The Config tab is hidden for non-superusers, and its backend endpoints reject them as well, so a deep link does not leak it. The whole Audit menu is hidden when `QOD_TELEMETRY_STORE=none`; it is visible to superusers and tenant admins otherwise. Deep links to the three pages keep working regardless (they render a "telemetry is disabled" state when recording is off).

![The top navigation bar](/img/ui/nav.png)

![The Audit dropdown menu open](/img/ui/nav-audit-menu.png)

## Nodes

`Nodes` is the landing page and the at-a-glance operational view. A metrics strip across the top summarizes the deployment: node count, healthy count, statements in flight, QPS (computed client-side from deltas), total served, average latency, worst p95, DuckDB memory in use, and spill volume. A **Tenant filter** dropdown narrows everything on the page to one tenant.

Below the strip, a live table lists every Quack node grouped by tenant and pool: role, status badge (healthy / draining / unhealthy / quarantined), endpoint, in-flight count, total served, average and percentile latency (rolling 256-sample window), DuckDB memory, spill, and a per-node **Max conc.** column. Node ids are links that filter the page to that node. For superusers each row also offers **Quarantine** / **Unquarantine** (stop routing new statements to the node; durable across restarts) and **Restart** (kill and respawn with the same id), both behind confirmation dialogs; the quarantine dialog warns when the node is the pool's last routable one. These are the UI forms of the [incident-response endpoints](/administration/day-2-operations).

A **Running statements** card above the Recent statements table shows active queries with the user, tenant/pool, node, a live elapsed counter, and the SQL; each row has a **Kill** button (best-effort stream close; the card reports when the statement had already completed before the kill arrived).

A **Recent statements** table at the bottom shows the last statements routed through the edge with a status badge (`ok`, `denied`, `transient`, `permanent`, `no-node`, `no-pool`, `pin-lost`, `killed`), duration (with the FlightSQL prepare-probe duration as subtext when the statement was prepared), the node that served it, and the SQL with syntax highlighting and a copy button. The page refreshes every 2 seconds.

![The Nodes overview with live per-node counters](/img/ui/nodes.png)

## Tenants

`Tenants` lists every tenant with an enable/disable toggle per row (the disabled state is the data-plane kill switch: the edge rejects fresh handshakes for a disabled tenant) and offers a **New tenant** form. Selecting a tenant opens its detail page.

The tenant detail page has three tabs:

- **Databases** - create and manage the tenant's databases (tenant-dbs).
- **Pools** - create pools bound to a database and operate them.
- **Auth provider** - the tenant's auth provider and its configuration.

The page also links to the tenant's live nodes and recent statements.

![The tenant detail page on the Pools tab](/img/ui/tenant-pools.png)

### Databases and federation

The **Databases** tab lists the tenant's databases with a kind badge (`ducklake`, `duckdb-file`, or `memory`), schema, and data path (marked `(inherited)` when the manager default applies). The **Tables** count is a link that opens the catalog browser inline, including the database's snapshot history (see [Snapshots and time travel](#snapshots-and-time-travel)); **Federation** (with a badge counting the database's federated sources) opens the federated-source panel. Disabled databases render dimmed with a `(disabled)` marker.

![The Databases tab](/img/ui/databases.png)

Clicking a database name opens its **edit form**: default database and schema, init SQL, and the metastore and object-store maps as `key=value` lines. The kind is immutable. Editing the metastore, object store, or init SQL restarts all the database's nodes immediately (in-flight statements on them fail); the form warns about this, and the save button changes to **Save and restart nodes** when such a field is dirty. `pgPassword` is kept unless a new value is supplied (sending one rotates it). The form also carries the **Delete database** action.

Clicking **Federation** opens the federated-source panel, where DuckDB `ATTACH` / extension catalogs are registered (with their secrets) and injected at session start. See [Federation](/operating/federation).

![The federation panel for a database](/img/ui/federation.png)

### Pools

The **Pools** tab lists each pool with its database, node count, an enable/disable toggle, and actions:

- **Scale** opens a modal with the target role distribution (WriteOnly / ReadOnly / Dual counts) and, when scaling down, a **Force** checkbox that skips the graceful drain (outstanding queries fail).
- **Drain** is the graceful stop (stop accepting new queries, then shut down); **Force** stops immediately.
- **Delete** removes the pool from the registry after stopping it.

The **New pool** form takes the role distribution, optional pool init SQL, optional CPU and memory limits for the pool's node pods (sliders; applied as both request and limit on Kubernetes), a **Create disabled** toggle (nodes spawn, but the edge rejects fresh handshakes until enabled), and the node-placement section described below. Clicking a pool name opens the pool detail inline.

### Pool detail

The pool detail page has tabs for **Nodes**, **Connections**, **Storage**, and **Placement**.

The **Nodes** tab shows the per-node table with role, host, and port, plus per-pool **CPU limit** and **Memory limit** editors (checkbox + slider, with a **Save** button; a note reminds that node restarts apply resource changes and that sizing is Kubernetes-only) and a per-node **Max concurrent** input (0 = unlimited, saved on blur). Superusers get the same **Quarantine** / **Restart** actions as on the Nodes page.

![The pool detail page on the Nodes tab](/img/ui/pool-detail.png)

The **Connections** tab gives ready-made **JDBC**, **ODBC**, and **ADBC (Python)** connection recipes pre-filled with the pool's `tenant` and `pool` (and `superuser=true` when the operator is a superuser), plus direct per-node `ATTACH` URIs for the DuckDB `quack` extension (see [Connecting clients](/connecting/clients)). The **Storage** tab shows the pool's effective metastore: data path, catalog database, schema, and Postgres endpoint.

![The pool Connections tab with client connection strings](/img/ui/pool-connections.png)

![The pool Storage tab](/img/ui/pool-storage.png)

### Node placement

When you create a pool, the **New pool** form has a **Node placement** section. Enabling "Pin nodes to Kubernetes node labels (cohorts)" opens the cohort editor: per-cohort read-only / write-only / dual counts and a `nodeSelector` of `key=value` Kubernetes node labels, with **+ add cohort** for additional cohorts. On a manager that is not running on Kubernetes, the form shows a warning that placement is saved with the pool (and survives a manifest export to a K8s cluster) but ignored at runtime, where the local backend spawns every node regardless. The pool detail's **Placement** tab renders the persisted plan: per-cohort role counts, `nodeSelector`, and Kubernetes tolerations. The model is described on the [Pools and cohorts](/operating/pools-cohorts) page.

![The New pool form with the cohort placement editor open](/img/ui/placement.png)

## Users and access control

`Users` is the RBAC console, titled **Users & access control**. A tenant selector at the top scopes the view; besides the concrete tenants it offers two synthetic scopes, **(all)** (every user, superusers included) and **(superusers)** (only rows with `tenant IS NULL`). When a concrete tenant is selected, its configured auth provider and settings are shown next to the selector. Three tabs cover the graph:

- **Users** - create users, set their role, and assign roles, groups, and pool grants.
- **Groups** - define groups and the roles they carry (needs a concrete tenant scope; the tab shows a hint on the synthetic scopes).
- **Roles** - define roles and their table permissions (needs a concrete tenant scope).

The **Users** tab lists each user with their assigned roles, groups, and pool grants.

![The Users tab](/img/ui/rbac-users.png)

The **Groups** tab shows each group with its user, role, and pool-grant counts.

![The Groups tab](/img/ui/rbac-groups.png)

The **Roles** tab lists each role with a per-verb count (SELECT / INSERT / UPDATE / DELETE / ALL) of its table permissions, and **+ New role** plus per-role edit and delete actions.

![The Roles tab](/img/ui/rbac-roles.png)

The model these screens edit (the EffectiveSet, verbs, wildcards, the two gates) is described in the [Access control model](/operating/rbac-model); the step-by-step grant flows are on the "Administering access" page.

## Catalog browser

The catalog browser lists the schemas and tables of a database (the DuckLake catalog). It is reachable at `/ui/catalog` with tenant and database selectors (both preserved in the URL), and contextually from the table-count links on the Databases tab. Use it to confirm what a pool actually exposes, including federated catalogs attached to the database.

![The catalog browser with a schema's tables listed](/img/ui/catalog.png)

Clicking a table opens its detail page, with breadcrumbs back to the catalog and links to the owning tenant and its live nodes. It shows a summary (row count, data-file count, total parquet size, folder), the column list (name, type, nullability, primary-key flag), and the table's parquet files with per-file size, row count, and snapshot id.

### Snapshots and time travel

Below the table list, a **Snapshots** panel shows the database's DuckLake snapshot history, newest first: snapshot id, commit time, the raw change summary (`created_table:...`, `inserted_into_table:...`), rows added, files added / removed, and the affected tables. Each affected table links to its detail page as of that snapshot. The panel loads 200 snapshots at a time; **Load older snapshots** fetches the next page (keyset pagination, so new commits do not shift the window while you browse).

![The snapshots panel with the demo database's history](/img/ui/snapshots.png)

On the table detail page, a **Snapshot** selector switches between the current state and any listed snapshot: the summary, column list, and parquet files re-render as of the chosen snapshot, a banner recalls which snapshot is shown, and **back to current** drops it. The view is addressable directly with the `?asOf=<snapshot id>` query parameter (what the panel's deep links use). Picking a snapshot where the table did not yet exist shows a not-found message while keeping the selector usable, and an unknown snapshot id is a 404 rather than an empty render.

![A table viewed as of a snapshot, with the AS OF banner](/img/ui/catalog-asof.png)

Snapshot semantics (linear history, inlined DML, retention and expiry) are covered in [DuckLake catalogs](/concepts/catalogs#snapshots-and-time-travel).

## Control Plane

The **Control Plane** page (`/audit`, the first entry of the Audit menu) shows a tenant-scoped, newest-first table of administrative and data-plane events. It is available to superusers and tenant admins. When `QOD_TELEMETRY_STORE=none`, a deep link shows an empty state with a "telemetry is disabled" message.

The page has a filter bar with:

- **Family** - dropdown select for `control-plane`, `auth`, `data-denial`, and `data-write`.
- **Tenant** - a select populated from the live tenant list, visible to superusers only. Includes a "(no tenant)" option to show only tenant-less events (anonymous authentication failures, node operations, and manifest imports). Tenant admins are pinned to their own tenant and do not see this filter.
- **Actor** - filter by username.
- **Action** - a select populated from the exhaustive action vocabulary served by `GET /api/audit/actions`.
- **Time range** - from / to fields.

The event table shows timestamp, family badge, actor (with a `(system)` marker for system-realm actors), action, target, tenant, and an outcome badge (`ok`, `denied`, `error`). Each row is expandable to reveal the `detail` key-value map as a formatted list.

Pagination uses a keyset "Load more" button rather than numbered pages. There is no live polling; audit is a forensic view, so a manual **Refresh** button is provided.

![The Control Plane audit table](/img/ui/control-plane.png)

The four event families, the full action taxonomy, tenant scoping rules, retention, and the sanitization guarantee are documented on the [Audit log](/administration/audit-log) page.

## Statements

The **Statements** page (`/history`, the second entry of the Audit menu) gives a time-series view of FlightSQL activity. It is available to superusers and tenant admins; with `QOD_TELEMETRY_STORE=none` a deep link shows the "telemetry is disabled" state.

A range picker (1h / 24h / 7d / 30d) sets the window shared by the charts and the statement table; ranges up to 48 hours use hourly buckets, wider ranges use daily buckets automatically. Next to it sit a tenant select (superusers only) and a pool select whose options narrow to the chosen tenant; changing a selection reloads immediately.

The three charts are:

- **Throughput** - stacked ok / denied / error statement counts per bucket.
- **Error rate** - percentage of statements per bucket that were denied or failed (transient, permanent, no-node, no-pool, or pin-lost).
- **Latency percentiles** - p50, p95, and p99 as separate lines. Percentiles come from the hourly rollup; at daily granularity the chart is replaced by a note that percentiles are available at hourly granularity.

Below the charts, a searchable statement table shows the raw rows for the selected window: timestamp, user, pool, status badge, duration (with the prepare-probe duration as subtext when present), and a truncated SQL preview. Clicking a row expands the full SQL (up to the 500-character recording cap) with syntax highlighting, plus the error text if any. The filter bar above the table takes a user filter, a status select (`ok` through `pin-lost`), and a free-text SQL substring search, applied on **Refresh** rather than per keystroke. Pagination uses a keyset "Load more" button.

![The Statements page with trend charts](/img/ui/statements.png)

The storage model, watermark semantics, retention knobs, and the curl API for both endpoints are covered on the [Statement history and trends](/operating/history-trends) page.

## Usage

The **Usage** page (`/usage`, the third entry of the Audit menu) is the durable metering ledger for FlightSQL activity, aggregated per tenant, pool, or user. It is available to superusers and tenant admins; with `QOD_TELEMETRY_STORE=none` a deep link shows the "telemetry is disabled" state. A note at the top states the metering unit's caveats (engine-ms is manager-measured execution time, the current day trails by up to one rollup tick, and accounting is best-effort measurement).

The page has a filter bar at the top with:

- **Period picker** - a month input (defaults to the current calendar month) or a custom date range, toggled by the "custom range" button. Custom ranges translate to a half-open `[from, to)` interval in UTC before being sent to the API.
- **Group-by selector** - `by tenant` (superusers only), `by pool`, or `by user`. Tenant admins land on the `by pool` grouping; the `by tenant` option is hidden for them because the API pins them to their own tenant.
- **Metric toggle** - `statements` (total statement count) or `engine-ms` (summed execution time in milliseconds). Switching updates the chart; the totals table always shows all four measures.
- **Tenant and pool filters** - the tenant filter (visible to superusers only) is a select populated from the live tenant list; the pool select narrows its options to the chosen tenant.

Below the filters, a stacked per-day bar chart shows each group's contribution over the period. The top 8 groups by `engineMs` receive distinct colors; all remaining groups are merged into a single gray "other" segment. Periods with no activity show an empty chart.

Below the chart, a totals table shows one row per group, sorted by `engineMs` descending, with columns for tenant, pool (when `groupBy=pool`), user (when `groupBy=user`), statements, errors, denied, and engine-ms. A **Download CSV** button above the table produces a client-side CSV using the column contract described on the [Usage and accounting](/administration/usage-accounting) page.

The `dataStart` field in the API response drives a notice below the filter bar when the requested period starts before the retention horizon: "Data starts YYYY-MM-DD (older buckets purged)."

![The Usage metering page grouped by tenant](/img/ui/usage.png)

The full API reference, CSV column contract, retention knob, and scoping rules are documented on the [Usage and accounting](/administration/usage-accounting) page.

## Config (superuser only)

`Config` is a cross-tenant view of the whole deployment, so it is restricted to a superuser admin. It has two parts:

- **Configuration** - the resolved `application.conf` rendered as a table of each key, its `QOD_*` env var, description, and effective value, grouped by HOCON path section with a filter box that searches paths, env vars, and descriptions. Sensitive values are masked as `(set)` or `(unset)`. This is the live equivalent of the [Configuration reference](/reference/configuration).
- **Manifest** - export the entire control-plane configuration as YAML (**Download YAML**) and re-import an edited file via a drag-and-drop dropzone with an explicit **Apply** step; a summary grid reports what the import touched (tenants, tenant-dbs, pools, roles, groups, users). The semantics, redaction, and apply rules are on the [Manifest backup and restore](/operating/manifest) page.

![The Config page resolved-configuration table](/img/ui/config.png)
