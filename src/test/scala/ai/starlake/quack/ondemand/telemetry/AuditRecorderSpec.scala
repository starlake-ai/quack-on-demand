package ai.starlake.quack.ondemand.telemetry

import ai.starlake.quack.edge.auth.AuthenticatedProfile
import ai.starlake.quack.ondemand.api.SessionTokenStore
import ai.starlake.quack.ondemand.auth.SessionScope
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class AuditRecorderSpec extends AnyFlatSpec with Matchers:

  private class RecordingStore extends TelemetryStore:
    val enabled  = true
    val events   = scala.collection.mutable.ListBuffer.empty[AuditEvent]
    var failNext = false
    def appendAudit(es: List[AuditEvent]): Unit =
      if failNext then { failNext = false; throw new RuntimeException("pg down") }
      events ++= es
    def listAudit(q: AuditQuery): List[AuditRow] = Nil
    def purgeAudit(olderThan: Instant): Int      = 0

  private def session(username: String, superuser: Boolean) =
    SessionTokenStore.Session(
      AuthenticatedProfile(username, "admin", Set.empty, Map.empty, "db", None),
      SessionScope(superuser, if superuser then Set.empty else Set("t-a")),
      Instant.now()
    )

  private def recorder(store: TelemetryStore, lookup: String => Option[SessionTokenStore.Session]) =
    new AuditRecorder(store, lookup)

  "rest" should "resolve a session token to actor and realm" in {
    val store = new RecordingStore
    recorder(store, t => if t == "tok" then Some(session("alice", superuser = false)) else None)
      .rest(Some("tok"), "control-plane", "role.create", "ok", tenant = Some("t-a"))
    val e = store.events.head
    (e.actor, e.actorRealm, e.origin, e.outcome) shouldBe ("alice", "tenant", "rest", "ok")
  }

  it should "record static-key callers as static-key/system and no token as anonymous" in {
    val store = new RecordingStore
    val r     = recorder(store, _ => None)
    r.rest(Some("static-abc"), "control-plane", "pool.delete", "ok")
    r.rest(None, "control-plane", "pool.delete", "ok")
    store.events.map(e => (e.actor, e.actorRealm)).toList shouldBe
      List(("static-key", "system"), ("anonymous", "system"))
  }

  it should "swallow store failures (never throws into the handler) and count the drop" in {
    val store   = new RecordingStore
    var dropped = 0
    val r       = new AuditRecorder(store, _ => None, onDrop = dropped += _)
    store.failNext = true
    noException should be thrownBy r.rest(None, "control-plane", "role.create", "ok")
    dropped shouldBe 1
  }

  it should "do nothing when the store is disabled" in {
    var dropped = 0
    val r       = new AuditRecorder(NoopTelemetryStore, _ => None, onDrop = dropped += _)
    r.rest(None, "control-plane", "role.create", "ok")
    dropped shouldBe 0
  }

  "AuditRateLimiter" should "allow one event per key per interval" in {
    var now = 0L
    val rl  = new AuditRateLimiter(intervalMillis = 60000, clock = () => now)
    rl.allow("1.2.3.4") shouldBe true
    rl.allow("1.2.3.4") shouldBe false
    rl.allow("5.6.7.8") shouldBe true
    now = 60001
    rl.allow("1.2.3.4") shouldBe true
  }
  "onDropCounter" should "route drops through the registered hook instead of the constructor hook" in {
    val store     = new RecordingStore
    var ctorDrops = 0
    var hookDrops = 0
    val r         = new AuditRecorder(store, _ => None, onDrop = ctorDrops += _)
    r.onDropCounter(hookDrops += _)
    store.failNext = true
    noException should be thrownBy r.rest(None, "control-plane", "role.create", "ok")
    ctorDrops shouldBe 0
    hookDrops shouldBe 1
  }
