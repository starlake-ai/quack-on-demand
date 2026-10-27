package ai.starlake.quack.edge.authz

import ai.starlake.quack.model.UserS3Credential
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}

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

  it should "follow the store on refresh, and serve nothing while a refresh has failed" in {
    val rows  = new AtomicReference(List(A))
    val fails = new AtomicBoolean(false)
    val clock = new AtomicLong(1_000_000L)
    val c     = new UserS3CredentialCache(
      () => if fails.get() then throw new RuntimeException("pg down") else rows.get(),
      () => clock.get()
    )
    c.refresh()
    c.current("u-1") shouldBe Right(List(A))
    // A removal another write made, then a refresh that fails: the removed credential is never
    // served from the last sets.
    rows.set(Nil)
    fails.set(true)
    c.refresh()
    c.current("u-1") shouldBe Left(UserS3CredentialCache.StaleReason)
    c.of("u-1") shouldBe List(A)
    // The store is back: the next read past RetryMs reloads, with no RBAC mutation to ask for it.
    fails.set(false)
    c.current("u-1") shouldBe Left(UserS3CredentialCache.StaleReason)
    clock.addAndGet(UserS3CredentialCache.RetryMs)
    c.current("u-1") shouldBe Right(Nil)
    c.digest("u-1") shouldBe ""
  }

  it should "retry a failing store at most once per RetryMs" in {
    val loads = new AtomicLong(0L)
    val clock = new AtomicLong(1_000_000L)
    val c     = new UserS3CredentialCache(
      () => { loads.incrementAndGet(); throw new RuntimeException("pg down") },
      () => clock.get()
    )
    c.refresh()
    (1 to 5).foreach(_ => c.current("u-1").isLeft shouldBe true)
    loads.get() shouldBe 1L
    clock.addAndGet(UserS3CredentialCache.RetryMs)
    (1 to 5).foreach(_ => c.digest("u-1"))
    loads.get() shouldBe 2L
  }

  "the served view" should "withhold a credential whose scope now lies at or under a data folder" in {
    val folders = new AtomicReference[Either[String, List[String]]](Right(Nil))
    val etl     = cred("a" * 32, "u-1", "s3://acme-etl/x/", 10L)
    val inLake  = cred("c" * 32, "u-1", "s3://lake/root/salesdb/main/", 30L)
    val cache   = new UserS3CredentialCache(() => List(etl, inLake))
    cache.refresh()
    val served = new ServedUserS3Credentials(cache, () => folders.get())
    served.current("u-1").map(_.map(_.id).toSet) shouldBe Right(Set(etl.id, inLake.id))
    served.digest("u-1") shouldBe UserS3Credentials.digestOf(List(etl, inLake))
    // A database created afterwards at an ancestor of the scope.
    folders.set(Right(List("s3://lake/root/salesdb/")))
    served.current("u-1") shouldBe Right(List(etl))
    served.digest("u-1") shouldBe UserS3Credentials.digestOf(List(etl))
    // At the scope itself too; an ancestor scope of a data folder stays.
    folders.set(Right(List("s3://lake/root/salesdb/main/", "s3://acme-etl/x/y/")))
    served.current("u-1") shouldBe Right(List(etl))
    // Unreadable deny set, or a stale cache: nothing is served.
    folders.set(Left("the database data folders could not be read"))
    served.current("u-1") shouldBe Left("the database data folders could not be read")
    served.of("u-1") shouldBe Nil
    served.current("nobody") shouldBe Right(Nil)
  }
