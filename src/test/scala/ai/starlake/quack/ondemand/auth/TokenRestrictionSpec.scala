package ai.starlake.quack.ondemand.auth

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/** The attenuation invariant: `narrow` can only ever shrink. Pure, no Postgres. */
class TokenRestrictionSpec extends AnyFlatSpec with Matchers:

  private val U = TokenRestriction.Unrestricted

  private def r(
      roles: Option[Set[String]] = None,
      databases: Option[Set[String]] = None,
      pools: Option[Set[String]] = None,
      tools: Option[Set[String]] = None,
      verbCeiling: Option[String] = None,
      dropAdmin: Boolean = false,
      stmtTimeoutMs: Option[Int] = None,
      maxRows: Option[Int] = None,
      expiresAt: Option[Instant] = None
  ): TokenRestriction =
    TokenRestriction(roles, databases, pools, tools, verbCeiling, dropAdmin,
      stmtTimeoutMs, maxRows, expiresAt)

  "narrow" should "inherit the parent value when the child leaves an axis unset" in {
    val parent = r(databases = Some(Set("a", "b")))
    TokenRestriction.narrow(parent, U).map(_.databases) shouldBe Right(Some(Set("a", "b")))
  }

  it should "accept a subset on a set axis" in {
    val parent = r(databases = Some(Set("a", "b")))
    val child  = r(databases = Some(Set("a")))
    TokenRestriction.narrow(parent, child).map(_.databases) shouldBe Right(Some(Set("a")))
  }

  it should "refuse a superset on a set axis, naming the axis" in {
    val parent = r(databases = Some(Set("a")))
    val child  = r(databases = Some(Set("a", "b")))
    TokenRestriction.narrow(parent, child).left.map(_.contains("databases")) shouldBe Left(true)
  }

  it should "treat a parent None as the universe" in {
    TokenRestriction.narrow(U, r(tools = Some(Set("run_sql")))).map(_.tools) shouldBe
      Right(Some(Set("run_sql")))
  }

  it should "allow an empty set as the narrowest possible value" in {
    val parent = r(tools = Some(Set("run_sql")))
    TokenRestriction.narrow(parent, r(tools = Some(Set.empty))).map(_.tools) shouldBe
      Right(Some(Set.empty))
  }

  // The verb ceiling is a subset lattice, NOT a rank. RO does not sit "below" DDL:
  // RO covers {Read}, DDL covers {Ddl}, and neither contains the other.
  it should "accept RO under RW" in {
    TokenRestriction.narrow(r(verbCeiling = Some("RW")), r(verbCeiling = Some("RO")))
      .map(_.verbCeiling) shouldBe Right(Some("RO"))
  }

  it should "refuse RO under DDL, because RO would add Read" in {
    TokenRestriction.narrow(r(verbCeiling = Some("DDL")), r(verbCeiling = Some("RO")))
      .isLeft shouldBe true
  }

  it should "refuse DDL under RW, because DDL would add Ddl" in {
    TokenRestriction.narrow(r(verbCeiling = Some("RW")), r(verbCeiling = Some("DDL")))
      .isLeft shouldBe true
  }

  it should "accept anything under ALL" in {
    List("RO", "RW", "DDL", "ALL").foreach { v =>
      TokenRestriction.narrow(r(verbCeiling = Some("ALL")), r(verbCeiling = Some(v)))
        .isRight shouldBe true
    }
  }

  it should "let dropAdmin go false to true but never back" in {
    TokenRestriction.narrow(U, r(dropAdmin = true)).map(_.dropAdmin) shouldBe Right(true)
    TokenRestriction.narrow(r(dropAdmin = true), r(dropAdmin = false))
      .map(_.dropAdmin) shouldBe Right(true)
  }

  it should "only let numeric limits decrease" in {
    TokenRestriction.narrow(r(maxRows = Some(100)), r(maxRows = Some(10)))
      .map(_.maxRows) shouldBe Right(Some(10))
    TokenRestriction.narrow(r(maxRows = Some(10)), r(maxRows = Some(100))).isLeft shouldBe true
  }

  // Expiry is not optional. Cascade revocation hides an unclamped child only while
  // the parent is alive; a parent that expires naturally cascades nothing.
  it should "clamp a child expiry to the parent's" in {
    val early = Instant.parse("2026-01-01T00:00:00Z")
    val late  = Instant.parse("2027-01-01T00:00:00Z")
    TokenRestriction.narrow(r(expiresAt = Some(early)), r(expiresAt = Some(late)))
      .map(_.expiresAt) shouldBe Right(Some(early))
    TokenRestriction.narrow(r(expiresAt = Some(late)), r(expiresAt = Some(early)))
      .map(_.expiresAt) shouldBe Right(Some(early))
    TokenRestriction.narrow(r(expiresAt = Some(early)), U).map(_.expiresAt) shouldBe
      Right(Some(early))
  }
