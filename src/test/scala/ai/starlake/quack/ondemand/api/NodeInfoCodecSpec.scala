package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.api.Dtos.given
import io.circe.parser.decode
import io.circe.syntax._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** NodeInfo has a HAND-ROLLED codec (Dtos.scala) with an explicit field list - a new case-class
  * field silently never reaches the wire unless both the encoder and decoder are extended. This
  * spec pins the round-trip so that failure mode shows up in CI instead of in a live manager.
  */
class NodeInfoCodecSpec extends AnyFlatSpec with Matchers:

  private val full = NodeInfo(
    nodeId = "n1",
    role = "ReadOnly",
    host = "127.0.0.1",
    port = 21900,
    maxConcurrent = 4,
    inFlight = 1,
    totalServed = 42L,
    avgDurationMs = 12.5,
    p50Ms = 10.0,
    p95Ms = 20.0,
    p99Ms = 30.0,
    healthy = true,
    draining = false,
    duckdbMemoryBytes = Some(6647281L),
    duckdbTempStorageBytes = Some(1024L),
    duckdbSpillFiles = Some(2L),
    duckdbSpillBytes = Some(4096L)
  )

  "NodeInfo codec" should "round-trip every field including the engine stats" in:
    decode[NodeInfo](full.asJson.noSpaces) shouldBe Right(full)

  it should "emit the duckdb engine-stat keys on the wire" in:
    val obj = full.asJson.asObject.get
    obj("duckdbMemoryBytes").flatMap(_.asNumber).flatMap(_.toLong) shouldBe Some(6647281L)
    obj("duckdbTempStorageBytes").flatMap(_.asNumber).flatMap(_.toLong) shouldBe Some(1024L)
    obj("duckdbSpillFiles").flatMap(_.asNumber).flatMap(_.toLong) shouldBe Some(2L)
    obj("duckdbSpillBytes").flatMap(_.asNumber).flatMap(_.toLong) shouldBe Some(4096L)

  it should "decode legacy JSON without the engine-stat keys to None" in:
    val legacy = """{"nodeId":"n1","role":"Dual","host":"h","port":1}"""
    val n      = decode[NodeInfo](legacy).toOption.get
    n.duckdbMemoryBytes shouldBe None
    n.duckdbTempStorageBytes shouldBe None
    n.duckdbSpillFiles shouldBe None
    n.duckdbSpillBytes shouldBe None
