# Whole-codebase security & correctness audit

Date: 2026-07-02. Method: 6 parallel subsystem auditors (edge, control plane, ACL
parser, auth/secrets cross-cutting, UI, infra) producing ~40 candidates, then 3
adversarial verifiers that ran JSQLParser 5.3 directly and traced end-to-end
reachability. Severities below are POST-verification and state their gating
conditions explicitly. The just-merged HA code was audited separately and is out
of scope here.

## Load-bearing context (changes how to read everything below)

- **ACL enforcement is OFF by default.** `acl.enabled=false` in application.conf;
  Main wires `StatementValidator.allowAll` unless `QOD_ACL_ENABLED=true`. Every
  ACL-parser finding is dormant until an operator turns ACL on.
- **RLS/CLS are OFF by default** and marked experimental (`QOD_RLS_ENABLED` /
  `QOD_CLS_ENABLED`). The RLS findings are dormant until enabled AND a policy
  exists on the referenced table for the principal.
- **Cross-tenant is separately blocked** at the data plane: each node attaches
  exactly one tenant-db catalog and the session is pinned with `USE db.schema`.
  A SQL reference to a sibling tenant's catalog fails with a DuckDB error. So the
  SQL-reference ACL bypasses are confined to the caller's OWN tenant, except
  file-reading table functions (`read_parquet`) which escape the catalog layer.
- **The validator fails open on an empty access set**: a statement that parses
  successfully but yields zero table refs is admitted unconditionally
  (PostgresAclValidator.scala:102). Parse ERRORS fail closed. This is the
  amplifier that turns each silent parser drop into an allow.

## Findings, ranked

### 1. Authenticated SQL injection into node bootstrap (HIGH, default-reachable)
`scripts/spawn-quack-node.sh:182-193` interpolates `schemaName` and `dataPath`
(and `dbName` for `duckdb-file`/`memory` tenant-db kinds) UNQUOTED into DuckDB
bootstrap SQL (`CREATE SCHEMA`, `USE`, `ATTACH ... AS`, `DATA_PATH '...'`).
`LocalQuackBackend.scala:38` copies the whole metastore map into the child env
verbatim; the K8s backend injects the same keys via pod env and runs the same
script. `TenantDbRequest.metastore`/`.dataPath` pass verbatim through
`TenantDbHandlers.createTenantDb` into `PoolSupervisor.createTenantDb`.
`dbName` for DuckLake is regex-allowlisted (`^[a-zA-Z_][a-zA-Z0-9_]*$`), but
`schemaName` and `dataPath` get only non-empty checks. A tenant admin (endpoint
gated by `TenantScopeCheck.reject`, not superuser-only) can set
`schemaName = x; ATTACH 'ducklake:postgres:...dbname=<sibling>' AS evil` or a
`dataPath` containing a quote, injecting SQL that runs with the node's Postgres
credentials, giving cross-tenant catalog access. NOT gated on acl/rls flags.
Fix: allowlist/slug `schemaName` like `dbName`; reject quote/semicolon in
`dataPath`; quote identifiers in the script. Affects both backends.

