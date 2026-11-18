package ai.starlake.quack.ondemand.state

import ai.starlake.quack.ondemand.state.ReleaseGate.Stored
import ai.starlake.quack.ondemand.state.ReleasePolicy.{Policy, Release, Version}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager
import scala.util.{Try, Using}

/** The boot's release gate (#221, #222): the pure decision, the bundled `release/upgrades.json`,
  * and the gate around a real Liquibase apply on Postgres.
  */
class ReleaseGateSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodrelgate")

  private def v(s: String): Version = Version.parse(s).get

  private def release(version: String, minFrom: String, rollback: Boolean): Release =
    Release(
      v(version),
      v(minFrom),
      rollback,
      if rollback then Nil else List("a column dropped"),
      s"https://example.test/$version"
    )

  // 0.11.0 is one way; 0.12.0 is not; 0.14.0 is one way and upgrades only from 0.12.0.
  private val policy = Policy(
    List(
      release("0.12.0", "0.10.0", rollback = true),
      release("0.11.0", "0.10.0", rollback = false),
      release("0.14.0", "0.12.0", rollback = false)
    )
  )

  "Version.parse" should "read a release number and judge a snapshot as its release" in {
    v("0.13.2-SNAPSHOT") shouldBe v("0.13.2")
    v("0.13.10") should be > v("0.13.9")
    Version.parse("dev") shouldBe None
  }

  "the policy" should "speak for a build with its own entry, else the newest one before it" in {
    policy.entryFor(v("0.12.0")).map(_.version) shouldBe Some(v("0.12.0"))
    policy.entryFor(v("0.13.5")).map(_.version) shouldBe Some(v("0.12.0"))
    policy.entryFor(v("0.9.0")) shouldBe None
  }

  it should "put the schema floor at the newest one-way release up to the build" in {
    policy.schemaFloor(v("0.11.0")) shouldBe Some(v("0.11.0"))
    policy.schemaFloor(v("0.12.0")) shouldBe Some(v("0.11.0"))
    policy.schemaFloor(v("0.14.0")) shouldBe Some(v("0.14.0"))
  }

  "decide" should "admit a fresh control plane, or one no gated release ran on" in {
    ReleaseGate.decide(v("0.14.0"), Stored(None, None), Nil, policy) shouldBe Right(Nil)
  }

  it should "refuse a jump past min_from_version, naming the release to go through" in {
    val refused = ReleaseGate.decide(v("0.14.0"), Stored(Some(v("0.11.0")), None), Nil, policy)
    refused.isLeft shouldBe true
    val message = refused.left.toOption.get
    message should include("upgrades directly only from qod 0.12.0 or later")
    message should include("last run by qod 0.11.0")
    message should include("https://example.test/0.14.0")
    ReleaseGate.decide(v("0.14.0"), Stored(Some(v("0.12.0")), None), Nil, policy) shouldBe Right(
      Nil
    )
  }

  it should "refuse a schema a newer release changed one way, naming what this build does not know" in {
    val refused = ReleaseGate.decide(
      v("0.12.0"),
      Stored(Some(v("0.14.0")), Some(v("0.14.0"))),
      List("0060-drop-something"),
      policy
    )
    val message = refused.left.toOption.getOrElse(fail(s"expected a refusal, got $refused"))
    message should include("only qod 0.14.0 or later can run on its schema")
    message should include("0060-drop-something")
    message should include("restore the control-plane database backup")
  }

  it should "admit an older build a newer release left the schema to, with a note" in {
    val admitted = ReleaseGate.decide(
      v("0.12.0"),
      Stored(Some(v("0.13.0")), Some(v("0.11.0"))),
      List("0059-additive"),
      policy
    )
    admitted.isRight shouldBe true
    admitted.toOption.get.mkString should include("0059-additive")
  }

  "the bundled release/upgrades.json" should "parse, and state a reason for every one-way release" in {
    val bundled = ReleasePolicy.bundled()
    bundled.releases should not be empty
    bundled.releases.map(_.version) shouldBe bundled.releases.map(_.version).sorted
    bundled.releases.foreach { r =>
      withClue(r.version.toString) {
        r.minFromVersion should be <= r.version
        r.rollbackBlockedBy.isEmpty shouldBe r.rollbackWithoutRestore
        r.notes should startWith("https://")
      }
    }
  }

  it should "hold an entry for the version this build carries" in {
    val sbt     = scala.io.Source.fromFile("version.sbt").mkString
    val current = raw""""([^"]+)"""".r.findFirstMatchIn(sbt).map(_.group(1)).flatMap(Version.parse)
    current.isDefined shouldBe true
    ReleasePolicy.bundled().releases.map(_.version) should contain(current.get)
  }

  "the gate around Liquibase" should "record a release, then refuse what the rows forbid" in {
    TestPostgres.ensureReachable()
    val db = s"qodrelgate_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$db"""")
    try
      val url                 = TestPostgres.dbUrl(db)
      def gate(build: String) =
        new ReleaseGate(url, TestPostgres.pgUser, TestPostgres.pgPass, Some(v(build)), policy)
      def sql(statement: String): Unit =
        Using.resource(DriverManager.getConnection(url, TestPostgres.pgUser, TestPostgres.pgPass))(
          c => Using.resource(c.createStatement())(_.execute(statement))
        )

      // A fresh control plane: no table yet, nothing to refuse.
      gate("0.12.0").check() shouldBe Right(Nil)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      gate("0.12.0").record()
      Using.resource(DriverManager.getConnection(url, TestPostgres.pgUser, TestPostgres.pgPass)) {
        c => ReleaseGate.readRows(c) shouldBe Stored(Some(v("0.12.0")), Some(v("0.11.0")))
      }
      // The core changelog this build bundles is all known.
      Using.resource(DriverManager.getConnection(url, TestPostgres.pgUser, TestPostgres.pgPass)) {
        c => ReleaseGate.unknownChangesets(c) shouldBe Nil
      }

      // A newer release ran: a changeset this build does not know, and a module's beside it.
      sql(
        "INSERT INTO databasechangelog (id, author, filename, dateexecuted, orderexecuted, " +
          "exectype) VALUES ('0060-drop-something', 'qod', 'db/changelog/0060-drop.yaml', now(), " +
          "9001, 'EXECUTED'), ('hosted-0001', 'qod', 'db/hosted/0001.yaml', now(), 9002, 'EXECUTED')"
      )
      sql("UPDATE qodstate_release SET value = '0.15.0' WHERE name = 'last_version'")
      // It left the schema to 0.11.0 and later: an older build boots, with a note.
      gate("0.12.0").check() shouldBe Right(
        List(
          "WARN: this control plane holds Liquibase changesets qod 0.12.0 does not know " +
            "(0060-drop-something), applied by a newer release (last run: qod 0.15.0), which " +
            "recorded that qod 0.12.0 can still run on its schema."
        )
      )
      // Its change was one way: the floor moves to it and the older build is refused.
      sql("UPDATE qodstate_release SET value = '0.15.0' WHERE name = 'schema_floor'")
      val refused = gate("0.12.0").check()
      val message = refused.left.toOption.getOrElse(fail(s"expected a refusal, got $refused"))
      message should include("only qod 0.15.0 or later can run on its schema")
      message should include("0060-drop-something")
      message should not include "hosted-0001"

      // Recording never lowers the floor.
      gate("0.14.0").record()
      Using.resource(DriverManager.getConnection(url, TestPostgres.pgUser, TestPostgres.pgPass)) {
        c => ReleaseGate.readRows(c) shouldBe Stored(Some(v("0.14.0")), Some(v("0.15.0")))
      }

      // A jump past min_from_version.
      sql("UPDATE qodstate_release SET value = '0.11.0'")
      gate("0.14.0").check().left.toOption.getOrElse(fail("expected a refusal")) should include(
        "upgrades directly only from qod 0.12.0 or later"
      )
    finally Try(TestPostgres.dropDatabase(db))
  }

  "a build with no release number" should "be neither gated nor recorded" in {
    val gate = new ReleaseGate("jdbc:postgresql://unreachable.invalid/x", "u", "p", None, policy)
    gate.check() shouldBe Right(Nil)
    gate.record()
  }
