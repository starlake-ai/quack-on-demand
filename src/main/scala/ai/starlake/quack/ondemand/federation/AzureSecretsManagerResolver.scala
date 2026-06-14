package ai.starlake.quack.ondemand.federation

import ai.starlake.quack.model.FederatedSecret
import cats.effect.IO

/** Stub. Wire the Azure Key Vault SDK call when ready. externalRef format: `azure-kv:<secretName>`
  * (vault URL from config).
  *
  * Co-wired in [[DispatchingSecretResolver]] under `dispatch` mode; only sources whose externalRef
  * actually begins with `azure-kv:` hit this resolver at runtime. See sibling
  * [[AwsSecretsManagerResolver]] for the rationale.
  */
final class AzureSecretsManagerResolver extends SecretResolver {
  def resolve(secret: FederatedSecret): IO[String] = IO.raiseError(
    new NotImplementedError(
      s"AzureSecretsManagerResolver is a stub; secret '${secret.name}' carries an azure-kv: " +
        "externalRef but the Azure SDK is not wired. Store the secret value directly " +
        "(postgres resolver) or as an env var (env: prefix), or implement this resolver " +
        "and rebuild. See ondemand/federation/AzureSecretsManagerResolver.scala."
    )
  )
}
