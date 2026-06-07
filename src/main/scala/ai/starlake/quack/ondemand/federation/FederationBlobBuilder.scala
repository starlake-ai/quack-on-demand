package ai.starlake.quack.ondemand.federation

import ai.starlake.quack.model.{FederatedSecret, FederatedSource}
import cats.effect.IO
import cats.syntax.all.*

/** Assembles the post-DuckLake setup SQL blob for a single tenant-db's
  * federated sources. The blob is what `spawn-quack-node.sh` runs in
  * DuckDB after attaching the default catalog (or instead of, for
  * `kind = InMemory`).
  *
  * Two methods:
  *  - [[build]] returns the resolved blob with secret VALUES inlined.
  *    This is what gets passed to the child node via `extraSetupSql`.
  *    NEVER log this output.
  *  - [[logSafePreview]] returns the same blob but with
  *    `{{secret.NAME}}` placeholders preserved (the alias placeholder
  *    IS expanded). Safe to log for diagnostics. */
final class FederationBlobBuilder(
    loadEnabled: String => IO[List[FederatedSource]],
    loadSecrets: String => IO[List[FederatedSecret]],
    resolver:    SecretResolver
) {

  private val PlaceholderRegex = """\{\{[^}]*\}\}""".r
  private val SecretRegex      = """\{\{secret\.([A-Za-z0-9_]+)\}\}""".r
  private val OpenBraceRegex   = """\{\{""".r
  private val AliasToken       = "{{alias}}"

  def build(tenantDbId: String): IO[Option[String]] =
    assemble(tenantDbId, redactSecrets = false)

  def logSafePreview(tenantDbId: String): IO[Option[String]] =
    assemble(tenantDbId, redactSecrets = true)

  private def assemble(tenantDbId: String, redactSecrets: Boolean): IO[Option[String]] = for {
    sources <- loadEnabled(tenantDbId).map(_.sortBy(_.alias))
    blobs   <- sources.traverse(renderOne(_, redactSecrets))
  } yield if blobs.isEmpty then None else Some(blobs.mkString("\n"))

  private def renderOne(src: FederatedSource, redactSecrets: Boolean): IO[String] = for {
    secrets <- loadSecrets(src.id)
    byName  =  secrets.map(s => s.name -> s).toMap
    body    <- substitute(src, byName, redactSecrets)
  } yield s"-- BEGIN federation: ${src.alias}\n$body\n-- END federation: ${src.alias}"

  private def substitute(
      src: FederatedSource,
      secrets: Map[String, FederatedSecret],
      redactSecrets: Boolean
  ): IO[String] = {
    // 1. Substitute {{alias}} unconditionally.
    val step1 = src.setupSql.replace(AliasToken, src.alias)

    // 2. Resolve each distinct {{secret.NAME}} via the resolver (or keep
    //    as placeholder when building the log-safe preview).
    val secretsNeeded = SecretRegex.findAllMatchIn(step1).map(_.group(1)).toList.distinct

    val resolvePairs: IO[List[(String, String)]] =
      if redactSecrets then
        secretsNeeded.traverse { name =>
          secrets.get(name) match
            case Some(_) => IO.pure(name -> s"{{secret.$name}}")
            case None    => IO.raiseError(new RuntimeException(
              s"unresolved secret '$name' in source '${src.alias}'"))
        }
      else
        secretsNeeded.traverse { name =>
          secrets.get(name) match
            case Some(s) => resolver.resolve(s).map(name -> _)
            case None    => IO.raiseError(new RuntimeException(
              s"unresolved secret '$name' in source '${src.alias}'"))
        }

    for {
      pairs  <- resolvePairs
      lookup =  pairs.toMap
      step2  =  SecretRegex.replaceAllIn(step1, m =>
                  java.util.regex.Matcher.quoteReplacement(lookup(m.group(1))))
      // In preview mode secret placeholders are intentionally kept; strip
      // them before checking for other strays, but still catch malformed
      // or unknown {{ sequences.
      checkTarget = if redactSecrets then SecretRegex.replaceAllIn(step2, "") else step2
      _      <- OpenBraceRegex.findFirstIn(checkTarget) match
                  case Some(stray) =>
                    val context = PlaceholderRegex.findFirstIn(checkTarget).getOrElse(stray)
                    IO.raiseError(new RuntimeException(
                      s"unsubstituted placeholder in source '${src.alias}': $context"))
                  case None        => IO.unit
    } yield step2
  }
}