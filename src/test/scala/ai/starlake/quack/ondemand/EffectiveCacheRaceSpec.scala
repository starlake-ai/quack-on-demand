package ai.starlake.quack.ondemand

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}

/** The effective-set cache and an RBAC mutation racing (Task 11 addendum 1): a resolution that read
  * the grants before a mutation and finished after its invalidation must not be served afterwards,
  * since a session that saw the mutation's new policy version would otherwise pin those grants
  * under it until the next change.
  */
class EffectiveCacheRaceSpec extends AnyFlatSpec with Matchers:

  private given ExecutionContext = ExecutionContext.global

  /** Once armed, holds the role edges it read until `release`: the resolution reading them is then
    * caught between its reads and its cache write.
    */
  private final class SlowRolesStore extends InMemoryControlPlaneStore:
    val armed   = new AtomicBoolean(false)
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)

    override def listDirectRolesForUser(userId: String): List[String] =
      val roles = super.listDirectRolesForUser(userId)
      if armed.compareAndSet(true, false) then
        entered.countDown()
        release.await(10, TimeUnit.SECONDS)
      roles

  "a resolution that straddles a role removal" should "not be served after it" in {
    val store = new SlowRolesStore
    val sup   = new PoolSupervisor(StubQuackBackend.noop(), new NodeLoadTracker, store)
    sup.restore()
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    val role   = sup.createRole("acme", "reader").unsafeRunSync().toOption.get
    val userId = store.upsertUserWithHash(Some("acme"), "alice", "x", "user")
    sup.addUserRole(userId, role.id).unsafeRunSync().isRight shouldBe true

    store.armed.set(true)
    val stale = Future(sup.effectiveSetForUser(userId))
    store.entered.await(10, TimeUnit.SECONDS) shouldBe true
    sup.removeUserRole(userId, role.id).unsafeRunSync().isRight shouldBe true
    store.release.countDown()
    // The straddling resolution answers its own caller with what it read ...
    Await.result(stale, 10.seconds).get.roles.map(_.name) shouldBe List("reader")
    // ... but the next one, after the removal, never sees it.
    sup.effectiveSetForUser(userId).get.roles.map(_.name) shouldBe Nil
  }
