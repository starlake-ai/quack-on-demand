package ai.starlake.quack.edge.authz

import ai.starlake.quack.edge.opa.Decision as PolicyDecision
import com.github.benmanes.caffeine.cache.{Cache, Caffeine}
import io.circe.Json

import java.util.concurrent.TimeUnit

/** What the tenant's OPA was asked and answered for one statement of the OPA dry run (decision 25):
  * `rule` the rule asked (`statement`, or `connect` for the handshake form), `input` the exact
  * `{"input": ...}` document sent, `decision` allow, deny or error, `reason` the deny reason or the
  * error's cause (shown to a superuser only: the REST handler shows anyone else
  * `OpaAuthorizer.UnavailableMessage`), `decisionId` OPA's decision id when it gave one.
  */
final case class DryRunOutcome(
    rule: String,
    input: Json,
    decision: String,
    reason: Option[String],
    decisionId: Option[String]
)

object DryRunOutcome:

  /** `d`, OPA's answer to `input` on `rule`, as the dry run reports it. */
  def of(rule: String, input: Json, d: PolicyDecision): DryRunOutcome = d match
    case PolicyDecision.Allow(id)           => DryRunOutcome(rule, input, "allow", None, id)
    case PolicyDecision.Deny(reason, _, id) => DryRunOutcome(rule, input, "deny", Some(reason), id)
    case PolicyDecision.Error(cause) => DryRunOutcome(rule, input, "error", Some(cause), None)

/** The OPA calls the decision endpoint made for dry-run statements ([[DecideRoute]]), by the nonce
  * of the handle the node decided under, until the REST handler that ran the statement takes them
  * (`OpaDryRun`). A handle names the replica that minted it, and that replica runs the handler, so
  * the map is in memory only. Kept [[DryRunResults.RetentionMs]] after recording and bounded by
  * `maxEntries`: past the bound Caffeine evicts by W-TinyLFU, and the handler then answers as if
  * OPA had not been asked.
  */
final class DryRunResults(
    maxEntries: Long = DryRunResults.MaxEntries,
    clock: () => Long = () => System.currentTimeMillis()
):
  private val cache: Cache[String, (DryRunOutcome, Long)] =
    Caffeine
      .newBuilder()
      .maximumSize(maxEntries)
      .expireAfterWrite(DryRunResults.RetentionMs, TimeUnit.MILLISECONDS)
      .executor(_.run())
      .build[String, (DryRunOutcome, Long)]()

  /** Records `o` for the statement decided under handle `nonce`; a later call replaces it. */
  def put(nonce: String, o: DryRunOutcome): Unit = cache.put(nonce, (o, clock()))

  /** The outcome recorded under `nonce`, removed: each is answered once. */
  def take(nonce: String): Option[DryRunOutcome] =
    Option(cache.asMap().remove(nonce)).collect {
      case (o, recordedAt) if clock() <= recordedAt + DryRunResults.RetentionMs => o
    }

  /** How many outcomes are held, after pending evictions ran. */
  def size: Long =
    cache.cleanUp()
    cache.estimatedSize()

object DryRunResults:

  /** How long an outcome waits for its handler, which takes it within the statement's round trip.
    */
  val RetentionMs: Long = 5 * 60_000L

  /** Outcomes kept at most. */
  val MaxEntries: Long = 10_000L
