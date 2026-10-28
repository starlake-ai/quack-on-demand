package ai.starlake.quack.ondemand.initsql

import ai.starlake.quack.model.{Pool, TenantDb}
import com.typesafe.scalalogging.LazyLogging

/** The boot report of untrusted `initSql`: one WARN per database or pool whose `initSql` a tenant
  * admin wrote (or that predates Liquibase 0053 and was not the manager's), naming the tenant, the
  * row and how a superuser vouches for it. Never the SQL itself: init SQL may carry credentials.
  */
object InitSqlTrustReport extends LazyLogging:

  /** One untrusted, non-empty `initSql`. `fix` is the way a superuser makes it trusted. */
  final case class Row(tenant: String, label: String, sql: String, fix: String)

  /** The rows of `tenantDbs` and `pools` whose `initSql` is untrusted and not empty. */
  def untrusted(tenantDbs: List[TenantDb], pools: List[Pool]): List[Row] =
    val dbNames = tenantDbs.map(td => td.id -> td.name).toMap
    val dbs     = tenantDbs.filter(td => !td.initSqlTrusted && td.initSql.trim.nonEmpty).map { td =>
      Row(
        td.tenantId,
        s"database '${td.name}'",
        td.initSql,
        "a superuser re-saves it unchanged (database/update with the same initSql)"
      )
    }
    val ps = pools.filter(p => !p.initSqlTrusted && p.initSql.trim.nonEmpty).map { p =>
      Row(
        p.tenantId,
        s"pool '${p.name}' of database '${dbNames.getOrElse(p.tenantDbId, p.tenantDbId)}'",
        p.initSql,
        "a superuser re-creates the pool with the same initSql (a pool's initSql has no update)"
      )
    }
    (dbs ++ ps).sortBy(r => (r.tenant, r.label))

  /** The WARN line of `row`. `refused` is what the manager's own check found: Some(positions and
    * reasons) when it ran, None when this manager has no qod-node to run it with.
    */
  def line(row: Row, refused: Option[List[String]]): String =
    val what = refused match
      case None =>
        "will be checked on the node: each node runs only the statements of the tenant initSql " +
          "allowlist and skips every other one, logging its position and the reason"
      case Some(reasons) =>
        s"loses ${reasons.size} statement(s) under the tenant initSql allowlist, which every node " +
          s"skips: ${reasons.mkString("; ")}"
    s"tenant '${row.tenant}' ${row.label}: its initSql was not written by a superuser and $what. " +
      s"If its author is to be trusted, ${row.fix}."

  /** The WARN line of `row` after `verdict`, None when the check admits every statement (nothing is
    * lost: the row runs as before, under the allowlist).
    */
  def lineAfter(row: Row, verdict: InitSqlVerdict): Option[String] = verdict match
    case InitSqlVerdict.Checked(_, Nil)     => None
    case InitSqlVerdict.Checked(n, refused) =>
      Some(line(row, Some(refused.map(r => s"statement ${r.statement} of $n: ${r.reason}"))))
    case InitSqlVerdict.Unchecked(_) => Some(line(row, None))

  /** Logs [[lineAfter]] for every untrusted row, each checked with `checker` (one qod-node run per
    * row on a manager that has one), one row after the other.
    */
  def report(
      tenantDbs: List[TenantDb],
      pools: List[Pool],
      checker: InitSqlChecker
  ): cats.effect.IO[Unit] =
    import cats.syntax.foldable.*
    untrusted(tenantDbs, pools).traverse_ { r =>
      checker.check(r.sql).map(v => lineAfter(r, v).foreach(l => logger.warn(l)))
    }
