package ai.starlake.quack.edge.authz

import ai.starlake.acl.parser.Verb
import ai.starlake.quack.edge.authz.location.{LocationChecker, LocationRules, LocationRulesSource}
import ai.starlake.quack.edge.config.OpaConfig
import ai.starlake.quack.edge.opa.{
  Decision as PolicyDecision,
  OpaAccess,
  OpaAuthorizer,
  OpaInput,
  OpaLocation,
  OpaTarget,
  OpaUser
}
import ai.starlake.quack.edge.policy.PolicyCoverage
import ai.starlake.quack.edge.sql.GrantMatch
import ai.starlake.quack.model.{PoolKey, Tenant, TenantAcl, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.auth.TokenRestriction
import ai.starlake.quack.ondemand.branch.BranchNames
import ai.starlake.quack.ondemand.rbac.{Attenuation, EffectiveSet}

import com.typesafe.scalalogging.LazyLogging

import java.util.Locale
import scala.util.control.NonFatal

/** What the decision endpoint answers a node: a verdict (allow or refuse), or unavailable: a
  * retryable 503, when something the decision needs could not be read.
  */
enum Decision:
  case Allow
  case Refuse(reason: String)
  case Unavailable(reason: String)

/** Who asks: the principal the verified handle names, and the context its replica minted it with
  * ([[MintedHandles]]); None for superusers and the system principal, who need none.
  */
final case class DecidePrincipal(principal: NodePrincipal, context: Option[DecideContext])

/** The decision pipeline (spec Section 6, decisions 15 to 19): judges what qod-node resolved for
  * one statement, a bound plan ([[Description]]) or the locations its parse screen found
  * ([[ScreenDescription]]), for the principal of its handle.
  *
  * Order: superusers and the system principal are allowed; then the rules that hold whatever the
  * grants say (DuckLake catalog metadata, the protected kinds' DDL, the system catalog, function
  * and setting classes, control steps); then every file location (`LocationChecker` on the
  * principal's rules: its own S3 credential scopes, the pool tenant's folders, read once and only
  * when a location needs them); then a branch-only token's writes, and file writes under a token's
  * verb ceiling; then the objects, by QoD grants (always: decision 5) or by the tenant's OPA
  * policy. Local rules first, so OPA is never asked about a statement QoD refuses.
  *
  * Reasons carry no `"QoD: "` prefix; the node adds it.
  */
final class AuthzDecider(
    tenantOf: String => Option[Tenant],
    tenantCatalogs: String => Set[String],
    poolCatalog: PoolKey => Option[String],
    parentPoolsOf: PoolKey => List[String],
    locations: LocationRulesSource,
    opa: OpaAuthorizer,
    opaCfg: OpaConfig,
    functionClass: (String, String, String, String) => Option[FunctionClass] = FunctionClasses.of,
    settingClass: String => Option[SettingClass] = SettingClasses.of
) extends LazyLogging:
  import AuthzDecider.*

  /** A function's class. `FunctionClasses.of` (Task 2) reads catalog "" and schema "" (a COPY
    * FROM's reader, bound without a catalog) as the built-in `system.main`.
    */
  private def classOf(f: DescribedFunction): Option[FunctionClass] =
    functionClass(f.catalog, f.schema, f.name, f.tpe)

  private type Step = Either[Decision, Unit]
  private val Pass: Step                   = Right(())
  private def refuse(reason: String): Step = Left(Decision.Refuse(reason))

  private def all[A](xs: List[A])(f: A => Step): Step =
    xs.foldLeft(Pass)((acc, x) => acc.flatMap(_ => f(x)))

  def decidePlan(p: DecidePrincipal, d: Description): Decision =
    // Defense in depth: a dry run's plan is judged by dryRunPlan, whose caller never allows it.
    if p.context.exists(_.purpose == DecidePurpose.DryRun) then Decision.Refuse(DryRunReason)
    else plan(p, d, None)

  /** [[decidePlan]] of a dry run's statement (decision 25): the same rules, with the tenant's OPA
    * asked past its decision cache ([[OpaAuthorizer.decideDryRun]]). Returns the rules' decision
    * and the OPA call's outcome, None when they decided without asking OPA. [[DecideRoute]], the
    * caller, never answers it an allow.
    */
  def dryRunPlan(p: DecidePrincipal, d: Description): (Decision, Option[DryRunOutcome]) =
    val asked    = new java.util.concurrent.atomic.AtomicReference[Option[DryRunOutcome]](None)
    val decision = plan(p, d, Some(o => asked.set(Some(o))))
    (decision, asked.get())

  /** The plan rules; `dryRun` set asks OPA past its cache and is told the call's outcome. */
  private def plan(
      p: DecidePrincipal,
      d: Description,
      dryRun: Option[DryRunOutcome => Unit]
  ): Decision =
    judged(p) { ctx =>
      val rules      = new Rules(p, ctx)
      val writesFile = d.control.exists(_.op == "copy_to")
      val writes     = d.objects.exists(writesData) || writesFile
      for
        _ <- all(d.objects)(alwaysOnObject)
        _ <- all(d.objects)(historyUnderPolicies(ctx, _))
        _ <- all(d.functions)(alwaysOnFunction)
        _ <- all(d.settings)(alwaysOnSetting)
        _ <- all(d.control)(alwaysOnControl)
        _ <- viaBoundaries(d)
        _ <- all(d.functions)(fileFunction(_, rules))
        _ <- all(d.control)(copyTarget(_, rules))
        _ <- branchOnly(ctx, writes)
        _ <- fileWriteCeiling(ctx, writesFile)
        _ <- objects(ctx, narrowed(ctx), d, rules, dryRun)
      yield ()
    }

  /** The statistics population of each search call of `d` (search spec 4.2 rules 5 and 8, 7.5), for
    * a plan [[decidePlan]] allowed, on `pool`, the handle's pool. `Right(Nil)` for a plan holding
    * no search call. The population only: the index record is not the verdict's (spec ruling 18),
    * and every principal takes it from its own route.
    *
    * A superuser or the system principal is judged nothing and gets the table population (rule 8);
    * [[DecideRoute]] never asks for them, as the node never asks it for their plans. Any other
    * principal: each search column must be an object read of `d` the plan rules judged (a direct
    * read of that table, not through a secure view, so judged with the principal's own rights), and
    * rule 5 refuses a search column a column policy (mask or deny) covers for them; then the
    * population is `allowed` when a row policy of theirs may cover the table (its table part is `*`
    * or the table's name, whatever its catalog and schema), OPA tenants included (the gateway
    * applies QoD row policies whatever the tenant's mode), else `table`. Never a caller's choice,
    * and never absent for a call: a `Left` is a refusal or an unavailable decision.
    */
  def searchPopulations(
      p: DecidePrincipal,
      d: Description,
      pool: PoolKey
  ): Either[Decision, List[SearchPopulation]] =
    val calls = d.searches.map(_.call)
    if d.searches.isEmpty then Right(Nil)
    else if calls.sorted != calls.indices.toList then
      malformed("search calls are not numbered 0 to n-1")
    else
      val privileged = p.principal.superuser || p.principal.system
      val judge: Either[Decision, Option[EffectiveSet]] =
        if privileged then Right(None)
        else
          p.context match
            case None                             => Left(Decision.Unavailable(ContextExpired))
            case Some(ctx) if ctx.poolKey != pool =>
              Left(Decision.Refuse("the handle's pool is not its context's"))
            case Some(ctx) => Right(Some(narrowed(ctx)))
      judge.flatMap { eff =>
        d.searches.sortBy(_.call).foldLeft[Either[Decision, List[SearchPopulation]]](Right(Nil)) {
          (acc, s) => acc.flatMap(done => searchPopulation(eff, d, s).map(done :+ _))
        }
      }

  private def searchPopulation(
      eff: Option[EffectiveSet],
      d: Description,
      s: DescribedSearch
  ): Either[Decision, SearchPopulation] =
    def refused(reason: String) = Left(Decision.Refuse(reason))
    // -1: Java's split drops trailing empty parts, so "t." would read as "t" (review m1).
    s.table.split("\\.", -1) match
      case Array(cat, sch, tab) if cat.nonEmpty && sch.nonEmpty && tab.nonEmpty =>
        if s.columns.isEmpty || s.columns.exists(_.isEmpty) then
          malformed(s"a search call on ${s.table} names no column")
        else
          eff match
            // A superuser or the system principal: nothing judged (rule 8).
            case None    => Right(SearchPopulation(s.call, SearchPopulation.Table))
            case Some(e) =>
              // Each search column is an object read the plan rules judged as the principal's:
              // the node describes every search column so (rule 5); a description that does not
              // is refused, never taken on the call's word.
              def judgedRead(column: String) = d.objects.exists(o =>
                o.kind == "table" && o.access == "read" && o.via.isEmpty && !o.dropped &&
                  o.catalog.equalsIgnoreCase(cat) && o.schema.equalsIgnoreCase(sch) &&
                  o.name.equalsIgnoreCase(tab) && o.columns.exists(_.equalsIgnoreCase(column))
              )
              // Rule 5 fails closed on the table part alone, whatever the policy's catalog and
              // schema, like the population's match below (review m2): a column policy that may
              // cover the table refuses its column as a search column.
              def masked(column: String) = e.columnPolicies.exists(cp =>
                rowPolicyMayCover(cp.tableName, tab) &&
                  (cp.columnName == "*" || cp.columnName.equalsIgnoreCase(column))
              )
              // On the table part alone, whatever the policy's catalog and schema (spec 7.5,
              // P9): `allowed` over an unfiltered input discloses nothing, so the over-match (a
              // same-named table of another schema or catalog) costs only the statistics' scope.
              val rowPolicy = e.rowPolicies.exists(rp => rowPolicyMayCover(rp.tableName, tab))
              s.columns.find(c => !judgedRead(c)) match
                case Some(column) =>
                  malformed(
                    s"search column $column of ${s.table} is not an object read of the plan"
                  )
                case None =>
                  s.columns.find(masked) match
                    case Some(column) =>
                      refused(s"search column $column carries a column policy")
                    case None =>
                      Right(
                        SearchPopulation(
                          s.call,
                          if rowPolicy then SearchPopulation.Allowed else SearchPopulation.Table
                        )
                      )
      case _ =>
        malformed(s"search table '${s.table}' is not catalog.schema.table")

  /** A search description the decider cannot read (Task 5 review M3, Task 10), worded apart from
    * the route's own "malformed description" (a description that does not decode, a refusal), so
    * each text keeps one class: qod-node writes every search call numbered 0 to n-1, on a
    * catalog.schema.table, with its columns as direct object reads of the plan, so another one
    * points to a node bug, never to the principal's denial. It takes the answer of a decision that
    * cannot be made, [[Decision.Unavailable]] (the node's "authorization unavailable, retry",
    * Flight UNAVAILABLE), never a refusal, which the router would book as a denial (UNAUTHORIZED, a
    * data-denial audit event). The statement is refused either way: nothing runs.
    */
  private def malformed(why: String): Left[Decision, Nothing] =
    Left(Decision.Unavailable(s"unreadable search description: $why"))

  /** Whether a policy whose table part is `policyTable` may cover a table named `table`, for the
    * statistics population and rule 5's column-policy refusal: the table part as
    * [[PolicyCoverage.covers]] reads it (`*`, or the same name ignoring case), the catalog and
    * schema ignored, so it over-includes and both uses fail closed.
    */
  private def rowPolicyMayCover(policyTable: String, table: String): Boolean =
    PolicyCoverage.covers(RowPolicyAny, RowPolicyAny, policyTable, "", "", table)

  def decideScreen(p: DecidePrincipal, d: ScreenDescription): Decision =
    judged(p) { ctx =>
      val rules = new Rules(p, ctx)
      for
        _ <- all(d.locations)(screenLocation(_, rules))
        _ <- branchOnly(ctx, d.locations.exists(_.access == "write"))
        _ <- fileWriteCeiling(ctx, d.locations.exists(_.access == "write"))
      yield ()
    }

  /** How long a plan verdict may be cached: the effective set's 60 s, or the OPA decision cache's
    * TTL (at least [[MinOpaLifetimeMs]]) for an OPA tenant.
    */
  def lifetimeMs(p: DecidePrincipal): Long =
    if p.context.exists(c => isOpa(c.poolKey.tenant)) then
      math.max(opaCfg.cacheTtlSec * 1000L, MinOpaLifetimeMs)
    else GrantLifetimeMs

  /** The tenant's authorization mode as the decision metrics label it: `opa` or `qod`. A tenant the
    * control plane cannot read is labelled as one it does not hold: the manager-wide default mode.
    */
  def mode(tenantId: String): String =
    val opaMode =
      try isOpa(tenantId)
      catch case NonFatal(_) => TenantAcl().isOpa(opaCfg.defaultMode)
    if opaMode then "opa" else "qod"

  private def judged(p: DecidePrincipal)(rules: DecideContext => Step): Decision =
    if p.principal.superuser || p.principal.system then Decision.Allow
    else
      p.context match
        case None      => Decision.Unavailable(ContextExpired)
        case Some(ctx) => rules(ctx).fold(identity, _ => Decision.Allow)

  private def narrowed(ctx: DecideContext): EffectiveSet =
    Attenuation.attenuatedBy(
      ctx.effective,
      ctx.restriction.getOrElse(TokenRestriction.Unrestricted)
    )

  private def isOpa(tenantId: String): Boolean =
    tenantOf(tenantId).map(_.acl).getOrElse(TenantAcl()).isOpa(opaCfg.defaultMode)

  /** The principal's location rules on the pool's tenant (its own S3 scopes, the tenant's folders,
    * decision 23), read at most once per decision.
    */
  private final class Rules(p: DecidePrincipal, ctx: DecideContext):
    lazy val value: Either[Decision, LocationRules] =
      if !p.principal.tenant.contains(ctx.poolKey.tenant) then
        Left(
          Decision.Refuse(
            s"a principal of another tenant names locations on a pool of tenant " +
              s"'${ctx.poolKey.tenant}'"
          )
        )
      else
        // A store failure, as a Left or thrown, makes the decision unavailable: never a verdict.
        try locations.rulesFor(ctx.poolKey.tenant, p.principal.id).left.map(Decision.Unavailable(_))
        catch
          case NonFatal(e) =>
            logger.warn(
              s"authz decide: location rules of ${p.principal.name} unreadable " +
                s"(${e.getClass.getSimpleName})"
            )
            Left(Decision.Unavailable(RulesUnavailable))

    def check(location: String): Step =
      value.flatMap(r => LocationChecker.check(location, r).left.map(Decision.Refuse(_)))

  // ---- rule 2 ----

  private def alwaysOnObject(o: DescribedObject): Step =
    if !ObjectKinds.contains(o.kind) then
      refuse(s"the description names an unknown object kind '${o.kind}'")
    else if !Accesses.contains(o.access) then
      refuse(s"the description names an unknown access '${o.access}'")
    else if o.catalog.toLowerCase(Locale.ROOT).startsWith(DuckLakeMetadataPrefix) then
      refuse(s"${o.qualified} is DuckLake catalog metadata, which only superusers reach")
    else if ProtectedKinds.contains(o.kind) && DdlAccesses.contains(o.access) then
      refuse(s"${o.access} ${o.kind.replace('_', ' ')} ${o.qualified} is superuser-only")
    else if o.catalog.equalsIgnoreCase("system") && o.access != "read" then
      refuse(s"${o.access} in the system catalog is superuser-only")
    else if o.via.isDefined && o.access != "read" then
      refuse(
        s"${o.qualified} is reached through secure view ${o.via.getOrElse("")} by a ${o.access}"
      )
    else Pass

  /** A DuckLake read across a change of the table (review M1, owner rulings): `renamed` (the
    * statement wrote a name the table, or a column read, had at that snapshot and has no longer),
    * `dropped` (a table dropped since, read under its last live name) or `replaced` (a table
    * replaced since, read under its successor's name). The node describes it under the names the
    * grants and OPA judge, but the column and row policy rewriters ran before it on the names the
    * statement wrote, against the tables as they are now, so they matched another table's policies,
    * or none. Refused to a principal holding any column or row policy (the rewriters' and the
    * protected-write guard's switches are not known here: the refusal does not depend on them).
    */
  private def historyUnderPolicies(ctx: DecideContext, o: DescribedObject): Step =
    val eff      = ctx.effective
    val policies = eff.columnPolicies.nonEmpty || eff.rowPolicies.nonEmpty
    val why      =
      if o.renamed then Some(s"${o.qualified} was named otherwise at the snapshot it is read at")
      else if o.dropped then Some(s"${o.qualified} is read as it was before it was dropped")
      else if o.replaced then Some(s"${o.qualified} is read as it was before it was replaced")
      else None
    why.filter(_ => policies) match
      case Some(reason) =>
        refuse(
          s"$reason, and column and row policies match the names a statement writes; not " +
            "available to principals with column or row policies"
        )
      case None => Pass

  private def alwaysOnFunction(f: DescribedFunction): Step =
    if !FunctionTypes.contains(f.tpe) then
      refuse(s"the description names an unknown function type '${f.tpe}'")
    else
      classOf(f) match
        case None => refuse(s"function ${f.name} is not classified")
        case Some(FunctionClass.Superuser) if f.via.isEmpty =>
          refuse(s"function ${f.name} is superuser-only")
        case _ => Pass

  private def alwaysOnSetting(s: DescribedSetting): Step =
    if s.scope == "variable" then Pass
    else
      settingClass(s.name) match
        case None                         => refuse(s"setting ${s.name} is not classified")
        case Some(SettingClass.Superuser) => refuse(s"setting ${s.name} is superuser-only")
        case Some(SettingClass.Session)   =>
          if s.scope == "global" then refuse(s"setting ${s.name} at global scope is superuser-only")
          else if s.scope == "session" || s.scope == "local" then Pass
          else refuse(s"the description names an unknown setting scope '${s.scope}'")

  /** Defense in depth behind the describer: every `via`, of an object or a function, must lead to a
    * secure view the description itself reads (its boundary object, kind `secure_view`, access
    * `read`). The view's grant is checked on that object, so a function or object exempted through
    * a view the description does not list would be judged without it.
    */
  private def viaBoundaries(d: Description): Step =
    def low(s: String): String = s.toLowerCase(Locale.ROOT)
    val read                   =
      d.objects
        .filter(o => o.kind == "secure_view" && o.access == "read")
        .map(o => low(o.qualified))
    val vias = d.objects.flatMap(_.via) ++ d.functions.flatMap(_.via)
    vias.find(v => !read.contains(low(outermostView(v, d)))) match
      case Some(v) => refuse(s"the description reads through secure view $v without reading it")
      case None    => Pass

  private def alwaysOnControl(c: DescribedControl): Step =
    if UserControl.contains(c.op) then Pass
    else if SuperuserControl.contains(c.op) then
      refuse(s"statement uses ${c.op}, which only superusers may run")
    else refuse(s"the description names an unknown control op '${c.op}'")

  // ---- rule 4 ----

  private def fileFunction(f: DescribedFunction, rules: Rules): Step =
    if f.via.isDefined || !classOf(f).contains(FunctionClass.File) then Pass
    else if f.args.isEmpty then refuse(s"function ${f.name} names no location")
    else
      all(f.args) {
        case None      => refuse(s"function ${f.name} names a location that is not a literal")
        case Some(loc) => rules.check(loc)
      }

  private def copyTarget(c: DescribedControl, rules: Rules): Step =
    if c.op != "copy_to" then Pass
    else c.target.fold(refuse("COPY TO names no location"))(rules.check)

  private def screenLocation(l: DescribedLocation, rules: Rules): Step =
    if l.access != "read" && l.access != "write" then
      refuse(s"the description names an unknown location access '${l.access}'")
    else if SuperuserVias.contains(l.via) then
      refuse(s"statement uses ${l.via}, which only superusers may run")
    else if l.via == "copy_from" || l.via == "copy_to" then rules.check(l.resolved)
    else
      functionClass(BuiltinCatalog, BuiltinSchema, l.via, "table") match
        case Some(FunctionClass.File) => rules.check(l.resolved)
        case None                     => refuse(s"function ${l.via} is not classified")
        case Some(_)                  => refuse(s"function ${l.via} is not a file function")

  // ---- rule 5 ----

  private def writesData(o: DescribedObject): Boolean =
    o.access != "read" && !o.catalog.equalsIgnoreCase("temp")

  private def branchOnly(ctx: DecideContext, writes: Boolean): Step =
    if writes && ctx.restriction.exists(_.branchOnly) &&
      !BranchNames.isBranchPool(ctx.poolKey.pool)
    then refuse(WriteRequiresBranch)
    else Pass

  /** A token's verb ceiling on file writes (a COPY TO, a write location). Attenuation clips the
    * ceiling into the permissions on objects and the OPA arm filters gated objects by it, but a
    * file is no object, so neither ever meets it: a read-only token would write any file its read
    * grants and the location rules admit. Both arms.
    */
  private def fileWriteCeiling(ctx: DecideContext, writesFile: Boolean): Step =
    val eff = narrowed(ctx)
    if writesFile && eff.verbCeiling.exists(c => !c.contains(Verb.Write)) then
      refuse(s"user:${eff.user.username} denied by token verb ceiling on file writes")
    else Pass

  // ---- rule 3 ----

  private def objects(
      ctx: DecideContext,
      eff: EffectiveSet,
      d: Description,
      rules: Rules,
      dryRun: Option[DryRunOutcome => Unit]
  ): Step =
    val gated = d.objects.filterNot(admittedWithoutGrant)
    if isOpa(ctx.poolKey.tenant) then policy(ctx, eff, d, gated, rules, dryRun)
    else
      val missing = gated.filterNot(granted(eff, sessionCatalogs(eff, ctx.poolKey), d, _))
      if missing.isEmpty then Pass
      else refuse(s"user:${eff.user.username} lacks grants on ${names(missing)}")

  /** The catalogs a wildcard catalog grant covers on this statement: the tenant-db names of the
    * user's tenant, plus the catalog the pool's node attaches its database as (`memory` for kind
    * memory, a `catalogAlias` or `dbName`), when the pool is the user's own tenant's. Per pool,
    * never tenant-wide: on a DuckLake pool the instance-wide `memory` catalog stays outside every
    * wildcard.
    */
  private def sessionCatalogs(eff: EffectiveSet, key: PoolKey): Set[String] =
    eff.user.tenant.map(tenantCatalogs).getOrElse(Set.empty) ++
      (if eff.user.tenant.contains(key.tenant) then poolCatalog(key) else None)

  /** Temporary objects, and reads in the system catalog. DuckDB's built-in views (pg_catalog's,
    * information_schema's, `sqlite_master`) never reach a description: the binder inlines them as
    * calls of the listing functions, so qod-node's describer judges them by name instead (it
    * refuses every one to a non-superuser but the four information_schema views the gateway's
    * metadata filter narrows).
    */
  private def admittedWithoutGrant(o: DescribedObject): Boolean =
    o.catalog
      .equalsIgnoreCase("temp") || (o.catalog.equalsIgnoreCase("system") && o.access == "read")

  private def granted(
      eff: EffectiveSet,
      sessionCatalogs: Set[String],
      d: Description,
      o: DescribedObject
  ): Boolean =
    o.via match
      case Some(view) =>
        viewParts(outermostView(view, d)).exists((c, s, n) =>
          GrantMatch.covers(eff.permissions, sessionCatalogs, c, s, n, Verb.Read)
        )
      case None if o.kind == "schema" =>
        GrantMatch.coversSchema(
          eff.permissions,
          sessionCatalogs,
          o.catalog,
          o.schema,
          verbOf(o.access)
        )
      case None =>
        GrantMatch.covers(
          eff.permissions,
          sessionCatalogs,
          o.catalog,
          o.schema,
          o.name,
          verbOf(o.access)
        )

  /** The outermost secure view of `via`'s chain: a nested view's boundary object (kind
    * `secure_view`) carries the next view out as its own `via`. A cycle stops the walk.
    */
  private def outermostView(via: String, d: Description): String =
    // Lowercased throughout: DuckDB resolves names case-insensitively.
    def low(s: String): String = s.toLowerCase(Locale.ROOT)
    val outer                  =
      d.objects.filter(_.kind == "secure_view").map(v => low(v.qualified) -> v.via.map(low)).toMap
    @scala.annotation.tailrec
    def up(view: String, seen: Set[String]): String =
      outer.get(view).flatten match
        case Some(next) if !seen.contains(next) => up(next, seen + view)
        case _                                  => view
    up(low(via), Set.empty)

  private def names(os: List[DescribedObject]): String =
    os.map(o => s"${o.qualified}:${o.access}").distinct.sorted.mkString(", ")

  private def policy(
      ctx: DecideContext,
      eff: EffectiveSet,
      d: Description,
      gated: List[DescribedObject],
      rules: Rules,
      dryRun: Option[DryRunOutcome => Unit]
  ): Step =
    tenantOf(ctx.poolKey.tenant) match
      case None    => refuse(s"tenant '${ctx.poolKey.tenant}' not found; deny")
      case Some(t) =>
        val outside = eff.verbCeiling.fold(List.empty[DescribedObject])(c =>
          gated.filterNot(o => c.contains(verbOf(o.access)))
        )
        // A `via` that names no `catalog.schema.name` covers nothing under QoD grants either:
        // refused here too, so OPA never judges a malformed secure view.
        val badVia =
          gated.filter(o => o.via.exists(v => viewParts(outermostView(v, d)).isEmpty))
        if outside.nonEmpty then
          refuse(s"user:${eff.user.username} denied by token verb ceiling on ${names(outside)}")
        else if badVia.nonEmpty then
          refuse(s"user:${eff.user.username} lacks grants on ${names(badVia)}")
        else
          // Lowercase names (as the gateway's statement input to OPA had them); `via` the
          // outermost view of its chain, the one whose grant QoD itself checks.
          def low(s: String): String = s.toLowerCase(Locale.ROOT)
          val accesses               = gated.map { o =>
            val schemaWide = o.kind == "schema"
            OpaAccess(
              low(o.catalog),
              low(o.schema),
              if schemaWide then "*" else low(o.name),
              o.kind,
              verbOf(o.access),
              o.columns.map(low),
              o.via.map(v => low(outermostView(v, d))),
              List("renamed" -> o.renamed, "dropped" -> o.dropped, "replaced" -> o.replaced)
                .collect { case (h, true) => h }
            )
          }
          val located = planLocations(d, rules)
          if accesses.isEmpty && located.isEmpty then Pass
          else
            val key  = ctx.poolKey
            val user =
              OpaUser(eff.user.username, eff.roles.map(_.name), eff.groups.map(_.name), eff.claims)
            val target = OpaTarget(key.tenant, key.tenantDb, key.pool, parentPoolsOf(key))
            val cls    = statementClass(gated, d)
            val answer = dryRun match
              case None         => opa.decide(t, target, user, eff.user.id, cls, accesses, located)
              case Some(record) =>
                val (input, decision) = opa.decideDryRun(t, target, user, cls, accesses, located)
                record(DryRunOutcome.of("statement", input, decision))
                decision
            answer match
              case PolicyDecision.Allow(_)                => Pass
              case PolicyDecision.Deny(reason, denied, _) =>
                val on = denied.toList
                  .map(a => s"${a.table.canonical}:${OpaInput.verbName(a.verb)}")
                  .sorted
                  .mkString(", ")
                refuse(s"user:${eff.user.username} denied by policy on $on: $reason")
              case PolicyDecision.Error(_) =>
                Left(Decision.Unavailable(OpaAuthorizer.UnavailableMessage))

  /** The locations OPA is told about, each with the scope or folder QoD admitted it under (rule 4
    * already passed every one, so the rules are read).
    */
  private def planLocations(d: Description, rules: Rules): List[OpaLocation] =
    def at(access: String, location: String, via: String): OpaLocation =
      val scope =
        rules.value.toOption.flatMap(LocationChecker.admittedBy(location, _)).getOrElse("")
      OpaLocation(access, location, scope, via)
    d.functions
      .filter(f => f.via.isEmpty && classOf(f).contains(FunctionClass.File))
      .flatMap(f => f.args.flatten.map(at("read", _, f.name))) ++
      d.control.filter(_.op == "copy_to").flatMap(_.target).map(at("write", _, "copy_to"))

  private def statementClass(gated: List[DescribedObject], d: Description): String =
    if gated.exists(o => verbOf(o.access) == Verb.Ddl) then "DDL"
    else if gated.exists(o => verbOf(o.access) == Verb.Write) || d.control.exists(_.op == "copy_to")
    then "WRITE"
    else "READ"

object AuthzDecider:

  /** A user statement whose handle's context this replica no longer holds (or never held). */
  val ContextExpired: String = "authorization context expired; retry"

  /** What a dry run's plan is answered when the rules allow it: refused, so it never runs. */
  val DryRunReason: String = "dry run"

  /** Any catalog or schema, for [[rowPolicyMayCover]]. */
  private val RowPolicyAny: String = "*"

  /** The location rules threw while being read (a store failure): the decision is unavailable. */
  val RulesUnavailable: String = "the location rules could not be read; retry"

  /** RoutedExecutor's branch-only refusal, word for word. */
  val WriteRequiresBranch: String =
    "access denied: write_requires_branch: this token may only write on a branch " +
      "(create_branch, then pass branch=<name>)"

  /** A plan verdict's lifetime under QoD grants: the effective set's cache TTL. */
  val GrantLifetimeMs: Long = 60_000L

  /** The shortest plan verdict for an OPA tenant: the node refuses a plan verdict that is already
    * expired, so an OPA cache TTL of 0 must not mean 0 here.
    */
  val MinOpaLifetimeMs: Long = 5_000L

  val ObjectKinds: Set[String] =
    Set("table", "view", "secure_view", "schema", "sequence", "index", "macro", "type", "trigger")
  val Accesses: Set[String] = Set("read", "insert", "update", "delete", "create", "drop", "alter")
  val FunctionTypes: Set[String] = Set("scalar", "aggregate", "window", "table", "pragma")

  /** Control steps any principal may take (`copy_to` only into an authorized location). */
  val UserControl: Set[String] =
    Set("transaction", "prepare", "execute", "deallocate", "explain_analyze", "copy_to")

  /** Every other control step of the contract: superusers only. */
  val SuperuserControl: Set[String] = Set(
    "attach",
    "detach",
    "load",
    "install",
    "create_secret",
    "drop_secret",
    "export",
    "copy_database",
    "pragma",
    "vacuum",
    "update_extensions",
    "checkpoint",
    "extension_operator"
  )

  private val DdlAccesses: Set[String]    = Set("create", "drop", "alter")
  private val ProtectedKinds: Set[String] = Set("macro", "trigger", "secure_view")
  private val SuperuserVias: Set[String]  = Set("attach", "import", "export", "load")
  private val DuckLakeMetadataPrefix      = "__ducklake_metadata_"
  private val BuiltinCatalog              = "system"
  private val BuiltinSchema               = "main"

  /** The catalog a pool's node attaches its database as, from the pool's kind and effective
    * metastore (as the spawn scripts and the router read them): DuckDB's built-in `memory` for kind
    * memory, else the `catalogAlias` or `dbName`; None when neither is set or the kind is unknown.
    */
  def attachedCatalog(kindWire: String, metastore: Map[String, String]): Option[String] =
    kindWire match
      case TenantDbKind.InMemory.wireValue                                     => Some("memory")
      case TenantDbKind.DuckLake.wireValue | TenantDbKind.DuckDbFile.wireValue =>
        Option(TenantDb.catalogAlias(metastore)).filter(_.nonEmpty)
      case _ => None

  /** A description access as a collapsed grant verb. */
  def verbOf(access: String): Verb = access match
    case "read"                         => Verb.Read
    case "insert" | "update" | "delete" => Verb.Write
    case _                              => Verb.Ddl

  /** A secure view's `catalog.schema.name`: exactly three non-empty parts. */
  private def viewParts(view: String): Option[(String, String, String)] =
    view.split('.') match
      case Array(c, s, n) if c.nonEmpty && s.nonEmpty && n.nonEmpty => Some((c, s, n))
      case _                                                        => None
