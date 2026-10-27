package ai.starlake.quack.edge.authz

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.jdk.CollectionConverters.*

class VerdictsSpec extends AnyFlatSpec with Matchers:

  private def golden(key: String): String =
    Files
      .readAllLines(Paths.get("native/qod-node/tests/golden/verdict-v1.txt"))
      .asScala
      .collectFirst { case l if l.startsWith(s"$key=") => l.drop(key.length + 1) }
      .get

  private val Secret = "qod-golden-node-key-secret-0123456789"
  private val Node   = "quack-acme-db-bi-1"
  private val Key    = NodeHandles.nodeKey(Secret, Node)

  private val Allow = Verdict(
    kind = "plan",
    decision = "allow",
    reason = "",
    retryable = false,
    principal = "u-1",
    policyVersion = 42L,
    fingerprint = golden("fingerprint"),
    expiresAt = 1761200060000L,
    ctx = golden("ctx")
  )

  "Verdicts.bytes" should "be the contract's canonical verdict JSON" in {
    new String(Verdicts.bytes(Allow), UTF_8) shouldBe
      s"""{"ctx":"${golden("ctx")}","decision":"allow","expiresAt":1761200060000,""" +
      s""""fingerprint":"${golden("fingerprint")}","kind":"plan","masks":[],"policyVersion":42,""" +
      """"principal":"u-1","reason":"","retryable":false,"rowFilters":[],"v":1}"""
  }

  "Verdicts.sign" should "produce exactly the golden verdicts qod-node verifies" in {
    NodeHandles.nodeKeyHex(Secret, Node) shouldBe golden("key")
    Verdicts.sign(Allow, Key) shouldBe golden("allow")
    Verdicts.sign(
      Allow.copy(decision = "refuse", reason = "function lower is not classified"),
      Key
    ) shouldBe golden("refuse")
  }

  it should "MAC the verdict bytes as sent, under the node key" in {
    Verdicts.sign(Allow, Key).split('.').toList match
      case List(b64, mac) =>
        val bytes = Base64.getUrlDecoder.decode(b64)
        bytes shouldBe Verdicts.bytes(Allow)
        b64.contains('=') shouldBe false
        val m = Mac.getInstance("HmacSHA256")
        m.init(new SecretKeySpec(Key, "HmacSHA256"))
        mac shouldBe m.doFinal(bytes).map(b => f"${b & 0xff}%02x").mkString
      case other => fail(s"not <payload>.<mac>: $other")
    Verdicts.sign(Allow, NodeHandles.nodeKey(Secret, "another-node")) should not be golden("allow")
  }

  "the golden fingerprint" should "be the SHA-256 of the golden description" in {
    MessageDigest
      .getInstance("SHA-256")
      .digest(golden("description").getBytes(UTF_8))
      .map(b => f"${b & 0xff}%02x")
      .mkString shouldBe golden("fingerprint")
  }
