package ai.starlake.quack.model

/** What the manager wants the runtime backend to start. */
final case class NodeSpec(
    poolKey: PoolKey,
    nodeId: String,
    role: Role,
    metastore: Map[String, String], // pgHost, pgPort, ... -- opaque to manager
    s3: Map[String, String],        // optional keys; empty when local FS
    maxConcurrent: Int = 0,         // 0 = unlimited; hard cap on in-flight queries
    kindWire: String = "ducklake",  // passed to spawn script as `kind` env var
    // The manager's own SQL run after the catalog ATTACH: the resolved federation blob (and a
    // merge node's branch attach). Trusted; never a tenant's initSql.
    extraSetupSql: String = "",
    // The tenant-db's initSql. Runs after the proxy and resource settings and before the catalog
    // ATTACH, so engine-level defaults are in effect before quack starts. Delivered as the secret
    // file `tenantDbInitSql`; `tenantDbInitTrusted` (env QOD_INIT_TRUSTED_DB) says whether the
    // spawn script hands it to qod-node as written (a superuser's or the manager's) or inside a
    // marked untrusted section that qod-node runs under its tenant initSql allowlist.
    tenantDbInitSql: String = "",
    tenantDbInitTrusted: Boolean = false,
    // The pool's initSql. Runs after the catalog ATTACH, before the federation blob. Delivered as
    // the secret file `tenantPoolInitSql`, its trust as env QOD_INIT_TRUSTED_POOL, like the above.
    tenantPoolInitSql: String = "",
    tenantPoolInitTrusted: Boolean = false,
    // Engine lockdown block (NodeLockdown.sql). Runs BEFORE quack_serve, after
    // extraSetupSql, so the value restrictions are in effect before the node
    // serves any tenant statement. Empty string = no lockdown.
    lockdownSql: String = "",
    // Per-database object-store CREATE SECRET (ObjectStoreSecret.sql), scoped to the db's
    // dataPath. Runs before the DuckLake ATTACH so the bucket is authenticated. Empty when the
    // tenant-db has no objectStore config (the global-env secret path is used instead). Shipped
    // via the `objectStoreSql` node env var; in K8s the value rides in a per-node Secret.
    objectStoreSql: String = "",
    // K8s scheduling hint inherited from the node's cohort. None / empty
    // = no placement constraint (default scheduler decides). Backends
    // other than KubernetesQuackBackend ignore this field.
    placement: NodePlacement = NodePlacement.empty,
    cpu: Option[String] = None,
    memory: Option[String] = None,
    podTemplateYaml: Option[String] = None
)
