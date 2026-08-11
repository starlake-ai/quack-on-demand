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

  "a root mount with spaFallback=false" should "serve index at / and a real 404 elsewhere" in {
    val harness = bootWith(List(StaticMount("/", "/www-test", spaFallback = false)))
    try
      val root = get(harness.httpClient, s"${harness.baseUrl}/")
      root.statusCode() shouldBe 200
      root.body() should include("www-index")

      val missing = get(harness.httpClient, s"${harness.baseUrl}/no/such/page")
      missing.statusCode() shouldBe 404
      missing.body() should include("www-not-found")
    finally harness.shutdown()
  }

  it should "resolve nested directory-index pages on classpath-backed mounts" in {
    val harness = bootWith(List(StaticMount("/", "/www-test", spaFallback = false)))
    try
      val pricing = get(harness.httpClient, s"${harness.baseUrl}/pricing/")
      pricing.statusCode() shouldBe 200
      pricing.body() should include("www-pricing")
    finally harness.shutdown()
  }

  "a non-root mount with spaFallback=false" should "serve its own index and a real 404" in {
    val harness = bootWith(List(StaticMount("/legal", "/www-test", spaFallback = false)))
    try
      val index = get(harness.httpClient, s"${harness.baseUrl}/legal/")
      index.statusCode() shouldBe 200
      index.body() should include("www-index")

      val missing = get(harness.httpClient, s"${harness.baseUrl}/legal/no/such")
      missing.statusCode() shouldBe 404
      missing.body() should include("www-not-found")
    finally harness.shutdown()
  }

  it should "lose to longer-prefix mounts regardless of declaration order" in {
    val harness = bootWith(
      List(
        StaticMount("/", "/www-test", spaFallback = false),
        StaticMount("/portal", "/portal-test")
      )
    )
    try
      val portal = get(harness.httpClient, s"${harness.baseUrl}/portal/")
      portal.statusCode() shouldBe 200
      portal.body() should include("portal-index")
    finally harness.shutdown()
  }

  "the bare root with no root mount" should "still redirect to /ui/" in {
    val harness = bootWith(List(StaticMount("/portal", "/portal-test")))
    try
      // SecurityHttpHelpers' client uses HttpClient.newHttpClient(), whose
      // default redirect policy is NEVER, so the 302 itself is observable
      // instead of being auto-followed.
      val resp = get(harness.httpClient, s"${harness.baseUrl}/")
      resp.statusCode() shouldBe 302
      resp.headers().firstValue("Location").get() shouldBe "/ui/"
    finally harness.shutdown()
  }
