package ai.starlake.quack.edge.opa

import com.github.benmanes.caffeine.cache.{Cache, Caffeine}
import java.util.concurrent.TimeUnit

/** Everything a decision depends on. `settings` fingerprints the tenant's OPA URL / path / token /
  * sendStatementText, so a settings change can never be answered from a stale entry. Every other
  * field the OPA input actually carries must be here too: a cached Allow must never answer a
  * different question (different statement class, edge, parent pools, user name or statement text)
  * than the one that produced it.
  */
final case class OpaCacheKey(
    tenantId: String,
    settings: String,
    userId: String,
    userName: String,
    roles: List[String],
    groups: List[String],
    claims: List[(String, String)],
    pool: String,
    kind: String,
    statementClass: String,
    edge: String,
    parentPools: List[String],
    accesses: List[String],
    textHash: String,
    // SHA-256 of a node decision's whole input (kinds, columns, via, locations: what `accesses`
    // above does not carry); empty for the gateway's inputs.
    detail: String = ""
)

/** Short-TTL decision cache. `Error` is never stored; ttlSec 0 disables caching. */
final class OpaDecisionCache(ttlSec: Int):
  private val cache: Option[Cache[OpaCacheKey, Decision]] =
    Option.when(ttlSec > 0)(
      Caffeine
        .newBuilder()
        .expireAfterWrite(ttlSec.toLong, TimeUnit.SECONDS)
        .maximumSize(50_000)
        .build[OpaCacheKey, Decision]()
    )

  def getOrCompute(key: OpaCacheKey)(compute: => Decision): (Decision, Boolean) =
    cache.flatMap(c => Option(c.getIfPresent(key))) match
      case Some(hit) => (hit, true)
      case None      =>
        val d = compute
        d match
          case _: Decision.Error => ()
          case _                 => cache.foreach(_.put(key, d))
        (d, false)
