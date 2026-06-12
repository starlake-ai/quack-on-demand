package ai.starlake.quack.ondemand.federation

import ai.starlake.quack.model.FederatedSecret
import cats.effect.IO

/** Stub. Wire the HashiCorp Vault HTTP client when ready. externalRef format:
  * `vault:secret/data/<path>#<key>`.
  *
  * Co-wired in [[DispatchingSecretResolver]] under `dispatch` mode; only sources whose externalRef
  * actually begins with `vault:` hit this resolver at runtime. See sibling
  * [[AwsSecretsManagerResolver]] for the rationale.
  */
final class VaultSecretResolver extends SecretResolver {
  def resolve(secret: FederatedSecret): IO[String] = IO.raiseError(
    new NotImplementedError(
      s"VaultSecretResolver is a stub; secret '${secret.name}' carries a vault: " +
        "externalRef but the Vault client is not wired. Store the secret value directly " +
        "(postgres resolver) or as an env var (env: prefix), or implement this resolver " +
        "and rebuild. See ondemand/federation/VaultSecretResolver.scala."
    )
  )
}
