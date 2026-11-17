package ai.starlake.quack.edge.authz

import ai.starlake.quack.model.{SearchIndex, TenantDb}
import ai.starlake.quack.ondemand.state.SearchIndexStore
import cats.effect.IO
import cats.syntax.all.*
import com.typesafe.scalalogging.LazyLogging
import io.circe.{ACursor, Json}
import io.circe.parser.parse
import org.http4s.{HttpRoutes, Status}
import org.http4s.dsl.io.*

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/** `POST /api/internal/search/records` (search spec 4.2 rule 8, 13 "Bundle authenticity"; OWNER
  * RULING 2026-11-16, D4): a node fetches the index records of the DuckLake tables its statement
  * searches or builds, each with its bundle MAC key. Served by [[NodeInternalServer]] only, beside
  * [[CredentialsRoute]], never on the REST port, in OpenAPI, CLI parity or MCP. Every call
  * authenticates itself, in order, each failure answered before the next step reads anything:
  *
  *   1. `X-QoD-Node` and `X-QoD-Signature`, the lowercase hex HMAC-SHA256 of the body under that
  *      node's key, as for the credentials route ([[NodeCalls.serveIO]]), the MAC checked
  *      (constant-time) before the body is parsed;
  *   2. the body: `{"handle":"<text>","tables":[{"catalog","tableId","name"}, ...]}`, 1 to
  *      [[SearchRecordRoute.MaxTables]] tables, none twice, a table id of 0 or more (`name` is for
  *      errors only and is never read); anything else is 400 [[SearchRecordRoute.Malformed]];
  *   3. the handle verifies under the same key for the same node ([[NodeHandles.verify]]) and has
  *      not expired (no grace: the node sends its connection's current handle);
  *   4. the handle is one this replica minted for that node: a superuser's or a tenant user's in
  *      [[IssuedHandles]] (for that principal), the system principal's in [[SearchSystemHandles]]
  *      (minted at a call site that asks for records, so never a probe's), with the pool and the
  *      expiry recorded at mint equal to the handle's (the node holds the key that signs handles,
  *      so it could re-sign one naming another pool or a later expiry: security review I1). A nonce
  *      held for another node, principal, pool or expiry is 401; one this replica does not hold is
  *      503 [[SearchRecordRoute.ContextExpired]], retryable, as for the credentials route.
  *
  * Scope: taken from the pool recorded at mint (`scopeOf`, [[SearchRecordRoute.scopeResolver]] in
  * Main), never from the handle. A table is looked up only when its `catalog` is the catalog alias
  * of that pool's tenant-db (compared ASCII case folded, as DuckDB names catalogs), and then only
  * by that tenant-db's id and the table id ([[SearchIndexStore.find]]), never by its dotted name:
  * an alias is tenant-set free text. Any other catalog (another tenant's alias, `memory`, a
  * federated alias), and a pool `scopeOf` does not know, is answered in `missing` with no lookup.
  * Records are not per principal and the node fetches only after its verdict admitted the
  * statement, so nothing here is decided for the principal: the handle only proves which node of
  * which pool asks.
  *
  * Answers `200 text/plain` with `<base64url(JSON)>.<hex HMAC-SHA256(node key, JSON bytes)>` (the
  * verdicts' format), the JSON canonical: `{"expiresAt","issuedAt","missing":[{"catalog",
  * "tableId"}],"node","nonce","records":[{"bundleRoot","catalog","fields":[{"column","fieldId"}],
  * "indexId","macKey","segmentsVersion","state","table","tableId"}],"v":1}`, `nonce` the handle's,
  * `expiresAt` [[SearchRecordRoute.RecordLifetimeMs]] after `issuedAt` (the node keeps an answer
  * for that lifetime from receipt, so the clocks need not agree), `catalog` as the node sent it,
  * `bundleRoot` the tenant-db's data path at create (the node uses a record only when it equals the
  * catalog's own DuckLake data path, security review M1 and M2), `table` the index's name at create
  * (display only), `macKey` 64 hex digits of [[NodeKeys.searchBundleMacKey]]. No k1 or b (D10): a
  * record is built from [[SearchIndex]], which carries none. A registry that cannot be read is 503
  * [[SearchRecordRoute.Unavailable]]. The keys travel signed, not encrypted, like users' S3 keys:
  * the verdict listener needs a private network. The log never holds the body, the handle, the
  * signature, the answer or a key.
  */
