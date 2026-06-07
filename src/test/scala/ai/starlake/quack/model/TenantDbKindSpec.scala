package ai.starlake.quack.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TenantDbKindSpec extends AnyFlatSpec with Matchers {

  "TenantDbKind.fromWire" should "decode each known wire value" in {
    TenantDbKind.fromWire("ducklake")    shouldBe Right(TenantDbKind.DuckLake)
    TenantDbKind.fromWire("duckdb-file") shouldBe Right(TenantDbKind.DuckDbFile)
    TenantDbKind.fromWire("memory")      shouldBe Right(TenantDbKind.InMemory)
  }

  it should "reject unknown wire values with the offending input" in {
    TenantDbKind.fromWire("postgres") shouldBe Left("unknown TenantDbKind: 'postgres'")
    TenantDbKind.fromWire("")         shouldBe Left("unknown TenantDbKind: ''")
  }

  "wireValue" should "round-trip every kind through fromWire" in {
    val all: List[TenantDbKind] =
      List(TenantDbKind.DuckLake, TenantDbKind.DuckDbFile, TenantDbKind.InMemory)
    all.foreach { k =>
      TenantDbKind.fromWire(k.wireValue) shouldBe Right(k)
    }
  }
}