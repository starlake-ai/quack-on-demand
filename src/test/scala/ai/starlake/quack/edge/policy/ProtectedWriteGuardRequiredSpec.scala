package ai.starlake.quack.edge.policy

import ai.starlake.quack.edge.FlightSqlRouter
import ai.starlake.quack.edge.cls.ColumnCatalog
import ai.starlake.quack.edge.meta.MetadataFilterRewriter
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The protected-write guard and its policy switches are never defaulted (final review I1): since
  * decision 27 the guard is the only place the policy holders' screens run (dotted names, query()
  * and query_table(), SQL-valued SET, schema moves, ambiguous two-part names), so a router built
  * without it, or a guard built without saying whether column and row policies are on, would let a
  * masked user read `query_table('customer')` unmasked. Leaving either out must not compile. Each
  * refusal is paired with the same construction given the argument, so it cannot pass for another
  * reason.
  */
class ProtectedWriteGuardRequiredSpec extends AnyFlatSpec with Matchers:

  // Typed placeholders the snippets name: type-checked, never run.
  private val supervisor: ai.starlake.quack.ondemand.PoolSupervisor    = null
  private val sessions: ai.starlake.quack.edge.SessionRegistry         = null
  private val tracker: ai.starlake.quack.edge.adapter.NodeLoadTracker  = null
  private val adapter: ai.starlake.quack.edge.adapter.QuackHttpAdapter = null
  private val filter: MetadataFilterRewriter                           = null
  private val catalog: ColumnCatalog                                   = null
  private val guard: ProtectedWriteGuard                               = null

  // The snippets below are type-checked when this spec compiles, and a string records no
  // dependency: these compiled constructions (never run) make an incremental build recompile the
  // spec, and so check the snippets again, whenever one of these constructors changes.
  private def dependencies(): Unit =
    new FlightSqlRouter(
      supervisor,
      sessions,
      tracker,
      adapter,
      metadataFilterRewriter = filter,
      protectedWriteGuard = guard
    )
    new ProtectedWriteGuard(catalog, clsEnabled = true, rlsEnabled = true)
    ()

  "the router" should "need the protected-write guard" in {
    """new FlightSqlRouter(supervisor, sessions, tracker, adapter, metadataFilterRewriter = filter)""" shouldNot
      typeCheck
    """new FlightSqlRouter(supervisor, sessions, tracker, adapter, metadataFilterRewriter = filter, protectedWriteGuard = guard)""" should
      compile
  }

  "the guard" should "need to be told whether column and row policies are on" in {
    """new ProtectedWriteGuard(catalog)""" shouldNot typeCheck
    """new ProtectedWriteGuard(catalog, clsEnabled = true)""" shouldNot typeCheck
    """new ProtectedWriteGuard(catalog, rlsEnabled = true)""" shouldNot typeCheck
    """new ProtectedWriteGuard(catalog, clsEnabled = true, rlsEnabled = true)""" should compile
  }
