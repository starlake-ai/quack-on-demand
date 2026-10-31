package ai.starlake.quack.edge.opa

import ai.starlake.acl.parser.{TableAccess, Verb}
import io.circe.Json

/** Addressed pool. `parentPools` is non-empty only for a branch pool (`__br_<id8>`) and lists the
  * parent tenant-db's pool names, so a policy can mirror "a branch inherits any parent pool".
  */
final case class OpaTarget(
    tenant: String,
    tenantDb: String,
    pool: String,
    parentPools: List[String]
)

final case class OpaUser(
    name: String,
    roles: List[String],
    groups: List[String],
    claims: Map[String, String]
)

/** One access of a node decision: what DuckDB resolved, with the object's kind, the columns read
  * (sorted) and the secure view it comes through. `history`: for a DuckLake read at an earlier
  * snapshot that crossed a change of the table, which ones (`renamed`, `dropped`, `replaced`,
  * sorted); in the input only when non-empty. A plain earlier-snapshot or change-feed read of a
  * table that did not change carries none: the node cannot tell it from a current read.
  */
final case class OpaAccess(
    catalog: String,
    schema: String,
    table: String,
    kind: String,
    verb: Verb,
    columns: List[String],
    via: Option[String],
    history: List[String] = Nil
)

/** One file location a node decision names: `access` read or write, `scope` the principal's own S3
  * credential scope or the tenant folder that admitted it (decision 23), `via` the reader function
  * or `copy_to`.
  */
final case class OpaLocation(access: String, location: String, scope: String, via: String)

/** Builds the `{"input": ...}` documents of the OPA contract. Pure; golden-tested in OpaInputSpec:
  * any change here is a breaking change for every customer policy.
  */
object OpaInput:

  def verbName(v: Verb): String = v match
    case Verb.Read  => "read"
    case Verb.Write => "write"
    case Verb.Ddl   => "ddl"

  def sorted(accesses: Set[TableAccess]): List[TableAccess] =
    accesses.toList.sortBy(a => (a.table.canonical, verbName(a.verb)))

  private def accessJson(a: TableAccess): Json = Json.obj(
    "catalog" -> Json.fromString(a.table.database),
    "schema"  -> Json.fromString(a.table.schema),
    "table"   -> Json.fromString(a.table.table),
    "verb"    -> Json.fromString(verbName(a.verb))
  )

  private def common(kind: String, t: OpaTarget, u: OpaUser, edge: String): List[(String, Json)] =
    List(
      "kind"       -> Json.fromString(kind),
      "tenant"     -> Json.fromString(t.tenant),
      "database"   -> Json.fromString(t.tenantDb),
      "pool"       -> Json.fromString(t.pool),
      "parentPool" -> (if t.parentPools.isEmpty then Json.Null
                       else Json.arr(t.parentPools.sorted.map(Json.fromString)*)),
      "user" -> Json.obj(
        "name"   -> Json.fromString(u.name),
        "roles"  -> Json.arr(u.roles.sorted.map(Json.fromString)*),
        "groups" -> Json.arr(u.groups.sorted.map(Json.fromString)*),
        "claims" -> Json.obj(u.claims.toList.sortBy(_._1).map((k, v) => k -> Json.fromString(v))*)
      ),
      "client" -> Json.obj("edge" -> Json.fromString(edge))
    )

  def connect(target: OpaTarget, user: OpaUser, edge: String): Json =
    Json.obj("input" -> Json.obj(common("connect", target, user, edge)*))

  def statement(
      target: OpaTarget,
      user: OpaUser,
      edge: String,
      statementClass: String,
      accesses: Set[TableAccess],
      text: Option[String]
  ): Json =
    val stmt = Json.obj(
      (("class" -> Json.fromString(statementClass)) ::
        text.map(s => "text" -> Json.fromString(s)).toList)*
    )
    Json.obj(
      "input" -> Json.obj(
        (common("statement", target, user, edge) ++ List(
          "statement" -> stmt,
          "accesses"  -> Json.arr(sorted(accesses).map(accessJson)*)
        ))*
      )
    )

  /** `client.edge` of a node decision. */
  val NodeEdge: String = "node"

  private def decideAccessJson(a: OpaAccess): Json =
    val base = List(
      "catalog" -> Json.fromString(a.catalog),
      "schema"  -> Json.fromString(a.schema),
      "table"   -> Json.fromString(a.table),
      "verb"    -> Json.fromString(verbName(a.verb)),
      "kind"    -> Json.fromString(a.kind),
      "columns" -> Json.arr(a.columns.sorted.map(Json.fromString)*),
      "via"     -> a.via.fold(Json.Null)(Json.fromString)
    )
    val history =
      if a.history.isEmpty then Nil
      else List("history" -> Json.arr(a.history.sorted.map(Json.fromString)*))
    Json.obj((base ++ history)*)

  private def locationJson(l: OpaLocation): Json = Json.obj(
    "access"   -> Json.fromString(l.access),
    "location" -> Json.fromString(l.location),
    "scope"    -> Json.fromString(l.scope),
    "via"      -> Json.fromString(l.via)
  )

  /** The statement input of a node decision (in-engine authorization): the accesses DuckDB resolved
    * and the file locations the statement names, sorted. There is never statement text: the node
    * describes plans, not SQL.
    */
  def decide(
      target: OpaTarget,
      user: OpaUser,
      statementClass: String,
      accesses: List[OpaAccess],
      locations: List[OpaLocation]
  ): Json =
    val sortedAccesses =
      accesses.distinct.sortBy(a =>
        (s"${a.catalog}.${a.schema}.${a.table}", verbName(a.verb), a.via.getOrElse(""), a.kind)
      )
    val sortedLocations = locations.distinct.sortBy(l => (l.location, l.access, l.via))
    Json.obj(
      "input" -> Json.obj(
        (common("statement", target, user, NodeEdge) ++ List(
          "statement" -> Json.obj("class" -> Json.fromString(statementClass)),
          "accesses"  -> Json.arr(sortedAccesses.map(decideAccessJson)*),
          "locations" -> Json.arr(sortedLocations.map(locationJson)*)
        ))*
      )
    )
