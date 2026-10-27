package ai.starlake.quack.edge.authz.location

import ai.starlake.quack.ondemand.runtime.NodePaths

import java.io.File
import java.nio.file.{Files, Path, Paths}
import java.util.Locale
import scala.annotation.tailrec

/** A database data path as [[LocationChecker]] deny-set entries. */
object DataFolders:

  /** Schemes whose URLs a tenant `s3://` secret with another endpoint can reach (S3-compatible
    * APIs, GCS interop, Azure containers named like buckets): all read as `s3://<bucket>/<key>/`,
    * the family-blind matching of `ai.starlake.quack.model.BucketKeys`.
    */
  private val ObjectSchemes =
    List("s3://", "s3a://", "s3n://", "r2://", "gs://", "gcs://", "az://", "azure://")

  private val SchemeRe = "^[a-zA-Z][a-zA-Z0-9+.-]*://.*$".r

  /** The deny-set entries of data path `path`. `sameHost`: nodes run on this host and resolve a
    * relative data path against this process's directory (the local runtime), so a relative path is
    * resolved and every local entry is joined by its real path (`realPath`). Off the local runtime
    * a relative path stays as written, which the checker reads as "may be anywhere on the node". A
    * remote scheme no tenant can name yields nothing.
    */
  def of(path: String, sameHost: Boolean, realPath: String => String): List[String] =
    val p     = path.trim
    val lower = p.toLowerCase(Locale.ROOT)
    if p.isEmpty then Nil
    else
      ObjectSchemes.find(lower.startsWith) match
        case Some(sch) => objectFolder(p.substring(sch.length)).toList
        case None      =>
          if lower.startsWith("abfss://") then
            val rest      = p.substring("abfss://".length)
            val container = rest.takeWhile(c => c != '@' && c != '/')
            val key       = rest.dropWhile(_ != '/')
            objectFolder(container + key).toList
          else if lower.startsWith("http://") || lower.startsWith("https://") then
            val rest      = p.substring(p.indexOf("://") + 3)
            val authority = rest.takeWhile(_ != '/')
            val hostLabel = authority.takeWhile(c => c != '.' && c != ':')
            val pathPart  = rest.drop(authority.length).stripPrefix("/")
            (objectFolder(s"$hostLabel/$pathPart").toList ++ objectFolder(pathPart).toList).distinct
          else if SchemeRe.matches(p) then Nil
          else local(p, sameHost, realPath)

  /** `bucket/key...` as `s3://<lowercase bucket>/<key segments>/`; None without a bucket. */
  private def objectFolder(rest: String): Option[String] =
    val bucket = rest.takeWhile(_ != '/').toLowerCase(Locale.ROOT)
    val key    = rest.drop(bucket.length).split("/").filter(_.nonEmpty).map(_ + "/").mkString
    Option.when(bucket.nonEmpty)(s"s3://$bucket/$key")

  /** A path this host cannot parse (`Paths.get` refuses it) stays as written: the checker reads it
    * as "may be anywhere on the node" and refuses every local location.
    */
  private def local(p: String, sameHost: Boolean, realPath: String => String): List[String] =
    val absolute =
      try
        if NodePaths.isRelativeLocal(p) && !isWindowsAbsolute(p) then
          if sameHost then Some(NodePaths.resolve(p)) else None
        else Some(p)
      catch case _: java.nio.file.InvalidPathException => None
    absolute match
      case None      => List(p)
      case Some(abs) =>
        LocationPath.normalizeLocal(abs) match
          case None        => List(p)
          case Some(lexed) =>
            if !sameHost then List(lexed)
            else (lexed :: LocationPath.normalizeLocal(realPath(lexed)).toList).distinct

  private def isWindowsAbsolute(p: String): Boolean =
    p.length >= 3 && p.charAt(0).isLetter && p.charAt(1) == ':' &&
      (p.charAt(2) == '\\' || p.charAt(2) == '/')

/** The real path a node gives a local path: the deepest existing ancestor through `toRealPath`
  * (symlinks resolved), the rest appended as written, a trailing separator kept. qod-node's
  * `ResolveLocalPath` does the same before its parse screen asks; the manager applies it to data
  * paths, the files root and tenant folders on the local runtime, where it sees the nodes'
  * filesystem.
  */
object LocalRealPath:

  def of(path: String): String =
    val dir = path.endsWith("/") || path.endsWith("\\")
    try
      val p = Paths.get(path)
      if !p.isAbsolute then path
      else
        existing(p, Nil) match
          case None               => path
          case Some((base, rest)) =>
            val real = rest.foldLeft(base.toRealPath())((acc, seg) => acc.resolve(seg)).toString
            if dir && !real.endsWith(File.separator) then real + File.separator else real
    catch case _: Exception => path

  @tailrec
  private def existing(p: Path, rest: List[String]): Option[(Path, List[String])] =
    if p == null then None
    else if Files.exists(p) then Some((p, rest))
    else existing(p.getParent, Option(p.getFileName).map(_.toString).toList ++ rest)
