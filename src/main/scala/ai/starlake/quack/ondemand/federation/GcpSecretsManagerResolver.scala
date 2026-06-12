package ai.starlake.quack.ondemand.federation

import ai.starlake.quack.model.FederatedSecret
import cats.effect.IO

/** Stub. Wire the GCP Secret Manager SDK call when ready. externalRef format:
  * `gcp-sm:projects/<p>/secrets/<name>/versions/latest`.
  *
  * Co-wired in [[DispatchingSecretResolver]] under `dispatch` mode; only sources whose externalRef
  * actually begins with `gcp-sm:` hit this resolver at runtime. See sibling
  * [[AwsSecretsManagerResolver]] for the rationale.
  */
final class GcpSecretsManagerResolver extends SecretResolver {
  def resolve(secret: FederatedSecret): IO[String] = IO.raiseError(
    new NotImplementedError(
      s"GcpSecretsManagerResolver is a stub; secret '${secret.name}' carries a gcp-sm: " +
        "externalRef but the GCP SDK is not wired. Store the secret value directly " +
        "(postgres resolver) or as an env var (env: prefix), or implement this resolver " +
        "and rebuild. See ondemand/federation/GcpSecretsManagerResolver.scala."
    )
  )
}
