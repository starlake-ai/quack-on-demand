package ai.starlake.quack.ondemand.demo

import ai.starlake.quack.ondemand.storage.ManagedStoreClient
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DemoS3SettingsSpec extends AnyFlatSpec with Matchers:

  private val Run = "s3://my-bucket/some/where/qod-demo-1a2b3c4d"

  private def env(extra: (String, String)*): String => Option[String] =
    val m = Map(
      "QOD_DEMO_S3_PATH"         -> Run,
      "QOD_S3_ACCESS_KEY_ID"     -> "AKIDVALUE",
      "QOD_S3_SECRET_ACCESS_KEY" -> "SECRETVALUE",
      "QOD_S3_ENDPOINT"          -> "127.0.0.1:8333",
      "QOD_S3_REGION"            -> "eu-west-1",
      "QOD_S3_URL_STYLE"         -> "path",
      "QOD_S3_USE_SSL"           -> "false"
    ) ++ extra.toMap
    m.get

  private def parsed(extra: (String, String)*): DemoS3 =
    DemoS3.fromEnv(env(extra*)).toOption.flatten.getOrElse(fail("did not parse"))

  "DemoS3.fromEnv" should "be None with no path set" in {
    DemoS3.fromEnv(_ => None) shouldBe Right(None)
    DemoS3.fromEnv(env("QOD_DEMO_S3_PATH" -> "  ")) shouldBe Right(None)
  }

  it should "split the run folder into a bucket and a key prefix" in {
    val s = parsed()
    s.bucket shouldBe "my-bucket"
    s.runKeyPrefix shouldBe "some/where/qod-demo-1a2b3c4d/"
    s.runFolder shouldBe Run
    s.local shouldBe false
    s.useSsl shouldBe false
    s.region shouldBe "eu-west-1"
  }

  it should "accept a run folder at the bucket's root and drop a trailing slash" in {
    val s = parsed("QOD_DEMO_S3_PATH" -> "s3://b12/qod-demo-1a2b3c4d/")
    s.runKeyPrefix shouldBe "qod-demo-1a2b3c4d/"
    s.runFolder shouldBe "s3://b12/qod-demo-1a2b3c4d"
  }

  it should "read local mode only from the exact value 1" in {
    parsed("QOD_DEMO_S3_LOCAL" -> "1").local shouldBe true
    parsed("QOD_DEMO_S3_LOCAL" -> "true").local shouldBe false
  }

  it should "refuse a path that is not a demo run folder, so cleanup can delete nothing else" in {
    val bad = List(
      "s3://my-bucket",
      "s3://my-bucket/data",
      "s3://my-bucket/qod-demo-XYZ",
      "s3://my-bucket/qod-demo-1a2b3c4",
      "s3://my-bucket/qod-demo-1a2b3c4d/extra",
      "s3://my-bucket/../qod-demo-1a2b3c4d",
      "s3://my-bucket/it's/qod-demo-1a2b3c4d",
      "gs://my-bucket/qod-demo-1a2b3c4d",
      "/tmp/qod-demo-1a2b3c4d"
    )
    bad.foreach { p =>
      withClue(p)(DemoS3.fromEnv(env("QOD_DEMO_S3_PATH" -> p)).isLeft shouldBe true)
    }
  }

  it should "refuse missing keys, naming the variable and no value" in {
    val out = DemoS3.fromEnv(env("QOD_S3_SECRET_ACCESS_KEY" -> ""))
    out.isLeft shouldBe true
    out.left.getOrElse("") should include("QOD_S3_SECRET_ACCESS_KEY")
    out.left.getOrElse("") should not include "AKIDVALUE"
  }

  it should "refuse a USE_SSL or URL style it would splice into SQL unchecked" in {
    DemoS3.fromEnv(env("QOD_S3_USE_SSL" -> "maybe")).isLeft shouldBe true
    DemoS3.fromEnv(env("QOD_S3_URL_STYLE" -> "weird")).isLeft shouldBe true
  }

  "defaultDataPath" should "derive a tenant-db folder inside the run folder" in {
    val derived = ai.starlake.quack.ondemand.PoolSupervisor
      .replaceLastSegment(parsed().defaultDataPath, "acme_tpch")
    derived shouldBe s"$Run/acme_tpch"
  }

  "storeConfig" should "give the client a URL with the scheme USE_SSL says" in {
    parsed().storeConfig.endpoint shouldBe "http://127.0.0.1:8333"
    parsed("QOD_S3_USE_SSL" -> "true").storeConfig.endpoint shouldBe "https://127.0.0.1:8333"
    parsed("QOD_S3_ENDPOINT" -> "").storeConfig.endpoint shouldBe ""
  }

  "toString" should "hold no key" in {
    val s = parsed().toString
    s should not include "AKIDVALUE"
    s should not include "SECRETVALUE"
  }

  "redact" should "mask both keys" in {
    parsed().redact("x AKIDVALUE y SECRETVALUE z") shouldBe "x *** y *** z"
  }

  "seedStorageSql" should "keep one statement to a line start and scope the secret to the run folder" in {
    val sql = parsed().seedStorageSql
    sql.take(2) shouldBe List("INSTALL httpfs", "LOAD httpfs")
    val secret = sql(2)
    secret should startWith("CREATE OR REPLACE TEMPORARY SECRET qod_demo_s3 (")
    secret should include(s"SCOPE '$Run/'")
    secret should include("ENDPOINT '127.0.0.1:8333'")
    secret should include("USE_SSL false")
    secret should not include ";"
  }

  it should "quote a key holding a single quote" in {
    parsed("QOD_S3_SECRET_ACCESS_KEY" -> "a'b").seedStorageSql(2) should include("SECRET 'a''b'")
  }

  "bannerNotes" should "warn in own-bucket mode and print no key" in {
    val own = parsed().bannerNotes.mkString("\n")
    own should include("deleted on exit")
    own should include("127.0.0.1")
    own should include("dedicated bucket")
    own should not include "AKIDVALUE"
    parsed("QOD_DEMO_S3_LOCAL" -> "1").bannerNotes.mkString("\n") should include("throwaway")
  }

  private final class FakeStore(initial: List[String]) extends ManagedStoreClient:
    var keys: List[String]                        = initial
    var ensured: Int                              = 0
    var ensureAnswers: List[Either[String, Unit]] = Nil
    var deleteError: Option[String]               = None
    def ensureBucket(): Either[String, Unit]      =
      ensured += 1
      ensureAnswers match
        case head :: tail =>
          ensureAnswers = tail
          head
        case Nil => Right(())
    def listPrefix(prefix: String, max: Int): Either[String, List[String]] =
      Right(keys.take(max))
    def deleteBatch(batch: List[String]): Either[String, Unit] =
      deleteError match
        case Some(e) => Left(e)
        case None    =>
          keys = keys.diff(batch)
          Right(())

  private val Prefix = "some/where/qod-demo-1a2b3c4d/"

  "deleteRun" should "delete until a listing comes back empty and count what it removed" in {
    val store = new FakeStore((1 to 2500).toList.map(i => s"${Prefix}f$i"))
    parsed().deleteRun(store) shouldBe Right(2500)
    store.keys shouldBe Nil
  }

  it should "refuse a listed key outside the run folder and delete nothing" in {
    val store = new FakeStore(List(s"${Prefix}a", "some/where/other/b"))
    val out   = parsed().deleteRun(store)
    out.isLeft shouldBe true
    out.left.getOrElse("") should include("some/where/other/b")
    store.keys should have size 2
  }

  it should "report a failed delete" in {
    val store = new FakeStore(List(s"${Prefix}a"))
    store.deleteError = Some("denied")
    parsed().deleteRun(store) shouldBe Left("denied")
  }

  it should "stop at its time budget instead of looping on a store that never empties" in {
    val store = new ManagedStoreClient:
      def ensureBucket(): Either[String, Unit]                        = Right(())
      def listPrefix(p: String, m: Int): Either[String, List[String]] = Right(List(s"${Prefix}a"))
      def deleteBatch(k: List[String]): Either[String, Unit]          = Right(())
    var t   = 0L
    val out = parsed().deleteRun(store, nowMs = () => { t += 30000L; t })
    out.isLeft shouldBe true
    out.left.getOrElse("") should include("time")
  }

  "prepare" should "create the bucket in local mode, retrying while the store settles" in {
    val store = new FakeStore(Nil)
    store.ensureAnswers = List(Left("filer not ready"), Left("filer not ready"), Right(()))
    var slept = 0
    var t     = 0L
    parsed("QOD_DEMO_S3_LOCAL" -> "1").prepare(
      store,
      _ => fail("local mode writes no marker"),
      nowMs = () => { t += 1000L; t },
      sleep = _ => slept += 1
    ) shouldBe Right(())
    store.ensured shouldBe 3
    slept shouldBe 2
  }

  it should "give up on the bucket after its 30 s budget" in {
    val store = new FakeStore(Nil)
    store.ensureAnswers = List.fill(100)(Left("nope"))
    var t   = 0L
    val out = parsed("QOD_DEMO_S3_LOCAL" -> "1").prepare(
      store,
      _ => Right(()),
      nowMs = () => { t += 10000L; t },
      sleep = _ => ()
    )
    out.isLeft shouldBe true
    out.left.getOrElse("") should include("nope")
  }

  it should "write and delete one marker in own-bucket mode, and never create a bucket" in {
    val store = new FakeStore(Nil)
    var put   = List.empty[String]
    parsed().prepare(store, k => { put = k :: put; Right(()) }) shouldBe Right(())
    put should have size 1
    put.head should startWith(Prefix)
    store.ensured shouldBe 0
  }

  it should "name the bucket and the cause when the write check fails" in {
    val out = parsed().prepare(new FakeStore(Nil), _ => Left("AccessDenied"))
    out.left.getOrElse("") should include("my-bucket")
    out.left.getOrElse("") should include("AccessDenied")
  }
