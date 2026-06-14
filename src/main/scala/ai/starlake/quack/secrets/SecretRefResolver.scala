package ai.starlake.quack.secrets

/** Resolves a prefixed secret reference (e.g. `env:GOOGLE_CLIENT_SECRET_TPCH`) to its plaintext
  * value. Same prefix vocabulary as `quack.ondemand.federation.DispatchingSecretResolver` (`env:` /
  * `aws-sm:` / `gcp-sm:` / `azure-kv:` / `vault:`), but stripped of the federation row model so the
  * edge can call it without dragging in `FederatedSecret`.
  *
  * First cut wires only `env:`; the cloud backends are stubs that fail with a clear message until
  * the federation resolvers are refactored to share this entry point. That's intentional: the
  * `env:` path is enough to make per-tenant OIDC clientSecrets usable (operators inject the secret
  * as an env var on the manager process), and we don't need to lift four cloud-SDK resolvers up the
  * dependency graph before that vertical works end to end.
  */
trait SecretRefResolver:
  def resolve(ref: String): Either[String, String]

object SecretRefResolver:

  /** Default resolver: handles `env:NAME`; rejects everything else with a clear error so
    * misconfigurations show up at startup-then-handshake rather than as silent fallthroughs.
    */
  val default: SecretRefResolver = fromEnv(name => Option(System.getenv(name)))

  /** Same as [[default]] but with an injectable env lookup -- the only seam tests need to drive the
    * resolver without polluting the process environment.
    */
  def fromEnv(envLookup: String => Option[String]): SecretRefResolver =
    (ref: String) =>
      ref.split(":", 2) match
        case Array("env", name) if name.nonEmpty =>
          envLookup(name).toRight(s"env var '$name' not set for secret ref '$ref'")
        case Array(prefix, _) if Set("aws-sm", "gcp-sm", "azure-kv", "vault").contains(prefix) =>
          Left(
            s"secret ref prefix '$prefix:' not yet wired into the edge resolver; " +
              "use env: for per-tenant OIDC client secrets"
          )
        case _ =>
          Left(
            s"invalid secret ref '$ref' (expected one of: env:NAME, aws-sm:..., " +
              "gcp-sm:..., azure-kv:..., vault:...)"
          )
