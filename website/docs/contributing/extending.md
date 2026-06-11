---
id: extending
title: Extending the manager
---

Two seams are designed to be extended: the runtime backend (where Quack nodes run) and the authentication providers (how clients prove who they are). Both follow the same shape, a small interface with one implementation per strategy, selected from configuration in `Main.scala`. See the [Architecture map](/contributing/architecture-map) for where these sit.

## Adding a runtime backend

A backend is anything that can start, stop, and track Quack nodes. The interface is `QuackBackend` in `ai.starlake.quack.ondemand.runtime`:

```scala
trait QuackBackend:
  def start(spec: NodeSpec): IO[RunningNode]
  def stop(nodeId: String): IO[Unit]
  def isAlive(nodeId: String): Boolean
  def discoverExisting(): IO[List[RunningNode]]
  def cleanup(): IO[Unit]
  def supportsPlacement: Boolean = false      // override to honor NodeSpec.placement
  def adopt(node: RunningNode): IO[Unit] = IO.unit  // re-register a node the backend didn't start
```

The two shipped implementations are `LocalQuackBackend` (forks child processes via `scripts/spawn-quack-node.sh`, allocating ports from a `PortAllocator`) and `KubernetesQuackBackend` (creates pods and honors placement). To add a third:

1. Implement `QuackBackend`. Return a `RunningNode` from `start` with the node's id, host, port, and role. Override `supportsPlacement` to `true` only if you can honor `NodeSpec.placement` (the `nodeSelector` / tolerations); otherwise the supervisor drops cohort data on create so the persisted pool matches what was actually scheduled.
2. Implement `discoverExisting` so a manager restart re-adopts nodes that outlived it, and `cleanup` for shutdown.
3. Add a case to the backend selector in `Main.scala`, which matches on `mgrCfg.runtimeType` (`local`, `kubernetes`/`k8s`):

```scala
val backend: QuackBackend = mgrCfg.runtimeType.toLowerCase match
  case "local"               => new LocalQuackBackend(...)
  case "kubernetes" | "k8s"  => new KubernetesQuackBackend(...)
  case "your-backend"        => new YourBackend(...)   // add here
```

Add the matching `QOD_RUNTIME_TYPE` value and any backend-specific config keys to `application.conf` (each as a `QOD_*`-overridable scalar).

## Adding an authentication provider

Authentication is split by credential shape into two interfaces in `ai.starlake.quack.edge.auth`:

```scala
sealed trait AuthScope
object AuthScope:
  case object System extends AuthScope          // empty tenant / ?superuser=true
  final case class Tenant(id: String) extends AuthScope

trait BasicAuthProvider extends AutoCloseable:
  def name: String
  def authenticate(scope: AuthScope, username: String, password: String)
      : Either[String, AuthenticatedProfile]

trait BearerAuthProvider extends AutoCloseable:
  def name: String
  def authenticate(token: String): Either[String, AuthenticatedProfile]
```

Pick `BasicAuthProvider` for a username/password backend (the database authenticator and OIDC resource-owner-password flow implement this) or `BearerAuthProvider` for a token backend (JWT and OIDC bearer implement this). `authenticate` returns an `AuthenticatedProfile` on success or a `Left` reason on rejection.

The `AuthScope` argument is the caller-declared realm. It's `System` for management-plane logins with no tenant and FlightSQL handshakes carrying `?superuser=true`; it's `Tenant(id)` for everything else. A database-style provider uses the scope to pick which `qodstate_user` row to read; OIDC ROPC providers ignore the scope because the OIDC server is authoritative.

`AuthenticationService` builds an ordered chain of each kind from config (`buildBasicChain` / `buildBearerChain`) and tries them in order until one accepts. For Bearer, `bearerChainFor(scope)` consults the optional `TenantOidcRegistry` and may substitute a per-tenant authenticator into the chain (Google today; the pattern extends to Keycloak / Azure / AWS). To add a provider:

1. Implement the appropriate trait.
2. If your provider extracts roles or groups from a token, route them through `RoleExtractor` so they map into the RBAC graph the same way the built-in providers do.
3. Add the provider to the relevant chain builder in `AuthenticationService`, gated by a config flag, and add that flag plus its settings to `application.conf` under `quack-flightsql.auth` (with `QOD_*` overrides).
4. For per-tenant overrides (a tenant has its own client credentials), extend `TenantOidcRegistry` with a typed lookup that reads from `qodstate_tenant.authConfig` and call `bearerChainFor` to splice it in. Secrets must be referenced via `SecretRefResolver` (e.g. `env:NAME`), never stored as literals on the tenant row.

Because providers are tried in order and the first acceptance wins, ordering in the chain builder is the precedence. A provider that throws should instead return a `Left` so the chain can fall through to the next one.

## Tests

The routing core and classifier have focused specs (`RouterSpec`, `StatementClassifierSpec`, `FlightSqlRouterSpec`) that run without a live Flight connection, so a new backend or provider can be unit-tested against the same seams. Run the suite with `sbt test`; see the [Development loop](/contributing/dev-loop).
