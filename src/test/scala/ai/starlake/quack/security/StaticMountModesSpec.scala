package ai.starlake.quack.security

import ai.starlake.quack.spi.StaticMount
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

/** Pins the diskDir override added for the marketing site: a mount with a diskDir that exists is
  * served from the filesystem (live-updatable content), and falls back to the classpath resources
  * when diskDir is absent or missing on disk.
  */
class StaticMountModesSpec extends AnyFlatSpec with Matchers with SecurityHttpHelpers:

  private def bootWith(mounts: List[StaticMount]): ManagerServerHarness.Harness =
    val fix = SecurityFixtures.freshStore()
    ManagerServerHarness.boot(fix.store, moduleStaticMounts = mounts)

  "a mount with a diskDir" should "serve files from disk instead of the classpath" in {
    val dir = Files.createTempDirectory("qod-www-disk")
    Files.writeString(dir.resolve("index.html"), "<html><body>disk-index</body></html>")
    val harness =
      bootWith(List(StaticMount("/www", "/www-test", diskDir = Some(dir.toString))))
    try
      val resp = get(harness.httpClient, s"${harness.baseUrl}/www/")
      resp.statusCode() shouldBe 200
      resp.body() should include("disk-index")
      resp.body() should not include "www-index"
    finally harness.shutdown()
  }

  it should "fall back to classpath resources when the diskDir does not exist" in {
    val harness = bootWith(
      List(StaticMount("/www", "/www-test", diskDir = Some("/nonexistent/qod-www")))
    )
    try
      val resp = get(harness.httpClient, s"${harness.baseUrl}/www/")
      resp.statusCode() shouldBe 200
      resp.body() should include("www-index")
    finally harness.shutdown()
  }

  "a mount without diskDir" should "keep serving classpath resources" in {
    val harness = bootWith(List(StaticMount("/www", "/www-test")))
    try
      val resp = get(harness.httpClient, s"${harness.baseUrl}/www/")
      resp.statusCode() shouldBe 200
      resp.body() should include("www-index")
    finally harness.shutdown()
  }
