package ai.starlake.quack.edge.authz

import ai.starlake.quack.model.{
  PoolKey,
  Role,
  RunningNode,
  SearchIndex,
  SearchIndexField,
  Tenant,
  TenantDb,
  TenantDbKind
}
import ai.starlake.quack.ondemand.state.{
  LiquibaseRunner,
  PostgresControlPlaneStore,
  SearchIndexStore
}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.{HCursor, Json}
import io.circe.parser.parse
import org.http4s.{Header, Method, Request, Status}
import org.http4s.implicits.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIString

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.{Base64, UUID}
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import scala.util.Try

/** `POST /api/internal/search/records` (search spec 4.2 rule 8, 13; Task 7) over a fake store and
  * real keys: signed both ways, bound to a handle this replica minted for the asking node, a pool's
  * own catalog only, and no k1 or b in any answer (D10).
  */
class SearchRecordRouteSpec extends AnyFlatSpec with Matchers:

  private val Secret                          = "qod-search-record-route-spec-secret-0123456789"
  private val Pool                            = PoolKey("acme", "lake", "bi")
  private val PoolB                           = PoolKey("globex", "lake", "bi")
  private def node(id: String, pool: PoolKey) =
    RunningNode(id, pool, Role.Dual, "127.0.0.1", 21900, "tok", None, None, Instant.EPOCH)
  private val Node      = node("quack-acme-lake-bi-1", Pool)
  private val OtherNode = node("quack-acme-lake-bi-2", Pool)
  private val NodeB     = node("quack-globex-lake-bi-1", PoolB)
  private val Key       = NodeHandles.nodeKey(Secret, Node.nodeId)
  private val Alice = NodePrincipal("u-1", "alice", Some("acme"), superuser = false, system = false)
  private val Root  = NodePrincipal("u-root", "root", None, superuser = true, system = false)

  private val PagesId = UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100")
  private val NotesId = UUID.fromString("1f1e1d1c-1b1a-4918-9716-151413121110")
  private def index(id: UUID, td: String, table: String, tableId: Long, state: String) =
    SearchIndex(
      indexId = id,
      tenantDbId = td,
      schemaName = "main",
      tableName = table,
      tableId = tableId,
      fields = List(SearchIndexField("title", 2L), SearchIndexField("body", 3L)),
      bundleRoot = "s3://lake/acme_lake/",
      state = state,
      segmentsVersion = 4L,
      createdBy = "alice",
      createdAt = Instant.EPOCH
    )

  /** A store holding `indexes`, counting every lookup. */
  private final class FakeStore(indexes: List[SearchIndex]) extends SearchIndexStore:
    val lookups                                                          = new AtomicInteger(0)
    @volatile var fail: Boolean                                          = false
    def find(tenantDbId: String, tableId: Long): IO[Option[SearchIndex]] = IO {
      lookups.incrementAndGet()
      if fail then throw new RuntimeException("pg down at 10.0.0.7")
      indexes.find(i => i.tenantDbId == tenantDbId && i.tableId == tableId)
    }
    def create(ix: SearchIndex)                = IO.raiseError(new UnsupportedOperationException)
    def list(tenantDbIds: Set[String])         = IO.raiseError(new UnsupportedOperationException)
    def findByIndexId(indexId: UUID)           = IO.raiseError(new UnsupportedOperationException)
    def delete(indexId: UUID)                  = IO.raiseError(new UnsupportedOperationException)
    def setState(indexId: UUID, state: String) = IO.raiseError(new UnsupportedOperationException)
    def deleteForTenantDb(tenantDbId: String)  = IO.raiseError(new UnsupportedOperationException)
    def recordSegment(
        indexId: UUID,
        pathKey: String,
        path: String,
        bytes: Long,
        docs: Long,
        buildMs: Long,
        formatVersion: Int
    ) = IO.raiseError(new UnsupportedOperationException)

  private val Scopes: Map[String, SearchRecordRoute.Scope] = Map(
    NodeHandles.pool(Pool)  -> SearchRecordRoute.Scope("td-acme-lake", "acme_lake"),
    NodeHandles.pool(PoolB) -> SearchRecordRoute.Scope("td-globex-lake", "globex_lake")
  )

  private final class Fixture(
      store: SearchIndexStore = new FakeStore(
        List(
          index(PagesId, "td-acme-lake", "pages", 12L, SearchIndex.Ready),
          index(NotesId, "td-acme-lake", "notes", 13L, SearchIndex.Disabled)
        )
      ),
      secret: String = Secret
  ):
    val clock  = new AtomicLong(1_761_200_000_000L)
    val issued = new IssuedHandles(maxEntries = 200_000L, clock = () => clock.get())
    val system = new SearchSystemHandles(clock = () => clock.get())
    val keys   = new NodeKeys(
      secret,
      "http://127.0.0.1:20901",
      () => clock.get(),
      issued = Some(issued),
      readOnlyCatalogs = ReadOnlyCatalogs.none,
      versions = PolicyVersions.zero
    )
    val route = new SearchRecordRoute(keys, issued, system, Scopes.get, store, () => clock.get())

    def handle(p: NodePrincipal = Alice, on: RunningNode = Node): MintedHandle = keys.mint(on, p)

    def call(
        body: Array[Byte],
        nodeId: String = Node.nodeId,
        signature: Option[String] = None
    ): (Status, String) =
      val key = NodeHandles.nodeKey(Secret, nodeId)
      val req = Request[IO](Method.POST, uri"/api/internal/search/records")
        .withEntity(body)
        .putHeaders(
          Header.Raw(CIString("X-QoD-Node"), nodeId),
          Header.Raw(
            CIString("X-QoD-Signature"),
            signature.getOrElse(NodeHandles.hex(NodeHandles.hmac(key, body)))
          )
        )
      val resp = route.routes.orNotFound.run(req).unsafeRunSync()
      (resp.status, resp.as[String].unsafeRunSync())

  private def table(catalog: String, id: Long, name: String = "x"): Json =
    Json.obj(
      "catalog" -> Json.fromString(catalog),
      "tableId" -> Json.fromLong(id),
      "name"    -> Json.fromString(name)
    )

  private def body(handle: String, tables: Json*): Array[Byte] =
    CanonicalJson
      .render(Json.obj("handle" -> Json.fromString(handle), "tables" -> Json.arr(tables*)))
      .getBytes(UTF_8)

  private val Pages = table("acme_lake", 12L, "acme_lake.main.pages")

  /** The answer's JSON, after checking its MAC under `key`. */
  private def answer(text: String, key: Array[Byte] = Key): (String, HCursor) =
    text.split('.').toList match
      case List(b64, mac) =>
        val bytes = Base64.getUrlDecoder.decode(b64)
        mac shouldBe NodeHandles.hex(NodeHandles.hmac(key, bytes))
        val json = new String(bytes, UTF_8)
        (json, parse(json).toOption.get.hcursor)
      case other => fail(s"not a signed answer: $other")

  /** `handle` with its payload changed by `change`, signed again with `key`: what a node that read
    * its own key can send (security review I1).
    */
  private def resigned(handle: String, key: Array[Byte])(change: Json => Json): String =
    val payload = HandleInspector.payload(handle)
    val bytes   = change(payload).noSpaces.getBytes(UTF_8)
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes) + "." +
      NodeHandles.hex(NodeHandles.hmac(key, bytes))

  private def withPool(pool: PoolKey): Json => Json =
    _.mapObject(_.add("pool", Json.fromString(NodeHandles.pool(pool))))

  private def holdsNoRecord(text: String): Unit =
    text should not include "macKey"
    text should not include "bundleRoot"

  "a signed call" should "answer the record, signed, with the index's MAC key and the handle's nonce" in {
    val f       = new Fixture
    val h       = f.handle()
    val (st, t) = f.call(body(h.text, Pages))
    st shouldBe Status.Ok
    val (json, c) = answer(t)
    json shouldBe CanonicalJson.render(parse(json).toOption.get)
    c.get[Int]("v") shouldBe Right(1)
    c.get[String]("node") shouldBe Right(Node.nodeId)
    c.get[String]("nonce") shouldBe Right(h.nonce)
    c.get[Long]("issuedAt") shouldBe Right(f.clock.get())
    c.get[Long]("expiresAt") shouldBe Right(f.clock.get() + SearchRecordRoute.RecordLifetimeMs)
    c.downField("missing").focus shouldBe Some(Json.arr())
    val r = c.downField("records").downN(0)
    r.get[String]("catalog") shouldBe Right("acme_lake")
    r.get[Long]("tableId") shouldBe Right(12L)
    r.get[String]("table") shouldBe Right("acme_lake.main.pages")
    r.get[String]("indexId") shouldBe Right(PagesId.toString)
    r.get[String]("bundleRoot") shouldBe Right("s3://lake/acme_lake/")
    r.get[String]("state") shouldBe Right("ready")
    r.get[Long]("segmentsVersion") shouldBe Right(4L)
    r.downField("fields").downN(1).get[String]("column") shouldBe Right("body")
    r.downField("fields").downN(1).get[Long]("fieldId") shouldBe Right(3L)
    r.get[String]("macKey") shouldBe Right(
      NodeHandles.hex(f.keys.searchBundleMacKey(PagesId.toString))
    )
    r.keys.get.toSet shouldBe SearchRecordRoute.RecordKeys
  }

  it should "serve a superuser's handle and a tenant user's alike" in {
    val f = new Fixture
    f.call(body(f.handle(Root).text, Pages))._1 shouldBe Status.Ok
    f.call(body(f.handle(Alice).text, Pages))._1 shouldBe Status.Ok
  }

  it should "answer a disabled index's record with its state" in {
    val f      = new Fixture
    val (_, c) = answer(f.call(body(f.handle().text, table("acme_lake", 13L)))._2)
    c.downField("records").downN(0).get[String]("state") shouldBe Right("disabled")
  }

  it should "answer a table with no index in missing" in {
    val f      = new Fixture
    val (_, c) = answer(f.call(body(f.handle().text, Pages, table("acme_lake", 99L)))._2)
    c.downField("records").values.get.size shouldBe 1
    c.downField("missing").downN(0).get[String]("catalog") shouldBe Right("acme_lake")
    c.downField("missing").downN(0).get[Long]("tableId") shouldBe Right(99L)
  }

  "a refused call" should "hold no record: a signature off by one byte" in {
    val f       = new Fixture
    val b       = body(f.handle().text, Pages)
    val good    = NodeHandles.hex(NodeHandles.hmac(Key, b))
    val bad     = (if good.head == '0' then "1" else "0") + good.tail
    val (st, t) = f.call(b, signature = Some(bad))
    st shouldBe Status.Unauthorized
    holdsNoRecord(t)
  }

  it should "hold no record: a handle another replica minted" in {
    val f       = new Fixture
    val foreign = new NodeKeys(
      Secret,
      "http://10.0.0.9:20901",
      () => f.clock.get(),
      readOnlyCatalogs = ReadOnlyCatalogs.none,
      versions = PolicyVersions.zero
    )
    val (st, t) = f.call(body(foreign.handle(Node, Alice), Pages))
    st shouldBe Status.ServiceUnavailable
    t shouldBe SearchRecordRoute.ContextExpired
    holdsNoRecord(t)
  }

  it should "hold no record: an expired handle, even within the grace other routes give" in {
    val f = new Fixture
    val h = f.handle()
    f.clock.addAndGet(NodeHandles.LifetimeMs + 1)
    val (st, t) = f.call(body(h.text, Pages))
    st shouldBe Status.Unauthorized
    holdsNoRecord(t)
  }

  it should "hold no record: a handle naming another node" in {
    val f = new Fixture
    // Minted for node 2, sent by node 1 (signed with node 1's key).
    val (st1, t1) = f.call(body(f.handle(on = OtherNode).text, Pages))
    st1 shouldBe Status.Unauthorized
    holdsNoRecord(t1)
    // Minted for node 1, sent by node 2 (signed with node 2's key): it does not verify there.
    val (st2, t2) = f.call(body(f.handle().text, Pages), nodeId = OtherNode.nodeId)
    st2 shouldBe Status.Unauthorized
    holdsNoRecord(t2)
  }

  "the system principal" should "be served for a handle recorded as minted for a search statement" in {
    val f = new Fixture
    val h = f.handle(NodePrincipal.System)
    f.system.put(h.nonce, Node.nodeId, NodeHandles.pool(Pool), h.expiresAt)
    val (st, t) = f.call(body(h.text, Pages))
    st shouldBe Status.Ok
    answer(t)._2.get[String]("nonce") shouldBe Right(h.nonce)
  }

  it should "be refused for a probe's handle and for one this replica never minted" in {
    val f        = new Fixture
    val probe    = f.handle(NodePrincipal.System) // minted, never recorded
    val (s1, t1) = f.call(body(probe.text, Pages))
    s1 shouldBe Status.ServiceUnavailable
    holdsNoRecord(t1)
    val foreign = new NodeKeys(
      Secret,
      "http://10.0.0.9:20901",
      () => f.clock.get(),
      readOnlyCatalogs = ReadOnlyCatalogs.none,
      versions = PolicyVersions.zero
    )
    val (s2, t2) = f.call(body(foreign.handle(Node, NodePrincipal.System), Pages))
    s2 shouldBe Status.ServiceUnavailable
    holdsNoRecord(t2)
  }

  it should "be refused for a recorded nonce naming another node" in {
    val f = new Fixture
    val h = f.handle(NodePrincipal.System)
    f.system.put(h.nonce, OtherNode.nodeId, NodeHandles.pool(Pool), h.expiresAt)
    val (st, t) = f.call(body(h.text, Pages))
    st shouldBe Status.Unauthorized
    holdsNoRecord(t)
  }

  it should "not admit a user's handle through the system cache" in {
    val f = new Fixture
    val h = f.handle(Alice)
    // Alice's nonce sits in IssuedHandles; a stale system entry for it changes nothing.
    f.system.put(h.nonce, Node.nodeId, NodeHandles.pool(Pool), h.expiresAt)
    f.call(body(h.text, Pages))._1 shouldBe Status.Ok
    val g = new Fixture
    val p = g.handle(Root)
    g.issued.put(p.nonce, OtherNode.nodeId, Root.id, NodeHandles.pool(Pool), p.expiresAt)
    g.system.put(p.nonce, Node.nodeId, NodeHandles.pool(Pool), p.expiresAt)
    g.call(body(p.text, Pages))._1 shouldBe Status.Unauthorized
  }

  "a handle the node re-signed" should "be refused when it names another pool, whoever minted it" in {
    val store = new FakeStore(
      List(index(PagesId, "td-globex-lake", "pages", 12L, SearchIndex.Ready))
    )
    val f = new Fixture(store)
    // A tenant user's nonce recorded for this node, and a system nonce recorded for this node.
    val user   = f.handle(Alice)
    val system = f.handle(NodePrincipal.System)
    f.system.put(system.nonce, Node.nodeId, NodeHandles.pool(Pool), system.expiresAt)
    List(user, system).foreach { h =>
      val forged  = resigned(h.text, Key)(withPool(PoolB))
      val (st, t) = f.call(body(forged, table("globex_lake", 12L, "globex_lake.main.pages")))
      st shouldBe Status.Unauthorized
      holdsNoRecord(t)
    }
    store.lookups.get() shouldBe 0
    // The handles as minted are served, from their own pool's scope.
    f.call(body(user.text, table("globex_lake", 12L)))._1 shouldBe Status.Ok
    store.lookups.get() shouldBe 0
  }

  it should "be refused when it stretches its expiry" in {
    val store = new FakeStore(List(index(PagesId, "td-acme-lake", "pages", 12L, SearchIndex.Ready)))
    val f     = new Fixture(store)
    val user  = f.handle(Alice)
    val system = f.handle(NodePrincipal.System)
    f.system.put(system.nonce, Node.nodeId, NodeHandles.pool(Pool), system.expiresAt)
    f.clock.addAndGet(NodeHandles.LifetimeMs + 1) // past the minted expiry
    List(user, system).foreach { h =>
      val forged = resigned(h.text, Key)(
        _.mapObject(_.add("exp", Json.fromLong(f.clock.get() + NodeHandles.LifetimeMs)))
      )
      val (st, t) = f.call(body(forged, Pages))
      st shouldBe Status.Unauthorized
      holdsNoRecord(t)
    }
    store.lookups.get() shouldBe 0
  }

  "the scope" should "answer another catalog in missing without asking the store" in {
    val store = new FakeStore(List(index(PagesId, "td-acme-lake", "pages", 12L, SearchIndex.Ready)))
    val f     = new Fixture(store)
    val (st, t) = f.call(
      body(
        f.handle().text,
        table("globex_lake", 12L, "globex_lake.main.pages"),
        table("memory", 12L),
        table("pg_fed", 12L),
        table("acme_lake.main", 12L)
      )
    )
    st shouldBe Status.Ok
    val (_, c) = answer(t)
    c.downField("records").focus shouldBe Some(Json.arr())
    c.downField("missing").values.get.size shouldBe 4
    store.lookups.get() shouldBe 0
    f.call(body(f.handle().text, table("ACME_LAKE", 12L)))._1 shouldBe Status.Ok
    store.lookups.get() shouldBe 1
  }

  it should "give a node of tenant B's pool nothing of tenant A's catalog" in {
    val store = new FakeStore(List(index(PagesId, "td-acme-lake", "pages", 12L, SearchIndex.Ready)))
    val f     = new Fixture(store)
    val keyB  = NodeHandles.nodeKey(Secret, NodeB.nodeId)
    val (st, t) = f.call(body(f.handle(Alice, NodeB).text, Pages), nodeId = NodeB.nodeId)
    st shouldBe Status.Ok
    val (_, c) = answer(t, keyB)
    c.downField("records").focus shouldBe Some(Json.arr())
    c.downField("missing").downN(0).get[String]("catalog") shouldBe Right("acme_lake")
    store.lookups.get() shouldBe 0
    // Its own catalog is looked up in its own tenant-db only.
    val (_, c2) = answer(
      f.call(body(f.handle(Alice, NodeB).text, table("globex_lake", 12L)), NodeB.nodeId)._2,
      keyB
    )
    c2.downField("missing").values.get.size shouldBe 1
    store.lookups.get() shouldBe 1
  }

  it should "answer every table in missing for a pool the supervisor no longer knows" in {
    val store   = new FakeStore(Nil)
    val f       = new Fixture(store)
    val gone    = node("quack-initech-lake-bi-1", PoolKey("initech", "lake", "bi"))
    val (st, t) =
      f.call(body(f.handle(Alice, gone).text, Pages), nodeId = gone.nodeId)
    st shouldBe Status.Ok
    answer(t, NodeHandles.nodeKey(Secret, gone.nodeId))._2
      .downField("missing")
      .values
      .get
      .size shouldBe 1
    store.lookups.get() shouldBe 0
  }

  it should "fold catalog names ASCII only: a KELVIN SIGN alias is another catalog" in {
    val store = new FakeStore(List(index(PagesId, "td-acme-lake", "pages", 12L, SearchIndex.Ready)))
    val f     = new Fixture(store)
    val (st, t) = f.call(body(f.handle().text, table("acme_la\u212Ae", 12L)))
    st shouldBe Status.Ok
    answer(t)._2.downField("missing").values.get.size shouldBe 1
    store.lookups.get() shouldBe 0
    SearchRecordRoute.asciiFolded("ACME_La\u212Ae") shouldBe "acme_la\u212Ae"
  }

  "scopeResolver" should "resolve a pool to its tenant-db and alias, and nothing else" in {
    val lake   = TenantDb("td-lake", "acme", "lake", TenantDbKind.DuckLake, Map.empty, "")
    val branch =
      TenantDb("td-br", "acme", "lake__br_0f0e0d0c", TenantDbKind.DuckLake, Map.empty, "")
    val asked   = scala.collection.mutable.ListBuffer.empty[(String, String)]
    val scopeOf = SearchRecordRoute.scopeResolver(
      (t, d) =>
        asked += (t -> d)
        Option.when(t == "acme")(d).collect {
          case "lake"              => lake
          case "lake__br_0f0e0d0c" => branch
        }
      ,
      // The branch attaches under its parent's alias (TenantDb.catalogAlias of its metastore).
      _ => "acme_lake"
    )
    scopeOf("acme/lake/bi") shouldBe Some(SearchRecordRoute.Scope("td-lake", "acme_lake"))
    // A branch pool resolves to the branch's own row, which holds no index: its tables are missing.
    scopeOf("acme/lake__br_0f0e0d0c/__br_0f0e0d0c") shouldBe
      Some(SearchRecordRoute.Scope("td-br", "acme_lake"))
    scopeOf("initech/lake/bi") shouldBe None
    asked.clear()
    List("acme/lake", "acme/lake/bi/x", "", "acme//bi", "/lake/bi", "acme/lake/").foreach { p =>
      withClue(p)(scopeOf(p) shouldBe None)
    }
    asked shouldBe empty
    SearchRecordRoute.scopeResolver((_, _) => Some(lake), _ => "")("acme/lake/bi") shouldBe None
  }

  it should "answer a branch pool's tables in missing, its parent's index never looked up there" in {
    val store = new FakeStore(List(index(PagesId, "td-acme-lake", "pages", 12L, SearchIndex.Ready)))
    val clock = new AtomicLong(1_761_200_000_000L)
    val issued = new IssuedHandles(maxEntries = 1000L, clock = () => clock.get())
    val keys   = new NodeKeys(
      Secret,
      "http://127.0.0.1:20901",
      () => clock.get(),
      issued = Some(issued),
      readOnlyCatalogs = ReadOnlyCatalogs.none,
      versions = PolicyVersions.zero
    )
    val brPool = PoolKey("acme", "lake__br_0f0e0d0c", "__br_0f0e0d0c")
    val brNode = node("quack-br-1", brPool)
    val route  = new SearchRecordRoute(
      keys,
      issued,
      new SearchSystemHandles(clock = () => clock.get()),
      SearchRecordRoute.scopeResolver(
        (t, d) =>
          Option.when(t == "acme" && d == brPool.tenantDb)(
            TenantDb("td-br", "acme", d, TenantDbKind.DuckLake, Map.empty, "")
          ),
        _ => "acme_lake"
      ),
      store,
      () => clock.get()
    )
    val b    = body(keys.handle(brNode, Root), Pages)
    val bkey = NodeHandles.nodeKey(Secret, brNode.nodeId)
    val req  = Request[IO](Method.POST, uri"/api/internal/search/records")
      .withEntity(b)
      .putHeaders(
        Header.Raw(CIString("X-QoD-Node"), brNode.nodeId),
        Header.Raw(CIString("X-QoD-Signature"), NodeHandles.hex(NodeHandles.hmac(bkey, b)))
      )
    val resp = route.routes.orNotFound.run(req).unsafeRunSync()
    resp.status shouldBe Status.Ok
    val (_, c) = answer(resp.as[String].unsafeRunSync(), bkey)
    c.downField("records").focus shouldBe Some(Json.arr())
    c.downField("missing").values.get.size shouldBe 1
    store.lookups.get() shouldBe 1 // asked for the branch's own row, which holds none
  }

  "a malformed request" should "be refused with the route's fixed text" in {
    val f = new Fixture
    val h = f.handle().text
    f.call(body(h, (1 to 17).map(i => table("acme_lake", i.toLong))*)) shouldBe
      (Status.BadRequest                                             -> SearchRecordRoute.Malformed)
    f.call(body(h)) shouldBe (Status.BadRequest                      -> SearchRecordRoute.Malformed)
    f.call("not json {".getBytes(UTF_8)) shouldBe (Status.BadRequest -> SearchRecordRoute.Malformed)
    f.call(body(h, Json.obj("catalog" -> Json.fromString("acme_lake")))) shouldBe
      (Status.BadRequest -> SearchRecordRoute.Malformed)
    f.call(body(h, table("acme_lake", -1L))) shouldBe
      (Status.BadRequest                                      -> SearchRecordRoute.Malformed)
    f.call(body(h, Pages, Pages)) shouldBe (Status.BadRequest -> SearchRecordRoute.Malformed)
    f.call(
      CanonicalJson.render(Json.obj("tables" -> Json.arr(Pages))).getBytes(UTF_8)
    ) shouldBe (Status.BadRequest -> SearchRecordRoute.Malformed)
    // 16 tables are taken.
    f.call(body(f.handle().text, (1 to 16).map(i => table("acme_lake", i.toLong))*))._1 shouldBe
      Status.Ok
  }

  "a store that cannot be read" should "answer a retryable 503 naming nothing of the cause" in {
    val store = new FakeStore(Nil)
    store.fail = true
    val f       = new Fixture(store)
    val (st, t) = f.call(body(f.handle().text, Pages))
    st shouldBe Status.ServiceUnavailable
    t shouldBe SearchRecordRoute.Unavailable
    t should not include "10.0.0.7"
  }

  "no answer" should "hold k1 or b, even for a row whose options column holds them" in {
    TestPostgres.ensureReachable()
    val dbName = s"qodsrr_${UUID.randomUUID().toString.take(8)}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val cp = new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try
        cp.upsertTenant(Tenant("acme"))
        cp.upsertTenantDb(
          TenantDb("td-acme-lake", "acme", "lake", TenantDbKind.DuckLake, Map.empty, "")
        )
        val store = SearchIndexStore.over(cp)
        store
          .create(index(PagesId, "td-acme-lake", "pages", 12L, SearchIndex.Ready))
          .unsafeRunSync()
          .isRight shouldBe true
        TestPostgres.psql(
          dbName,
          s"""UPDATE qodstate_search_index SET options = '{"k1": 2.0, "b": 0.3}'::jsonb """ +
            s"""WHERE index_id = '$PagesId'"""
        )
        val f       = new Fixture(store)
        val (st, t) = f.call(body(f.handle().text, Pages))
        st shouldBe Status.Ok
        val (json, c) = answer(t)
        c.downField("records").values.get.size shouldBe 1
        json should not include "\"k1\""
        json should not include "\"b\""
        c.downField("records").downN(0).keys.get.toSet shouldBe SearchRecordRoute.RecordKeys
      finally cp.close()
    finally Try(TestPostgres.dropDatabase(dbName))
  }
