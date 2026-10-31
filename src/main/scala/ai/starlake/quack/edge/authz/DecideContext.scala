package ai.starlake.quack.edge.authz

import ai.starlake.quack.model.PoolKey
import ai.starlake.quack.ondemand.auth.TokenRestriction
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import io.circe.Json

/** Why a session's statements are decided (decision 25). `Live` statements run on an allow.
  * `DryRun` statements (the OPA dry run, `qod tenant opa-test` with SQL) are judged and never run:
  * the decision endpoint never answers their plans an allow ([[DecideRoute]]).
  */
enum DecidePurpose:
  case Live, DryRun

/** What the manager decides a statement's verdicts with: the effective set the session's handle was
  * minted for, its pool, a personal access token's restriction, and why it is decided. The handle
  * carries the SHA-256 of [[json]] as its `ctx` ([[NodeHandles.contextDigest]]), and nodes key
  * cached verdicts by it, so two sessions share a verdict only when every input below agrees.
  */
final case class DecideContext(
    effective: EffectiveSet,
    poolKey: PoolKey,
    restriction: Option[TokenRestriction],
    purpose: DecidePurpose
)

object DecideContext:

  /** The canonical session context: everything of `c` a decision can read except what varies per
    * token without changing authority (a PAT's expiry, statement timeout and row limit). Role and
    * group ids, not names: a rename bumps the tenant's policy version. `purpose` appears only for a
    * dry run, so a live context's digest is the one it always was and a dry run never shares a
    * cached verdict with a live session.
    */
  def json(c: DecideContext): Json =
    val e                                   = c.effective
    def strings(xs: Iterable[String]): Json = CanonicalJson.sortedArray(xs.map(Json.fromString))
    def optStrings(xs: Option[Iterable[String]]): Json = xs.fold(Json.Null)(strings)
    val purpose                                        = c.purpose match
      case DecidePurpose.Live   => Nil
      case DecidePurpose.DryRun => List("purpose" -> Json.fromString("dryRun"))
    Json.obj(
      (List(
        "claims"         -> Json.obj(e.claims.toList.map((k, v) => k -> Json.fromString(v))*),
        "columnPolicies" -> CanonicalJson.sortedArray(e.columnPolicies.map { p =>
          Json.obj(
            "action"    -> Json.fromString(p.action),
            "catalog"   -> Json.fromString(p.catalogName),
            "column"    -> Json.fromString(p.columnName),
            "schema"    -> Json.fromString(p.schemaName),
            "table"     -> Json.fromString(p.tableName),
            "transform" -> p.transformSql.fold(Json.Null)(Json.fromString)
          )
        }),
        "groups"      -> strings(e.groups.map(_.id)),
        "permissions" -> CanonicalJson.sortedArray(e.permissions.map { p =>
          Json.obj(
            "catalog" -> Json.fromString(p.catalogName),
            "schema"  -> Json.fromString(p.schemaName),
            "table"   -> Json.fromString(p.tableName),
            "verb"    -> Json.fromString(p.verb)
          )
        }),
        "pool" -> Json.fromString(NodeHandles.pool(c.poolKey))
      ) ++ purpose ++ List(
        "restriction" -> c.restriction.fold(Json.Null) { r =>
          Json.obj(
            "branchOnly"  -> Json.fromBoolean(r.branchOnly),
            "databases"   -> optStrings(r.databases),
            "dropAdmin"   -> Json.fromBoolean(r.dropAdmin),
            "pools"       -> optStrings(r.pools),
            "roles"       -> optStrings(r.roles),
            "tools"       -> optStrings(r.tools),
            "verbCeiling" -> r.verbCeiling.fold(Json.Null)(Json.fromString)
          )
        },
        "roles"       -> strings(e.roles.map(_.id)),
        "rowPolicies" -> CanonicalJson.sortedArray(e.rowPolicies.map { p =>
          Json.obj(
            "catalog"   -> Json.fromString(p.catalogName),
            "predicate" -> Json.fromString(p.predicateSql),
            "schema"    -> Json.fromString(p.schemaName),
            "table"     -> Json.fromString(p.tableName)
          )
        }),
        "user"        -> Json.fromString(e.user.id),
        "verbCeiling" -> optStrings(e.verbCeiling.map(_.map(_.toString)))
      ))*
    )
