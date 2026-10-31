package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.edge.adapter.*
import ai.starlake.quack.edge.authz.{NodeKeys, NodePrincipal}
import cats.effect.unsafe.implicits.global
import org.apache.arrow.memory.RootAllocator
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path, Paths}

/** A node one protocol behind, started from the vendored binary (native/qod-node/binaries), which
  * this build's manager reaches with its own handles. Such a node enforces nothing in the engine
  * and reads only the handle members it names, so nothing it does with a handle can be trusted: it
  * must refuse every handle this manager mints, the probe's system handle included, and so stay
  * unhealthy and never routed to on every backend (Kubernetes pods and fleet servers are not
  * checked by QodNodeBinary). Cancelled where nothing is vendored for this platform, or once the
  * vendored binary speaks this build's protocol.
  */
class OldProtocolNodeSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val allocator         = new RootAllocator()
  private val client            = new QuackHttpClient(allocator)
  private var dir: Path         = null
  private var live: LiveQodNode = null
  private var skipped           = "no qod-node is vendored for this platform"
  private val keys              = NodeKeys.ephemeral()
  private val Alice             =
    NodePrincipal("u-1", "alice", Some("acme"), superuser = false, system = false)

  override def beforeAll(): Unit =
    val vendored = QuackNativeSupport
      .platform()
      .map(p => Paths.get("native/qod-node/binaries", p, "qod-node"))
      .filter(Files.isRegularFile(_))
    vendored.foreach { file =>
      dir = Files.createTempDirectory("qod-node-old-protocol")
      QodNodeBinary.install(Files.readAllBytes(file), QuackNativeSupport.libDuckDbDir, dir) match
        case Left(why)  => skipped = why
        case Right(bin) =>
          QodNodeBinary.runBuildInfo(bin).toOption.flatMap(QodNodeBinary.parseMarker) match
            case Some(info) if info.protocol < QodNodeBinary.Protocol =>
              live = LiveQodNode.start(
                dir,
                "CREATE TABLE answers AS SELECT 42 AS v;\n",
                keys = keys,
                bin = Some(bin)
              )
            case Some(info) =>
              skipped = s"the vendored qod-node speaks node protocol ${info.protocol}"
            case None => skipped = s"the vendored qod-node does not start here"
    }

  override def afterAll(): Unit =
    try if live != null then live.stop()
    finally
      allocator.close()
      if dir != null then
        val tree = Files.walk(dir)
        try tree.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.delete(p))
        catch
          case e: Exception =>
            System.err.println(s"[OldProtocolNodeSpec] afterAll: could not fully delete $dir: $e")
        finally tree.close()

  private def node: LiveQodNode =
    assume(live != null, skipped)
    live

  "a node of an earlier protocol" should "fail the health probe on this manager's handle" in {
    new QuackHttpAdapter(client, new NodeLoadTracker, node.keys)
      .probe(node.node)
      .unsafeRunSync() match
      case ProbeVerdict.HandleRefused(answer) => answer should include("unsupported handle version")
      case other                              => fail(s"expected HandleRefused, got $other")
  }

  it should "run no statement of an ordinary principal" in {
    node.query(client, "SELECT v FROM answers", Alice) match
      case Left(m)  => m should include("unsupported handle version")
      case Right(r) => fail(s"a protocol-behind node ran a user statement: $r")
  }
