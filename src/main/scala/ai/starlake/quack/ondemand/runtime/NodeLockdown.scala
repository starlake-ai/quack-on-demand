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
  // Intentional divergence from the edge LockdownScreen's RemoteSchemes (which also exempts
  // r2:// / http(s)://): only s3 / gs / az dataPaths trigger the LocalFileSystem restriction,
  // because those are the only object-store schemes DuckLake writes a dataPath to. r2 and http(s)
  // are read-only federation URL forms, never a dataPath, so they must not gate disabled_filesystems.
  private val ObjectStoreSchemes = List("s3://", "gs://", "az://")

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
