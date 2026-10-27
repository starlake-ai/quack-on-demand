package ai.starlake.quack.edge.authz

import ai.starlake.quack.model.UserS3Credential
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

class UserS3CredentialsSpec extends AnyFlatSpec with Matchers:

  private def cred(id: String, user: String, scope: String, updatedAt: Long) =
    UserS3Credential(id, user, scope, "k", "s", None, None, None, None, 1L, updatedAt)

  private val A = cred("a" * 32, "u-1", "s3://b/x/", 10L)
  private val B = cred("b" * 32, "u-1", "s3://a/", 20L)

  "digestOf" should "be empty for no credential and hash the ids and update times, by id" in {
    UserS3Credentials.digestOf(Nil) shouldBe ""
    UserS3Credentials.digestOf(List(A, B)) should fullyMatch regex "[0-9a-f]{64}"
    UserS3Credentials.digestOf(List(B, A)) shouldBe UserS3Credentials.digestOf(List(A, B))
    CanonicalJson.render(UserS3Credentials.digestJson(List(B, A))) shouldBe
      s"""[["${"a" * 32}",10],["${"b" * 32}",20]]"""
  }

  it should "change with any added, removed or replaced credential, and nothing else" in {
    val base = UserS3Credentials.digestOf(List(A, B))
    UserS3Credentials.digestOf(List(A)) should not be base
    UserS3Credentials.digestOf(List(A, B.copy(updatedAt = 21L))) should not be base
    UserS3Credentials.digestOf(List(A, B, cred("c" * 32, "u-1", "s3://c/", 1L))) should not be base
    // The keys themselves are not in the digest: a replace always moves updatedAt.
    UserS3Credentials.digestOf(List(A, B.copy(accessKeyId = "other"))) shouldBe base
  }

  "the cache" should "serve each user's set by scope and its digest, and none for others" in {
    val c = new UserS3CredentialCache(() => List(A, B, cred("c" * 32, "u-2", "s3://c/", 1L)))
    c.of("u-1") shouldBe Nil
    c.refresh()
    c.of("u-1") shouldBe List(B, A)
    c.digest("u-1") shouldBe UserS3Credentials.digestOf(List(A, B))
    c.of("nobody") shouldBe Nil
    c.digest("nobody") shouldBe ""
    UserS3Credentials.none.digest("u-1") shouldBe ""
  }

  it should "follow the store on refresh, and keep the last sets when the store fails" in {
    val rows  = new AtomicReference(List(A))
    val fails = new AtomicBoolean(false)
    val c     = new UserS3CredentialCache(() =>
      if fails.get() then throw new RuntimeException("pg down") else rows.get()
    )
    c.refresh()
    rows.set(Nil)
    fails.set(true)
    c.refresh()
    c.of("u-1") shouldBe List(A)
    fails.set(false)
    c.refresh()
    c.of("u-1") shouldBe Nil
  }
