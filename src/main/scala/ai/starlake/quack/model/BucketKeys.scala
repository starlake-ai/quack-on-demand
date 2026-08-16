package ai.starlake.quack.model

/** Extracts the bucket (or Azure container) key of an object-store URL, lowercased. The key is the
  * unit the LockdownScreen's DuckLake bucket denial matches on: scheme families are deliberately
  * NOT distinguished, so a DuckLake bucket named B is denied under every scheme (cross-family
  * collisions over-deny, accepted as fail-closed).
  */
object BucketKeys:
  private val Schemes = List("s3://", "s3a://", "r2://", "gs://", "az://", "azure://")

  /** The lowercased authority segment of an object-store URL (`abfss://` yields the container, the
    * part before '@'); None for anything else (http(s), local paths, bare names).
    */
  def of(url: String): Option[String] =
    val lower = url.trim.toLowerCase
    if lower.startsWith("abfss://") then
      val rest = lower.stripPrefix("abfss://")
      val end  = rest.indexWhere(c => c == '@' || c == '/')
      val cont = if end < 0 then rest else rest.substring(0, end)
      Option(cont).filter(_.nonEmpty)
    else
      Schemes
        .collectFirst {
          case sch if lower.startsWith(sch) =>
            val rest = lower.stripPrefix(sch)
            val end  = rest.indexOf('/')
            if end < 0 then rest else rest.substring(0, end)
        }
        .filter(_.nonEmpty)
