# n8n-nodes-quack-on-demand

An [n8n](https://n8n.io/) community node that runs SQL against a
[Quack on Demand](https://github.com/starlake-ai/quack-on-demand) FlightSQL edge
and returns each result row as an n8n item.

It talks to the edge over raw gRPC (Node has no first-party Flight SQL driver)
and decodes the Arrow result stream with `apache-arrow`. The gRPC stack is
`@grpc/grpc-js`, which is pure JavaScript with no native addon, so the node
bundles cleanly. The Flight protocol is inlined, so there is no `.proto` asset
to ship.

## Node

**Quack on Demand → Execute Query.** Enter a SQL statement; the node runs it
once per input item and emits one output item per result row. Int64 and Decimal
columns are returned as strings so they serialize cleanly into n8n items.

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