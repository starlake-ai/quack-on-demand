package ai.starlake.quack.model

import java.time.Instant

/** A named secret referenced from a FederatedSource's setupSql as `{{secret.NAME}}`. Exactly one of
  * `value` (resolved by PostgresSecretResolver) or `externalRef` (resolved by env / aws-sm / gcp-sm
  * / azure-kv / vault) is set.
  */
final case class FederatedSecret(
    id: String,
    federatedSourceId: String,
    name: String,
    value: Option[String],
    externalRef: Option[String],
    createdAt: Option[Instant] = None
) {
  require(
    value.isDefined ^ externalRef.isDefined,
    s"FederatedSecret '$name': exactly one of value/externalRef must be set"
  )
}

object FederatedSecret {

  /** Sentinel emitted in place of a resolved inline secret value on any read path that must not
    * leak it: the REST secret response, the manifest exporter, and (recognized on the way back in)
    * the manifest importer, which treats it as "reuse the stored value, do not overwrite". Single
    * source of truth so the redaction marker and its round-trip recognition can never drift.
    */
  val RedactedMarker: String = "***REDACTED***"
}
