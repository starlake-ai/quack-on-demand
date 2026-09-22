package ai.starlake.quack.route

import ai.starlake.quack.edge.sql.LockdownScreen
import ai.starlake.quack.model.StatementKind
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.Locale

/** Case folding in the screening and routing paths must not depend on the JVM's default locale.
  *
  * Turkish and Azeri fold `i` and `I` to dotless/dotted forms: `"install".toUpperCase` is
  * `"İNSTALL"` and `"INSTALL".toLowerCase` is `"ınstall"`, neither of which equals the ASCII
  * keyword the screens compare against. A deployment whose JVM default locale is `tr` or `az`
  * therefore misses keyword matches silently, with no error and no log line, which turns a
  * classification into a routing decision and a lockdown denial into an allow.
  *
  * These tests force the default locale rather than relying on the CI machine's, and restore it
  * afterwards, so they fail on an ASCII-assuming implementation regardless of where they run.
  */
class LocaleIndependenceSpec extends AnyFlatSpec with Matchers:

  /** Runs `body` with the JVM default locale forced to Turkish, restoring the previous default on
    * every path. `Locale.setDefault` is process-global, so the restore is not optional.
    */
  private def withTurkishLocale[A](body: => A): A =
    val previous = Locale.getDefault
    Locale.setDefault(Locale.forLanguageTag("tr-TR"))
    try body
    finally Locale.setDefault(previous)

  "the Turkish locale" should "actually fold the ASCII i, or these tests prove nothing" in {
    withTurkishLocale {
      "install".toUpperCase should not be "INSTALL"
      "INSTALL".toLowerCase should not be "install"
    }
  }

  "StatementClassifier" should "classify a lowercase INSERT as DML under a Turkish locale" in {
    withTurkishLocale {
      // A missed keyword match falls through to StatementKind.Other, which is treated like a
      // read: the write would route to a reader node and skip the write-path guards.
      StatementClassifier.classify("insert into t values (1)") shouldBe StatementKind.Dml
    }
  }

  it should "classify a lowercase SELECT as a read under a Turkish locale" in {
    withTurkishLocale {
      StatementClassifier.classify("select * from t") shouldBe StatementKind.Select
    }
  }

  it should "classify a lowercase CREATE TABLE as DDL under a Turkish locale" in {
    withTurkishLocale {
      StatementClassifier.classify("create table t (a int)") shouldBe StatementKind.Ddl
    }
  }

  "LockdownScreen" should "still deny INSTALL under a Turkish locale" in {
    withTurkishLocale {
      LockdownScreen.screen("INSTALL httpfs", Set.empty) shouldBe defined
    }
  }

  it should "still deny a lowercase install under a Turkish locale" in {
    withTurkishLocale {
      LockdownScreen.screen("install httpfs", Set.empty) shouldBe defined
    }
  }

  "classification" should "agree between the default locale and Turkish" in {
    val statements = List(
      "insert into t values (1)",
      "select * from t",
      "create table t (a int)",
      "update t set a = 1",
      "delete from t",
      "drop table t"
    )
    val underDefault = statements.map(StatementClassifier.classify)
    val underTurkish = withTurkishLocale(statements.map(StatementClassifier.classify))
    underTurkish shouldBe underDefault
  }
