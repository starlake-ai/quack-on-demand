package ai.starlake.quack.ondemand.federation

import ai.starlake.quack.model.{FederatedSecret, FederatedSource, FederatedSourceType}
import ai.starlake.quack.ondemand.federation.iceberg.{IcebergRestConfig, IcebergSetupSql}
import cats.effect.IO
import cats.syntax.all.*

/** One source's resolved setup SQL, plus the plaintext secret VALUES that were substituted into it.
  *
  * `secretValues` exists so the Iceberg attach verifier does not have to re-derive the credentials
  * by parsing the SQL back out: the builder is the component that actually resolved them, so it is
  * the component that knows what they are. That matters for
  * [[ai.starlake.quack.ondemand.federation.iceberg.AttachErrorRedactor]], whose whole job is to
  * keep those values out of an error string a remote catalog controls -- a parser-derived set
  * silently stops covering a credential the day the generator grows a new option, whereas this set
  * covers every `{{secret.NAME}}` the builder resolved regardless of where in the template it sat.
  *
  * Empty in `redactSecrets` (preview) mode, where nothing was resolved. NEVER log `sql`.
  */
final case class ResolvedFederationBlock(sql: String, secretValues: Set[String])

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
    resolver: SecretResolver,
    catalogAliasOf: String => IO[Option[String]] = _ => IO.pure(None)
) {

  private val PlaceholderRegex = """\{\{[^}]*\}\}""".r
  private val SecretRegex      = """\{\{secret\.([A-Za-z0-9_]+)\}\}""".r
  private val OpenBraceRegex   = """\{\{""".r
  private val AliasToken       = "{{alias}}"

  def build(tenantDbId: String): IO[Option[String]] =
    assemble(tenantDbId, redactSecrets = false)

  def logSafePreview(tenantDbId: String): IO[Option[String]] =
    assemble(tenantDbId, redactSecrets = true)

  /** Resolve ONE source's block, secrets substituted, together with the values that were
    * substituted. Used by the Iceberg attach verifier to re-issue a single catalog's ATTACH on a
    * live node: re-running the whole blob would re-execute every other source's CREATE SECRET and
    * ATTACH. The verifier feeds `secretValues` straight to
    * [[ai.starlake.quack.ondemand.federation.iceberg.AttachErrorRedactor]]. NEVER log `sql`.
    */
  def buildOne(src: FederatedSource): IO[ResolvedFederationBlock] =
    renderOne(src, redactSecrets = false)

  private def assemble(tenantDbId: String, redactSecrets: Boolean): IO[Option[String]] = for {
    sources <- loadEnabled(tenantDbId).map(_.sortBy(_.alias))
    // Resolved once per blob (not per source): the tenant-db's own DuckDB catalog alias, if this
    // tenant-db is known to the caller. Reserved alongside the sibling aliases below so an
    // iceberg source can't claim the name the tenant-db itself is ATTACHed under -- e.g. a
    // manifest-imported source aliased the same as its own tenant-db, which never goes through
    // `FederatedSourceHandlers.toSource`'s REST/MCP-time check.
    ownAlias <- catalogAliasOf(tenantDbId).map(_.map(_.toLowerCase))
    blobs    <- sources.traverse { src =>
      // Every OTHER source's alias (by id, NOT by alias equality -- two rows can already share an
      // alias, which is exactly the case this is meant to catch), lowercased since `validated`
      // compares case-insensitively. Passed as `extraReserved` so an iceberg source can't claim a
      // DuckDB catalog name a sibling in this same tenant-db already holds, or the tenant-db's own
      // catalog alias -- see `bodyTemplate`.
      val siblingAliases = sources.filterNot(_.id == src.id).map(_.alias.toLowerCase).toSet
      renderOne(src, redactSecrets, siblingAliases ++ ownAlias).map(_.sql)
    }
  } yield if blobs.isEmpty then None else Some(blobs.mkString("\n"))

  private def renderOne(
      src: FederatedSource,
      redactSecrets: Boolean,
      siblingAliases: Set[String] = Set.empty
  ): IO[ResolvedFederationBlock] = for {
    template <- bodyTemplate(src, siblingAliases)
    secrets  <- loadSecrets(src.id)
    byName = secrets.map(s => s.name -> s).toMap
    resolved <- substitute(src, template, byName, redactSecrets)
  } yield ResolvedFederationBlock(
    s"-- BEGIN federation: ${src.alias}\n${resolved._1}\n-- END federation: ${src.alias}",
    resolved._2
  )

  /** The pre-substitution SQL for one source. A `Sql` source supplies it directly; an `IcebergRest`
    * source has it rendered from typed config. The rendered form still carries `{{secret.NAME}}`
    * placeholders, so everything downstream (substitution, SQL escaping, the stray-placeholder
    * check, redaction) applies to both shapes identically.
    *
    * A config that will not parse raises rather than degrading: silently skipping the source would
    * bring a node up with the catalog missing, which is exactly the failure the attach verifier
    * exists to make visible.
    *
    * `siblingAliases` reserves every OTHER enabled source's alias in the same tenant-db, PLUS (via
    * `assemble`'s `catalogAliasOf`) the tenant-db's own DuckDB catalog alias; default empty for
    * [[buildOne]], which re-issues one already-stored source whose collisions were already settled
    * when that row was created. `FederatedSourceHandlers.toSource` runs the same check at REST/MCP
    * write time, but `ManifestImporter` builds `FederatedSource` rows directly and calls
    * `upsertSource` without going through that handler, so this is the layer that check reaches for
    * every write path -- for `IcebergRest` sources only. Only the `IcebergRest` arm below calls
    * `IcebergRestConfig.validated`; two colliding `Sql` sources (or a `Sql` source colliding with
    * the tenant-db's own alias) are never checked here, since a `Sql` source's setup SQL is
    * operator-written and unparsed.
    *
    * On an actual collision this raises rather than degrading: without it, a colliding ATTACH fails
    * on the node but the piped DuckDB CLI does not bail, so the node comes up healthy with a
    * half-applied catalog set and the operator only sees `Catalog "x" does not exist` at query
    * time, with no pointer back to the cause. The raise is only as loud as its caller makes it,
    * though: `PoolSupervisor.createPool` propagates it to the REST/CLI caller, but
    * `PoolSupervisor.restore()` (`resolvedBlobFor`) catches it, falls back to the tenant-db's
    * previous blob (or `""`), and logs -- degrading that tenant-db's WHOLE federation blob, not
    * just the offending source.
    */
  private def bodyTemplate(src: FederatedSource, siblingAliases: Set[String]): IO[String] =
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
                IcebergRestConfig.validated(cfg, src.alias, siblingAliases) match
                  case Left(errs) =>
                    IO.raiseError(
                      new RuntimeException(
                        s"invalid iceberg config for source '${src.alias}': ${errs.mkString("; ")}"
                      )
                    )
                  case Right(v) => IO.pure(IcebergSetupSql.render(v, src.readOnly))
              case Left(err) =>
                IO.raiseError(
                  new RuntimeException(s"invalid iceberg config for source '${src.alias}': $err")
                )

  private def substitute(
      src: FederatedSource,
      template: String,
      secrets: Map[String, FederatedSecret],
      redactSecrets: Boolean
  ): IO[(String, Set[String])] = {
    // 1. Substitute {{alias}} unconditionally. SQL-escape so an alias with
    //    an apostrophe can't break the surrounding literal context the
    //    operator wrote (`AS {{alias}}` is normally an identifier, but a
    //    template may wrap it in a literal for a comment or label).
    val step1 = template.replace(AliasToken, sqlEscapeSingleQuote(src.alias))

    // 2. Resolve each distinct {{secret.NAME}} via the resolver (or keep
    //    as placeholder when building the log-safe preview). Resolved
    //    VALUES go through sqlEscapeSingleQuote at SPLICE time so a single-quote
    //    in a secret can't terminate the surrounding string literal (the
    //    production template writes `PASSWORD '{{secret.PG_PWD}}'`); the pairs
    //    themselves stay RAW, because the raw form is what a catalog echoing the
    //    credential back would show, and so the form the reported set has to carry
    //    for AttachErrorRedactor to find it.
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
            case Some(s) => resolver.resolve(s).map(v => name -> v)
            case None    =>
              IO.raiseError(
                new RuntimeException(s"unresolved secret '$name' in source '${src.alias}'")
              )
        }

    for {
      pairs <- resolvePairs
      lookup =
        if redactSecrets then pairs.toMap
        else pairs.map((name, value) => name -> sqlEscapeSingleQuote(value)).toMap
      step2 = SecretRegex.replaceAllIn(
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
    } yield (step2, if redactSecrets then Set.empty[String] else pairs.map(_._2).toSet)
  }

  /** Double every embedded single quote so the value can be spliced into a SQL string literal
    * without breaking the surrounding quotes. Standard SQL string-literal escape; safe on values
    * that contain no apostrophes (no-op).
    */
  private def sqlEscapeSingleQuote(v: String): String = v.replace("'", "''")
}