### 2. Cross-tenant privilege escalation via membership edges (HIGH/MEDIUM)
`PoolSupervisor.membershipCheck` (PoolSupervisor.scala:1433) validates only that
the user and role EXIST, not that they share a tenant. `MembershipHandlers`
gates `addUserRole` on the USER's tenant and `addGroupRole` on the GROUP's
tenant, never on the ROLE's tenant, and there is no DB cross-column CHECK. The
code comment at MembershipHandlers.scala:13 ("supervisor refuses cross-tenant
edges at the store layer") is FALSE. A multi-tenant admin, or anyone holding a
tenant-B role id, can attach tenant-B's role to a tenant-A user. Wildcard `*`
catalog grants are re-scoped to the session tenant (partial mitigation for pure
cross-tenant reads), but explicit table-level permissions from the foreign role
apply in full, and a `*.*.* ALL` foreign role sets `hasWildcardAll` and admits
even unparseable statements in the session's own catalog space. Real intra-tenant
escalation. Fix: enforce `user.tenant == role.tenant` (and group/role tenant) in
membershipCheck; correct or delete the false comment. Note: a prior memory item
recorded "no privilege escalation on grant", this is a gap in that.

### 3. ACL parser fail-open bypasses (CRITICAL/HIGH, ACL-enabled only)
All admitted via the empty-access-set fail-open. Confined to same-tenant except 3c.
- 3a CTE name-shadowing (TableExtractor.scala:141): the CTE filter matches the
  UNQUALIFIED name, so `WITH lineitem AS (...) SELECT * FROM db.main.lineitem`
  drops the real qualified table. CONFIRMED, High-when-enabled.
- 3b Parenthesized joins (TableExtractor.scala:150): no `ParenthesedFromItem`
  arm, so `FROM (a JOIN b ON ...)` drops both tables. CONFIRMED, High-when-enabled.
- 3c Table functions (TableExtractor.scala:146): `read_parquet('.../lineitem/*.parquet')`
  is dropped AND escapes the tenant-catalog boundary (direct file read).
  CONFIRMED, Critical-when-enabled, cross-tenant on shared storage.
- 3d UPDATE SET-clause subqueries (UpdateReadExtractor.scala:30): `UPDATE t SET
  c=(SELECT ... FROM secret)` misses `secret`. Needs an existing Write grant on
  `t`. CONFIRMED, Medium-when-enabled.
- 3e MERGE ON/WHEN action subqueries (SqlParser.scala:145): dropped. Needs Write
  on target. CONFIRMED, Medium-when-enabled.
- 3f ControlFlow catch-all (SqlParser.scala:204) and 3g visitExpression default
  (TableExtractor.scala:211): PLAUSIBLE, not weaponized in review.
Systemic fix: make the validator fail CLOSED on an empty access set when ACL is
enabled (deny, or require an explicit no-tables allowlist for COMMIT/SET/etc.);
add the missing walker arms; fail closed on any node type the walker does not
recognize.

### 4. EXPLAIN ANALYZE executes DML past the ACL gate (HIGH, ACL-enabled only)
`StatementClassifier.scala:64` buckets EXPLAIN as read (read node);
`SqlParser.scala:204` puts `ExplainStatement` in the ControlFlow admit-all arm.
Verified against JSQLParser 5.3.218 that `EXPLAIN ANALYZE DELETE/INSERT/UPDATE`
all parse (do not throw), so the ACL layer admits them with zero accesses.
DuckDB's `EXPLAIN ANALYZE` executes the inner statement. CONFIRMED on the
parse/ACL-bypass leg; PLAUSIBLE on whether a read-role node actually commits the
write (no node-side write lock found). Fix: classify EXPLAIN by its inner
statement, or deny EXPLAIN ANALYZE with a non-read body.

### 5. RLS/CLS security-filter bypasses (MEDIUM, RLS/CLS-enabled only)
- 5a Fail-open on parse failure (RowPolicyRewriter.scala:104,
  ColumnPolicyRewriter.scala:90): the rewriters use single-statement
  `CCJSqlParserUtil.parse` while the ACL gate uses multi-statement
  `parseStatements`; a statement that parses at the list level but throws
  single-statement (verified: `SELECT ... SEMI JOIN ... USING (...)`) yields
  PassthroughParseFailed and FlightSqlRouter forwards the ORIGINAL sql, skipping
  row/column filtering. Cleanly exploitable only with acl.enabled=false (ACL-on
  denies the diverging statement first).
- 5b RLS dropped on retry (FlightSqlRouter.scala:273): the transient-failure
  retry sends `rewrittenSql` (CLS-applied, pre-RLS) instead of `finalSql`
  (RLS-wrapped), so a retried query returns unfiltered rows. CONFIRMED.
Fix: rewriters should fail closed (deny) on parse failure when a policy applies;
retry must send `finalSql`.

### 6. Prepared-statement revocation lag & handle sharing (MEDIUM/LOW)
`FlightProducerImpl.scala:334,853` execute with the EffectiveSet captured at
Prepare time; a grant revoked mid-session is bypassed for the handle's lifetime
(until ClosePreparedStatement; connection-context TTL default ~1h). The handle
TrieMap is keyed only by a 128-bit UUID with no peer check, so any client holding
the UUID executes with the owner's grants (needs response interception; UUID
guessing infeasible). Fix: re-derive authorization per call from the live session,
or bound handle lifetime to the session TTL.

