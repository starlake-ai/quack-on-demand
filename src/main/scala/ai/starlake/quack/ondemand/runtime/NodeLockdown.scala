package ai.starlake.quack.ondemand.runtime

/** Engine-side lockdown block, authored here as the single source of truth and shipped to both
  * spawn scripts via NodeSpec.lockdownSql / the `lockdownSql` env var. Runs BEFORE quack_serve,
  * after every legitimate INSTALL/LOAD/ATTACH, so the restrictions are in effect before the node
  * ever serves a tenant statement.
  *
  * Value-sets only, no config freeze. A live smoke test showed two things break node serving:
  *   - `SET lock_configuration = true` freezes DuckDB's global config outright and is incompatible
  *     with quack_serve regardless of which side of `CALL quack_serve(...)` it runs on -- nodes
  *     come up unhealthy (the SELECT 1 probe fails). It is also redundant: the edge LockdownScreen
  *     already denies every protected-setting SET/RESET/PRAGMA for tenant sessions, so nothing
  *     short of a superuser bypass could change these values anyway. Dropped entirely.
  *   - `SET autoload_known_extensions = false` blocks quack_serve from lazily autoloading the
  *     signed built-in extensions it needs to handle incoming connections, which also marks nodes
  *     unhealthy. Autoloading a signed built-in that is already present on disk is benign, so it is
  *     left ON.
  *
  * What stays blocked: `autoinstall_known_extensions` (fetching arbitrary extensions over the
  * network), `allow_community_extensions` and `allow_unsigned_extensions` (unvetted code), and, for
  * object-store dataPaths, the local filesystem. Those are the real threats; autoloading a signed
  * built-in is not one of them.
  *
  * The LocalFileSystem restriction applies only when the tenant-db data lives in an object store; a
  * local dataPath needs the filesystem, and the edge LockdownScreen carries the local-file guard in
  * that mode.
  */
object NodeLockdown:
  // s3/gs/az plus their `qod serve` aliases (s3a, r2, gcs, azure, abfss): serve accepts all
  // eight as a target scheme, and a lockdown-enabled node serving one of the aliased forms
  // must disable the local filesystem exactly like its canonical form does. r2 is listed here
  // (unlike the edge LockdownScreen's RemoteSchemes, which exempts it as a read-only
  // federation URL form) because `qod serve r2://...` writes it straight into dataPath.
  private val ObjectStoreSchemes =
    List("s3://", "s3a://", "r2://", "gs://", "gcs://", "az://", "azure://", "abfss://")

  def sql(dataPath: String, enabled: Boolean): String =
    if !enabled then ""
    else
      val fsLine =
        if ObjectStoreSchemes.exists(dataPath.startsWith) then
          "SET disabled_filesystems = 'LocalFileSystem';\n"
        else ""
      "SET autoinstall_known_extensions = false;\n" +
        "SET allow_community_extensions = false;\n" +
        "SET allow_unsigned_extensions = false;\n" +
        fsLine
