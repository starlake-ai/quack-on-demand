package ai.starlake.quack.ondemand.federation

import ai.starlake.quack.model.{FederatedSecret, FederatedSource, FederatedSourceType}
import ai.starlake.quack.ondemand.federation.iceberg.{IcebergRestConfig, IcebergSetupSql}
import cats.effect.IO
import cats.syntax.all.*

/** Assembles the post-DuckLake setup SQL blob for a single tenant-db's federated sources. The blob
  * is what `spawn-quack-node.sh` runs in DuckDB after attaching the default catalog (or instead of,
  * for `kind = InMemory`).
  *
  * Two methods:
  *   - [[build]] returns the resolved blob with secret VALUES inlined. This is what gets passed to
  *     the child node via `extraSetupSql`. NEVER log this output.
  *   - [[logSafePreview]] returns the same blob but with `{{secret.NAME}}` placeholders preserved
  *     (the alias placeholder IS expanded). Safe to log for diagnostics.
  *
  * **SQL-safety of substituted values.** Operator templates routinely wrap secret placeholders in a
  * single-quoted string literal -- the production tpch template literally writes
  * `PASSWORD '{{secret.PG_PWD}}'`. A naive replacement would break (or silently injection-attack)
  * the moment a value contained an apostrophe: `O'Brien` ⇒ `PASSWORD 'O'Brien'` ⇒ DuckDB parses the
  * literal as `O`, then chokes on `Brien'`. To prevent both the breakage and the injection vector,
  * every resolved secret value AND the expanded alias are passed through [[sqlEscapeSingleQuote]]
  * before splicing -- doubling each `'` so the value survives unchanged when it lands inside a SQL
  * string literal, while remaining a no-op for values that don't contain apostrophes.
  *
  * The escape is SAFE for the only two contexts a placeholder can legitimately appear in:
  *   - Inside a SQL string literal (`'{{secret.X}}'`): `''` is the standard escape, value is
  *     intact.
  *   - Bare (e.g. `PORT {{secret.X}}`): values without apostrophes pass through unchanged; a value
  *     WITH an apostrophe was already a malformed identifier/numeric, so the doubled form is no
  *     worse.
  */
