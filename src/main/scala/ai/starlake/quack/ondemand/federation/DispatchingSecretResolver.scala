package ai.starlake.quack.ondemand.federation

import ai.starlake.quack.model.FederatedSecret
import cats.effect.IO

/** Routes a [[FederatedSecret]] to the right concrete resolver based on
  * the row's shape:
  *
  *   1. `value` is set        -> [[PostgresSecretResolver]] returns the literal
  *      plaintext stored in `qodstate_federated_secret.value`.
  *   2. `externalRef` is set  -> the prefix selects the resolver:
  *      `env:` `aws-sm:` `gcp-sm:` `azure-kv:` `vault:`.
  *
  * Lets a single manager mix backends: one source can hold its password
  * in Vault while another reads from AWS Secrets Manager, with no
  * global mode flip needed. Wired by `Main.scala` when
  * `federation.secretStore = "dispatch"` (the default in newer
  * deployments).
  *
  * Each leaf resolver must already be authenticated by its surrounding
  * environment (SDK default credential chain for the cloud backends,
  * `VAULT_TOKEN` for Vault). The dispatcher itself holds no credentials. */
final class DispatchingSecretResolver(
    postgres: SecretResolver,
    env:      SecretResolver,
    awsSm:    SecretResolver,
    gcpSm:    SecretResolver,
    azureKv:  SecretResolver,
    vault:    SecretResolver
) extends SecretResolver {

  def resolve(secret: FederatedSecret): IO[String] =
    if secret.value.isDefined then postgres.resolve(secret)
    else
      secret.externalRef match
        case Some(ref) => routeByPrefix(secret, ref)
        case None =>
          IO.raiseError(new RuntimeException(
            s"secret '${secret.name}': neither value nor externalRef is set"))

  private def routeByPrefix(secret: FederatedSecret, ref: String): IO[String] =
    val colon = ref.indexOf(':')
    val prefix = if colon < 0 then ref else ref.substring(0, colon)
    prefix match
      case "env"      => env.resolve(secret)
      case "aws-sm"   => awsSm.resolve(secret)
      case "gcp-sm"   => gcpSm.resolve(secret)
      case "azure-kv" => azureKv.resolve(secret)
      case "vault"    => vault.resolve(secret)
      case other      =>
        IO.raiseError(new RuntimeException(
          s"secret '${secret.name}': unknown externalRef prefix '$other:' " +
          s"(expected env:, aws-sm:, gcp-sm:, azure-kv:, or vault:)"))
}