# Whole-codebase security & correctness audit

Date: 2026-07-02. Method: 6 parallel subsystem auditors (edge, control plane, ACL
parser, auth/secrets cross-cutting, UI, infra) producing ~40 candidates, then 3
adversarial verifiers that ran JSQLParser 5.3 directly and traced end-to-end
reachability. Severities below are POST-verification and state their gating
conditions explicitly. The just-merged HA code was audited separately and is out
of scope here.

## Remediation status (updated 2026-07-03)

The three default-reachable HIGH findings are **SOLVED** and merged to main
(commits 4feea27, f2bf610, 0d9f239 for #1; 9142c5f for #2; b2e4c86 for #7).
The ACL-parser fail-open cluster (#3), the EXPLAIN-ANALYZE bypass (#4), the
RLS/CLS fail-open + retry bug (#5), and the prepared-statement revocation lag /
handle sharing (#6) are now **SOLVED** on the working tree (2026-07-03). Every
finding below carries an inline **Status:** line. The remaining lower-severity
items in #8 (info leaks other than the exception-text one, resource-leak
counters, infra hardening) are a partially-burned-down follow-up backlog.

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
- **The validator fails open on an empty access set** (HISTORICAL, now fixed):
  a statement that parses successfully but yields zero table refs was admitted
  unconditionally. As of 2026-07-03 the walker surfaces every construct it
  cannot map to a grantable table as an `unsupported` marker, and the validator
  fails CLOSED whenever that list is non-empty; unknown statement types are
  reported as parse errors (also fail-closed). An empty access set now means the
  statement genuinely touches no table (COMMIT/SET/`SELECT 1`) and stays admitted.

## Findings, ranked

### 1. Authenticated SQL injection into node bootstrap (HIGH, default-reachable)
**Status: SOLVED (4feea27, f2bf610, 0d9f239).** `TenantDb.validate` now rejects
injection metacharacters in `schemaName`, `dbName`, `dataPath`, and the `pg*`
connection params (`pgHost/pgUser/pgPassword` denylist, `pgPort` numeric) at the
control-plane trust boundary, protecting both the local and Kubernetes backends;
`spawn-quack-node.sh` also quotes SQL identifiers. Manifest import runs the
injection-safety subset (`TenantDb.validateSafety`) so it does not wrongly demand
keys sourced from the default metastore. Original report:

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
**Status: SOLVED (9142c5f).** `addUserRole` / `addUserGroup` / `addGroupRole`
now enforce that the referenced role/group shares the principal's tenant at the
supervisor layer (so every REST caller is covered), superusers are rejected from
acquiring tenant-scoped roles, and the false MembershipHandlers comment was
corrected. Original report:

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
**Status: SOLVED (2026-07-03).** The walker now returns a `TableExtraction`
carrying both the extracted tables AND an `unsupported` marker list; the
validator fails CLOSED (deny, unless the principal holds an unrestricted `*.*.*
ALL`) whenever a statement yields any marker. Specifically: 3a CTE shadowing is
fixed by only suppressing UNQUALIFIED names (a qualified `db.main.lineitem` is
always extracted); 3b parenthesized joins get a `ParenthesedFromItem` walk arm;
3c table functions and string-literal file refs are flagged `unsupported`
(deny); 3d UPDATE SET-clause value subqueries are walked; 3e MERGE
WHEN-MATCHED/NOT-MATCHED action subqueries are walked; 3f/3g unrecognized
FROM-item, Select, and statement node types are flagged `unsupported` /
ParseError instead of silently admitted. Covered by `SqlParserAclBypassSpec`
and the fail-closed cases in `StatementAclSecuritySpec`. Original report:
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
**Status: SOLVED (2026-07-03).** `SqlParser.processStatement` now has an
`ExplainStatement` arm that recurses into the inner statement and re-labels the
result, so `EXPLAIN ANALYZE DELETE FROM t` is authorized as the Write on `t` it
actually executes, not admitted as a table-free control-flow verb. Bare
`EXPLAIN` with no body stays ControlFlow. Covered by `SqlParserAclBypassSpec`
and `StatementAclSecuritySpec`. (The coarse routing-layer `StatementClassifier`
still buckets EXPLAIN as read for node selection; that only picks a node role
and is independent of the per-table authorization above.) Original report:
`StatementClassifier.scala:64` buckets EXPLAIN as read (read node);
`SqlParser.scala:204` puts `ExplainStatement` in the ControlFlow admit-all arm.
Verified against JSQLParser 5.3.218 that `EXPLAIN ANALYZE DELETE/INSERT/UPDATE`
all parse (do not throw), so the ACL layer admits them with zero accesses.
DuckDB's `EXPLAIN ANALYZE` executes the inner statement. CONFIRMED on the
parse/ACL-bypass leg; PLAUSIBLE on whether a read-role node actually commits the
write (no node-side write lock found). Fix: classify EXPLAIN by its inner
statement, or deny EXPLAIN ANALYZE with a non-read body.

### 5. RLS/CLS security-filter bypasses (MEDIUM, RLS/CLS-enabled only)
**Status: SOLVED (2026-07-03).** 5a: both rewriters only reach a parse attempt
after confirming the principal has policies, so `FlightSqlRouter` now treats a
`PassthroughParseFailed` from either rewriter as a DENY (fail-closed) instead of
forwarding the original SQL. 5b: the transient-failure retry now sends `finalSql`
(RLS-wrapped, CLS-applied) instead of `rewrittenSql` (CLS-only, pre-RLS), so a
retried query keeps its row filter. Original report:
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
**Status: SOLVED (2026-07-03).** `PreparedExec` no longer stores the Prepare-time
EffectiveSet. Both Execute paths (`getStreamPreparedStatement`,
`acceptPutPreparedStatementUpdate`) now go through `resolvePreparedCall`, which
(a) rejects a caller whose `peerIdentity` differs from the handle's creating peer
with UNAUTHORIZED, so a leaked 128-bit handle cannot be replayed under the owner's
grants, and (b) re-reads the EffectiveSet LIVE from `ConnectionContext` keyed on
the owner peer -- so a grant revoked mid-session takes effect within the session
TTL exactly as it does for a non-prepared statement, and a handle whose session
has expired/unbound resolves to a deny (UNAUTHENTICATED) instead of running.
Covered by four new cases in `FlightProducerImplPrepareSpec` (cross-peer query,
cross-peer update, expired-session, owner still works). Original report:
`FlightProducerImpl.scala:334,853` execute with the EffectiveSet captured at
Prepare time; a grant revoked mid-session is bypassed for the handle's lifetime
(until ClosePreparedStatement; connection-context TTL default ~1h). The handle
TrieMap is keyed only by a 128-bit UUID with no peer check, so any client holding
the UUID executes with the owner's grants (needs response interception; UUID
guessing infeasible). Fix: re-derive authorization per call from the live session,
or bound handle lifetime to the session TTL.

### 7. K8s orphaned pods/secrets + stuck pool on spawn failure (HIGH for K8s)
**Status: SOLVED (b2e4c86).** `KubernetesQuackBackend.start` now deletes the pod,
service, and per-pod token secret on failure (via `onError` + `onCancel`,
best-effort), which prevents orphan accumulation and lets reconcile respawn the
deterministic nodeId cleanly instead of dead-locking on a name conflict. The
shared per-pool federation secret is intentionally left (GC'd by `stop()`).
Original report:

`KubernetesQuackBackend.start` (line 200-229) creates token/federation Secrets and
the pod before `waitReady`, which `sys.error`s on timeout with no cleanup;
`createPool`'s spawn fold has no bracket/rollback and `store.upsertPool` already
persisted the pool. Reconcile then tries to respawn with the SAME deterministic
nodeIds, which K8s rejects as conflicts, so the pool never recovers AND orphans
accumulate (`cleanup()` is `IO.unit`, no owner-refs/finalizers). Fix: wrap spawn
in a bracket that deletes pod+secrets on failure, or set an ownerReference so
K8s GCs them; make reconcile adopt/replace conflicting pods.

### 8. Lower-severity confirmed items
**Status: PARTIALLY SOLVED** (two items fixed 2026-07-03, rest OPEN).
- **SOLVED (2026-07-03).** FlightProducerImpl forwarded internal exception text
  (SQL, node hostnames) to Flight clients at many sites
  (INTERNAL.withDescription(t.getMessage)). Now every `catch { case t: Throwable }`
  INTERNAL arm goes through `internalError(context, t)`, which logs the full detail
  server-side against a random errorId and returns the client only
  "internal error (errorId=...)".
- **SOLVED (2026-07-03).** Non-constant-time X-API-Key comparison
  (`Option.contains`) replaced with a `MessageDigest.isEqual` constant-time compare
  in `ManagerServer.apiKeyGuard`.
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
1. ~~spawn-script injection (#1)~~ **DONE** (4feea27, f2bf610, 0d9f239).
2. ~~membership cross-tenant edge (#2)~~ **DONE** (9142c5f).
3. ~~K8s spawn-failure orphans + stuck pool (#7)~~ **DONE** (b2e4c86).
4. ~~ACL fail-closed on empty access set + missing walker arms (#3, #4)~~ **DONE** (2026-07-03). The walker now surfaces `unsupported` markers and the validator denies on any of them; EXPLAIN is classified by its inner statement.
5. ~~RLS fail-closed + retry sends finalSql (#5)~~ **DONE** (2026-07-03).
6. ~~Prepared-statement revocation lag + handle sharing (#6)~~ **DONE** (2026-07-03). Handles are peer-bound; the EffectiveSet is re-read live per Execute.
7. ~~Exception-text leak + constant-time API-key compare (#8, 2 of N)~~ **DONE** (2026-07-03).

Remaining OPEN backlog: the rest of the #8 lower-severity items (resource-leak
counters, PoolPermission tenant check, ON CONFLICT, PortAllocator sync, TLS key
perms, K8s Secret scope, OIDC ConfigMap secret, image pinning, stop-script
pgrep, UI leaks) plus the documented-by-design defaults. None are
default-reachable HIGH.
