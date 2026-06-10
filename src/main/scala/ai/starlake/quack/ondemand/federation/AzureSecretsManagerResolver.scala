package ai.starlake.quack.ondemand.federation

import ai.starlake.quack.model.FederatedSecret
import cats.effect.IO

/** Stub. Wire the Azure Key Vault SDK call when ready. externalRef format: azure-kv:<secretName>
  * (vault URL from config)
  */
final class AzureSecretsManagerResolver extends SecretResolver {
  def resolve(secret: FederatedSecret): IO[String] = IO.raiseError(
    new NotImplementedError(
      s"AzureSecretsManagerResolver not yet implemented (secret '${secret.name}')"
    )
  )
}
