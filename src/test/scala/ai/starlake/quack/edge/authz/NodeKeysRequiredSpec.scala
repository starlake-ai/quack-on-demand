package ai.starlake.quack.edge.authz

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

/** The node keys and the policy versions are never defaulted (Phase C review C-m1, Phase B T6-M6).
  * A wiring site that dropped the versions would mint version 0 forever, so no grant change would
  * flush a node's cached verdicts (the one fail-open default NodeKeys had); one that dropped the
  * keys would hand nodes keys of their own, and every handle would be refused. Leaving either out
  * must not compile. Each refusal is paired with the same construction given the argument, so it
  * cannot pass for another reason.
  */
class NodeKeysRequiredSpec extends AnyFlatSpec with Matchers:

  // Typed placeholders the snippets name: type-checked, never run.
  private val client: ai.starlake.quack.edge.adapter.QuackHttpClient        = null
  private val k8s: io.fabric8.kubernetes.client.KubernetesClient            = null
  private val fleetStore: ai.starlake.quack.ondemand.state.FleetServerStore = null
  private val tracker: ai.starlake.quack.edge.adapter.NodeLoadTracker       = null
  private val fleetCfg: ai.starlake.quack.FleetConfig                       = null

  // The snippets below are type-checked when this spec compiles, and a string records no
  // dependency: these compiled constructions (never run) make an incremental build recompile the
  // spec, and so check the snippets again, whenever one of these constructors changes.
  private def dependencies(): Unit =
    val keys = new NodeKeys(
      "k" * 32,
      "http://127.0.0.1:20900",
      versions = PolicyVersions.zero,
      readOnlyCatalogs = ReadOnlyCatalogs.none
    )
    new ai.starlake.quack.edge.adapter.QuackHttpAdapter(client, tracker, keys)
    new ai.starlake.quack.ondemand.runtime.LocalQuackBackend(23000, 23001, nodeKeys = keys)
    new ai.starlake.quack.ondemand.runtime.KubernetesQuackBackend(
      k8s,
      "ns",
      "img",
      8080,
      "a=b",
      5,
      nodeKeys = keys
    )
    new ai.starlake.quack.ondemand.api.FleetHandlers(fleetStore, fleetCfg, nodeKeys = keys)
    ()

  "NodeKeys" should "need the policy versions and the read-only catalogs" in {
    """new NodeKeys("k" * 32, "http://127.0.0.1:20900", readOnlyCatalogs = ReadOnlyCatalogs.none)""" shouldNot
      typeCheck
    """new NodeKeys("k" * 32, "http://127.0.0.1:20900", versions = PolicyVersions.zero)""" shouldNot
      typeCheck
    """new NodeKeys("k" * 32, "http://127.0.0.1:20900", versions = PolicyVersions.zero, readOnlyCatalogs = ReadOnlyCatalogs.none)""" should
      compile
  }

  "the adapter that mints session handles" should "need the manager's keys" in {
    """new ai.starlake.quack.edge.adapter.QuackHttpAdapter(client, tracker)""" shouldNot typeCheck
    """new ai.starlake.quack.edge.adapter.QuackHttpAdapter(client, tracker, NodeKeys.ephemeral())""" should
      compile
  }

  "the backends that hand nodes their keys" should "need the manager's keys" in {
    """new ai.starlake.quack.ondemand.runtime.LocalQuackBackend(23000, 23001)""" shouldNot typeCheck
    """new ai.starlake.quack.ondemand.runtime.LocalQuackBackend(23000, 23001, nodeKeys = NodeKeys.ephemeral())""" should
      compile
    """new ai.starlake.quack.ondemand.runtime.KubernetesQuackBackend(k8s, "ns", "img", 8080, "a=b", 5)""" shouldNot
      typeCheck
    """new ai.starlake.quack.ondemand.runtime.KubernetesQuackBackend(k8s, "ns", "img", 8080, "a=b", 5, nodeKeys = NodeKeys.ephemeral())""" should
      compile
    """new ai.starlake.quack.ondemand.api.FleetHandlers(fleetStore, fleetCfg)""" shouldNot typeCheck
    """new ai.starlake.quack.ondemand.api.FleetHandlers(fleetStore, fleetCfg, nodeKeys = NodeKeys.ephemeral())""" should
      compile
  }

  "production code" should "never build keys of its own" in {
    // NodeKeys.ephemeral() is a random secret: nodes started with the manager's keys refuse every
    // handle it mints. Only tests may use it.
    val root      = Paths.get("src/main/scala")
    val stream    = Files.walk(root)
    val offenders =
      try
        stream
          .iterator()
          .asScala
          .filter(p => p.toString.endsWith(".scala") && !p.endsWith("NodeKeys.scala"))
          .filter(p => Files.readString(p).contains("NodeKeys.ephemeral("))
          .map(p => root.relativize(p).toString)
          .toList
      finally stream.close()
    offenders shouldBe Nil
  }
