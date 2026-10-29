package ai.starlake.quack.ondemand.initsql

import ai.starlake.quack.model.{FederatedSource, FederatedSourceType, Pool, TenantDb}
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
        "a superuser re-saves it unchanged (database/update, qod database update --init-sql, " +
          "with the same initSql)"
      )
    }
    val ps = pools.filter(p => !p.initSqlTrusted && p.initSql.trim.nonEmpty).map { p =>
      Row(
        p.tenantId,
        s"pool '${p.name}' of database '${dbNames.getOrElse(p.tenantDbId, p.tenantDbId)}'",
        p.initSql,
        "a superuser re-saves it unchanged (pool/setInitSql, qod pool set-init-sql, with the " +
          "same initSql)"
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

  /** The WARN line of every `sql` federated source with no known author (CONTRACT Addendum 4 item
    * 26: only superusers write one now; a row stored before has no recorded author). Such a source
    * keeps running its setupSql unrestricted on every node. `sources` pairs each with its database.
    */
  def federationLines(sources: List[(TenantDb, FederatedSource)]): List[String] =
    sources.collect {
      case (td, s) if s.sourceType == FederatedSourceType.Sql && !s.setupSqlTrusted =>
        s"tenant '${td.tenantId}' database '${td.name}' federated source '${s.alias}' (sourceType " +
          "sql): no superuser is known to have written it, and every node of the database runs " +
          "its setupSql unrestricted. Only superusers may create or change a sql source: review " +
          "it, then have a superuser re-save it unchanged (federation create with the same " +
          "body) or delete it."
    }.sorted

  /** The WARN line of every `sql` federated source whose stored template holds a secret placeholder
    * outside a single-quoted literal (CONTRACT Addendum 4 item 29, amended): a save of it is now
    * refused, and at render such a placeholder is substituted only for a plain-token value, else
    * the source is left out of the node's init. Names the placeholders, never a value or the SQL.
    */
  def unquotedSecretLines(sources: List[(TenantDb, FederatedSource)]): List[String] =
    sources
      .collect {
        case (td, s) if s.sourceType == FederatedSourceType.Sql =>
          ai.starlake.quack.ondemand.federation.SecretPlaceholderScan.unquoted(s.setupSql) match
            case Nil   => None
            case names =>
              Some(
                s"tenant '${td.tenantId}' database '${td.name}' federated source '${s.alias}' " +
                  s"(sourceType sql): ${names.map(n => s"{{secret.$n}}").mkString(", ")} sits " +
                  "outside a single-quoted literal. Nodes substitute such a placeholder only when " +
                  "its value is a plain token ([A-Za-z0-9_.-]+) and otherwise leave the source " +
                  "out; a save of the template is refused until each placeholder sits inside " +
                  "'...' (a superuser re-saves it, quoted)."
              )
      }
      .flatten
      .sorted

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
