package ai.starlake.quack.module

import ai.starlake.quack.ondemand.module.ModuleMigrations
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer

class ModuleMigrationsSpec extends AnyFlatSpec with Matchers:

  private def moduleWithChangelog(path: Option[String]): TestModule =
    new TestModule:
      override def changelogPath: Option[String] = path

  "ModuleMigrations.run" should "apply each module changelog and skip None" in {
    val applied = ListBuffer.empty[String]
    ModuleMigrations.run(
      List(
        moduleWithChangelog(Some("db/hosted/changelog.yaml")),
        moduleWithChangelog(None)
      ),
      Map("pgHost" -> "h"),
      runnerFor = (_, path) => () => { applied += path; () }
    )
    applied.toList shouldBe List("db/hosted/changelog.yaml")
  }

  it should "propagate a failing migration (fail-fast boot)" in {
    an[RuntimeException] should be thrownBy ModuleMigrations.run(
      List(moduleWithChangelog(Some("db/hosted/changelog.yaml"))),
      Map.empty,
      runnerFor = (_, _) => () => throw new RuntimeException("migration failed")
    )
  }
