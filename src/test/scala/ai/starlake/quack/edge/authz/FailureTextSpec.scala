package ai.starlake.quack.edge.authz

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** What a control-plane failure line may say: the class and the SQLState, never the message, which
  * for a write can quote the row (pgjdbc's "Failing row contains (...)").
  */
class FailureTextSpec extends AnyFlatSpec with Matchers:

  private val Detail = "ERROR: null value in column \"region\"\n  Detail: Failing row contains " +
    "(c1, u-1, s3://acme-etl/, AKIA, failure-text-marker-secret)."

  "FailureText.of" should "name the class and the SQLState, never the message" in {
    val text = FailureText.of(new java.sql.SQLException(Detail, "23502"))
    text shouldBe "SQLException (SQLState 23502)"
    text.contains("failure-text-marker-secret") shouldBe false
  }

  it should "find the SQLState in the cause chain" in {
    FailureText.of(
      new RuntimeException(Detail, new java.sql.SQLException(Detail, "23514"))
    ) shouldBe "RuntimeException (SQLState 23514)"
  }

  it should "name the class alone when no SQLState is known" in {
    FailureText.of(new IllegalStateException(Detail)) shouldBe "IllegalStateException"
    FailureText.of(new java.sql.SQLException(Detail)) shouldBe "SQLException"
  }

  it should "stop on a cause chain that loops" in {
    val a = new RuntimeException("a")
    val b = new RuntimeException("b", a)
    a.initCause(b)
    FailureText.of(a) shouldBe "RuntimeException"
  }
