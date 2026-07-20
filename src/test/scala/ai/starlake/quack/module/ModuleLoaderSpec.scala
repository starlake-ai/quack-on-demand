package ai.starlake.quack.module

import ai.starlake.quack.ondemand.module.ModuleLoader
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ModuleLoaderSpec extends AnyFlatSpec with Matchers:

  "ModuleLoader.discover" should "find TestModule via META-INF/services" in {
    val found = ModuleLoader.discover()
    found.map(_.name) should contain("test-module")
  }
