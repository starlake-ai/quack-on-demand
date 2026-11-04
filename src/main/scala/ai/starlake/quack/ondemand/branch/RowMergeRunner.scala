package ai.starlake.quack.ondemand.branch

import cats.data.EitherT
import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json

/** The merge node, up for one analysis or merge. Each call is a new connection: session settings do
  * not carry over from one call to the next, tables in its in-memory database do. Neither call
  * raises: a failure is a value, with the engine's text.
  */
trait MergeNode:

  /** `statements` in order on the call's one connection, so a transaction and session settings span
    * them. Every entry is exactly one statement: the node binds each under a session handle of its
    * own, and a statement sharing an entry with another could bind after that handle has expired.
    * The first failure ends the call and its connection, which rolls an open transaction back. A
    * COMMIT whose answer was lost may have landed: its reason starts with
    * [[MergeExecutor.TimedOut]], as for a call that timed out.
    */
  def exec(statements: List[String]): IO[Either[String, Unit]]

  def rows(sql: String): IO[Either[String, List[List[Json]]]]

/** Runs the row merge's stages on a node (design section 5). Knows nothing of fences or proposals.
  */
final class RowMergeRunner(
    parentAlias: String,
    branchAlias: String,
    fork: Long,
    policy: Option[ConflictPolicy]
):
  private type Step[A] = EitherT[IO, String, A]

  private def exec(node: MergeNode, statements: List[String]): Step[Unit] =
    EitherT(node.exec(RowMergeSql.Settings :: statements))

  private def rows(node: MergeNode, sql: String): Step[List[List[Json]]] =
    EitherT(node.rows(sql))

  /** Everything on the frozen branch, once. */
  def prepare(node: MergeNode, tables: List[RowMergeTable]): IO[Either[String, Unit]] =
    exec(
      node,
      RowMergeSql.CreateWorkSchema ::
        tables.flatMap(RowMergeSql.branchSide(branchAlias, parentAlias, _, fork))
    ).value

  /** Reads main's side and fetches the original values not held yet, skipping the fetch when there
    * is none to fetch: a semi-join on an empty table reads the whole table (design finding 14).
    */
  private def refreshMain(node: MergeNode, t: RowMergeTable): Step[RowMergeSql.OverlapSizes] =
    for
      _     <- exec(node, RowMergeSql.mainSide(parentAlias, t))
      sizes <- rows(node, RowMergeSql.overlapSizes(t)).subflatMap(RowMergeSql.parseSizes)
      _     <-
        if sizes.need > 0L then exec(node, List(RowMergeSql.topUp(parentAlias, t, fork)))
        else EitherT.rightT[IO, String](())
    yield sizes

  /** Fetches original values ahead of the first attempt, so a hold never covers that read. */
  def warmUp(node: MergeNode, tables: List[RowMergeTable]): IO[Either[String, Unit]] =
    tables.traverse_(refreshMain(node, _)).value

  /** Main's side at the tables' `mainRanges`, each row's action, the sets Apply writes. */
  def decide(node: MergeNode, tables: List[RowMergeTable]): IO[Either[String, List[RowCounts]]] =
    tables.traverse { t =>
      for
        sizes   <- refreshMain(node, t)
        _       <- exec(node, RowMergeSql.values(parentAlias, t, fork, sizes.overlap, policy))
        counted <- rows(node, RowMergeSql.counts(t))
        sampled <- rows(node, RowMergeSql.samples(t))
        parsed  <- EitherT.fromEither[IO](RowMergeSql.parseCounts(t, counted, sampled))
      yield parsed
    }.value
