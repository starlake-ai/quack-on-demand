package ai.starlake.quack.spi

import cats.effect.IO

/** A structure mutation the manager is about to perform. Consulted through [[MutationGate]] BEFORE
  * the supervisor acts, so a module can veto resource growth (quota policy lives in modules, never
  * in core). Node counts are the requested totals; cpu/memory are the pool's declared Kubernetes
  * quantity strings, possibly empty when undeclared.
  */
enum StructureMutation:
  case CreateTenantDb(tenant: String)
  case CreatePool(tenant: String, tenantDb: String, nodes: Int, cpu: String, memory: String)
  case ResizePool(
      tenant: String,
      tenantDb: String,
      pool: String,
      from: Int,
      to: Int,
      cpu: String,
      memory: String
  )
  case SetPoolResources(
      tenant: String,
      tenantDb: String,
      pool: String,
      nodes: Int,
      fromCpu: String,
      fromMemory: String,
      toCpu: String,
      toMemory: String
  )

/** Module-contributed veto hook. `Left(reason)` refuses the mutation; the reason string travels to
  * the caller as an HTTP 429 body. Gates run on the request path: keep checks cheap (a cached read
  * or one indexed SQL query). Superuser and static-key callers bypass gates entirely.
  */
trait MutationGate:
  def check(m: StructureMutation): IO[Either[String, Unit]]

/** Raised by supervisor paths that report errors by throwing (createPool, scale) when a gate
  * refuses. Either-returning paths use SupervisorError.QuotaExceeded instead.
  */
final class QuotaExceededException(val reason: String) extends RuntimeException(reason)
