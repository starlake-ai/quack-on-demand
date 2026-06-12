package ai.starlake.quack.ondemand.federation

import ai.starlake.quack.model.FederatedSecret
import cats.effect.IO

/** Stub. Wire the AWS Secrets Manager SDK call when the team is ready to depend on it. externalRef
  * formats accepted: `aws-sm:arn:aws:secretsmanager:region:acct:secret:Name`,
  * `aws-sm:name#jsonKey`.
  *
  * In `dispatch` mode this stub is wired into [[DispatchingSecretResolver]] alongside the working
  * `postgres` + `env` resolvers; deployments that only consume those keep working until the day a
  * source actually carries an `aws-sm:` externalRef, at which point [[resolve]] raises with a
  * pointer at the supported alternatives.
  */
final class AwsSecretsManagerResolver extends SecretResolver {
  def resolve(secret: FederatedSecret): IO[String] = IO.raiseError(
    new NotImplementedError(
      s"AwsSecretsManagerResolver is a stub; secret '${secret.name}' carries an aws-sm: " +
        "externalRef but the AWS SDK is not wired. Store the secret value directly " +
        "(postgres resolver) or as an env var (env: prefix), or implement this resolver " +
        "and rebuild. See ondemand/federation/AwsSecretsManagerResolver.scala."
    )
  )
}
