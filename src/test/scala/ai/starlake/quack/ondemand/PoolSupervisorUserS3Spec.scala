package ai.starlake.quack.ondemand

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{Tenant, UserS3CredentialRules}
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PoolSupervisorUserS3Spec extends AnyFlatSpec with Matchers:

  private final class Fixture:
    val store = new InMemoryControlPlaneStore()
    val sup   = new PoolSupervisor(StubQuackBackend.noop(), new NodeLoadTracker, store)
    sup.restore()
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup.createTenant(Tenant("globex")).unsafeRunSync()
    val alice = store.upsertUserWithHash(Some("acme"), "alice", "x", "user")
    val gina  = store.upsertUserWithHash(Some("globex"), "gina", "x", "user")

    def accepted(scope: String = "s3://acme-etl/", key: String = "AKIA") =
      UserS3CredentialRules
        .validate(UserS3CredentialRules.Input(scope, key, "sekr3t"), Nil)
        .toOption
        .get

    def put(user: String, scope: String = "s3://acme-etl/", key: String = "AKIA") =
      sup.putUserS3Credential(user, accepted(scope, key)).unsafeRunSync()

  "putUserS3Credential" should "store, list and serve a user's credential to the cache" in {
    val f = new Fixture
    val c = f.put(f.alice).toOption.get
    c.userId shouldBe f.alice
    c.id should fullyMatch regex "[0-9a-f]{32}"
    f.sup.listUserS3Credentials(f.alice) shouldBe Right(List(c))
    f.sup.userS3.of(f.alice) shouldBe List(c)
    f.sup.userS3.digest(f.alice) should fullyMatch regex "[0-9a-f]{64}"
    f.sup.userS3.of(f.gina) shouldBe Nil
  }

  it should "replace a scope in place: same id, a new digest" in {
    val f      = new Fixture
    val first  = f.put(f.alice).toOption.get
    val digest = f.sup.userS3.digest(f.alice)
    val second = f.put(f.alice, key = "AKIA2").toOption.get
    second.id shouldBe first.id
    f.sup.userS3.of(f.alice).map(_.accessKeyId) shouldBe List("AKIA2")
    f.sup.userS3.digest(f.alice) should not be digest
  }

  it should "cap a user at MaxPerUser credentials, a replace not counting" in {
    val f = new Fixture
    (1 to ai.starlake.quack.model.UserS3Credential.MaxPerUser).foreach(i =>
      f.put(f.alice, s"s3://acme-etl/p$i/").isRight shouldBe true
    )
    f.put(f.alice, "s3://acme-etl/one-more/").left.map(_.message) match
      case Left(m)  => m should startWith("too_many_credentials: ")
      case Right(c) => fail(s"stored a credential past the cap: ${c.id}")
    f.put(f.alice, "s3://acme-etl/p1/", key = "AKIA2").isRight shouldBe true
  }

  it should "bump the user's tenant's policy version, and only that tenant's" in {
    val f  = new Fixture
    val a0 = f.sup.policyVersions.current("acme")
    val g0 = f.sup.policyVersions.current("globex")
    f.put(f.alice)
    f.sup.policyVersions.current("acme") should be > a0
    f.sup.policyVersions.current("globex") shouldBe g0
  }

  it should "refuse an unknown user" in {
    val f = new Fixture
    f.put("u-nobody").left.map(_.getClass.getSimpleName) shouldBe Left("NotFound")
    f.sup.listUserS3Credentials("u-nobody").isLeft shouldBe true
  }

  "removeUserS3Credential" should "remove a user's own credential, never another user's" in {
    val f = new Fixture
    val c = f.put(f.gina, "s3://globex-etl/").toOption.get
    f.sup.removeUserS3Credential(f.alice, c.id).unsafeRunSync().isLeft shouldBe true
    f.sup.userS3.of(f.gina) shouldBe List(c)
    f.sup.removeUserS3Credential(f.gina, c.id).unsafeRunSync() shouldBe Right(c)
    f.sup.userS3.of(f.gina) shouldBe Nil
    f.sup.userS3.digest(f.gina) shouldBe ""
  }

  "restore" should "reload the credentials another replica wrote" in {
    val f = new Fixture
    f.put(f.alice)
    f.store.deleteUserS3Credential(f.alice, f.sup.userS3.of(f.alice).head.id)
    f.sup.userS3.of(f.alice).size shouldBe 1
    f.sup.restore()
    f.sup.userS3.of(f.alice) shouldBe Nil
  }
