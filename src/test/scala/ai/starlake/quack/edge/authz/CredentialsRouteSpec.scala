package ai.starlake.quack.edge.authz

import ai.starlake.quack.model.{PoolKey, Role, RunningNode, UserS3Credential}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.{HCursor, Json}
import io.circe.parser.parse
import org.http4s.{Header, MediaType, Method, Request, Status}
import org.http4s.headers.`Content-Type`
import org.http4s.implicits.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIString

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

class CredentialsRouteSpec extends AnyFlatSpec with Matchers:

  private val Secret = "qod-credentials-route-spec-secret-0123456789"
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
  private val Key   = NodeHandles.nodeKey(Secret, Node.nodeId)
  private val Alice = NodePrincipal("u-1", "alice", Some("acme"), superuser = false, system = false)
  private val Bob   = NodePrincipal("u-2", "bob", Some("acme"), superuser = false, system = false)
  private val Root  = NodePrincipal("u-root", "root", None, superuser = true, system = false)

  private val UserSecret = "alice-s3-secret-value"
  private val Creds      = List(
    UserS3Credential(
      "b" * 32,
      "u-1",
      "s3://acme-etl/in/",
      "AKIA2",
      UserSecret,
      Some("tok"),
      Some("eu-west-3"),
      Some("http://127.0.0.1:9000"),
      Some("path"),
      1L,
      20L
    ),
    UserS3Credential(
      "a" * 32,
      "u-1",
      "s3://acme-etl/",
      "AKIA1",
      UserSecret,
      None,
      None,
      None,
      None,
      1L,
      10L
    ),
    UserS3Credential(
      "c" * 32,
      "u-root",
      "s3://root-etl/",
      "AKIA3",
      "root-secret",
      None,
      None,
      Some("minio.internal:9000"),
      None,
      1L,
      30L
    )
  )

  private def sign(body: Array[Byte], key: Array[Byte] = Key): String =
    NodeHandles.hex(NodeHandles.hmac(key, body))

  private final class Fixture:
    val clock  = new AtomicLong(1_761_200_000_000L)
    val issued = new IssuedHandles(clock = () => clock.get())
    val cache  = new UserS3CredentialCache(() => Creds)
    cache.refresh()
    val keys = new NodeKeys(
      Secret,
      "http://127.0.0.1:20900",
      () => clock.get(),
      creds = cache,
      issued = Some(issued)
    )
    val route = new CredentialsRoute(keys, issued, cache, () => clock.get())

    def handle(p: NodePrincipal = Alice): String = keys.handle(Node, p)

    def call(
        body: Array[Byte],
        node: Option[String] = Some(Node.nodeId),
        signature: Option[String] = None,
        unsigned: Boolean = false
    ): (Status, String, Option[MediaType]) =
      val headers = node.map(n => Header.Raw(CIString("X-QoD-Node"), n)).toList ++
        Option.when(!unsigned)(
          Header.Raw(CIString("X-QoD-Signature"), signature.getOrElse(sign(body)))
        )
      val req = headers.foldLeft(
        Request[IO](Method.POST, uri"/api/internal/authz/credentials").withEntity(body)
      )((r, h) => r.putHeaders(h))
      val resp = route.routes.orNotFound.run(req).unsafeRunSync()
      (
        resp.status,
        resp.as[String].unsafeRunSync(),
        resp.headers.get[`Content-Type`].map(_.mediaType)
      )

  private def body(handle: String): Array[Byte] =
    CanonicalJson.render(Json.obj("handle" -> Json.fromString(handle))).getBytes(UTF_8)

  /** The answer's JSON, after checking its MAC under `key`. */
  private def answer(text: String, key: Array[Byte] = Key): (String, HCursor) =
    text.split('.').toList match
      case List(b64, mac) =>
        val bytes = Base64.getUrlDecoder.decode(b64)
        mac shouldBe NodeHandles.hex(NodeHandles.hmac(key, bytes))
        val json = new String(bytes, UTF_8)
        (json, parse(json).toOption.get.hcursor)
      case other => fail(s"not a signed answer: $other")

  "a signed call" should "answer the handle principal's credentials, signed, every member present" in {
    val f              = new Fixture
    val (st, text, mt) = f.call(body(f.handle()))
    st shouldBe Status.Ok
    mt shouldBe Some(MediaType.text.plain)
    val (json, c) = answer(text)
    json shouldBe CanonicalJson.render(parse(json).toOption.get)
    c.get[Int]("v") shouldBe Right(1)
    c.get[String]("creds") shouldBe Right(
      UserS3Credentials.digestOf(Creds.filter(_.userId == "u-1"))
    )
    val creds = c.downField("credentials")
    creds.downN(0).get[String]("id") shouldBe Right("a" * 32)
    creds.downN(1).get[String]("id") shouldBe Right("b" * 32)
    val b = creds.downN(1)
    b.get[String]("scope") shouldBe Right("s3://acme-etl/in/")
    b.get[String]("keyId") shouldBe Right("AKIA2")
    b.get[String]("secret") shouldBe Right(UserSecret)
    b.get[Option[String]]("sessionToken") shouldBe Right(Some("tok"))
    b.get[Option[String]]("region") shouldBe Right(Some("eu-west-3"))
    b.get[Option[String]]("endpoint") shouldBe Right(Some("127.0.0.1:9000"))
    b.get[Option[String]]("urlStyle") shouldBe Right(Some("path"))
    b.get[Boolean]("useSsl") shouldBe Right(false)
    val a = creds.downN(0)
    List("sessionToken", "region", "endpoint", "urlStyle").foreach { k =>
      withClue(k)(a.downField(k).focus shouldBe Some(Json.Null))
    }
    a.get[Boolean]("useSsl") shouldBe Right(true)
  }

  it should "serve a superuser theirs, and the system principal an empty set" in {
    val f      = new Fixture
    val (_, c) = answer(f.call(body(f.handle(Root)))._2)
    c.downField("credentials").downN(0).get[String]("endpoint") shouldBe Right(
      "minio.internal:9000"
    )
    c.downField("credentials").downN(0).get[Boolean]("useSsl") shouldBe Right(true)
    val (st, text, _) = f.call(body(f.handle(NodePrincipal.System)))
    st shouldBe Status.Ok
    val (_, s) = answer(text)
    s.get[String]("creds") shouldBe Right("")
    s.downField("credentials").focus shouldBe Some(Json.arr())
    f.call(body(f.handle(Bob)))._1 shouldBe Status.Ok
  }

  it should "refuse a handle this replica did not mint, retryably, as an expired context" in {
    val f       = new Fixture
    val foreign =
      new NodeKeys(Secret, "http://10.0.0.9:20900", () => f.clock.get()).handle(Node, Alice)
    f.call(body(foreign)) shouldBe
      (Status.ServiceUnavailable, CredentialsRoute.ContextExpired, Some(MediaType.text.plain))
  }

  it should "refuse a minted nonce reused for another principal" in {
    val f      = new Fixture
    val minted = f.handle(Alice)
    val nonce  = HandleInspector.payload(minted).hcursor.get[String]("nonce").toOption.get
    val forged = NodeHandles
      .mint(
        Secret,
        Node.nodeId,
        "http://127.0.0.1:20900",
        Bob,
        "acme/acme_lake/bi",
        0L,
        NodeHandles.EmptyContextDigest,
        "",
        f.clock.get(),
        nonce = nonce
      )
      .text
    f.call(body(forged))._1 shouldBe Status.Unauthorized
  }

  "an unauthenticated call" should "be refused before its body is read" in {
    val f = new Fixture
    val b = body(f.handle())
    f.call(b, unsigned = true)._1 shouldBe Status.Unauthorized
    f.call(b, node = None)._1 shouldBe Status.Unauthorized
    f.call(b, signature = Some("0" * 64))._1 shouldBe Status.Unauthorized
    f.call(b, signature = Some(sign(b, NodeHandles.nodeKey(Secret, "other"))))._1 shouldBe
      Status.Unauthorized
    f.call(
      b,
      node = Some("quack-acme-lake-bi-2"),
      signature = Some(sign(b, NodeHandles.nodeKey(Secret, "quack-acme-lake-bi-2")))
    )._1 shouldBe
      Status.Unauthorized
  }

  it should "refuse a malformed, oversized or expired request" in {
    val f = new Fixture
    f.call("{}".getBytes(UTF_8))._1 shouldBe Status.BadRequest
    f.call("not json".getBytes(UTF_8))._1 shouldBe Status.BadRequest
    f.call(new Array[Byte](CredentialsRoute.MaxBodyBytes + 1))._1 shouldBe Status.PayloadTooLarge
    val b = body(f.handle())
    f.clock.addAndGet(NodeHandles.LifetimeMs + NodeHandles.DecideGraceMs + 1)
    f.call(b)._1 shouldBe Status.Unauthorized
  }
