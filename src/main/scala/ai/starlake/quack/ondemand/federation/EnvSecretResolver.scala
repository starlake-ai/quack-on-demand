package ai.starlake.quack.ondemand.federation

import ai.starlake.quack.model.FederatedSecret
import cats.effect.IO

/** Resolves `externalRef = "env:VAR_NAME"` via the provided lookup
  * (defaulting to `System.getenv`). The lookup is injected so tests
  * can drive it without polluting the process environment. */
final class EnvSecretResolver(
    lookup: String => Option[String] = name => Option(System.getenv(name))
) extends SecretResolver {

  private val Prefix = "env:"

  def resolve(secret: FederatedSecret): IO[String] = IO {
    val ref = secret.externalRef.getOrElse(
      sys.error(s"secret '${secret.name}': EnvSecretResolver needs externalRef"))
    if !ref.startsWith(Prefix) then
      sys.error(s"secret '${secret.name}': expected env: externalRef, got '$ref'")
    val varName = ref.substring(Prefix.length)
    lookup(varName).getOrElse(
      sys.error(s"env var '$varName' not set for secret '${secret.name}'"))
  }
}