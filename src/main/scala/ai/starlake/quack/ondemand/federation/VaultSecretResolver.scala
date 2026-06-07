package ai.starlake.quack.ondemand.federation

import ai.starlake.quack.model.FederatedSecret
import cats.effect.IO

/** Stub. Wire the HashiCorp Vault HTTP client when ready.
  * externalRef format: vault:secret/data/<path>#<key> */
final class VaultSecretResolver extends SecretResolver {
  def resolve(secret: FederatedSecret): IO[String] = IO.raiseError(
    new NotImplementedError(s"VaultSecretResolver not yet implemented (secret '${secret.name}')")
  )
}