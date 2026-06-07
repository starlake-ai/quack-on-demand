package ai.starlake.quack.ondemand.federation

import ai.starlake.quack.model.FederatedSecret
import cats.effect.IO

/** Stub. Wire the GCP Secret Manager SDK call when ready.
  * externalRef format: gcp-sm:projects/<p>/secrets/<name>/versions/latest */
final class GcpSecretsManagerResolver extends SecretResolver {
  def resolve(secret: FederatedSecret): IO[String] = IO.raiseError(
    new NotImplementedError(s"GcpSecretsManagerResolver not yet implemented (secret '${secret.name}')")
  )
}