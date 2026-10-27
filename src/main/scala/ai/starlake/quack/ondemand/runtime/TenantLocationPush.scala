package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.model.RunningNode
import cats.effect.IO
import cats.syntax.all.*
import com.typesafe.scalalogging.LazyLogging

/** Brings a tenant's running nodes in line with a change of its authorized locations: runs the
  * change's statements ([[TenantLocationSecrets.pushAdd]] / [[TenantLocationSecrets.pushRemove]]),
  * in order, on every running node of the tenant, the nodes in parallel. Main runs each statement
  * through the system principal (`QuackHttpAdapter.send`). Never logs a statement or a node's
  * answer: a `CREATE SECRET` carries the bucket's credentials and DuckDB echoes the statement in
  * its errors. A node that fails is named in the outcome and gets the change at its next spawn.
  */
final class TenantLocationPush(
    nodesOf: String => List[RunningNode],
    run: (RunningNode, String) => IO[Either[String, Unit]]
) extends LazyLogging:

  def apply(
      tenantId: String,
      label: String,
      statements: List[String]
  ): IO[TenantLocationPush.Outcome] =
    if statements.isEmpty then IO.pure(TenantLocationPush.Outcome(Nil, Nil))
    else
      nodesOf(tenantId)
        .parTraverse { n =>
          statements
            .foldLeft(IO.pure(true)) { (sofar, sql) =>
              sofar.flatMap(ok =>
                if ok then run(n, sql).map(_.isRight).handleError(_ => false) else IO.pure(false)
              )
            }
            .map(n.nodeId -> _)
        }
        .map { results =>
          val failed = results.collect { case (id, false) => id }
          failed.foreach { id =>
            logger.warn(
              s"tenant location $label: node $id did not take the change; it applies at the " +
                "node's next restart"
            )
          }
          TenantLocationPush.Outcome(results.collect { case (id, true) => id }, failed)
        }

object TenantLocationPush:

  /** Node ids that took the change, and those that did not. */
  final case class Outcome(updated: List[String], failed: List[String])