final class SearchRecordRoute(
    keys: NodeKeys,
    issued: IssuedHandles,
    system: SearchSystemHandles,
    scopeOf: String => Option[SearchRecordRoute.Scope],
    store: SearchIndexStore,
    clock: () => Long = () => System.currentTimeMillis()
) extends LazyLogging:
  import SearchRecordRoute.*

  private val refusals = new NodeCalls.RefusalLog(logger)

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "api" / "internal" / "search" / "records" =>
      NodeCalls.serveIO(req, MaxBodyBytes)(refused)(answer)
  }

  private def answer(nodeId: String, sig: String, body: Array[Byte]): IO[(Status, String)] =
    IO.defer {
      val key      = keys.key(nodeId)
      val expected = NodeHandles.hex(NodeHandles.hmac(key, body))
      if !MessageDigest.isEqual(
          expected.getBytes(StandardCharsets.US_ASCII),
          sig.getBytes(StandardCharsets.US_ASCII)
        )
      then IO.pure(refused(nodeId, "signature does not verify"))
      else
        requestOf(body) match
          case None      => IO.pure((Status.BadRequest, Malformed))
          case Some(req) =>
            val now = clock()
            NodeHandles.verify(req.handle, key, nodeId, now) match
              case Left(why)               => IO.pure(refused(nodeId, s"handle refused: $why"))
              case Right(h) if now > h.exp =>
                IO.pure(refused(nodeId, "handle refused: handle expired"))
              case Right(h) =>
                admitted(nodeId, h) match
                  case Left(refusal) => IO.pure(refusal)
                  case Right(pool)   => serve(nodeId, key, h, pool, req.tables)
    }

  /** The pool this replica minted `h` for, when it minted it for `nodeId`, its principal, that pool
    * and that expiry; else the refusal. The pool and the expiry are compared with what was recorded
    * at mint, since the node can re-sign a handle naming any pool or expiry with its own key
    * (security review I1, as `DecideRoute.consistent` binds the pool); the scope is then taken from
    * the recorded pool, so nothing the node signed picks it.
    */
  private def admitted(nodeId: String, h: VerifiedHandle): Either[(Status, String), String] =
    val p        = h.principal
    val recorded =
      if p.system then
        system
          .get(h.nonce)
          .map(r => (r.node == nodeId && r.pool == h.pool && r.expiresAt == h.exp, r.pool))
      else
        issued
          .get(h.nonce)
          .map(i =>
            (
              i.node == nodeId && i.principalId == p.id && i.pool == h.pool && i.expiresAt == h.exp,
              i.pool
            )
          )
    recorded match
      case Some((true, pool)) => Right(pool)
      case Some(_) => Left(refused(nodeId, "handle does not match the one this replica minted"))
      case None    => Left((Status.ServiceUnavailable, ContextExpired))

  private def serve(
      nodeId: String,
      key: Array[Byte],
      h: VerifiedHandle,
      pool: String,
      tables: List[Asked]
  ): IO[(Status, String)] =
    val scope = scopeOf(pool)
    tables
      .traverse { t =>
        scope.filter(s => asciiFolded(s.catalogAlias) == asciiFolded(t.catalog)) match
          case None    => IO.pure(t -> Option.empty[(Scope, SearchIndex)])
          case Some(s) => store.find(s.tenantDbId, t.tableId).map(ix => t -> ix.map(s -> _))
      }
      .attempt
      .map {
        case Left(e) =>
          logger.warn(
            s"search records: none served to node '$nodeId': the registry could not be read " +
              s"(${e.getClass.getSimpleName})"
          )
          (Status.ServiceUnavailable, Unavailable)
        case Right(found) =>
          val records = found.collect { case (t, Some((s, ix))) => record(t, s, ix) }
          val missing = found.collect { case (t, None) => missingEntry(t) }
          logger.info(
            s"search records: ${records.size} served, ${missing.size} missing, to node '$nodeId'"
          )
          (Status.Ok, signed(key, nodeId, h.nonce, records, missing))
      }

  private def record(t: Asked, s: Scope, ix: SearchIndex): Json =
    Json.obj(
      "catalog" -> Json.fromString(t.catalog),
      "tableId" -> Json.fromLong(ix.tableId),
      "table"   -> Json.fromString(
        asciiFolded(s"${s.catalogAlias}.${ix.schemaName}.${ix.tableName}")
      ),
      "indexId"    -> Json.fromString(ix.indexId.toString),
      "bundleRoot" -> Json.fromString(ix.bundleRoot),
      "fields"     -> Json.arr(ix.fields.map { f =>
        Json.obj("column" -> Json.fromString(f.column), "fieldId" -> Json.fromLong(f.fieldId))
      }*),
      "state"  -> Json.fromString(ix.state),
      "macKey" -> Json.fromString(
        NodeHandles.hex(keys.searchBundleMacKey(ix.indexId.toString))
      ),
      "segmentsVersion" -> Json.fromLong(ix.segmentsVersion)
    )

  private def missingEntry(t: Asked): Json =
    Json.obj("catalog" -> Json.fromString(t.catalog), "tableId" -> Json.fromLong(t.tableId))

  private def signed(
      key: Array[Byte],
      nodeId: String,
      nonce: String,
      records: List[Json],
      missing: List[Json]
  ): String =
    val now   = clock()
    val bytes = CanonicalJson
      .render(
        Json.obj(
          "v"         -> Json.fromInt(1),
          "node"      -> Json.fromString(nodeId),
          "nonce"     -> Json.fromString(nonce),
          "issuedAt"  -> Json.fromLong(now),
          "expiresAt" -> Json.fromLong(now + RecordLifetimeMs),
          "records"   -> Json.arr(records*),
          "missing"   -> Json.arr(missing*)
        )
      )
      .getBytes(StandardCharsets.UTF_8)
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes) + "." +
      NodeHandles.hex(NodeHandles.hmac(key, bytes))

  private def refused(nodeId: String, why: String): (Status, String) =
    refusals.refused(nodeId, s"search records: refused a call from node '$nodeId': $why")
    (Status.Unauthorized, why)