### 7. K8s orphaned pods/secrets + stuck pool on spawn failure (HIGH for K8s)
`KubernetesQuackBackend.start` (line 200-229) creates token/federation Secrets and
the pod before `waitReady`, which `sys.error`s on timeout with no cleanup;
`createPool`'s spawn fold has no bracket/rollback and `store.upsertPool` already
persisted the pool. Reconcile then tries to respawn with the SAME deterministic
nodeIds, which K8s rejects as conflicts, so the pool never recovers AND orphans
accumulate (`cleanup()` is `IO.unit`, no owner-refs/finalizers). Fix: wrap spawn
in a bracket that deletes pod+secrets on failure, or set an ownerReference so
K8s GCs them; make reconcile adopt/replace conflicting pods.

### 8. Lower-severity confirmed items
- FlightProducerImpl forwards internal exception text (SQL, node hostnames) to
  Flight clients at many sites (INTERNAL.withDescription(t.getMessage)); info leak.
- LocalQuackBackend leaks a leased port when `pb.start()` throws (no try-finally);
  bounded, recovered on restart. (runtime/LocalQuackBackend.scala:30)
- QuackHttpAdapter inFlight counter leaks if the query IO raises before onFinish,
  eventually excluding the node as "at capacity". (edge/adapter/QuackHttpAdapter.scala:27)
- streamArrow has no cancellation/backpressure check; a cancelled client cannot
  stop the server pulling batches. (FlightProducerImpl.scala:1486)
- PoolPermission grant does not verify the poolId belongs to the tenant (inert
  dangling reference, no data leak).
- insertRolePermission/insertPoolPermission lack ON CONFLICT, so duplicate grants
  500 instead of 409.
- PortAllocator markLeased/release are unsynchronized against lease() (narrow
  double-assign window under concurrent adopt+spawn).
- Non-constant-time X-API-Key comparison (Option.contains); MessageDigest.isEqual
  is available and used elsewhere.
- Flight TLS auto-cert uses an unencrypted key at default umask and docs advise
  disableCertificateVerification=true (MITM surface).
- K8s RBAC Role grants list/watch on ALL namespace Secrets (over-broad).
- Keycloak/OIDC clientSecret rendered into the ConfigMap in plaintext when
  existingSecret is unset.
- Node image defaults to a floating `:latest-snapshot` tag (no digest pinning).
- `stop-jar.sh` / `kill-quack-nodes.sh` `pgrep -f '^duckdb'` can SIGKILL an
  operator's unrelated local DuckDB sessions.
- UI: JSON.stringify(authConfig) in the tenant subtitle exposes OIDC
  clientSecretRef values; api client empty-body detection breaks on 204/HTTP2;
  several mutation error paths are swallowed and can mislead an operator.

### Documented-by-design defaults (not new findings, worth restating)
Open `/api/*` when `QOD_API_KEY` unset; well-known dev `QOD_SESSION_JWT_SECRET`;
`admin/admin` bootstrap superuser. All three are called out in CLAUDE.md as
"must set before non-localhost deploy" with loud boot warnings. Consider making
them fail-closed (refuse to bind non-loopback with defaults) rather than warn.

## Top fix priorities
1. spawn-script injection (#1): validate schemaName/dataPath, quote identifiers. Default-reachable by a tenant admin.
2. membership cross-tenant edge (#2): enforce tenant equality; fix the false comment.
3. K8s spawn-failure orphans + stuck pool (#7): bracket cleanup / owner refs.
4. ACL fail-closed on empty access set + missing walker arms (#3, #4): the whole ACL cluster collapses to safe once the validator denies empty-set and the walker fails closed. Only matters for ACL-enabled deployments but is the right structural fix.
5. RLS fail-closed + retry sends finalSql (#5).
