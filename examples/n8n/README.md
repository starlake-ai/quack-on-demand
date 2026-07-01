# n8n-nodes-quack-on-demand

An [n8n](https://n8n.io/) community node that runs SQL against a
[Quack on Demand](https://github.com/starlake-ai/quack-on-demand) FlightSQL edge
and returns each result row as an n8n item.

It talks to the edge over raw gRPC (Node has no first-party Flight SQL driver)
and decodes the Arrow result stream with `apache-arrow`. The gRPC stack is
`@grpc/grpc-js`, which is pure JavaScript with no native addon, so the node
bundles cleanly. The Flight protocol is inlined, so there is no `.proto` asset
to ship.

## Operations

Each operation maps to a Flight SQL command the edge implements natively (no raw
SQL workarounds). Int64 and Decimal columns are returned as strings so they
serialize cleanly into n8n items.

| Operation | Flight SQL command | Result columns |
| --------- | ------------------ | -------------- |
| **Execute Query** | `CommandStatementQuery` | your query's columns |
| **List Catalogs** | `CommandGetCatalogs` | `catalog_name` |
| **List Schemas** | `CommandGetDbSchemas` | `catalog_name`, `db_schema_name` |
| **List Tables** | `CommandGetTables` | `catalog_name`, `db_schema_name`, `table_name`, `table_type` |

- **Execute Query** runs once per input item and emits one output item per row.
- **List Schemas** / **List Tables** accept optional LIKE filters (e.g. `tpch%`).
- **List Tables** offers a **Schema** dropdown populated from the edge's schemas
  (via `CommandGetDbSchemas`), so you can browse without typing SQL.

## Credentials

**Quack on Demand API:**

| Field | Default | Purpose |
| ----- | ------- | ------- |
| Host | `127.0.0.1` | FlightSQL edge host |
| Port | `31338` | FlightSQL edge port |
| Tenant | `acme` | Routing tenant |
| Pool | `bi` | Routing pool |
| User / Password | `admin` / `admin` | HTTP Basic credential |
| Superuser | `true` | Authenticate against the system realm (bypasses the ACL gate) |
| Use TLS | `true` | Edge listens with TLS (the default) |
| Verify TLS Certificate | `false` | Validate the chain; leave off to accept the auto-generated self-signed cert |

## Install

### In n8n (recommended)

Settings → Community Nodes → Install, then enter `n8n-nodes-quack-on-demand`.
Requires a self-hosted n8n instance (community nodes are not available on n8n
Cloud's verified-only mode unless the package is verified).

### From source (local development)

```bash
npm install
npm run build
# link into your n8n custom extensions directory
mkdir -p ~/.n8n/custom
npm link
cd ~/.n8n/custom && npm link n8n-nodes-quack-on-demand
# then restart n8n
```

## Publishing to npm

This package is set up to publish as a community node (installable on
self-hosted n8n). It is **not eligible for n8n verification / n8n Cloud**: verified
nodes may not declare runtime dependencies, and this node bundles `@grpc/grpc-js`,
`@grpc/proto-loader`, `apache-arrow`, and `protobufjs`.

```bash
npm install
npm run lint     # n8n node conventions (eslint-plugin-n8n-nodes-base)
npm run build    # emits dist/ and copies the icon
npm publish      # publishConfig.access is already "public"
```

`prepublishOnly` re-runs build and lint. After publishing, install it from
**Settings → Community Nodes** using the package name `n8n-nodes-quack-on-demand`.

## Notes and limitations

- **Self-hosted only in practice.** A Code node cannot `require` these npm
  packages on n8n Cloud; this packaged node sidesteps that, but installing a
  community node still needs a self-hosted instance.
- **TLS.** The node pins the edge's self-signed certificate off the wire when
  *Verify TLS Certificate* is off. For a hardened deployment install a CA-signed
  cert on the edge and turn verification on.
- **One statement per query.** The edge runs a single statement per call; the
  node does not split multi-statement input.

## License

Apache-2.0