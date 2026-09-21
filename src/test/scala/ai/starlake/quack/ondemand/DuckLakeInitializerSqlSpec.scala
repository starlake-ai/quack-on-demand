package ai.starlake.quack.ondemand

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** TDD coverage for [[DuckLakeInitializer.attachSql]] and
  * [[DuckLakeInitializer.encryptionMismatch]], the two pure helpers extracted from the
  * catalog-creating ATTACH so its option list and the mismatch guard's messages are assertable
  * without a live Postgres.
  */
class DuckLakeInitializerSqlSpec extends AnyFlatSpec with Matchers:

  private val connstr = "ducklake:postgres:host=h port=5432 dbname=d user=u password=p"

  "attachSql" should "carry only DATA_PATH when encryption is off" in {
    val sql = DuckLakeInitializer.attachSql(connstr, "acme_lake", "/var/lake", encrypted = false)
    sql should include("DATA_PATH '/var/lake'")
    sql should not include "ENCRYPTED"
  }

  it should "carry ENCRYPTED alongside DATA_PATH when encryption is on" in {
    val sql = DuckLakeInitializer.attachSql(connstr, "acme_lake", "/var/lake", encrypted = true)
    sql should include("DATA_PATH '/var/lake'")
    sql should include("ENCRYPTED")
  }

  "encryptionMismatch" should "pass when the catalog has no recorded value yet" in {
    DuckLakeInitializer.encryptionMismatch(None, wanted = true) shouldBe None
  }

  it should "pass when the recorded value agrees" in {
    DuckLakeInitializer.encryptionMismatch(Some("true"), wanted = true) shouldBe None
    DuckLakeInitializer.encryptionMismatch(Some("false"), wanted = false) shouldBe None
  }

  it should "name the cause when the catalog was created unencrypted" in {
    DuckLakeInitializer.encryptionMismatch(Some("false"), wanted = true) shouldBe Some(
      "this DuckLake catalog was created unencrypted (ducklake_metadata.encrypted='false') but " +
        "the tenant-db row asks for encryption. Encryption cannot be enabled on an existing " +
        "catalog: create a new database with encrypted=true and copy the data."
    )
  }

  it should "name the cause when the catalog was created encrypted" in {
    DuckLakeInitializer.encryptionMismatch(Some("true"), wanted = false) shouldBe Some(
      "this DuckLake catalog was created encrypted (ducklake_metadata.encrypted='true') but the " +
        "tenant-db row asks for no encryption. Encryption cannot be removed from an existing " +
        "catalog: create a new database with encrypted=false and copy the data."
    )
  }
