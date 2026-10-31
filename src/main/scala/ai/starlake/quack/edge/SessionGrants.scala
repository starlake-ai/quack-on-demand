package ai.starlake.quack.edge

import ai.starlake.quack.edge.authz.{FailureText, PolicyClock}
import ai.starlake.quack.ondemand.rbac.{
  AuthorizedHandshake,
  AuthzRequest,
  EffectiveSet,
  HandshakeDenial
}
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging

import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import scala.util.control.NonFatal

/** The grants a data-plane session (a FlightSQL connection, a native front door session) runs its
  * statements under. They are resolved by the handshake's gates and follow the tenant's policy
  * version: before a statement's handle is minted, a session whose tenant moved past the version
  * its set was resolved under, or whose replica's policy generation moved (a local change whose
  * version read failed, a restore), runs the same gates again (the user, the pool access, QoD
  * grants or the tenant's OPA, the effective set through the supervisor's cache that every RBAC
  * mutation and restore invalidates). The one mechanism both doors use: a revoke reaches an open
  * session on its next statement, not at its reconnect.
  */
sealed trait SessionGrants:

  /** The effective set the next statement runs under, resolved again first when the tenant's
    * version or the replica's policy generation moved. Left: the gates now refuse the session
    * (`AccessDenied`, naming the lost access), or could not run (`Unavailable`, retryable); never
    * the set of before. May block on the control plane: [[currentIO]] from an IO.
    */
  def current(): Either[RouterFailure, EffectiveSet]

  /** [[current]] as an IO, shifted to the blocking pool only when the gates run again. */
  def currentIO: IO[Either[RouterFailure, EffectiveSet]]

  /** The set a lone ROLLBACK runs under when [[current]] refuses the session: without it the client
    * could not end the transaction it believes it is in. The principal the gates last admitted
    * (user, claims, token restriction) with every grant stripped ([[SessionGrants.stripped]]), so
    * whatever runs under it reaches no table, at the manager or at the node. Never resolved again,
    * and never the set the session had before the refusal.
    */
  def rollbackOnly: EffectiveSet

object SessionGrants extends LazyLogging:

  /** Grants never resolved again: a superuser's (no tenant, no grants to follow) and the system
    * principal's. Also what a spec binds when it builds a session without a handshake.
    */
  def fixed(set: EffectiveSet): SessionGrants = Fixed(set)

  /** `set` with no grants: the same principal and user, no role, group, table permission, column or
    * row policy, or pool grant, and a verb ceiling admitting no verb (the OPA arm never reads
    * permissions, and refuses any access outside the ceiling before asking OPA). A statement that
    * names no object, a ROLLBACK, still runs under it.
    */
  def stripped(set: EffectiveSet): EffectiveSet =
    set.copy(
      roles = Nil,
      groups = Nil,
      permissions = Nil,
      poolPerms = Nil,
      columnPolicies = Nil,
      rowPolicies = Nil,
      verbCeiling = Some(Set.empty)
    )

  /** The handshake's gates for `request`, the tenant's version read first: Right with the
    * authorization and the session's grants, or the gates' refusal. `versions` and `gates` are kept
    * to resolve the set again later.
    */
  def authorize(
      request: AuthzRequest,
      gates: AuthzRequest => Either[HandshakeDenial, AuthorizedHandshake],
      versions: PolicyClock
  ): Either[HandshakeDenial, (AuthorizedHandshake, SessionGrants)] =
    // The gates resolve the tenant case-insensitively, by its lower-case id: the key the versions
    // are under. Read before the gates, so a change landing while they run is newer than the set.
    val guessed    = request.tenant.toLowerCase(Locale.ROOT)
    val before     = versions.current(guessed)
    val generation = versions.generation
    gates(request).map { auth =>
      val set = auth.effectiveSet
      if set.user.tenant.isEmpty || set.system then (auth, Fixed(set))
      else
        val tenant = auth.poolKey.tenant
        // A version read under another key vouches for nothing: the first statement resolves again.
        val version = if tenant == guessed then before else Long.MinValue
        val snap    = Snapshot(version, generation, set)
        (auth, new Following(request, tenant, set.user.id, gates, versions, snap))
    }

  private final case class Fixed(set: EffectiveSet) extends SessionGrants:
    def current(): Either[RouterFailure, EffectiveSet]     = Right(set)
    def currentIO: IO[Either[RouterFailure, EffectiveSet]] = IO.pure(Right(set))
    def rollbackOnly: EffectiveSet                         = stripped(set)

  /** A set, and the tenant version and replica policy generation read before the gates resolved it.
    * It stands while neither moved past it.
    */
  private final case class Snapshot(version: Long, generation: Long, set: EffectiveSet):
    def covers(v: Long, g: Long): Boolean = v <= version && g <= generation

  /** A tenant principal's grants. `userId` is the row the handshake bound: the gates finding
    * another row under the same name (the user deleted and created again) refuse the session, whose
    * credential was checked against the first one.
    */
  private final class Following(
      request: AuthzRequest,
      tenant: String,
      userId: String,
      gates: AuthzRequest => Either[HandshakeDenial, AuthorizedHandshake],
      versions: PolicyClock,
      initial: Snapshot
  ) extends SessionGrants:

    private val state = new AtomicReference(initial)

    def rollbackOnly: EffectiveSet = stripped(state.get().set)

    // The version, then the generation: a local change moves the generation before it publishes
    // the version, so a version read here never runs ahead of the generation read after it.
    def current(): Either[RouterFailure, EffectiveSet] =
      val now  = versions.current(tenant)
      val gen  = versions.generation
      val snap = state.get()
      if snap.covers(now, gen) then Right(snap.set) else resolve(now, gen)

    def currentIO: IO[Either[RouterFailure, EffectiveSet]] = IO.defer {
      val now  = versions.current(tenant)
      val gen  = versions.generation
      val snap = state.get()
      if snap.covers(now, gen) then IO.pure(Right(snap.set)) else IO.blocking(resolve(now, gen))
    }

    /** The gates again, `version` and `generation` read before them. Only an admitted set is kept,
      * and never over one resolved under a newer version and generation; a refusal keeps nothing,
      * so every later statement asks the gates again until they admit it.
      */
    private def resolve(version: Long, generation: Long): Either[RouterFailure, EffectiveSet] =
      val outcome =
        try gates(request)
        catch
          case NonFatal(e) =>
            logger.warn(
              s"session grants: re-resolving '${request.username}' on tenant '$tenant' failed: " +
                FailureText.of(e)
            )
            Left(HandshakeDenial.Unavailable("the grants of this session could not be resolved"))
      outcome match
        case Right(auth) if auth.effectiveSet.user.id != userId =>
          Left(
            RouterFailure.AccessDenied(
              s"permission denied: user '${request.username}' is no longer the user this " +
                "session authenticated as; reconnect"
            )
          )
        case Right(auth) =>
          val set   = auth.effectiveSet
          val fresh = Snapshot(version, generation, set)
          state.updateAndGet { old =>
            if old.covers(version, generation) then old
            else if fresh.covers(old.version, old.generation) then fresh
            // Two resolutions read each other's newer half: kept under the older of each, so the
            // next statement resolves again rather than trusting either.
            else Snapshot(math.min(old.version, version), math.min(old.generation, generation), set)
          }
          Right(set)
        case Left(HandshakeDenial.Denied(msg)) =>
          Left(RouterFailure.AccessDenied(s"permission denied: $msg"))
        case Left(HandshakeDenial.Unavailable(msg)) =>
          Left(RouterFailure.Unavailable(s"authorization service unavailable: $msg"))
