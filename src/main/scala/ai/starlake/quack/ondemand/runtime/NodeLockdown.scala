package ai.starlake.quack.ondemand.runtime

/** Engine-side lockdown block, authored here as the single source of truth and shipped to both
  * spawn scripts via NodeSpec.lockdownSql / the `lockdownSql` env var. Runs LAST at node init,
  * after every legitimate INSTALL/LOAD/ATTACH, so lock_configuration freezes a fully-initialized
  * engine. The LocalFileSystem restriction applies only when the tenant-db data lives in an object
  * store; a local dataPath needs the filesystem, and the edge LockdownScreen carries the local-file
  * guard in that mode.
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
        "SET autoload_known_extensions = false;\n" +
        "SET allow_community_extensions = false;\n" +
        fsLine +
        "SET lock_configuration = true;"
