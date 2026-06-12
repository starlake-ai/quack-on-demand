# Changelog

## 0.3.2-SNAPSHOT

### Security

- **RBAC tenant scope on every handler.** Every endpoint in `/api/{user,role,group,membership,pool/permission}/*` now checks the calling session's `manageableTenants` before mutating. Tenant-A admin sessions get `403 tenant_forbidden` on tenant-B resources; missing ids return `404` (no cross-tenant existence leak).
- **Statement-history cross-tenant leak fixed.** `GET /api/node/statements` filters the ring buffer by the session's manageable tenants. Allow-set covers both surrogate id and display-name forms so the filter survives the FlightSQL router recording in either shape.
- **JWT session cookies.** `SessionTokenStore` is now a stateless HS256 JWT signer/verifier; login also sets `qod_session=<jwt>` as `HttpOnly Secure SameSite=Lax`. Sessions survive manager restart when `QOD_SESSION_JWT_SECRET` is pinned, enabling horizontal scale-out. The UI drops the localStorage token path entirely — browsers use the cookie; CLI clients still use `X-API-Key`.
- **Cloud secret resolvers gated.** `secretStore = aws-sm | gcp-sm | azure-kv | vault` is refused at config load (resolvers are stubs). Under `dispatch` mode the stubs stay wired so postgres+env deployments work; the runtime error names the prefix and the supported alternatives. UI dropdown options for stub stores are disabled.
- **FederationBlobBuilder SQL-quote-safe.** Every resolved secret value and the expanded alias are doubled (`'` → `''`) before splicing into the operator template. Apostrophes in passwords no longer break the `ATTACH`.
- **DuckLake ATTACH escape hardened.** Postgres calls in `DuckLakeInitializer` switched to `PreparedStatement` binds; the DuckLake connstring uses a two-layer escape (`libpqValue` inside `duckdbLiteral`) so the bearer password can contain any character.
- **Full 128-bit ids.** Every surrogate id is now `<prefix>-<32 hex>` (`Names.newSurrogateId`) instead of `<prefix>-<8 hex>` (~77K-row collision). Legacy 8-char ids in existing rows continue to match.
- **K8s federation works.** Per-pool `qod-fedsql-${tenant}-${tenantDb}-${pool}` Secret carries `extraSetupSql`; pods reference it via `env.valueFrom.secretKeyRef`. `kubectl describe pod` never shows the credential; etcd doesn't either.
- **K8s node tokens persist across restart.** Per-pod `qod-token-${nodeId}` Secret carries the bearer; `discoverExisting` reads it back, so adopted pods don't 401 on manager restart.
- **REST stubs removed.** `POST /api/node/setRole` and `POST /api/node/restart` were stubs that lied about persisting state. Use `POST /api/pool/scale` or `POST /api/node/quarantine` instead.

### Performance

- **HikariCP on the control-plane store.** `PostgresControlPlaneStore` and `UserStore` use HikariCP (sizes 20 and 10). Handshake path went from 5 fresh TCP+TLS+auth handshakes per request to 5 borrow-from-idle.
- **EffectiveSet cache.** 60s TTL `ConcurrentHashMap` keyed by `(userId, jwtRolesHash, jwtGroupsHash)`; invalidated from every RBAC mutator + `restore()`. Collapses N FlightSQL handshakes for the same user into 1.
- **Manifest export/import.** Single `store.snapshot()` + name→entity index maps replaced N+1 list calls per tenant/user/role.
- **`unsafeRunSync` in `PoolSupervisor.createPool`** removed (proper `flatMap` composition).
- **K8s `waitReady`** switched from a `Thread.sleep(500)` busy-poll to fabric8's `waitUntilCondition` watch — ~120 fewer API-server calls per 60s pod startup.
- **`DuckLakeCatalogReader` evicted** on tenant-db delete: the per-tenant-db Hikari pool is closed promptly. Stale-credential foot-gun closed.
- **Sessions auto-expire.** Initial sliding-window idle TTL on `SessionTokenStore` (superseded by the stateless JWT exp).

### Cleanup

- **File-state mode dropped** (~250 LOC + ~80 LOC tests): `PostgresStateStore`, `StateStore`/`FileStateStore`, `StoredState`, `StoredPool`, `StoredTenant`. The `stateStorage` / `statePath` config knobs and all 5 `Main.scala` guards are gone. Postgres is the only control-plane store.
- **`ManifestIdentity` + `FederationImportSummary`** dropped (defined, never read).
- **`DbAdmin.dropDatabase` non-FORCE fallback** dropped (PG16 floor — always supported).

## 0.3.1

- Per-pool node placement (cohorts) — a pool can now be defined as a
  list of cohorts. Operators can express
  layouts such as "2 writers on nodes tagged `disktype=ssd` and 1 reader
  + 1 writer on nodes tagged `disktype=hdd`" against a single pool.
- JDK floor raised to 21.