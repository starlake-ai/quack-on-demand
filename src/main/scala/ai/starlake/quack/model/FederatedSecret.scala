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
