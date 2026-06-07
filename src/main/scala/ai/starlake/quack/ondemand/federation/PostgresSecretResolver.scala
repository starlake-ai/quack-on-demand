package ai.starlake.quack.ondemand.federation

import ai.starlake.quack.model.FederatedSecret
import cats.effect.IO

/** Resolver for secrets whose plaintext value already lives in the
  * `qodstate_federated_secret.value` column. The secret row was loaded
  * by the store; this resolver just returns its `value` field. */
final class PostgresSecretResolver extends SecretResolver {
  def resolve(secret: FederatedSecret): IO[String] =
    IO.fromOption(secret.value)(
      new RuntimeException(s"secret '${secret.name}' has no value in qodstate_federated_secret")
    )
}