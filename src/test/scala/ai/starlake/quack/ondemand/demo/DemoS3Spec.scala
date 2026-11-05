package ai.starlake.quack.ondemand.demo

import ai.starlake.quack.ManagedObjectStoreConfig
import ai.starlake.quack.ondemand.storage.S3ManagedStoreClient
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

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

/** The demo on S3, end to end: a real `weed`, real nodes, the Postgres catalog and the jar's Java
  * S3 client. Cancelled with no `weed` (`QOD_DEMO_WEED_BIN` or PATH) or no `duckdb` on PATH, as
  * [[DemoDifferentiatorSpec]] is. A cancel is not a pass.
  *
  * The demo reads `QOD_S3_*` from its process environment, so each case runs it in a child JVM. The
  * `weed` flags repeat `qod_cli.demo_s3.weed_command`, which `test_demo_s3.py` pins.
  */
class DemoS3Spec extends AnyFlatSpec with Matchers:

  private val FlightPort = 31338
  private val Key        = "demo-spec-key"
  private val Secret     = "demo-spec-secret-value-0123456789"

  private def onPath(exe: String): Option[String] =
    sys.env
      .getOrElse("PATH", "")
      .split(java.io.File.pathSeparator)
      .map(Paths.get(_, exe))
      .find(Files.isExecutable(_))
      .map(_.toString)

  private lazy val weedBin: Option[String] =
    sys.env.get("QOD_DEMO_WEED_BIN").filter(_.trim.nonEmpty).orElse(onPath("weed"))

  private def freePorts(n: Int): List[Int] =
    val socks = List.fill(n)(new ServerSocket(0))
    try socks.map(_.getLocalPort)
    finally socks.foreach(_.close())

  /** The demo's REST and Quack ports. The node verdict listener takes the REST port + 1, so that
    * port must be free too and must not be the Quack port: two ports from `freePorts(2)` are often
    * consecutive, and boot then refuses the Quack port as the verdict listener's.
    */
  @tailrec
  private def demoPorts(): (Int, Int) =
    val rest = new ServerSocket(0)
    Try(new ServerSocket(rest.getLocalPort + 1)).toOption match
      case Some(internal) =>
        val quack = new ServerSocket(0)
        try (rest.getLocalPort, quack.getLocalPort)
        finally List(rest, internal, quack).foreach(_.close())
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
    def stop(): Unit =
      proc.destroyForcibly()
      proc.waitFor(10, TimeUnit.SECONDS)
      DemoHome(dir, dir, dir, dir).deleteRecursively()

  /** `autoCreateBuckets` is SeaweedFS's default for an admin identity, unlike AWS: a case about a
    * missing bucket must turn it off, or the write check creates the bucket it should fail on.
    */
  private def startStore(autoCreateBuckets: Boolean = true): Store =
    val dir = Files.createTempDirectory("demo-s3-spec-store")
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
    val http     = HttpClient.newHttpClient()
    val req      = HttpRequest.newBuilder(java.net.URI.create(s"http://127.0.0.1:$s3/")).build()
    val deadline = System.nanoTime() + 60.seconds.toNanos
    var ready    = false
    while !ready && System.nanoTime() < deadline do
      ready = Try(http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()).toOption
        .contains(403)
      if !ready then Thread.sleep(100)
    if !ready then
      proc.destroyForcibly()
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

  /** Run the demo until `body` is done, then stop it the way a user does, with SIGTERM, so its
    * cleanup runs.
    */
  private def withDemo(store: Store, runFolder: String, local: Boolean)(
      body: (Int, Int) => Unit
  ): Unit =
    val home                  = Files.createTempDirectory("demo-s3-spec-home")
    val log                   = Files.createTempFile("demo-s3-spec", ".log")
    val (restPort, quackPort) = demoPorts()
    val proc                  = demoProcess(home, log, store, runFolder, local, restPort, quackPort)
    try
      try body(restPort, quackPort)
      catch
        case e: Throwable =>
          info(s"demo log tail:\n${Files.readAllLines(log).asScala.takeRight(40).mkString("\n")}")
          throw e
      proc.destroy()
      proc.waitFor(120, TimeUnit.SECONDS) shouldBe true
    finally
      if proc.isAlive then proc.destroyForcibly()
      Try(
        new ProcessBuilder("scripts/kill-quack-nodes.sh", "--port", restPort.toString)
          .redirectErrorStream(true)
          .redirectOutput(ProcessBuilder.Redirect.DISCARD)
          .start()
          .waitFor(30, TimeUnit.SECONDS)
      )
      DemoHome(home, home, home, home).deleteRecursively()
      Files.deleteIfExists(log)

  private def requireEnvironment(): Unit =
    if weedBin.isEmpty then cancel("no weed: set QOD_DEMO_WEED_BIN or put one on PATH")
    if onPath("duckdb").isEmpty then cancel("duckdb CLI not on PATH")
    if accepts(InetAddress.getLoopbackAddress, FlightPort) then
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

  "the demo on the user's bucket" should
    "serve from the run folder, listen on loopback only, and delete only the run folder" in {
      requireEnvironment()
      val store = startStore()
      try
        val client = store.client("demospec")
        client.ensureBucket() shouldBe Right(())
        client.putObject("qod/keep.txt", "keep".getBytes) shouldBe Right(())
        val prefix = "qod/qod-demo-1a2b3c4d/"
        withDemo(store, s"s3://demospec/${prefix.stripSuffix("/")}", local = false) {
          (restPort, quackPort) =>
            checkPolicies()
            val keys = client.listPrefix(prefix, 1000).toOption.get
            keys should not be empty
            all(keys) should startWith(s"${prefix}acme_tpch/")
            lanAddress.foreach { addr =>
              List(restPort, FlightPort, quackPort).foreach { port =>
                withClue(s"$addr:$port ")(accepts(addr, port) shouldBe false)
              }
            }
        }
        client.listPrefix(prefix, 1000) shouldBe Right(Nil)
        client.listPrefix("qod/keep.txt", 10) shouldBe Right(List("qod/keep.txt"))
      finally store.stop()
    }

  it should "stop before seeding when the bucket does not exist, and create none" in {
    requireEnvironment()
    val store = startStore(autoCreateBuckets = false)
    val home  = Files.createTempDirectory("demo-s3-spec-nobucket")
    val log   = Files.createTempFile("demo-s3-spec", ".log")
    try
      val (restPort, quackPort) = demoPorts()
      val proc                  = demoProcess(
        home,
        log,
        store,
        "s3://nosuchbucket/qod-demo-1a2b3c4d",
        false,
        restPort,
        quackPort
      )
      try
        proc.waitFor(90, TimeUnit.SECONDS) shouldBe true
        proc.exitValue() should not be 0
      finally if proc.isAlive then proc.destroyForcibly()
      val output = Files.readString(log)
      output should include("nosuchbucket")
      output should not include Secret
      store.client("nosuchbucket").listPrefix("", 1).isLeft shouldBe true
    finally
      store.stop()
      DemoHome(home, home, home, home).deleteRecursively()
      Files.deleteIfExists(log)
  }

  "the demo on a local store" should
    "create its bucket, keep the configured listeners, and leave the objects to the store" in {
      requireEnvironment()
      val store = startStore()
      try
        val client = store.client("qod-demo")
        val prefix = "qod-demo-1a2b3c4d/"
        withDemo(store, s"s3://qod-demo/${prefix.stripSuffix("/")}", local = true) {
          (restPort, _) =>
            checkPolicies()
            lanAddress.foreach(addr => accepts(addr, restPort) shouldBe true)
        }
        client.listPrefix(prefix, 1000).toOption.get should not be empty
      finally store.stop()
    }
