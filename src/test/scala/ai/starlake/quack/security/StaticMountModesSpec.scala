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

  "cache headers on a non-SPA mount" should "mark hashed assets immutable" in {
    val harness = bootWith(List(StaticMount("/", "/www-test", spaFallback = false)))
    try
      val asset = get(harness.httpClient, s"${harness.baseUrl}/assets/app-a1b2c3d4.js")
      asset.statusCode() shouldBe 200
      asset.headers().firstValue("Cache-Control").get() shouldBe
        "public, max-age=31536000, immutable"

      val page = get(harness.httpClient, s"${harness.baseUrl}/")
      page.headers().firstValue("Cache-Control").get() shouldBe
        "public, max-age=300, stale-while-revalidate=600"

      val missing = get(harness.httpClient, s"${harness.baseUrl}/no/such/page")
      missing.headers().firstValue("Cache-Control").get() shouldBe "no-store"
    finally harness.shutdown()
  }

  "an extensionless zero-byte file on a non-SPA mount" should "still 404, not be served as 200-empty" in {
    // Pins the live-smoke jar bug: `sbt assembly` packs zero-byte directory
    // entries into the classpath jar, and http4s' resourceServiceBuilder
    // matched a bare-directory request against one and served it as a 200
    // with an empty body, ahead of the directory-index fallback. `blank` is a
    // real zero-byte extensionless file, which reproduces the same "assets
    // wins over pages" ordering bug on plain file: classpath URLs too - pre-fix
    // this asserts 200-empty, post-fix the pages route owns it and 404s.
    val harness = bootWith(List(StaticMount("/", "/www-test", spaFallback = false)))
    try
      val resp = get(harness.httpClient, s"${harness.baseUrl}/blank")
      resp.statusCode() shouldBe 404
      resp.body() should include("www-not-found")
      resp.headers().firstValue("Cache-Control").get() shouldBe "no-store"
    finally harness.shutdown()
  }

  "cache headers" should "not be added to SPA mounts" in {
    val harness = bootWith(List(StaticMount("/portal", "/portal-test")))
    try
      val resp = get(harness.httpClient, s"${harness.baseUrl}/portal/")
      resp.headers().firstValue("Cache-Control").isPresent shouldBe false
    finally harness.shutdown()
  }

  "a root mount with a diskDir" should "refuse a dot-segment path instead of resolving StaticFile.fromPath outside the mount root" in {
    // Finding: bounded directory traversal via unnormalized `..` in pathInfo on
    // disk-mode non-SPA static mounts (StaticFile.fromPath has no containment
    // guard, unlike classpath mode's getResource).
    val parent = Files.createTempDirectory("qod-www-disk-trav")
    val root   = Files.createDirectory(parent.resolve("root"))
    Files.writeString(root.resolve("index.html"), "<html><body>disk-index</body></html>")
    Files.writeString(root.resolve("404.html"), "<html><body>disk-not-found</body></html>")
    val outside = Files.createDirectory(parent.resolve("outside"))
    Files.writeString(outside.resolve("index.html"), "escaped")
    val harness = bootWith(
      List(StaticMount("/", "/www-test", diskDir = Some(root.toString), spaFallback = false))
    )
    try
      // Percent-encoded so java.net.URI / HttpClient send the ".." to the
      // server verbatim instead of normalizing it away client-side.
      val resp = get(harness.httpClient, s"${harness.baseUrl}/%2e%2e/outside/")
      resp.statusCode() shouldBe 404
      resp.body() should include("disk-not-found")
      resp.body() should not include "escaped"
    finally harness.shutdown()
  }

  it should "resolve percent-encoded spaces in the directory-index candidate" in {
    val dir = Files.createTempDirectory("qod-www-disk-space")
    Files.writeString(dir.resolve("index.html"), "<html><body>disk-index</body></html>")
    val ourTeam = Files.createDirectory(dir.resolve("our team"))
    Files.writeString(ourTeam.resolve("index.html"), "www-space")
    val harness = bootWith(
      List(StaticMount("/", "/www-test", diskDir = Some(dir.toString), spaFallback = false))
    )
    try
      val resp = get(harness.httpClient, s"${harness.baseUrl}/our%20team/")
      resp.statusCode() shouldBe 200
      resp.body() should include("www-space")
    finally harness.shutdown()
  }
