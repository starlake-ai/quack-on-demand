package ai.starlake.quack.edge.authz

import io.circe.Json

import java.nio.charset.StandardCharsets
import java.util.Base64

/** One search call's statistics population and index record in a plan verdict (search spec 4.2 rule
  * 8, 6.1, 7.5): `call` the description's `searches[].call`, `population` `table` or `allowed`,
  * `indexId` and `bundleRoot` the index's own record (the node takes the bundle folder from here,
  * never from its own data path).
  */
final case class SearchPopulation(
    call: Int,
    population: String,
    indexId: String,
    bundleRoot: String
)

object SearchPopulation:
  val Table: String   = "table"
  val Allowed: String = "allowed"

/** The manager's answer to one decide call (verdict v1). `decision` is `allow` or `refuse`;
  * `principal`, `policyVersion` and `ctx` are the handle's, `fingerprint` the SHA-256 of the
  * description bytes the node sent, `expiresAt` the epoch millisecond after which the node drops it
  * (now for a screen verdict, which it never caches). `search`: one entry per search call of an
  * allowed plan description holding `searches`; empty otherwise, and then absent on the wire, so a
  * verdict without search calls keeps the bytes it always had.
  */
final case class Verdict(
    kind: String,
    decision: String,
    reason: String,
    retryable: Boolean,
    principal: String,
    policyVersion: Long,
    fingerprint: String,
    expiresAt: Long,
    ctx: String,
    search: List[SearchPopulation] = Nil
)

/** Verdicts on the wire (decision 14): `<base64url(verdict canonical JSON)>.<lowercase hex
  * HMAC-SHA256(node key, verdict bytes)>`, the handles' format. The JSON is canonical so the bytes
  * are fixed by the fields (native/qod-node/tests/golden/verdict-v1.txt pins them); `rowFilters`
  * and `masks` are always empty, reserved for part 2, and the node refuses a verdict carrying
  * either.
  */
object Verdicts:

  def json(v: Verdict): Json =
    val base = Json.obj(
      "v"             -> Json.fromInt(1),
      "kind"          -> Json.fromString(v.kind),
      "decision"      -> Json.fromString(v.decision),
      "reason"        -> Json.fromString(v.reason),
      "retryable"     -> Json.fromBoolean(v.retryable),
      "principal"     -> Json.fromString(v.principal),
      "policyVersion" -> Json.fromLong(v.policyVersion),
      "fingerprint"   -> Json.fromString(v.fingerprint),
      "expiresAt"     -> Json.fromLong(v.expiresAt),
      "ctx"           -> Json.fromString(v.ctx),
      "rowFilters"    -> Json.arr(),
      "masks"         -> Json.arr()
    )
    if v.search.isEmpty then base
    else
      base.deepMerge(
        Json.obj(
          "search" -> Json.arr(v.search.sortBy(_.call).map { s =>
            Json.obj(
              "call"       -> Json.fromInt(s.call),
              "population" -> Json.fromString(s.population),
              "indexId"    -> Json.fromString(s.indexId),
              "bundleRoot" -> Json.fromString(s.bundleRoot)
            )
          }*)
        )
      )

  def bytes(v: Verdict): Array[Byte] =
    CanonicalJson.render(json(v)).getBytes(StandardCharsets.UTF_8)

  /** `v` signed for the node whose key is `nodeKey`. */
  def sign(v: Verdict, nodeKey: Array[Byte]): String =
    val b = bytes(v)
    Base64.getUrlEncoder.withoutPadding.encodeToString(b) + "." +
      NodeHandles.hex(NodeHandles.hmac(nodeKey, b))
