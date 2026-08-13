package ai.starlake.quack.ondemand.state

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/** Direct coverage for the managed-prefix (tombstone registry) methods on
  * [[InMemoryControlPlaneStore]]. Mirrors the two Postgres-backed cases in
  * [[PostgresControlPlaneStoreSpec]] so later purge-worker work can rely on both backends agreeing
  * on: the inclusive `purgeEligibleAt <= now` boundary, ordering by `purgeEligibleAt` ascending,
  * unknown-id marks being no-ops, and purged rows being excluded from `dueManagedPrefixes`. Plain
  * unit test -- no Postgres dependency.
  */
class InMemoryManagedPrefixSpec extends AnyFlatSpec with Matchers:

  private val tenant       = "tenant-1"
  private val tenantDbName = "acme_default"

  it should "walk the managed-prefix lifecycle" in {
    val store     = new InMemoryControlPlaneStore()
    val createdAt = Instant.parse("2026-08-12T10:00:00Z")
    store.insertManagedPrefix(
      "mp-1",
      tenant,
      tenantDbName,
      "acme/acme_default/td-abc/",
      createdAt
    )
    store.managedPrefix("mp-1") shouldBe Some(
      ManagedPrefixRow(
        id = "mp-1",
        tenant = tenant,
        tenantDbName = tenantDbName,
        prefix = "acme/acme_default/td-abc/",
        createdAt = createdAt,
        deletedAt = None,
        purgeEligibleAt = None,
        purgedAt = None
      )
    )

    val deletedAt       = createdAt.plusSeconds(3600)
    val purgeEligibleAt = deletedAt.plusSeconds(604800)
    store.markManagedPrefixDeleted("mp-1", deletedAt, purgeEligibleAt)
    val afterDelete = store.managedPrefix("mp-1").get
    afterDelete.deletedAt shouldBe Some(deletedAt)
    afterDelete.purgeEligibleAt shouldBe Some(purgeEligibleAt)
    afterDelete.purgedAt shouldBe None

    store.dueManagedPrefixes(purgeEligibleAt.minusSeconds(1)) shouldBe Nil
    // purge_eligible_at <= now is inclusive, so the boundary itself is due.
    val due = store.dueManagedPrefixes(purgeEligibleAt)
    due.map(_.id) shouldBe List("mp-1")

    val purgedAt = purgeEligibleAt.plusSeconds(60)
    store.markManagedPrefixPurged("mp-1", purgedAt)
    store.dueManagedPrefixes(purgedAt) shouldBe Nil
    val afterPurge = store.managedPrefix("mp-1").get
    afterPurge.purgedAt shouldBe Some(purgedAt)
  }

  it should "order due prefixes by eligibility and skip unknown-id marks" in {
    val store           = new InMemoryControlPlaneStore()
    val createdAt       = Instant.parse("2026-08-12T10:00:00Z")
    val laterEligible   = createdAt.plusSeconds(700000)
    val earlierEligible = createdAt.plusSeconds(600000)
    store.insertManagedPrefix(
      "mp-later",
      tenant,
      tenantDbName,
      "acme/acme_default/td-a/",
      createdAt
    )
    store.insertManagedPrefix(
      "mp-earlier",
      tenant,
      tenantDbName,
      "acme/acme_default/td-b/",
      createdAt
    )
    store.markManagedPrefixDeleted("mp-later", createdAt, laterEligible)
    store.markManagedPrefixDeleted("mp-earlier", createdAt, earlierEligible)

    val due = store.dueManagedPrefixes(laterEligible.plusSeconds(1))
    due.map(_.id) shouldBe List("mp-earlier", "mp-later")

    noException should be thrownBy store.markManagedPrefixDeleted(
      "no-such-id",
      createdAt,
      createdAt.plusSeconds(1)
    )
    noException should be thrownBy store.markManagedPrefixPurged("no-such-id", createdAt)
    store.managedPrefix("no-such-id") shouldBe None
  }

  it should "keep the first insert's createdAt on a duplicate id" in {
    val store         = new InMemoryControlPlaneStore()
    val firstCreated  = Instant.parse("2026-08-12T10:00:00Z")
    val secondCreated = firstCreated.plusSeconds(3600)
    store.insertManagedPrefix(
      "mp-dup",
      tenant,
      tenantDbName,
      "acme/acme_default/td-dup/",
      firstCreated
    )
    store.insertManagedPrefix(
      "mp-dup",
      tenant,
      tenantDbName,
      "acme/acme_default/td-dup-2/",
      secondCreated
    )

    val row = store.managedPrefix("mp-dup").get
    row.createdAt shouldBe firstCreated
    row.prefix shouldBe "acme/acme_default/td-dup/"
  }
