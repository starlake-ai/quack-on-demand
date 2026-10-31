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
    def listAudit(q: AuditQuery): List[AuditRow]                                     = Nil
    def purgeAudit(olderThan: Instant): Int                                          = 0
    def appendStatements(events: List[StatementEvent]): Unit                         = ()
    def searchStatements(q: StatementQuery): List[StatementRow]                      = Nil
    def purgeStatements(olderThan: Instant): Int                                     = 0
    def rollupWatermark(): Option[Instant]                                           = None
    def recomputeRollups(fromExclusive: Option[Instant], toInclusive: Instant): Unit = ()
    def advanceRollupWatermark(to: Instant): Unit                                    = ()
    def queryRollups(q: RollupQuery): List[RollupBucket]                             = Nil
    def purgeRollups(granularity: String, olderThan: Instant): Int                   = 0
    def queryUsage(q: UsageQuery): UsageResult = UsageResult(Nil, None)

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

  it should "record an unresolved token and no token alike as anonymous" in {
    val store = new RecordingStore
    val r     = recorder(store, _ => None)
    r.rest(Some("static-abc"), "control-plane", "pool.delete", "ok")
    r.rest(None, "control-plane", "pool.delete", "ok")
    store.events.map(e => (e.actor, e.actorRealm)).toList shouldBe
      List(("anonymous", "system"), ("anonymous", "system"))
  }

  it should "record a superuser session as its user in the system realm" in {
    val store = new RecordingStore
    recorder(store, t => if t == "root-tok" then Some(session("root", superuser = true)) else None)
      .rest(Some("root-tok"), "control-plane", "pool.delete", "ok")
    store.events.map(e => (e.actor, e.actorRealm)).toList shouldBe List(("root", "system"))
  }

  it should "record the manager's own work (AuditActor.System) as system, whatever the lookup" in {
    val store = new RecordingStore
    AuditActor.System.controlPlane(recorder(store, _ => None), "tenant.create", "ok")
    store.events.map(e => (e.actor, e.actorRealm)).toList shouldBe List(("system", "system"))
  }

  it should "resolve a PAT bearer to its owner's actor via the composed session lookup, " +
    "and fill pat_id from patIdOf with no call-site change" in {
      val store                                                      = new RecordingStore
      val patToken                                                   = "qod_pat_alice"
      val sessionLookup: String => Option[SessionTokenStore.Session] =
        t => if t == patToken then Some(session("alice", superuser = false)) else None
      val patIdOf: String => Option[String] =
        t => if t == patToken then Some("pat-1") else None
      val r = new AuditRecorder(store, sessionLookup, patIdOf = patIdOf)
      r.rest(Some(patToken), "control-plane", "pool.scale", "ok", tenant = Some("t-a"))
      val e = store.events.head
      (e.actor, e.actorRealm, e.patId) shouldBe ("alice", "tenant", Some("pat-1"))
    }

  it should "leave pat_id at None for an unresolved bearer even when patIdOf is wired" in {
    val store    = new RecordingStore
    val patToken = "qod_pat_alice"
    val r        = new AuditRecorder(
      store,
      t => if t == patToken then Some(session("alice", superuser = false)) else None,
      patIdOf = t => if t == patToken then Some("pat-1") else None
    )
    r.rest(Some("static-abc"), "control-plane", "pool.scale", "ok")
    val e = store.events.head
    (e.actor, e.actorRealm, e.patId) shouldBe ("anonymous", "system", None)
  }

  it should "let an explicit patId argument win over patIdOf's resolution" in {
    val store    = new RecordingStore
    val patToken = "qod_pat_alice"
    val r        = new AuditRecorder(
      store,
      t => if t == patToken then Some(session("alice", superuser = false)) else None,
      patIdOf = t => if t == patToken then Some("pat-1") else None
    )
    r.rest(Some(patToken), "control-plane", "pool.scale", "ok", patId = Some("override"))
    store.events.head.patId shouldBe Some("override")
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
  it should "forget a key once its interval passed, keeping keys still inside theirs" in {
    var now = 0L
    val rl  = new AuditRateLimiter(intervalMillis = 60000, clock = () => now)
    rl.allow("old") shouldBe true
    now = 30000
    rl.allow("recent") shouldBe true
    rl.allow("old") shouldBe false
    now = 60000
    rl.trackedKeys shouldBe 1
    rl.allow("recent") shouldBe false
    now = 90000
    rl.trackedKeys shouldBe 0
    // Forgetting changes nothing about allow: a key comes back allowed, once per interval.
    rl.allow("old") shouldBe true
    rl.allow("old") shouldBe false
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
