package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TenantDbEncryptionSpec extends AnyFlatSpec with Matchers:

  private def req(
      kind: String = "ducklake",
      encrypted: Boolean = false,
      encryptionKey: Option[String] = None
  ): TenantDbRequest =
    TenantDbRequest(
      tenant = "acme",
      name = "lake",
      kind = kind,
      dataPath = "/var/lake",
      encrypted = encrypted,
      encryptionKey = encryptionKey
    )

  private def supStub =
    new PoolSupervisor(new StubQuackBackend, new NodeLoadTracker, new InMemoryControlPlaneStore())

  /** Like `supStub` but with the `acme` tenant seeded, for the end-to-end create test in step 6. */
  private def supWithTenant(): PoolSupervisor =
    val sup = supStub
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup

  private val open     = new TenantDbHandlers(supStub, requireEncryption = false)
  private val required = new TenantDbHandlers(supStub, requireEncryption = true)

  "validateEncryption with the policy off" should "accept an unencrypted create" in {
    open.validateEncryption(req(encrypted = false)) shouldBe None
  }

  it should "accept an encrypted create" in {
    open.validateEncryption(req(encrypted = true)) shouldBe None
  }

  it should "refuse a key on kind=ducklake" in {
    open.validateEncryption(req(encrypted = true, encryptionKey = Some("k"))) shouldBe Some(
      "kind=ducklake manages its own per-file encryption keys: remove encryptionKey"
    )
  }

  it should "refuse a key without encrypted=true" in {
    open.validateEncryption(req(kind = "duckdb-file", encryptionKey = Some("k"))) shouldBe Some(
      "encryptionKey requires encrypted=true: one intent per call"
    )
  }

  "validateEncryption with the policy on" should "refuse an unencrypted create naming the env" in {
    required.validateEncryption(req(encrypted = false)) shouldBe Some(
      "this deployment requires encryption at rest (QOD_REQUIRE_ENCRYPTION): pass encrypted=true"
    )
  }

  it should "refuse kind=memory outright" in {
    required.validateEncryption(req(kind = "memory", encrypted = false)) shouldBe Some(
      "this deployment requires encryption at rest (QOD_REQUIRE_ENCRYPTION): kind=memory " +
        "cannot satisfy it"
    )
  }

  it should "accept an encrypted create" in {
    required.validateEncryption(req(encrypted = true)) shouldBe None
  }

  "the create response" should "never carry the encryption key in any field" in {
    val sup      = supWithTenant()
    val handlers = new TenantDbHandlers(sup, requireEncryption = false)
    val body     = req(kind = "duckdb-file", encrypted = true, encryptionKey = Some("s3cr3tk3y"))
      .copy(
        metastore = Map("dbName" -> "sales", "schemaName" -> "main"),
        dataPath = "/var/s.duckdb"
      )
    val resp = handlers.createTenantDb(body, None)((_: String) => None).unsafeRunSync().toOption.get
    resp.encrypted shouldBe true
    resp.metastore.keySet should not contain "encryptionKey"
    resp.toString should not include "s3cr3tk3y"
  }
