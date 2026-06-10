package ai.starlake.quack.ondemand.federation

import ai.starlake.quack.model.FederatedSecret
import cats.effect.IO

/** Strategy for turning a [[ai.starlake.quack.model.FederatedSecret]] row into its plaintext value.
  * Selected once at process boot from the configured `federation.secretStore`.
  */
abstract class SecretResolver {
  def resolve(secret: FederatedSecret): IO[String]
}
