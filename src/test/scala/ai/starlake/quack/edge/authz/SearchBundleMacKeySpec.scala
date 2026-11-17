package ai.starlake.quack.edge.authz

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.jdk.CollectionConverters.*

/** The per-index search bundle MAC key (search spec 13, Phase 1 Task 3): derived from the node-key
  * secret, never the per-node key, and pinned against qod_search_core and qod-node by the golden
  * file `native/qod-node/tests/golden/search-mac-v1.txt`, which this spec writes (QOD_CAPTURE set;
  * a `-D` never reaches the forked test JVM) and otherwise compares byte for byte. Test values
  * only: the secret is no real one.
  */
class SearchBundleMacKeySpec extends AnyFlatSpec with Matchers:

  private val GoldenPath   = Paths.get("native/qod-node/tests/golden/search-mac-v1.txt")
  private val GoldenSecret = "qod-golden-search-mac-secret-not-a-real-one-0123456789"
  private val GoldenIndex  = "0b6c2a4e-6f1d-4c8e-9a3b-7d5e1f2c3a4b"
  private val GoldenPath0  = "s3://lake/main/pages/ducklake-0193a1b2.parquet"

  /** 64 bytes, every value distinct, so a byte swapped or dropped on either side shows. */
  private val GoldenBody: Array[Byte] = Array.tabulate(64)(i => ((i * 37 + 11) & 0xff).toByte)

  private def keys(secret: String = GoldenSecret): NodeKeys =
    new NodeKeys(
      secret,
      "http://127.0.0.1:0",
      versions = PolicyVersions.zero,
      readOnlyCatalogs = ReadOnlyCatalogs.none
    )

  private def hex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString

  /** HMAC-SHA256 written out here, so the spec checks the derivation rather than repeating it. */
  private def hmac(key: Array[Byte], data: Array[Byte]): Array[Byte] =
    val m = Mac.getInstance("HmacSHA256")
    m.init(new SecretKeySpec(key, "HmacSHA256"))
    m.doFinal(data)

  private def utf8(s: String): Array[Byte] = s.getBytes(StandardCharsets.UTF_8)

  private val pathKey: String =
    hex(MessageDigest.getInstance("SHA-256").digest(utf8(GoldenPath0)))

  /** The bundle MAC of format 3: HMAC-SHA256(key, label || 0x0A || body), label
    * `qod-search-bundle|v3|<index id>|<path key>` (spec 6.2's label scheme).
    */
  private def bundleMac(key: Array[Byte], body: Array[Byte]): Array[Byte] =
    hmac(key, utf8(s"qod-search-bundle|v3|$GoldenIndex|$pathKey\n") ++ body)

  private def goldenText: String =
    val key = keys().searchBundleMacKey(GoldenIndex)
    List(
      "# The per-index search bundle MAC key and one bundle MAC, from fixed test inputs (no real",
      "# secret). SearchBundleMacKeySpec (Scala) writes this file with QOD_CAPTURE set and otherwise",
      "# compares it byte for byte; native/qod-search/tests/mac.rs and qod-node's search tests read it.",
      "# secret: the node-key secret's UTF-8 bytes, hex. key = HMAC-SHA256(secret,",
      "# \"qod-search-bundle-mac/v1|\" + index_id) (NodeKeys.searchBundleMacKey). path: a data file's",
      "# absolute path, and path_key its lowercase hex SHA-256 (spec 6.2). mac = HMAC-SHA256(key,",
      "# \"qod-search-bundle|v3|\" + index_id + \"|\" + path_key + \"\\n\" + body), the 32-byte trailer of a",
      "# format 3 bundle whose body is `body`. Regenerate every side together if the derivation, the",
      "# label or the format changes.",
      s"secret=${hex(utf8(GoldenSecret))}",
      s"index_id=$GoldenIndex",
      s"key=${hex(key)}",
      s"path=$GoldenPath0",
      s"path_key=$pathKey",
      s"body=${hex(GoldenBody)}",
      s"mac=${hex(bundleMac(key, GoldenBody))}"
    ).mkString("", "\n", "\n")

  private def golden(name: String): String =
    Files
      .readAllLines(GoldenPath, StandardCharsets.UTF_8)
      .asScala
      .filterNot(_.startsWith("#"))
      .collectFirst { case l if l.startsWith(s"$name=") => l.substring(name.length + 1) }
      .getOrElse(fail(s"no $name line in $GoldenPath"))

  "the golden file" should "be captured from the derivation" in {
    // A plain Boolean: ScalaTest's assume prints the values of its expression when it cancels.
    val capture: Boolean = sys.env.contains("QOD_CAPTURE")
    assume(capture, "set QOD_CAPTURE to capture the golden file")
    Files.write(GoldenPath, goldenText.getBytes(StandardCharsets.UTF_8))
    info(s"wrote ${GoldenPath.toAbsolutePath}")
  }

  it should "equal what the derivation gives now" in {
    new String(Files.readAllBytes(GoldenPath), StandardCharsets.UTF_8) shouldBe goldenText
  }

  "searchBundleMacKey" should "give the golden key from the golden secret and index id" in {
    golden("secret") shouldBe hex(utf8(GoldenSecret))
    golden("index_id") shouldBe GoldenIndex
    hex(keys().searchBundleMacKey(GoldenIndex)) shouldBe golden("key")
  }

  it should "be HMAC-SHA256 of the node-key secret over the versioned label, 32 bytes" in {
    val key = keys().searchBundleMacKey(GoldenIndex)
    key should have length 32
    key shouldBe hmac(utf8(GoldenSecret), utf8(s"qod-search-bundle-mac/v1|$GoldenIndex"))
  }

  it should "give the golden MAC over the golden body" in {
    golden("path") shouldBe GoldenPath0
    golden("path_key") shouldBe pathKey
    golden("body") shouldBe hex(GoldenBody)
    hex(bundleMac(keys().searchBundleMacKey(GoldenIndex), GoldenBody)) shouldBe golden("mac")
  }

  it should "give different keys for different index ids and different secrets" in {
    val ids = List(
      GoldenIndex,
      "0b6c2a4e-6f1d-4c8e-9a3b-7d5e1f2c3a4c",
      "00000000-0000-0000-0000-000000000000",
      "ffffffff-ffff-ffff-ffff-ffffffffffff"
    )
    val derived = ids.map(id => hex(keys().searchBundleMacKey(id)))
    derived.distinct should have size ids.size.toLong
    hex(keys(GoldenSecret + "x").searchBundleMacKey(GoldenIndex)) should not be golden("key")
  }

  it should "hand out a fresh array each call" in {
    val k     = keys()
    val first = k.searchBundleMacKey(GoldenIndex)
    java.util.Arrays.fill(first, 0.toByte)
    hex(k.searchBundleMacKey(GoldenIndex)) shouldBe golden("key")
  }

  it should "refuse an index id that is not a canonical lowercase hyphenated UUID" in {
    val refused = List(
      GoldenIndex.toUpperCase,
      "0B6C2A4E-6f1d-4c8e-9a3b-7d5e1f2c3a4b",
      s"{$GoldenIndex}",
      GoldenIndex.dropRight(1),
      GoldenIndex + "0",
      GoldenIndex.replace("-", ""),
      s" $GoldenIndex",
      s"$GoldenIndex ",
      s"$GoldenIndex\n",
      "0b6c2a4e_6f1d_4c8e_9a3b_7d5e1f2c3a4b",
      "0b6c2a4e-6f1d-4c8e-9a3b-7d5e1f2c3a4g",
      // A full-width digit: a Unicode-aware digit test would take it.
      "\uff10b6c2a4e-6f1d-4c8e-9a3b-7d5e1f2c3a4",
      ""
    )
    for id <- refused do
      withClue(s"<$id>") {
        val e = intercept[IllegalArgumentException](keys().searchBundleMacKey(id))
        e.getMessage shouldBe "the index id is not a canonical lowercase hyphenated UUID"
      }
    an[IllegalArgumentException] should be thrownBy keys().searchBundleMacKey(null)
  }

  it should "differ from every node's key" in {
    val k       = keys()
    val macKeys = List(GoldenIndex, "00000000-0000-0000-0000-000000000000")
      .map(id => hex(k.searchBundleMacKey(id)))
      .toSet
    val nodeIds = List(
      "quack-acme-db-bi-1",
      "quack--acme--lake--bi--0",
      GoldenIndex,
      s"|$GoldenIndex",
      s"qod-search-bundle-mac/v1|$GoldenIndex",
      "00000000-0000-0000-0000-000000000000",
      ""
    ) ++ (0 until 200).map(i => s"quack--t$i--d--p--$i")
    for n <- nodeIds do withClue(n)(macKeys should not contain hex(k.key(n)))
  }

  it should "not show the secret or a key in the keys' text" in {
    val k = keys()
    k.toString should not include GoldenSecret
    k.toString should not include golden("key")
  }
