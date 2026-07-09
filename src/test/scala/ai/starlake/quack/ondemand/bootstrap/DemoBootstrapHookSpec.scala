// src/test/scala/ai/starlake/quack/ondemand/bootstrap/DemoBootstrapHookSpec.scala
package ai.starlake.quack.ondemand.bootstrap

import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.{Failure, Success, Try}

/** Unit tests for [[DemoBootstrapHook]]. The hook is dependency-injected (env reader + file reader
  * + store) so the suite never touches the real filesystem nor the real process environment.
  */
class DemoBootstrapHookSpec extends AnyFlatSpec with Matchers:

  private val ValidYaml: String =
    """|apiVersion: quack-on-demand/v1
       |kind: ConfigManifest
       |exportedAt: '1970-01-01T00:00:00Z'
       |exportedFrom: { managerVersion: 'test', hostname: 'spec' }
       |tenants:
       |  - name: acme
       |  - name: globex
       |users:
       |  - username: root
       |    password: demo-root
       |    role: admin
       |""".stripMargin

  private def emptyEnv: String => Option[String]                      = _ => None
  private def envWith(k: String, v: String): String => Option[String] = s =>
    if s == k then Some(v) else None

  "DemoBootstrapHook.run" should "no-op when QOD_BOOTSTRAP_YAML is unset" in {
    val store = new InMemoryControlPlaneStore()
    DemoBootstrapHook.run(emptyEnv, _ => Success(""), store).unsafeRunSync()
    store.listTenants() shouldBe empty
  }

  it should "no-op when the manifest file is unreadable" in {
    val store = new InMemoryControlPlaneStore()
    val env   = envWith("QOD_BOOTSTRAP_YAML", "/does/not/exist")
    val read  = (_: String) => Failure(new java.nio.file.NoSuchFileException("/does/not/exist"))
    DemoBootstrapHook.run(env, read, store).unsafeRunSync()
    store.listTenants() shouldBe empty
  }

  it should "no-op when the YAML is malformed" in {
    val store = new InMemoryControlPlaneStore()
    val env   = envWith("QOD_BOOTSTRAP_YAML", "/p")
    val read  = (_: String) => Success("not: : valid: yaml:")
    DemoBootstrapHook.run(env, read, store).unsafeRunSync()
    store.listTenants() shouldBe empty
  }

  it should "import the manifest on an empty store" in {
    val store = new InMemoryControlPlaneStore()
    val env   = envWith("QOD_BOOTSTRAP_YAML", "/p")
    val read  = (_: String) => Success(ValidYaml)
    DemoBootstrapHook.run(env, read, store).unsafeRunSync()
    store.listTenants().map(_.id).toSet shouldBe Set("acme", "globex")
  }

  it should "skip import when a demo tenant already exists" in {
    val store = new InMemoryControlPlaneStore()
    // The demo tenant's key is its slug id "acme"; pre-seed that id so the
    // bootstrap guard (which keys collisions by tenant id) detects it.
    store.upsertTenant(Tenant(id = "acme", displayName = "Acme"))
    val env  = envWith("QOD_BOOTSTRAP_YAML", "/p")
    val read = (_: String) => Success(ValidYaml)
    DemoBootstrapHook.run(env, read, store).unsafeRunSync()
    // 'globex' from the manifest must NOT have been added because the guard tripped on 'acme'.
    store.listTenants().map(_.id).toSet shouldBe Set("acme")
  }

  it should "skip import when a non-demo tenant already exists in the store" in {
    val store = new InMemoryControlPlaneStore()
    // Pre-seed a tenant whose name is neither "acme" nor "globex". The old
    // gate only checked for demo-named collisions, so it would re-import
    // the demo manifest here and the importer's delete-then-upsert semantics
    // would wipe any REST-API-added rows under this tenant. The gate must be
    // "any tenant already present" so a non-demo store is left untouched.
    store.upsertTenant(Tenant(id = "wonka", displayName = "Wonka Industries"))
    val env  = envWith("QOD_BOOTSTRAP_YAML", "/p")
    val read = (_: String) => Success(ValidYaml)
    DemoBootstrapHook.run(env, read, store).unsafeRunSync()
    // Neither "acme" nor "globex" must have been imported, and "wonka" must
    // be left exactly as it was.
    store.listTenants().map(_.id).toSet shouldBe Set("wonka")
  }
