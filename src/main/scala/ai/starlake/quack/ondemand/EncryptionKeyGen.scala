package ai.starlake.quack.ondemand

/** Mints the per-database key for an encrypted `kind=duckdb-file` tenant-db. Base64 of 32 random
  * bytes: DuckDB accepts any string and derives its real key internally, but a full-entropy 32-byte
  * value is what its documentation recommends. The standard base64 alphabet contains none of the
  * metacharacters the spawn scripts deny (`TenantDb.ConnParamForbiddenChars`), so a minted key
  * always passes `TenantDb.validate`.
  */
object EncryptionKeyGen:

  private val rng = new java.security.SecureRandom()

  def mint(): String =
    val bytes = new Array[Byte](32)
    rng.nextBytes(bytes)
    java.util.Base64.getEncoder.encodeToString(bytes)
