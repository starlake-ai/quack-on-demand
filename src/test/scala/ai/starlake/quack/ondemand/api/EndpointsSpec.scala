package ai.starlake.quack.ondemand.api

import ai.starlake.quack.model.RoleDistribution
import io.circe.parser._
import io.circe.syntax._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class EndpointsSpec extends AnyFlatSpec with Matchers:

  import Dtos.given

  "CreatePoolRequest" should "round-trip through JSON" in:
    val req = CreatePoolRequest(
      tenant = "acme",
      pool = "sales",
      size = 3,
      roleDistribution = RoleDistribution(0, 2, 1),
      metastore = Map("pgHost" -> "localhost"),
      s3 = Map.empty,
      maxConcurrentPerNode = 4
    )
    val js = req.asJson.noSpaces
    decode[CreatePoolRequest](js) shouldBe Right(req)

  "SetMaxConcurrentRequest" should "round-trip through JSON" in:
    val req = SetMaxConcurrentRequest("acme", "sales", "node-1", 8)
    decode[SetMaxConcurrentRequest](req.asJson.noSpaces) shouldBe Right(req)

  "NodeInfo" should "default maxConcurrent to 0 when absent from JSON" in:
    val json = """{"nodeId":"n1","role":"DUAL","host":"127.0.0.1","port":21900}"""
    decode[NodeInfo](json) shouldBe Right(NodeInfo("n1", "DUAL", "127.0.0.1", 21900, 0))