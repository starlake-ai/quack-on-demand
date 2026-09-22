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
  *
  * `toString` is overridden because "NEVER log sql" is a comment, not a guard: a case class whose
  * DERIVED `toString` renders the SQL and the raw secret set is one string interpolation away from
  * the exact leak this type exists to help prevent. No caller renders it today; the override is so
  * that a future log line, exception message or debug print cannot.
  */
final case class ResolvedFederationBlock(sql: String, secretValues: Set[String]):
  override def toString: String =
    s"ResolvedFederationBlock(sql=${sql.length} chars, secretValues=${secretValues.size} values)"

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
    catalogAliasOf: String => IO[Option[String]]
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
    *
    * Reserves the SAME alias set [[assemble]] reserves for this source, resolved from
    * `src.tenantDbId` rather than inherited, because the two are two views of one thing: what the
    * node is supposed to be running. An earlier version reserved nothing here, on the reasoning
    * that a stored row's collisions were settled at write time -- which is true only of the
    * REST/MCP path. `ManifestImporter` writes rows straight through `upsertSource`, so a
    * manifest-imported alias colliding with its own tenant-db's catalog (or with a sibling) is
    * refused by the deployed blob and was accepted here, leaving the verifier re-issuing an ATTACH
    * the node's own startup script had deliberately refused to run.
    *
    * Costs one extra `loadEnabled` round trip per re-attempt. That is on the verifier's
    * backoff-gated retry path (at most once per alias per backoff window), not per health tick.
    */
  def buildOne(src: FederatedSource): IO[ResolvedFederationBlock] = for {
    siblings <- loadEnabled(src.tenantDbId)
    ownAlias <- ownAliasOf(src.tenantDbId)
    block    <- renderOne(src, redactSecrets = false, reservedFor(siblings, src.id, ownAlias))
  } yield block

  private def assemble(tenantDbId: String, redactSecrets: Boolean): IO[Option[String]] = for {
    sources  <- loadEnabled(tenantDbId).map(_.sortBy(_.alias))
    ownAlias <- ownAliasOf(tenantDbId)
    blobs    <- sources.traverse { src =>
      renderOne(src, redactSecrets, reservedFor(sources, src.id, ownAlias)).map(_.sql)
    }
  } yield if blobs.isEmpty then None else Some(blobs.mkString("\n"))

  /** The tenant-db's own DuckDB catalog alias, if the caller can resolve it, folded once here so
    * every reserved entry is folded the same way. Reserved alongside the sibling aliases in
    * [[reservedFor]] so an iceberg source can't claim the name the tenant-db itself is ATTACHed
    * under -- e.g. a manifest-imported source aliased the same as its own tenant-db, which never
    * goes through `FederatedSourceHandlers.toSource`'s REST/MCP-time check.
    */
  private def ownAliasOf(tenantDbId: String): IO[Option[String]] =
    catalogAliasOf(tenantDbId).map(_.map(fold))

  /** Every DuckDB catalog name the source `selfId` must not claim: every OTHER enabled source's
    * alias in the same tenant-db (by id, NOT by alias equality -- two rows can already share an
    * alias, which is exactly the case this is meant to catch), plus the tenant-db's own catalog
    * alias.
    *
    * This is the ONE producer of that set. [[assemble]] (what gets deployed to the node) and
    * [[buildOne]] (what the attach verifier re-issues onto a live node) both go through it, so the
    * two cannot model different things; they diverged exactly once, and that is the defect this
    * shape removes.
    */
  private def reservedFor(
      sources: List[FederatedSource],
      selfId: String,
      ownAlias: Option[String]
  ): Set[String] =
    sources.filterNot(_.id == selfId).map(s => fold(s.alias)).toSet ++ ownAlias

  /** The one alias fold, [[ai.starlake.quack.model.FederatedAlias.fold]]. `validated` compares
    * case-insensitively, so what is reserved has to be folded the same way no matter what default
    * locale the manager's JVM happens to carry. Aliased locally only to keep the call sites below
    * short.
    */
  private def fold(s: String): String = ai.starlake.quack.model.FederatedAlias.fold(s)

  private def renderOne(
      src: FederatedSource,
      redactSecrets: Boolean,
      reservedAliases: Set[String]
  ): IO[ResolvedFederationBlock] = for {
    template <- bodyTemplate(src, reservedAliases)
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
    * `reservedAliases` comes from [[reservedFor]]: every OTHER enabled source's alias in the same
    * tenant-db, PLUS the tenant-db's own DuckDB catalog alias. Both callers pass it (there is no
    * default -- a defaulted reserved set is how [[buildOne]] came to reserve nothing).
    * `FederatedSourceHandlers.toSource` runs the same check at REST/MCP write time, but
    * `ManifestImporter` builds `FederatedSource` rows directly and calls `upsertSource` without
    * going through that handler, so this is the layer that check reaches for every write path --
    * for `IcebergRest` sources only. Only the `IcebergRest` arm below calls
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
  private def bodyTemplate(src: FederatedSource, reservedAliases: Set[String]): IO[String] =
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
                IcebergRestConfig.validated(cfg, src.alias, reservedAliases) match
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