final class FederationBlobBuilder(
    loadEnabled: String => IO[List[FederatedSource]],
    loadSecrets: String => IO[List[FederatedSecret]],
    resolver: SecretResolver
) {

  private val PlaceholderRegex = """\{\{[^}]*\}\}""".r
  private val SecretRegex      = """\{\{secret\.([A-Za-z0-9_]+)\}\}""".r
  private val OpenBraceRegex   = """\{\{""".r
  private val AliasToken       = "{{alias}}"

  def build(tenantDbId: String): IO[Option[String]] =
    assemble(tenantDbId, redactSecrets = false)

  def logSafePreview(tenantDbId: String): IO[Option[String]] =
    assemble(tenantDbId, redactSecrets = true)

  /** Resolve ONE source's block, secrets substituted. Used by the Iceberg attach verifier to
    * re-issue a single catalog's ATTACH on a live node: re-running the whole blob would re-execute
    * every other source's CREATE SECRET and ATTACH. NEVER log this output.
    */
  def buildOne(src: FederatedSource): IO[String] =
    renderOne(src, redactSecrets = false)

  private def assemble(tenantDbId: String, redactSecrets: Boolean): IO[Option[String]] = for {
    sources <- loadEnabled(tenantDbId).map(_.sortBy(_.alias))
    blobs   <- sources.traverse(renderOne(_, redactSecrets))
  } yield if blobs.isEmpty then None else Some(blobs.mkString("\n"))

  private def renderOne(src: FederatedSource, redactSecrets: Boolean): IO[String] = for {
    template <- bodyTemplate(src)
    secrets  <- loadSecrets(src.id)
    byName = secrets.map(s => s.name -> s).toMap
    body <- substitute(src, template, byName, redactSecrets)
  } yield s"-- BEGIN federation: ${src.alias}\n$body\n-- END federation: ${src.alias}"

  /** The pre-substitution SQL for one source. A `Sql` source supplies it directly; an `IcebergRest`
    * source has it rendered from typed config. The rendered form still carries `{{secret.NAME}}`
    * placeholders, so everything downstream (substitution, SQL escaping, the stray-placeholder
    * check, redaction) applies to both shapes identically.
    *
    * A config that will not parse raises rather than degrading: silently skipping the source would
    * bring a node up with the catalog missing, which is exactly the failure the attach verifier
    * exists to make visible.
    */
  private def bodyTemplate(src: FederatedSource): IO[String] =
    src.sourceType match
      case FederatedSourceType.Sql         => IO.pure(src.setupSql)
      case FederatedSourceType.IcebergRest =>
        src.config.filter(_.trim.nonEmpty) match
          case None =>
            IO.raiseError(
              new RuntimeException(s"iceberg source '${src.alias}' has no config")
            )
          case Some(json) =>
            IcebergRestConfig.fromJson(json) match
              // validate BEFORE render. `render` is a pure total function with a stated
              // precondition: it does not check the config, so an invalid one renders SQL DuckDB
              // refuses (e.g. ENDPOINT_TYPE together with AUTHORIZATION_TYPE), and because this
              // SQL runs in the node's startup script that takes down the node's whole init,
              // not just this catalog.
              // `render` accepts ONLY a ValidatedIcebergConfig (owner decision after the session
              // audit): the illegal state is unrepresentable, so this arm cannot forget to validate.
              case Right(cfg) =>
                IcebergRestConfig.validated(cfg, src.alias) match
                  case Left(errs) =>
                    IO.raiseError(
                      new RuntimeException(
                        s"invalid iceberg config for source '${src.alias}': ${errs.mkString("; ")}"
                      )
                    )
                  case Right(v) => IO.pure(IcebergSetupSql.render(v))
              case Left(err) =>
                IO.raiseError(
                  new RuntimeException(s"invalid iceberg config for source '${src.alias}': $err")
                )

  private def substitute(
      src: FederatedSource,
      template: String,
      secrets: Map[String, FederatedSecret],
      redactSecrets: Boolean
  ): IO[String] = {
    // 1. Substitute {{alias}} unconditionally. SQL-escape so an alias with
    //    an apostrophe can't break the surrounding literal context the
    //    operator wrote (`AS {{alias}}` is normally an identifier, but a
    //    template may wrap it in a literal for a comment or label).
    val step1 = template.replace(AliasToken, sqlEscapeSingleQuote(src.alias))

    // 2. Resolve each distinct {{secret.NAME}} via the resolver (or keep
    //    as placeholder when building the log-safe preview). Resolved
    //    VALUES go through sqlEscapeSingleQuote so a single-quote in a
    //    secret can't terminate the surrounding string literal (the
    //    production template writes `PASSWORD '{{secret.PG_PWD}}'`).
    //    The placeholder form preserved in redactSecrets mode is intentionally
    //    NOT escaped -- it's the literal `{{secret.NAME}}` marker that the
    //    later strayPlaceholder check needs to see verbatim.
    val secretsNeeded = SecretRegex.findAllMatchIn(step1).map(_.group(1)).toList.distinct

    val resolvePairs: IO[List[(String, String)]] =
      if redactSecrets then
        secretsNeeded.traverse { name =>
          secrets.get(name) match
            case Some(_) => IO.pure(name -> s"{{secret.$name}}")
            case None    =>
              IO.raiseError(
                new RuntimeException(s"unresolved secret '$name' in source '${src.alias}'")
              )
        }
      else
        secretsNeeded.traverse { name =>
          secrets.get(name) match
            case Some(s) => resolver.resolve(s).map(v => name -> sqlEscapeSingleQuote(v))
            case None    =>
              IO.raiseError(
                new RuntimeException(s"unresolved secret '$name' in source '${src.alias}'")
              )
        }

    for {
      pairs <- resolvePairs
      lookup = pairs.toMap
      step2  = SecretRegex.replaceAllIn(
        step1,
        m => java.util.regex.Matcher.quoteReplacement(lookup(m.group(1)))
      )
      // In preview mode secret placeholders are intentionally kept; strip
      // them before checking for other strays, but still catch malformed
      // or unknown {{ sequences.
      checkTarget = if redactSecrets then SecretRegex.replaceAllIn(step2, "") else step2
      _ <- OpenBraceRegex.findFirstIn(checkTarget) match
        case Some(stray) =>
          val context = PlaceholderRegex.findFirstIn(checkTarget).getOrElse(stray)
          IO.raiseError(
            new RuntimeException(s"unsubstituted placeholder in source '${src.alias}': $context")
          )
        case None => IO.unit
    } yield step2
  }

  /** Double every embedded single quote so the value can be spliced into a SQL string literal
    * without breaking the surrounding quotes. Standard SQL string-literal escape; safe on values
    * that contain no apostrophes (no-op).
    */
  private def sqlEscapeSingleQuote(v: String): String = v.replace("'", "''")
}
