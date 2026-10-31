package ai.starlake.quack.spi

import ai.starlake.quack.model.PoolKey
import cats.effect.IO

/** Runs one SQL statement on a pool as the manager's own system caller: a module's provisioning
  * work, such as seeding a new tenant's database.
  *
  * Trust model: superuser-level. The statement runs as core's system caller (`ExecCaller.system`):
  * the routed executor gives it the synthetic superuser EffectiveSet (no grants, no column or row
  * policy, no token restriction) and the node runs it as `NodePrincipal.System`, the principal no
  * credential can become. This adds no trust boundary: a module runs in the manager's process and
  * already holds `ManagerContext.controlPlaneDs`, the control-plane database. Use it only for
  * statements the module writes itself, never for SQL a user supplied: a user's statement runs as
  * that user, through the FlightSQL edge or the routed executor.
  *
  * Attribution: Main builds each module's instance bound to that module's name before
  * `ManagerModule.start`, so a module cannot act under another's name. Every statement is labelled
  * `module:<name>` (statement history, the DuckLake commit author of a write) and leaves one
  * `module.sql` audit row naming the module, the pool, the outcome and the statement.
  *
  * Visibility: statement history and the audit rows of a pool are shown to that tenant's admins.
  * Their copy of the statement has every literal and option value masked (`CREATE SECRET` values,
  * `SET s3_secret_access_key = ...`), and its error is scrubbed of them; a claim-shaped admin
  * statement is replaced whole. The masking is of values, not of names or structure: never put a
  * credential in an identifier, and prefer a secret the node already holds to one in the statement.
  *
  * The statement runs once, through the router, on a node of `pool`, with the pool's default
  * catalog and schema current, so unqualified names resolve in the tenant-db. The answer is
  * `Right(())` once it ran and its result was read to the end (the rows are discarded), or `Left`
  * with the error text: a node refusal, a DuckDB error, no serving node. A failure never fails the
  * IO. There is no timeout of its own: bound the call with `IO.timeout` where it matters.
  */
trait SystemSql:
  def execute(pool: PoolKey, sql: String): IO[Either[String, Unit]]
