package ai.starlake.quack.ondemand.demo

import ai.starlake.quack.ManagedObjectStoreConfig
import ai.starlake.quack.ondemand.storage.S3ManagedStoreClient
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{
  HeadBucketRequest,
  NoSuchBucketException,
  S3Exception
}

import java.net.{
  Inet4Address,
  InetAddress,
  InetSocketAddress,
  NetworkInterface,
  ServerSocket,
  Socket
}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit
import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.control.NonFatal

/** The demo on S3, end to end: a real `weed`, real nodes, the Postgres catalog and the jar's Java
  * S3 client. Cancelled with no `weed` (`QOD_DEMO_WEED_BIN` or PATH) or no `duckdb` on PATH, as
  * [[DemoDifferentiatorSpec]] is. A cancel is not a pass.
  *
  * The demo reads `QOD_S3_*` from its process environment, so each case runs it in a child JVM. The
  * `weed` flags repeat `qod_cli.demo_s3.weed_command`, which `test_demo_s3.py` pins.
  *
  * Nothing this spec prints carries the store's keys, a failing assertion included: text from the
  * demo or the store goes through [[masked]], and assertions on that text are plain booleans with a
  * clue, never a matcher that quotes the text it refused.
  */
class DemoS3Spec extends AnyFlatSpec with Matchers:

  private val FlightPort = 31338
  private val Key        = "demo-spec-key"
  private val Secret     = "demo-spec-secret-value-0123456789"
  private val StopWait   = 120L

  /** Variables a developer's shell may carry that would change where the child demo stores data or
    * which ports it binds; the spec sets its own values for the ones it needs.
    */
  private def inheritedOverride(name: String): Boolean =
    name.startsWith("QOD_S3_") || name.startsWith("QOD_INTERNAL_") ||
      name == "QOD_MIN_PORT" || name == "QOD_MAX_PORT"

  private def masked(text: String): String =
    text.replace(Secret, "***").replace(Key, "***")

  private def onPath(exe: String): Option[String] =
    sys.env
      .getOrElse("PATH", "")
      .split(java.io.File.pathSeparator)
      .map(Paths.get(_, exe))
      .find(Files.isExecutable(_))
      .map(_.toString)

  private lazy val weedBin: Option[String] =
    sys.env.get("QOD_DEMO_WEED_BIN").filter(_.trim.nonEmpty).orElse(onPath("weed"))

  private val loopback: InetAddress = InetAddress.getLoopbackAddress

  /** Probed on loopback: on macOS a wildcard bind can succeed while a loopback socket holds the
    * port.
    */
  private def freePorts(n: Int): List[Int] =
    val socks = List.fill(n)(new ServerSocket(0, 50, loopback))
    try socks.map(_.getLocalPort)
    finally socks.foreach(_.close())

  /** The demo's REST and Quack ports. The node verdict listener takes the REST port + 1, so that
    * port must be free too and must not be the Quack port: two ports from `freePorts(2)` are often
    * consecutive, and boot then refuses the Quack port as the verdict listener's.
    */
  @tailrec
  private def demoPorts(): (Int, Int) =
    val rest = new ServerSocket(0, 50, loopback)
    Try(new ServerSocket(rest.getLocalPort + 1, 50, loopback)).toOption match
      case Some(internal) =>
        val quack = Try(new ServerSocket(0, 50, loopback))
        try (rest.getLocalPort, quack.get.getLocalPort)
        finally (List(rest, internal) ++ quack.toOption).foreach(_.close())
      case None =>
        rest.close()
        demoPorts()

  private def accepts(addr: InetAddress, port: Int): Boolean =
    Try {
      val s = new Socket()
      try s.connect(new InetSocketAddress(addr, port), 500)
      finally s.close()
    }.isSuccess

  private def lanAddress: Option[InetAddress] =
    NetworkInterface.getNetworkInterfaces.asScala
      .filter(i => i.isUp && !i.isLoopback)
      .flatMap(_.getInetAddresses.asScala)
      .collectFirst { case a: Inet4Address => a }

  private def listed(client: S3ManagedStoreClient, prefix: String, max: Int): List[String] =
    client.listPrefix(prefix, max) match
      case Right(keys) => keys
      case Left(err)   => fail(s"listing $prefix failed: ${masked(err)}")

  private final class Store(val port: Int, proc: Process, dir: Path):
    def client(bucket: String): S3ManagedStoreClient =
      new S3ManagedStoreClient(
        ManagedObjectStoreConfig(
          endpoint = s"http://127.0.0.1:$port",
          bucket = bucket,
          accessKeyId = Key,
          secretAccessKey = Secret
        )
      )

    /** A HEAD of the bucket: `Right(true)` only for the store's 404, so any other failure is not
      * read as "the bucket is missing".
      */
    def bucketMissing(bucket: String): Either[String, Boolean] =
      val s3 = S3Client
        .builder()
        .region(Region.US_EAST_1)
        .credentialsProvider(
          StaticCredentialsProvider.create(AwsBasicCredentials.create(Key, Secret))
        )
        .forcePathStyle(true)
        .endpointOverride(java.net.URI.create(s"http://127.0.0.1:$port"))
        .build()
      try
        s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build())
        Right(false)
      catch
        case _: NoSuchBucketException                => Right(true)
        case e: S3Exception if e.statusCode() == 404 => Right(true)
        case NonFatal(e) => Left(masked(s"HEAD $bucket failed: ${e.getMessage}"))
      finally s3.close()

    def stop(): Unit = Store.kill(proc, dir)

  private object Store:
    def kill(proc: Process, dir: Path): Unit =
      try
        proc.destroyForcibly()
        proc.waitFor(10, TimeUnit.SECONDS)
      finally DemoHome(dir, dir, dir, dir).deleteRecursively()

  /** `autoCreateBuckets` is SeaweedFS's default for an admin identity, unlike AWS: a case about a
    * missing bucket, or about the demo creating one, must turn it off, or the store creates the
    * bucket on the first write.
    */
  private def startStore(autoCreateBuckets: Boolean = true): Store =
    val dir     = Files.createTempDirectory("demo-s3-spec-store")
    val started =
      try
        Files.createDirectories(dir.resolve("data"))
        Files.createDirectories(dir.resolve("logs"))
        Files.writeString(
          dir.resolve("s3.json"),
          s"""{"identities":[{"name":"demo","credentials":[{"accessKey":"$Key","secretKey":"$Secret"}],
             |"actions":["Admin","Read","Write","List","Tagging"]}]}""".stripMargin
        )
        val List(s3, s3g, m, mg, v, vg, f, fg) = freePorts(8): @unchecked
        val proc                               = new ProcessBuilder(
          weedBin.get,
          s"-logdir=${dir.resolve("logs")}",
          "server",
          s"-dir=${dir.resolve("data")}",
          "-ip=127.0.0.1",
          "-ip.bind=127.0.0.1",
          s"-master.port=$m",
          s"-master.port.grpc=$mg",
          s"-volume.port=$v",
          s"-volume.port.grpc=$vg",
          s"-filer.port=$f",
          s"-filer.port.grpc=$fg",
          "-s3",
          s"-s3.port=$s3",
          s"-s3.port.grpc=$s3g",
          s"-s3.config=${dir.resolve("s3.json")}",
          "-s3.port.iceberg=0",
          "-s3.port.lance=0",
          s"-s3.autoCreateBucket=$autoCreateBuckets",
          "-master.volumeSizeLimitMB=1024"
        ).redirectErrorStream(true).redirectOutput(dir.resolve("logs/weed.out").toFile).start()
        Right((s3, proc))
      catch
        case NonFatal(e) =>
          DemoHome(dir, dir, dir, dir).deleteRecursively()
          Left(e)
    started match
      case Left(e)           => throw e
      case Right((s3, proc)) =>
        val ready =
          try
            val http = HttpClient.newHttpClient()
            val req  =
              HttpRequest.newBuilder(java.net.URI.create(s"http://127.0.0.1:$s3/")).build()
            val deadline = System.nanoTime() + 60.seconds.toNanos
            @tailrec
            def poll(): Boolean =
              if !proc.isAlive || System.nanoTime() >= deadline then false
              else if Try(
                  http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
                ).toOption
                  .contains(403)
              then true
              else
                Thread.sleep(100)
                poll()
            poll()
          catch
            case e: Throwable =>
              Store.kill(proc, dir)
              throw e
        if !ready then
          Store.kill(proc, dir)
          fail("weed did not answer within 60 s")
        new Store(s3, proc, dir)

  /** The demo in a child JVM. Its output goes to `log`, which holds no key: the demo redacts. */
  private def demoProcess(
      home: Path,
      log: Path,
      store: Store,
      runFolder: String,
      local: Boolean,
      restPort: Int,
      quackPort: Int
  ): Process =
    val java = Paths.get(System.getProperty("java.home"), "bin", "java").toString
    val pb   = new ProcessBuilder(
      java,
      "--add-opens=java.base/java.nio=ALL-UNNAMED",
      "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
      "-Darrow.allocation.manager.type=Unsafe",
      "-Dio.netty.tryReflectionSetAccessible=true",
      "-cp",
      System.getProperty("java.class.path"),
      "ai.starlake.quack.Main",
      "demo",
      home.toString
    ).redirectErrorStream(true).redirectOutput(log.toFile)
    val env = pb.environment()
    env.keySet.asScala.filter(inheritedOverride).toList.foreach(env.remove)
    env.put(DemoS3.PathVar, runFolder)
    if local then env.put(DemoS3.LocalVar, "1") else env.remove(DemoS3.LocalVar)
    env.put("QOD_S3_ACCESS_KEY_ID", Key)
    env.put("QOD_S3_SECRET_ACCESS_KEY", Secret)
    env.put("QOD_S3_ENDPOINT", s"127.0.0.1:${store.port}")
    env.put("QOD_S3_REGION", "us-east-1")
    env.put("QOD_S3_URL_STYLE", "path")
    env.put("QOD_S3_USE_SSL", "false")
    env.put("QOD_ON_DEMAND_PORT", restPort.toString)
    env.put("QOD_QUACK_PORT", quackPort.toString)
    env.put("DUCKDB_AGENT_MODE", "0")
    pb.start()

  /** SIGTERM first, so the demo's own cleanup runs (its nodes, its embedded Postgres, its run
    * folder); SIGKILL only once it has not exited within [[StopWait]] seconds. True when it stopped
    * on SIGTERM, or had already exited.
    */
  private def stopGracefully(proc: Process): Boolean =
    proc.destroy()
    val graceful = proc.waitFor(StopWait, TimeUnit.SECONDS)
    if !graceful then
      proc.destroyForcibly()
      proc.waitFor(10, TimeUnit.SECONDS)
    graceful

  private def sweepNodes(restPort: Int): Unit =
    Try(
      new ProcessBuilder("scripts/kill-quack-nodes.sh", "--port", restPort.toString)
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .start()
        .waitFor(30, TimeUnit.SECONDS)
    )

  private def logTail(log: Path, lines: Int): String =
    masked(Try(Files.readAllLines(log).asScala.takeRight(lines).mkString("\n")).getOrElse(""))

  /** Run the demo, hand it to `body` (process, REST port, Quack port, log), then stop it the way a
    * user does, with SIGTERM, on every path. The node sweep and the temp cleanup run on every path
    * too. When `body` passes, the stop must have finished within [[StopWait]] seconds.
    */
  private def withDemo(store: Store, runFolder: String, local: Boolean)(
      body: (Process, Int, Int, Path) => Unit
  ): Unit =
    val home                  = Files.createTempDirectory("demo-s3-spec-home")
    val log                   = Files.createTempFile("demo-s3-spec", ".log")
    val (restPort, quackPort) = demoPorts()
    try
      val proc = demoProcess(home, log, store, runFolder, local, restPort, quackPort)
      try
        val outcome = Try(body(proc, restPort, quackPort, log))
        if outcome.isFailure then info(s"demo log tail:\n${logTail(log, 40)}")
        val graceful = stopGracefully(proc)
        outcome.get
        withClue(s"the demo did not stop within $StopWait s of SIGTERM: ") {
          graceful shouldBe true
        }
      finally if proc.isAlive then stopGracefully(proc)
    finally
      sweepNodes(restPort)
      DemoHome(home, home, home, home).deleteRecursively()
      Files.deleteIfExists(log)

  private def requireEnvironment(): Unit =
    if weedBin.isEmpty then cancel("no weed: set QOD_DEMO_WEED_BIN or put one on PATH")
    if onPath("duckdb").isEmpty then cancel("duckdb CLI not on PATH")
    if accepts(loopback, FlightPort) then
      cancel(s"port $FlightPort is taken: the demo's FlightSQL port is fixed")

  private def checkPolicies(): Unit =
    val alice = DemoFlight.queryRetrying(
      FlightPort,
      "alice",
      "demo-alice",
      "SELECT c_phone, c_mktsegment FROM acme_tpch.tpch1.customer LIMIT 50",
      180.seconds
    )
    alice should not be empty
    alice.map(_("c_phone")).toSet shouldBe Set("***")
    alice.map(_("c_mktsegment")).toSet shouldBe Set("BUILDING")
    val admin = DemoFlight.queryRetrying(
      FlightPort,
      "acme-admin",
      "demo-acme-admin",
      "SELECT c_phone, c_mktsegment FROM acme_tpch.tpch1.customer LIMIT 50",
      60.seconds
    )
    admin.map(_("c_phone")).toSet should not contain "***"
    admin.map(_("c_mktsegment")).toSet.size should be > 1

  private def checkLoopbackListeners(restPort: Int, quackPort: Int): Unit =
    List(restPort, FlightPort, quackPort).foreach { port =>
      withClue(s"loopback:$port ")(accepts(loopback, port) shouldBe true)
    }

  "the demo on the user's bucket" should
    "serve from the run folder, listen on loopback only, and delete only the run folder" in {
      requireEnvironment()
      val lan = lanAddress.getOrElse(
        cancel("no non-loopback IPv4 address: the loopback-only check needs one")
      )
      val store = startStore()
      try
        val client = store.client("demospec")
        client.ensureBucket() match
          case Right(()) => ()
          case Left(err) => fail(s"ensureBucket failed: ${masked(err)}")
        val prefix  = "qod/qod-demo-1a2b3c4d/"
        val keepers = List("qod/keep.txt", "qod/qod-demo-1a2b3c4d-x/keep")
        keepers.foreach { k =>
          client.putObject(k, "keep".getBytes) match
            case Right(()) => ()
            case Left(err) => fail(s"putObject $k failed: ${masked(err)}")
        }
        withDemo(store, s"s3://demospec/${prefix.stripSuffix("/")}", local = false) {
          (_, restPort, quackPort, _) =>
            checkPolicies()
            val keys = listed(client, prefix, 1000)
            keys should not be empty
            all(keys) should startWith(s"${prefix}acme_tpch/")
            checkLoopbackListeners(restPort, quackPort)
            List(restPort, FlightPort, quackPort).foreach { port =>
              withClue(s"$lan:$port ")(accepts(lan, port) shouldBe false)
            }
        }
        listed(client, prefix, 1000) shouldBe Nil
        keepers.foreach(k => listed(client, k, 10) shouldBe List(k))
      finally store.stop()
    }

  it should "stop before seeding when the bucket does not exist, and create none" in {
    requireEnvironment()
    val store = startStore(autoCreateBuckets = false)
    try
      withDemo(store, "s3://nosuchbucket/qod-demo-1a2b3c4d", local = false) { (proc, _, _, log) =>
        withClue("the demo did not exit within 90 s: ") {
          proc.waitFor(90, TimeUnit.SECONDS) shouldBe true
        }
        withClue("the demo exited 0: ")(proc.exitValue() should not be 0)
        val output = Files.readString(log)
        withClue("the demo log holds the S3 secret: ")(output.contains(Secret) shouldBe false)
        withClue(s"the demo log does not name the bucket:\n${logTail(log, 20)}\n") {
          output.contains("nosuchbucket") shouldBe true
        }
      }
      store.bucketMissing("nosuchbucket") shouldBe Right(true)
    finally store.stop()
  }

  "the demo on a local store" should
    "create its bucket, keep the configured listeners, and leave the objects to the store" in {
      requireEnvironment()
      val store = startStore(autoCreateBuckets = false)
      try
        val client = store.client("qod-demo")
        val prefix = "qod-demo-1a2b3c4d/"
        store.bucketMissing("qod-demo") shouldBe Right(true)
        withDemo(store, s"s3://qod-demo/${prefix.stripSuffix("/")}", local = true) {
          (_, restPort, quackPort, _) =>
            checkPolicies()
            checkLoopbackListeners(restPort, quackPort)
            lanAddress match
              case Some(addr) =>
                withClue(s"$addr:$restPort ")(accepts(addr, restPort) shouldBe true)
              case None => info("no non-loopback IPv4 address: the LAN listener check is skipped")
        }
        store.bucketMissing("qod-demo") shouldBe Right(false)
        listed(client, prefix, 1000) should not be empty
      finally store.stop()
    }
