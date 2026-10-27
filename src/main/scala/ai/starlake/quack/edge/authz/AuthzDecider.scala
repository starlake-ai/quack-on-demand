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
import ai.starlake.quack.edge.sql.GrantMatch
import ai.starlake.quack.model.{PoolKey, Tenant, TenantAcl}
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
  * when a location needs them); then a branch-only token's writes; then the objects, by QoD grants
  * (always: decision 5) or by the tenant's OPA policy. Local rules first, so OPA is never asked
  * about a statement QoD refuses.
  *
  * Reasons carry no `"QoD: "` prefix; the node adds it.
  */
final class AuthzDecider(
    tenantOf: String => Option[Tenant],
    tenantCatalogs: String => Set[String],
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
    judged(p) { ctx =>
      val rules  = new Rules(p, ctx)
      val writes = d.objects.exists(writesData) || d.control.exists(_.op == "copy_to")
      for
        _ <- all(d.objects)(alwaysOnObject)
        _ <- all(d.functions)(alwaysOnFunction)
        _ <- all(d.settings)(alwaysOnSetting)
        _ <- all(d.control)(alwaysOnControl)
        _ <- all(d.functions)(fileFunction(_, rules))
        _ <- all(d.control)(copyTarget(_, rules))
        _ <- branchOnly(ctx, writes)
        _ <- objects(ctx, narrowed(ctx), d, rules)
      yield ()
    }

  def decideScreen(p: DecidePrincipal, d: ScreenDescription): Decision =
    judged(p) { ctx =>
      val rules = new Rules(p, ctx)
      for
        _ <- all(d.locations)(screenLocation(_, rules))
        _ <- branchOnly(ctx, d.locations.exists(_.access == "write"))
      yield ()
    }

  /** How long a plan verdict may be cached: the effective set's 60 s, or the OPA decision cache's
    * TTL (at least [[MinOpaLifetimeMs]]) for an OPA tenant.
    */
  def lifetimeMs(p: DecidePrincipal): Long =
    if p.context.exists(c => isOpa(c.poolKey.tenant)) then
      math.max(opaCfg.cacheTtlSec * 1000L, MinOpaLifetimeMs)
    else GrantLifetimeMs

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
                s"(${e.getClass.getSimpleName}: ${e.getMessage})"
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

  // ---- rule 3 ----

  private def objects(ctx: DecideContext, eff: EffectiveSet, d: Description, rules: Rules): Step =
    val gated = d.objects.filterNot(admittedWithoutGrant)
    if isOpa(ctx.poolKey.tenant) then policy(ctx, eff, d, gated, rules)
    else
      val sessionCatalogs = eff.user.tenant.map(tenantCatalogs).getOrElse(Set.empty)
      val missing         = gated.filterNot(granted(eff, sessionCatalogs, d, _))
      if missing.isEmpty then Pass
      else refuse(s"user:${eff.user.username} lacks grants on ${names(missing)}")

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
    val outer = d.objects.filter(_.kind == "secure_view").map(v => v.qualified -> v.via).toMap
    @scala.annotation.tailrec
    def up(view: String, seen: Set[String]): String =
      outer.get(view).flatten match
        case Some(next) if !seen.contains(next) => up(next, seen + view)
        case _                                  => view
    up(via, Set.empty)

  private def names(os: List[DescribedObject]): String =
    os.map(o => s"${o.qualified}:${o.access}").distinct.sorted.mkString(", ")

  private def policy(
      ctx: DecideContext,
      eff: EffectiveSet,
      d: Description,
      gated: List[DescribedObject],
      rules: Rules
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
          // Lowercase names, as the gateway's TableRef gives OPA; `via` the outermost view of
          // its chain, the one whose grant QoD itself checks.
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
              o.via.map(v => low(outermostView(v, d)))
            )
          }
          val located = planLocations(d, rules)
          if accesses.isEmpty && located.isEmpty then Pass
          else
            val key  = ctx.poolKey
            val user =
              OpaUser(eff.user.username, eff.roles.map(_.name), eff.groups.map(_.name), eff.claims)
            opa.decide(
              t,
              OpaTarget(key.tenant, key.tenantDb, key.pool, parentPoolsOf(key)),
              user,
              eff.user.id,
              statementClass(gated, d),
              accesses,
              located
            ) match
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