object SearchRecordRoute:

  val Path: String = "/api/internal/search/records"

  /** The handle's pool's tenant-db and the catalog alias its nodes attach it under. */
  final case class Scope(tenantDbId: String, catalogAlias: String)

  /** The largest request accepted: a handle is under a kilobyte, and 16 tables under 16 KiB. */
  val MaxBodyBytes: Int = 64 * 1024

  /** The most tables one call asks for. */
  val MaxTables: Int = 16

  /** The longest catalog alias or table name read. */
  private val MaxNameChars: Int = 1024

  /** How long a node keeps an answer, from when it receives it (`expiresAt - issuedAt`, so the
    * node's clock need not agree with this replica's): an index drop or recreate reaches nodes
    * within it.
    */
  val RecordLifetimeMs: Long = 10_000L

  /** Every refusal of a request body that is not the request's shape. */
  val Malformed: String = "malformed request body"

  /** A handle this replica did not mint, or no longer remembers: the other node routes' wording. */
  val ContextExpired: String = CredentialsRoute.ContextExpired

  /** The registry could not be read: retryable. */
  val Unavailable: String = "index records cannot be served now"

  /** The members of every record answered: no k1 or b (D10). */
  val RecordKeys: Set[String] =
    Set(
      "catalog",
      "tableId",
      "table",
      "indexId",
      "bundleRoot",
      "fields",
      "state",
      "macKey",
      "segmentsVersion"
    )

  private final case class Asked(catalog: String, tableId: Long)
  private final case class Req(handle: String, tables: List[Asked])

  /** `name` with ASCII letters lowercased and nothing else changed, as DuckDB and the node fold
    * catalog names (`toLowerCase` would also fold U+212A KELVIN SIGN to `k`: security review M1).
    */
  def asciiFolded(name: String): String =
    name.map(c => if c >= 'A' && c <= 'Z' then (c + 32).toChar else c)

  /** The scope of a handle's pool (`<tenant>/<tenantDb>/<pool>`, as `NodeHandles.pool` writes it):
    * the tenant-db `findTenantDb(tenant, tenantDb)` names and the alias `aliasOf` gives it (Main:
    * `TenantDb.catalogAlias` of its effective metastore). None for a pool string of another shape,
    * an empty part, or a tenant-db the supervisor does not know. A branch pool resolves to the
    * branch's own row, which holds no index, so its tables answer `missing`.
    */
  def scopeResolver(
      findTenantDb: (String, String) => Option[TenantDb],
      aliasOf: TenantDb => String
  ): String => Option[Scope] =
    pool =>
      pool.split("/", -1) match
        case Array(t, td, p) if t.nonEmpty && td.nonEmpty && p.nonEmpty =>
          findTenantDb(t, td)
            .map(row => Scope(row.id, aliasOf(row)))
            .filter(_.catalogAlias.nonEmpty)
        case _ => None

  private def requestOf(body: Array[Byte]): Option[Req] =
    parse(new String(body, StandardCharsets.UTF_8)).toOption.flatMap { json =>
      val c = json.hcursor
      for
        handle <- c.get[String]("handle").toOption
        items  <- c.downField("tables").values.map(_.toList)
        if items.nonEmpty && items.size <= MaxTables
        tables <- items.traverse(asked)
        if tables.map(t => (asciiFolded(t.catalog), t.tableId)).distinct.size == tables.size
      yield Req(handle, tables)
    }

  private def asked(json: Json): Option[Asked] =
    val c: ACursor = json.hcursor
    for
      catalog <- c.get[String]("catalog").toOption
      if catalog.nonEmpty && catalog.length <= MaxNameChars
      tableId <- c.get[Long]("tableId").toOption
      if tableId >= 0
      name <- c.get[String]("name").toOption
      if name.length <= MaxNameChars
    yield Asked(catalog, tableId)
