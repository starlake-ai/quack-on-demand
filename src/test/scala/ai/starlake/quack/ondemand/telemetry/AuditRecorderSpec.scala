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

    /** Like the Postgres insert: a NUL in any text of a row fails the whole row. */
    var rejectNul                              = false
    private def hasNul(e: AuditEvent): Boolean =
      (List(e.actor, e.actorRealm, e.action, e.outcome) ++ e.tenant ++ e.target ++
        e.detail.keys ++ e.detail.values).exists(_.contains('\u0000'))
    def appendAudit(es: List[AuditEvent]): Unit =
      if failNext then { failNext = false; throw new RuntimeException("pg down") }
      if rejectNul && es.exists(hasNul) then
        throw new RuntimeException("invalid byte sequence for encoding \"UTF8\": 0x00")
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

  // ----- text a stranger chose -----
  //
  // A failed login writes the typed user name twice and the typed tenant once, up to 64 KiB each,
  // for anyone who can reach the route: a row each is kept for the audit retention. And a NUL fails
  // the insert, which erases the row of whoever sent one. restAs is where every REST row is
  // written, so it strips and caps once, for every caller (ManagerServer's guard already did so for
  // its path).

  private val Nul = "\u0000"

  // One character outside the Basic Multilingual Plane (U+20000, a CJK ideograph): two chars, a
  // surrogate pair, in a Java string.
  private val Supplementary = new String(Character.toChars(0x20000))

  "restAs" should "write the row of a value holding a NUL, without it, in every field" in {
    val store = new RecordingStore
    store.rejectNul = true
    var dropped = 0
    val r       = new AuditRecorder(store, _ => None, onDrop = dropped += _)

    r.restAs(
      s"ali${Nul}ce",
      "tenant",
      "auth",
      "auth.login.failure",
      "denied",
      tenant = Some(s"t${Nul}x"),
      target = Some(s"${Nul}tg"),
      detail = Map("username" -> s"a${Nul}b${Nul}")
    )

    dropped shouldBe 0
    store.events.map(e => (e.actor, e.tenant, e.target, e.detail)).toList shouldBe
      List(("alice", Some("tx"), Some("tg"), Map("username" -> "ab")))
  }

  it should "drop every control character: the C0 ones, DEL and the C1 ones, a tab and a newline among them" in {
    val store = new RecordingStore
    val r     = new AuditRecorder(store, _ => None)

    r.restAs(
      "a\u0007b\tc\nd\re\u001bf\u007fg\u0085h",
      "tenant",
      "auth",
      "auth.login.failure",
      "denied",
      tenant = Some("t\u0001\u001f"),
      target = Some("x\ny"),
      detail = Map("username" -> "u\tv\nw\u009fz")
    )

    val e = store.events.head
    e.actor shouldBe "abcdefgh"
    e.tenant shouldBe Some("t")
    e.target shouldBe Some("xy")
    e.detail shouldBe Map("username" -> "uvwz")
  }

  it should "cap actor, tenant and target at 256 characters and each detail value at 1024" in {
    val store = new RecordingStore
    val r     = new AuditRecorder(store, _ => None)
    val long  = "x" * 60000

    r.restAs(
      long,
      "tenant",
      "auth",
      "auth.login.failure",
      "denied",
      tenant = Some(long),
      target = Some(long),
      detail = Map("username" -> long, "reason" -> "sso_required", "other" -> ("y" * 1025))
    )

    val e = store.events.head
    e.actor shouldBe "x" * 256
    e.tenant shouldBe Some("x" * 256)
    e.target shouldBe Some("x" * 256)
    e.detail shouldBe Map(
      "username" -> ("x" * 1024),
      "reason"   -> "sso_required",
      "other"    -> ("y" * 1024)
    )
  }

  it should "take the cap from what is left once the control characters are gone" in {
    val store = new RecordingStore
    val r     = new AuditRecorder(store, _ => None)

    r.restAs(
      ("\u0001" * 500) + ("a" * 300),
      "tenant",
      "auth",
      "auth.login.failure",
      "denied",
      detail = Map("username" -> (("\n" * 2000) + ("b" * 1500)))
    )

    val e = store.events.head
    e.actor shouldBe "a" * 256
    e.detail shouldBe Map("username" -> ("b" * 1024))
  }

  it should "leave a short, clean value, non-ASCII text included, as it was" in {
    val store = new RecordingStore
    val r     = new AuditRecorder(store, _ => None)

    val name = s"Zoë 日本語 $Supplementary"

    r.restAs(
      name,
      "tenant",
      "control-plane",
      "role.create",
      "ok",
      tenant = Some("acme"),
      target = Some("acme/tpch"),
      detail = Map("name" -> "analyst", "note" -> "a b  c")
    )

    val e = store.events.head
    (e.actor, e.tenant, e.target) shouldBe ((name, Some("acme"), Some("acme/tpch")))
    e.detail shouldBe Map("name" -> "analyst", "note" -> "a b  c")
  }

  it should "not cut a surrogate pair in two when it caps" in {
    val store = new RecordingStore
    val r     = new AuditRecorder(store, _ => None)
    // One plain character, then pairs: the 256th character is the first half of a pair.
    val name = "a" + (Supplementary * 200)

    r.restAs(name, "tenant", "auth", "auth.login.failure", "denied")

    val actor = store.events.head.actor
    actor.length should be <= 256
    Character.isHighSurrogate(actor.last) shouldBe false
    name.startsWith(actor) shouldBe true
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
