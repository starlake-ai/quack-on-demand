package ai.starlake.quack.ondemand.runtime

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LocalQuackBackendPathSpec extends AnyFlatSpec with Matchers:

  "duckdbPathPrefix" should "resolve under QOD_APP_HOME when set" in {
    LocalQuackBackend.duckdbPathPrefix(Some("/opt/qod")) shouldBe Some("/opt/qod/duckdb/bin")
  }

  it should "be None when QOD_APP_HOME is unset (dev/source path unaffected)" in {
    LocalQuackBackend.duckdbPathPrefix(None) shouldBe None
  }
