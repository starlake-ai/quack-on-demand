package ai.starlake.quack.ondemand.branch

import ai.starlake.quack.model.NodeSpec
import cats.effect.IO
import io.circe.Json

/** A merge node with nothing to say: every read answers zeros, every batch succeeds. For specs that
  * exercise everything around the merge.
  */
object ZeroRowsMergeNode extends MergeNode:
  def exec(batch: String): IO[Either[String, Unit]]           = IO.pure(Right(()))
  def rows(sql: String): IO[Either[String, List[List[Json]]]] =
    IO.pure(Right(List(List.fill(64)(Json.fromLong(0L)))))

object ZeroRowsMergeExecutor extends MergeExecutor:
  def withNode[A](spec: NodeSpec)(body: MergeNode => IO[Either[String, A]]): IO[Either[String, A]] =
    body(ZeroRowsMergeNode)
