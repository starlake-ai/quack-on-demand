package ai.starlake.quack.ondemand.api

import ai.starlake.quack.security.{ManagerServerHarness, SecurityFixtures, SecurityHttpHelpers}
import io.circe.parser.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.http.HttpResponse

/** REST surface of per-tenant OPA authorization: `tenant/setAcl` (token write-only) and the
  * `tenant/opaTest` dry run.
  */
class TenantAclApiSpec extends AnyFlatSpec with Matchers with SecurityHttpHelpers:

  private val t    = SecurityFixtures.TenantId
  private val pool = SecurityFixtures.PoolName
  private val user = SecurityFixtures.BobUsername

  private def withHarness(addTenantB: Boolean = false)(
      body: ManagerServerHarness.Harness => Unit
  ): Unit =
    val fix = SecurityFixtures.freshStore()
    if addTenantB then SecurityFixtures.addTenantB(fix)
    val h = ManagerServerHarness.boot(fix.store)
    try body(h)
    finally h.shutdown()

  private def postJson(h: ManagerServerHarness.Harness, path: String, json: String) =
    post(h.httpClient, s"${h.baseUrl}$path", json, apiKey = Some(h.superuserSession()))

  private def code(r: HttpResponse[String]): String =
    withClue(s"body: ${r.body()}")(errorCode(r.body()).getOrElse(""))

  private def cursor(r: HttpResponse[String]) =
    withClue(s"status ${r.statusCode()} body: ${r.body()}") {
      r.statusCode() shouldBe 200
      parse(r.body()).toOption.get.hcursor
    }

  "POST /api/tenant/setAcl" should
    "set opa mode, never echo the token, preserve on omit, clear on empty" in withHarness() { h =>
      val r1 = postJson(
        h,
        "/api/tenant/setAcl",
        s"""{"name":"$t","mode":"opa","opaUrl":"http://opa:8181","opaToken":"s3cr3t-tok"}"""
      )
      val j1 = cursor(r1)
      r1.body() should not include "s3cr3t-tok"
      j1.get[String]("aclMode") shouldBe Right("opa")
      j1.get[String]("opaUrl") shouldBe Right("http://opa:8181")
      j1.get[Boolean]("opaTokenSet") shouldBe Right(true)
      j1.downField("opaToken").succeeded shouldBe false

      val r2 = postJson(h, "/api/tenant/setAcl", s"""{"name":"$t","opaPolicyPath":"acme/authz"}""")
      val j2 = cursor(r2)
      j2.get[Boolean]("opaTokenSet") shouldBe Right(true)
      j2.get[String]("opaPolicyPath") shouldBe Right("acme/authz")
      j2.get[String]("aclMode") shouldBe Right("opa")

      val r3 = postJson(h, "/api/tenant/setAcl", s"""{"name":"$t","opaToken":""}""")
      cursor(r3).get[Boolean]("opaTokenSet") shouldBe Right(false)

      // The listing carries the same write-only shape.
      val list =
        get(h.httpClient, s"${h.baseUrl}/api/tenant/list", apiKey = Some(h.superuserSession()))
      list.body() should not include "s3cr3t-tok"

      // mode "" clears back to the manager default (qod in the harness).
      val r4 = postJson(h, "/api/tenant/setAcl", s"""{"name":"$t","mode":""}""")
      cursor(r4).get[Option[String]]("aclMode") shouldBe Right(None)
    }

  it should "persist the acl, and keep it through a later setAuth of the same tenant" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      cursor(
        postJson(h, "/api/tenant/setAcl", s"""{"name":"$t","mode":"opa","opaUrl":"http://opa"}""")
      )
      fix.store.listTenants().find(_.id == t).map(_.acl.mode) shouldBe Some(Some("opa"))
      cursor(postJson(h, "/api/tenant/setAuth", s"""{"name":"$t","authProvider":"db"}"""))
      fix.store.listTenants().find(_.id == t).map(_.acl.opaUrl) shouldBe Some(Some("http://opa"))
    finally h.shutdown()
  }

  it should "clear the stored token when opaUrl changes without a new token" in withHarness() { h =>
    cursor(
      postJson(
        h,
        "/api/tenant/setAcl",
        s"""{"name":"$t","mode":"opa","opaUrl":"http://opa:8181","opaToken":"s3cr3t-tok"}"""
      )
    ).get[Boolean]("opaTokenSet") shouldBe Right(true)
    // Same URL (and a patch that does not mention it): the token stays.
    cursor(
      postJson(h, "/api/tenant/setAcl", s"""{"name":"$t","opaUrl":"http://opa:8181"}""")
    ).get[Boolean]("opaTokenSet") shouldBe Right(true)
    // A new URL with no token in the same patch: the old bearer must never follow it.
    val moved = cursor(
      postJson(h, "/api/tenant/setAcl", s"""{"name":"$t","opaUrl":"http://other:8181"}""")
    )
    moved.get[String]("opaUrl") shouldBe Right("http://other:8181")
    moved.get[Boolean]("opaTokenSet") shouldBe Right(false)
    // A new URL WITH a token in the same patch keeps that token.
    cursor(
      postJson(
        h,
        "/api/tenant/setAcl",
        s"""{"name":"$t","opaUrl":"http://third:8181","opaToken":"t2"}"""
      )
    ).get[Boolean]("opaTokenSet") shouldBe Right(true)
  }

  it should "400 on bad mode, bad URL, bad policy path, and opa without any URL" in withHarness() {
    h =>
      code(postJson(h, "/api/tenant/setAcl", s"""{"name":"$t","mode":"x"}""")) shouldBe
        "invalid_acl_mode"
      code(postJson(h, "/api/tenant/setAcl", s"""{"name":"$t","mode":"OPA"}""")) shouldBe
        "invalid_acl_mode"
      code(postJson(h, "/api/tenant/setAcl", s"""{"name":"$t","opaUrl":"opa:1"}""")) shouldBe
        "invalid_opa_url"
      val creds =
        postJson(h, "/api/tenant/setAcl", s"""{"name":"$t","opaUrl":"http://u:s3cr3t@opa:1"}""")
      code(creds) shouldBe "invalid_opa_url"
      creds.body() should not include "s3cr3t"
      creds.body() should include("without credentials")
      code(postJson(h, "/api/tenant/setAcl", s"""{"name":"$t","opaPolicyPath":"../x"}""")) shouldBe
        "invalid_opa_policy_path"
      code(
        postJson(h, "/api/tenant/setAcl", s"""{"name":"$t","mode":"opa","opaUrl":""}""")
      ) shouldBe "opa_url_required"
      postJson(h, "/api/tenant/setAcl", s"""{"name":"$t","mode":"x"}""").statusCode() shouldBe 400
  }

  it should "403 a foreign-tenant admin and 404 an unknown tenant" in withHarness(addTenantB =
    true
  ) { h =>
    val aliceToken = h.mintToken(
      SecurityFixtures.AliceUsername,
      SecurityFixtures.AlicePassword,
      Some(SecurityFixtures.TenantId)
    )
    val foreign = post(
      h.httpClient,
      s"${h.baseUrl}/api/tenant/setAcl",
      s"""{"name":"${SecurityFixtures.GlobexTenantId}","mode":"qod"}""",
      apiKey = Some(aliceToken)
    )
    foreign.statusCode() shouldBe 403
    code(foreign) shouldBe "tenant_forbidden"
    val foreignTest = post(
      h.httpClient,
      s"${h.baseUrl}/api/tenant/opaTest",
      s"""{"tenant":"${SecurityFixtures.GlobexTenantId}","pool":"bi","user":"dave"}""",
      apiKey = Some(aliceToken)
    )
    foreignTest.statusCode() shouldBe 403
    postJson(h, "/api/tenant/setAcl", """{"name":"nope","mode":"qod"}""").statusCode() shouldBe 404
  }

  "POST /api/tenant/opaTest" should
    "return the exact input and an error outcome when OPA is unreachable" in withHarness() { h =>
      cursor(
        postJson(
          h,
          "/api/tenant/setAcl",
          s"""{"name":"$t","mode":"opa","opaUrl":"http://127.0.0.1:1"}"""
        )
      )
      val r = postJson(
        h,
        "/api/tenant/opaTest",
        s"""{"tenant":"$t","pool":"$pool","user":"$user","sql":"SELECT * FROM orders"}"""
      )
      val c = cursor(r)
      c.get[String]("rule") shouldBe Right("statement")
      c.get[String]("outcome") shouldBe Right("error")
      val in = c.downField("input").downField("input")
      in.get[String]("kind") shouldBe Right("statement")
      in.get[String]("tenant") shouldBe Right(t)
      in.get[String]("pool") shouldBe Right(pool)
      in.downField("user").get[String]("name") shouldBe Right(user)
      in.downField("statement").get[String]("class") shouldBe Right("READ")
      // sendStatementText is off by default: the text never leaves the manager.
      in.downField("statement").downField("text").succeeded shouldBe false
      in.downField("accesses").downArray.get[String]("table") shouldBe Right("orders")
    }

  it should "build a connect input when no SQL is given" in withHarness() { h =>
    cursor(
      postJson(
        h,
        "/api/tenant/setAcl",
        s"""{"name":"$t","mode":"opa","opaUrl":"http://127.0.0.1:1"}"""
      )
    )
    val c = cursor(
      postJson(h, "/api/tenant/opaTest", s"""{"tenant":"$t","pool":"$pool","user":"$user"}""")
    )
    c.get[String]("rule") shouldBe Right("connect")
    c.downField("input").downField("input").get[String]("kind") shouldBe Right("connect")
  }

  it should "400 an unknown pool or user" in withHarness() { h =>
    code(
      postJson(h, "/api/tenant/opaTest", s"""{"tenant":"$t","pool":"nope","user":"$user"}""")
    ) shouldBe "not_found"
    code(
      postJson(h, "/api/tenant/opaTest", s"""{"tenant":"$t","pool":"$pool","user":"nobody"}""")
    ) shouldBe "not_found"
  }

  it should "give a tenant admin a generic transport error and a superuser the detail" in
    withHarness() { h =>
      cursor(
        postJson(
          h,
          "/api/tenant/setAcl",
          s"""{"name":"$t","mode":"opa","opaUrl":"http://127.0.0.1:1"}"""
        )
      )
      val body = s"""{"tenant":"$t","pool":"$pool","user":"$user"}"""
      // A superuser: the transport detail, for debugging.
      val detailed = cursor(postJson(h, "/api/tenant/opaTest", body))
      detailed.get[String]("outcome") shouldBe Right("error")
      detailed.get[String]("reason").toOption.get should not be OpaDryRun.GenericTransportError
      // A tenant admin chooses the URL, so a detailed error would make opaTest a network probe.
      val aliceToken = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val r =
        post(h.httpClient, s"${h.baseUrl}/api/tenant/opaTest", body, apiKey = Some(aliceToken))
      val c = cursor(r)
      c.get[String]("outcome") shouldBe Right("error")
      c.get[String]("reason") shouldBe Right(OpaDryRun.GenericTransportError)
    }

  it should "404 a superuser row exactly like an unknown user" in withHarness() { h =>
    val su = postJson(
      h,
      "/api/tenant/opaTest",
      s"""{"tenant":"$t","pool":"$pool","user":"${SecurityFixtures.RootUsername}"}"""
    )
    su.statusCode() shouldBe 404
    code(su) shouldBe "not_found"
    su.body() should include(s"user '${SecurityFixtures.RootUsername}' not found in tenant")
  }
