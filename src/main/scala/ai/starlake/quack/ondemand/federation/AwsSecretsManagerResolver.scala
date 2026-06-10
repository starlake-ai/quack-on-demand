package ai.starlake.quack.ondemand.federation

import ai.starlake.quack.model.FederatedSecret
import cats.effect.IO

/** Stub. Wire the AWS SDK call when the team is ready to depend on it. externalRef formats
  * accepted: aws-sm:arn:aws:secretsmanager:region:acct:secret:Name aws-sm:name#jsonKey
  */
final class AwsSecretsManagerResolver extends SecretResolver {
  def resolve(secret: FederatedSecret): IO[String] = IO.raiseError(
    new NotImplementedError(
      s"AwsSecretsManagerResolver not yet implemented (secret '${secret.name}')"
    )
  )
}
