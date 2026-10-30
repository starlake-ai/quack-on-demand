package ai.starlake.quack.edge.authz

import ai.starlake.quack.edge.authz.location.{LocationRules, LocationRulesSource}
import ai.starlake.quack.edge.config.OpaConfig
import ai.starlake.quack.edge.opa.{OpaAuthorizer, OpaClient, OpaDecisionCache}
import ai.starlake.quack.model.{PoolKey, Role, RunningNode, Tenant}
import ai.starlake.quack.observability.metrics.OpaInstruments
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.runtime.testkit.LogCapture
import ai.starlake.quack.ondemand.state.{RbacRole, RbacUser, RolePermission}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.{HCursor, Json}
import io.circe.parser.parse
import org.http4s.{Header, MediaType, Method, Request, Status, Uri}
import org.http4s.headers.`Content-Type`
import org.http4s.implicits.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIString

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

class DecideRouteSpec extends AnyFlatSpec with Matchers:

  private val Secret = "qod-decide-route-spec-secret-0123456789"
  private val Pool   = PoolKey("acme", "acme_lake", "bi")
  private val Node   =
    RunningNode(
      "quack-acme-lake-bi-1",
      Pool,
      Role.Dual,
      "127.0.0.1",
      21900,
      "tok",
      None,
      None,
      Instant.EPOCH
    )
  private val Other = "quack-acme-lake-bi-2"
  private val Key   = NodeHandles.nodeKey(Secret, Node.nodeId)

  private val Alice = EffectiveSet(
    RbacUser("u-1", Some("acme"), "alice", "user"),
    List(RbacRole("r-1", "acme", "analyst")),
    Nil,
    List(RolePermission("p-1", "r-1", "acme_lake", "main", "orders", "RO")),
    Nil
  )
  private val AliceP = NodePrincipal.of(Some(Alice), Pool, DecidePurpose.Live)
  private val Root   = NodePrincipal("root", "root", None, superuser = true, system = false)

  private def s(x: String): Json = Json.fromString(x)

  private def read(table: String): Json = Json.obj(
    "access"      -> s("read"),
    "catalog"     -> s("acme_lake"),
    "catalogType" -> s("ducklake"),
    "columns"     -> Json.arr(s("id")),
    "kind"        -> s("table"),
    "name"        -> s(table),
    "schema"      -> s("main"),
    "via"         -> Json.Null
  )

  private def plan(objects: List[Json] = Nil, functions: List[Json] = Nil): Array[Byte] =
    CanonicalJson
      .render(
        Json.obj(
          "control"   -> Json.arr(),
          "functions" -> Json.arr(functions*),
          "objects"   -> Json.arr(objects*),
          "settings"  -> Json.arr(),
          "statement" -> s("PROJECTION"),
          "v"         -> Json.fromInt(1)
        )
      )
      .getBytes(UTF_8)

  private val EmptyScreen: Array[Byte] = """{"locations":[],"v":1}""".getBytes(UTF_8)

  private def request(handle: String, kind: String, description: Array[Byte]): Array[Byte] =
    CanonicalJson
      .render(
        Json.obj(
          "description" -> s(Base64.getUrlEncoder.withoutPadding.encodeToString(description)),
          "handle"      -> s(handle),
          "kind"        -> s(kind)
        )
      )
      .getBytes(UTF_8)

  private def sha256(b: Array[Byte]): String =
    NodeHandles.hex(MessageDigest.getInstance("SHA-256").digest(b))

  private def sign(body: Array[Byte], key: Array[Byte] = Key): String =
    NodeHandles.hex(NodeHandles.hmac(key, body))

  private final class Fixture(
      failLocations: Boolean = false,
      throwLocations: Boolean = false,
      throwTenants: Boolean = false
  ):
    val clock  = new AtomicLong(1_761_200_000_000L)
    val minted = new MintedHandles(clock = () => clock.get())
    val keys   =
      new NodeKeys(
        Secret,
        "http://127.0.0.1:20900",
        () => clock.get(),
        minted = Some(minted),
        readOnlyCatalogs = ReadOnlyCatalogs.none
      )
    val decider = new AuthzDecider(
      tenantOf = t =>
        if throwTenants then throw new IllegalStateException("control plane down")
        else Option.when(t == "acme")(Tenant("acme")),
      tenantCatalogs = _ => Set("acme_lake"),
      poolCatalog = _ => Some("acme_lake"),
      parentPoolsOf = _ => Nil,
      locations = new LocationRulesSource {
        def rulesFor(tenantId: String, userId: String): Either[String, LocationRules] =
          if throwLocations then throw new IllegalStateException("control plane down")
          else if failLocations then Left("the database data folders could not be read")
          else Right(LocationRules.Empty)
      },
      opa = new OpaAuthorizer(
        OpaConfig.default,
        new OpaClient(500, (_, _, _) => Left("no OPA in this spec")),
        new OpaDecisionCache(0),
        OpaInstruments.noop
      ),
      opaCfg = OpaConfig.default,
      functionClass = (_, _, name, _) => Option.when(name == "read_parquet")(FunctionClass.File),
      settingClass = _ => None
    )
    val route = new DecideRoute(keys, minted, decider, () => clock.get())

    def handle(p: NodePrincipal = AliceP): String = keys.handle(Node, p)

    def call(
        body: Array[Byte],
        node: Option[String] = Some(Node.nodeId),
        signature: Option[String] = None,
        unsigned: Boolean = false,
        method: Method = Method.POST
    ): (Status, String, Option[MediaType]) =
      val headers = node.map(n => Header.Raw(CIString("X-QoD-Node"), n)).toList ++
        Option.when(!unsigned)(
          Header.Raw(CIString("X-QoD-Signature"), signature.getOrElse(sign(body)))
        )
      val req = headers.foldLeft(
        Request[IO](method, uri"/api/internal/authz/decide").withEntity(body)
      )((r, h) => r.putHeaders(h))
      val resp = route.routes.orNotFound.run(req).unsafeRunSync()
      (
        resp.status,
        resp.as[String].unsafeRunSync(),
        resp.headers.get[`Content-Type`].map(_.mediaType)
      )

  /** A verdict's JSON, after checking its MAC under `key`. */
  private def verdict(text: String, key: Array[Byte] = Key): HCursor =
    text.split('.').toList match
      case List(b64, mac) =>
        val bytes = Base64.getUrlDecoder.decode(b64)
        mac shouldBe NodeHandles.hex(NodeHandles.hmac(key, bytes))
        parse(new String(bytes, UTF_8)).toOption.get.hcursor
      case other => fail(s"not a verdict: $other")

  "a signed decide call" should "answer a signed allow verdict for the handle's principal" in {
    val f              = new Fixture()
    val desc           = plan(List(read("orders")))
    val (st, body, mt) = f.call(request(f.handle(), "plan", desc))
    st shouldBe Status.Ok
    mt shouldBe Some(MediaType.text.plain)
    val v = verdict(body)
    v.get[Int]("v") shouldBe Right(1)
    v.get[String]("kind") shouldBe Right("plan")
    v.get[String]("decision") shouldBe Right("allow")
    v.get[String]("reason") shouldBe Right("")
    v.get[Boolean]("retryable") shouldBe Right(false)
    v.get[String]("principal") shouldBe Right("u-1")
    v.get[Long]("policyVersion") shouldBe Right(0L)
    v.get[String]("fingerprint") shouldBe Right(sha256(desc))
    v.get[String]("ctx") shouldBe Right(NodeHandles.contextDigest(AliceP.context))
    v.get[Long]("expiresAt") shouldBe Right(f.clock.get() + AuthzDecider.GrantLifetimeMs)
    v.get[List[Json]]("rowFilters") shouldBe Right(Nil)
    v.get[List[Json]]("masks") shouldBe Right(Nil)
  }

  it should "answer a signed refuse verdict carrying the reason" in {
    val f             = new Fixture()
    val (st, body, _) = f.call(request(f.handle(), "plan", plan(List(read("customers")))))
    st shouldBe Status.Ok
    val v = verdict(body)
    v.get[String]("decision") shouldBe Right("refuse")
    v.get[String]("reason") shouldBe Right(
      "user:alice lacks grants on acme_lake.main.customers:read"
    )
    v.get[Boolean]("retryable") shouldBe Right(false)
  }

  it should "answer a screen verdict that expires at once" in {
    val f             = new Fixture()
    val (st, body, _) = f.call(request(f.handle(), "screen", EmptyScreen))
    st shouldBe Status.Ok
    val v = verdict(body)
    v.get[String]("kind") shouldBe Right("screen")
    v.get[String]("decision") shouldBe Right("allow")
    v.get[Long]("expiresAt") shouldBe Right(f.clock.get())
    v.get[String]("fingerprint") shouldBe Right(sha256(EmptyScreen))
  }

  it should "allow a superuser's handle, which needs no minted context" in {
    val f             = new Fixture()
    val (st, body, _) = f.call(request(f.handle(Root), "plan", plan(List(read("customers")))))
    st shouldBe Status.Ok
    verdict(body).get[String]("decision") shouldBe Right("allow")
  }

  it should "refuse, signed, a description it cannot read" in {
    val f             = new Fixture()
    val (st, body, _) = f.call(request(f.handle(), "plan", "{}".getBytes(UTF_8)))
    st shouldBe Status.Ok
    val v = verdict(body)
    v.get[String]("decision") shouldBe Right("refuse")
    v.get[String]("reason").map(_.startsWith("malformed description")) shouldBe Right(true)
  }

  "the node's signature" should "be required, well-formed and valid before anything is read" in {
    val f    = new Fixture()
    val body = request(f.handle(), "plan", plan(List(read("orders"))))
    f.call(body, node = None)._1 shouldBe Status.Unauthorized
    f.call(body, unsigned = true)._1 shouldBe Status.Unauthorized
    f.call(body, node = Some("bad node id"))._1 shouldBe Status.Unauthorized
    f.call(body, signature = Some(sign(body).toUpperCase))._1 shouldBe Status.Unauthorized
    f.call(body, signature = Some("abc"))._1 shouldBe Status.Unauthorized
    f.call(body, signature = Some(sign(body, NodeHandles.nodeKey("x" * 40, Node.nodeId)))) shouldBe
      (Status.Unauthorized, "signature does not verify", Some(MediaType.text.plain))
    val altered = body.clone()
    altered(altered.length - 3) = 'x'.toByte
    f.call(altered, signature = Some(sign(body)))._2 shouldBe "signature does not verify"
    f.call("not json".getBytes(UTF_8), signature = Some(sign(body)))._2 shouldBe
      "signature does not verify"
  }

  it should "come from the node the handle was minted for" in {
    val f    = new Fixture()
    val body = request(f.handle(), "plan", plan(List(read("orders"))))
    f.call(
      body,
      node = Some(Other),
      signature = Some(sign(body, NodeHandles.nodeKey(Secret, Other)))
    ) shouldBe
      (Status.Unauthorized, "handle refused: handle does not verify", Some(MediaType.text.plain))
    f.call(body, node = Some(Other))._2 shouldBe "signature does not verify"
  }

  "the handle" should "verify under the node's key" in {
    val f             = new Fixture()
    val text          = f.handle()
    val tampered      = text.replaceFirst("^.", if text.charAt(0) == 'e' then "f" else "e")
    val (st, body, _) = f.call(request(tampered, "plan", plan()))
    st shouldBe Status.Unauthorized
    body should startWith("handle refused: ")
  }

  it should "be accepted for DecideGraceMs past its expiry, then refused" in {
    val f    = new Fixture()
    val body = request(f.handle(), "plan", plan(List(read("orders"))))
    f.clock.addAndGet(NodeHandles.LifetimeMs + 1000L)
    f.call(body)._1 shouldBe Status.Ok
    f.clock.addAndGet(NodeHandles.DecideGraceMs)
    f.call(body) shouldBe
      (Status.Unauthorized, "handle refused: handle expired", Some(MediaType.text.plain))
  }

  it should "name a context this replica minted, else the call is retryably unavailable" in {
    val f         = new Fixture()
    val elsewhere = new Fixture().handle()
    f.call(request(elsewhere, "plan", plan(List(read("orders"))))) shouldBe
      (Status.ServiceUnavailable, AuthzDecider.ContextExpired, Some(MediaType.text.plain))
  }

  it should "match the context registered for its nonce" in {
    val f       = new Fixture()
    val text    = f.handle()
    val nonce   = NodeHandles.verify(text, Key, Node.nodeId, f.clock.get()).toOption.get.nonce
    val mallory = Alice.copy(user = Alice.user.copy(id = "u-2", username = "mallory"))
    val h       = NodeHandles.verify(text, Key, Node.nodeId, f.clock.get()).toOption.get
    f.minted.put(
      nonce,
      DecideContext(mallory, Pool, None, DecidePurpose.Live),
      h.ctx,
      f.clock.get() + 60_000L
    )
    f.call(request(text, "plan", plan(List(read("orders"))))) shouldBe
      (Status.Unauthorized, "handle does not match its minted context", Some(MediaType.text.plain))
    // The same user and pool under another digest: the context is not the one minted.
    f.minted.put(nonce, AliceP.context.get, "f" * 64, f.clock.get() + 60_000L)
    f.call(request(text, "plan", plan(List(read("orders"))))) shouldBe
      (Status.Unauthorized, "handle does not match its minted context", Some(MediaType.text.plain))
  }

  "a malformed request" should "be answered 400 or 413" in {
    val f = new Fixture()
    f.call("not json".getBytes(UTF_8))._1 shouldBe Status.BadRequest
    f.call(request(f.handle(), "verdict", plan()))._1 shouldBe Status.BadRequest
    f.call("""{"description":"%%%","handle":"h","kind":"plan"}""".getBytes(UTF_8))._1 shouldBe
      Status.BadRequest
    f.call(new Array[Byte](DecideRoute.MaxBodyBytes + 1))._1 shouldBe Status.PayloadTooLarge
  }

  "an unavailable decision" should "be a retryable 503, not a verdict" in {
    val f      = new Fixture(failLocations = true)
    val reader = Json.obj(
      "args"    -> Json.arr(s("s3://acme-etl/in/x.parquet")),
      "catalog" -> s("system"),
      "name"    -> s("read_parquet"),
      "schema"  -> s("main"),
      "type"    -> s("table")
    )
    f.call(request(f.handle(), "plan", plan(functions = List(reader)))) shouldBe
      (
        Status.ServiceUnavailable,
        "the database data folders could not be read",
        Some(MediaType.text.plain)
      )
  }

  it should "be a 503 too when reading the location rules throws, or the decision does" in {
    val reader = Json.obj(
      "args"    -> Json.arr(s("s3://acme-etl/in/x.parquet")),
      "catalog" -> s("system"),
      "name"    -> s("read_parquet"),
      "schema"  -> s("main"),
      "type"    -> s("table")
    )
    val f = new Fixture(throwLocations = true)
    f.call(request(f.handle(), "plan", plan(functions = List(reader)))) shouldBe
      (Status.ServiceUnavailable, AuthzDecider.RulesUnavailable, Some(MediaType.text.plain))
    val g = new Fixture(throwTenants = true)
    g.call(request(g.handle(), "plan", plan(List(read("orders"))))) shouldBe
      (Status.ServiceUnavailable, DecideRoute.DecisionFailed, Some(MediaType.text.plain))
  }

  it should "answer a node whose id is the longest PoolSupervisor composes" in {
    val f       = new Fixture()
    val tenant  = "t" * 61
    val key     = PoolKey(tenant, s"${tenant}_d", "p" * 63)
    val longest = ai.starlake.quack.ondemand.PoolSupervisor.nodeId(key, Int.MaxValue)
    longest.length should be > 128
    val node          = Node.copy(nodeId = longest, poolKey = key)
    val body          = request(f.keys.handle(node, Root), "plan", plan())
    val (st, text, _) =
      f.call(
        body,
        node = Some(longest),
        signature = Some(sign(body, NodeHandles.nodeKey(Secret, longest)))
      )
    st shouldBe Status.Ok
    verdict(text, NodeHandles.nodeKey(Secret, longest)).get[String]("decision") shouldBe Right(
      "allow"
    )
  }

  it should "refuse an unsigned call before reading its body, and a declared oversize at once" in {
    val f         = new Fixture()
    val untouched = fs2.Stream.raiseError[IO](new IllegalStateException("the body was read"))
    def run(r: Request[IO]) = f.route.routes.orNotFound.run(r).unsafeRunSync().status
    run(
      Request[IO](Method.POST, uri"/api/internal/authz/decide").withBodyStream(untouched)
    ) shouldBe
      Status.Unauthorized
    run(
      Request[IO](Method.POST, uri"/api/internal/authz/decide")
        .withBodyStream(untouched)
        .putHeaders(
          Header.Raw(CIString("X-QoD-Node"), Node.nodeId),
          Header.Raw(CIString("X-QoD-Signature"), "0" * 64),
          org.http4s.headers.`Content-Length`(DecideRoute.MaxBodyBytes.toLong + 1)
        )
    ) shouldBe Status.PayloadTooLarge
  }

  it should "log a node's first refusal at WARN and its repeats at DEBUG" in {
    val f           = new Fixture()
    val body        = request(f.handle(), "plan", plan(List(read("orders"))))
    val (_, events) = LogCapture.logged("ai.starlake.quack.edge.authz.DecideRoute") {
      f.call(body, signature = Some("0" * 64))
      f.call(body, signature = Some("1" * 64))
    }
    events.map(_.getLevel.toString) shouldBe List("WARN", "DEBUG")
  }

  "the route" should "answer only POST on its path" in {
    val f = new Fixture()
    f.call(plan(), method = Method.GET)._1 shouldBe Status.NotFound
  }

  "the route's log" should "never hold a handle, a signature, a key or the secret" in {
    val f           = new Fixture()
    val secrets     = scala.collection.mutable.ListBuffer.empty[String]
    val (_, events) = LogCapture.logged("ai.starlake.quack.edge.authz.DecideRoute") {
      val h1 = f.handle()
      val b1 = request(h1, "plan", plan(List(read("orders"))))
      val b2 = request(f.handle(), "plan", plan(List(read("customers"))))
      val h3 = new Fixture().handle()
      val b3 = request(h3, "plan", plan())
      secrets ++= List(h1, sign(b1), sign(b2), sign(b3), h3)
      f.call(b1)
      f.call(b2)
      f.call(b1, signature = Some(sign(b2)))
      f.call(b3)
      f.call(request(h1.reverse, "plan", plan()))
    }
    events should not be empty
    secrets ++= List(NodeHandles.hex(Key), Secret)
    val text = events.map(_.getFormattedMessage).mkString("\n")
    secrets.foreach(sv =>
      withClue("a secret value reached the log")(text.contains(sv) shouldBe false)
    )
  }
