package ai.starlake.quack.ondemand.telemetry

import com.typesafe.scalalogging.LazyLogging
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import io.circe.parser.decode
import io.circe.syntax.*

import java.sql.{Connection, PreparedStatement, Timestamp}
import java.time.Instant
import scala.collection.mutable.ListBuffer

/** Postgres-backed [[TelemetryStore]]. Assumes the schema produced by changelog `0018-audit.yaml`
  * is already in place (run [[LiquibaseRunner]] first).
  *
  * Connections come from a HikariCP pool (size [[poolSize]], default 5, minimum idle 1). Call
  * [[close]] on shutdown to release idle connections.
  */
final class PostgresTelemetryStore(
    jdbcUrl: String,
    user: String,
    password: String,
    poolSize: Int = 5
) extends TelemetryStore
    with LazyLogging:

  Class.forName("org.postgresql.Driver")

  private val dataSource: HikariDataSource =
    val hc = new HikariConfig()
    hc.setJdbcUrl(jdbcUrl)
    hc.setUsername(user)
    hc.setPassword(password)
    hc.setMaximumPoolSize(poolSize)
    hc.setMinimumIdle(1)
    hc.setConnectionTimeout(5000)
    hc.setPoolName("qod-telemetry")
    new HikariDataSource(hc)

  private def withConn[A](f: Connection => A): A =
    val c = dataSource.getConnection
    try f(c)
    finally c.close()

  val enabled = true

  override def appendAudit(events: List[AuditEvent]): Unit =
    if events.nonEmpty then
      withConn { c =>
        val ps = c.prepareStatement(
          "INSERT INTO qodstate_audit" +
            " (ts, family, actor, actor_realm, tenant, action, target, outcome, origin, detail)" +
            " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)"
        )
        try
          events.foreach { e =>
            ps.setTimestamp(1, Timestamp.from(e.ts))
            ps.setString(2, e.family)
            ps.setString(3, e.actor)
            ps.setString(4, e.actorRealm)
            ps.setString(5, e.tenant.orNull)
            ps.setString(6, e.action)
            ps.setString(7, e.target.orNull)
            ps.setString(8, e.outcome)
            ps.setString(9, e.origin)
            ps.setString(10, e.detail.asJson.noSpaces)
            ps.addBatch()
          }
          ps.executeBatch()
        finally ps.close()
      }

  override def listAudit(q: AuditQuery): List[AuditRow] =
    withConn { c =>
      // Each part: (sql_fragment, setter(ps, startIdx) => nextIdx).
      // Parts are collected in order, then the SQL is assembled and parameters bound
      // sequentially with a running counter.
      val parts = List.newBuilder[(String, (PreparedStatement, Int) => Int)]

      q.family.foreach { v =>
        parts += (("family = ?", (ps, i) => { ps.setString(i, v); i + 1 }))
      }
      q.actor.foreach { v =>
        parts += (("actor = ?", (ps, i) => { ps.setString(i, v); i + 1 }))
      }
      q.action.foreach { v =>
        parts += (("action = ?", (ps, i) => { ps.setString(i, v); i + 1 }))
      }
      q.q.foreach { v =>
        val pat = s"%$v%"
        parts += ((
          "(action LIKE ? OR target LIKE ?)",
          (ps, i) => {
            ps.setString(i, pat); ps.setString(i + 1, pat); i + 2
          }
        ))
      }
      q.from.foreach { v =>
        parts += (("ts >= ?", (ps, i) => { ps.setTimestamp(i, Timestamp.from(v)); i + 1 }))
      }
      q.to.foreach { v =>
        parts += (("ts < ?", (ps, i) => { ps.setTimestamp(i, Timestamp.from(v)); i + 1 }))
      }
      q.beforeId.foreach { v =>
        parts += (("id < ?", (ps, i) => { ps.setLong(i, v); i + 1 }))
      }
      q.tenants match
        case Some(ts) if ts.isEmpty =>
          val frag = if q.includeNullTenant then "tenant IS NULL" else "FALSE"
          parts += ((frag, (_, i) => i))
        case Some(ts) =>
          val tenantList   = ts.toList
          val placeholders = tenantList.map(_ => "?").mkString(", ")
          val frag         =
            if q.includeNullTenant then s"(tenant IN ($placeholders) OR tenant IS NULL)"
            else s"tenant IN ($placeholders)"
          parts += ((
            frag,
            (ps, i) => {
              tenantList.zipWithIndex.foreach { case (t, o) => ps.setString(i + o, t) }
              i + tenantList.size
            }
          ))
        case None =>
          if !q.includeNullTenant then parts += (("tenant IS NOT NULL", (_, i) => i))

      val builtParts = parts.result()
      val whereSql   =
        if builtParts.isEmpty then ""
        else builtParts.map(_._1).mkString(" WHERE ", " AND ", "")
      val limitVal = math.max(1, math.min(q.limit, 500))
      val sql      =
        "SELECT id, ts, family, actor, actor_realm, tenant, action, target, outcome, origin, detail::text" +
          s" FROM qodstate_audit$whereSql ORDER BY id DESC LIMIT $limitVal"
      val ps = c.prepareStatement(sql)
      try
        builtParts.foldLeft(1) { case (idx, (_, setter)) => setter(ps, idx) }
        val rs  = ps.executeQuery()
        val out = ListBuffer.empty[AuditRow]
        while rs.next() do
          out += AuditRow(
            rs.getLong("id"),
            AuditEvent(
              rs.getTimestamp("ts").toInstant,
              rs.getString("family"),
              rs.getString("actor"),
              rs.getString("actor_realm"),
              Option(rs.getString("tenant")),
              rs.getString("action"),
              Option(rs.getString("target")),
              rs.getString("outcome"),
              rs.getString("origin"),
              decode[Map[String, String]](rs.getString("detail")).getOrElse(Map.empty)
            )
          )
        out.toList
      finally ps.close()
    }

  override def purgeAudit(olderThan: Instant): Int =
    withConn { c =>
      val ps = c.prepareStatement("DELETE FROM qodstate_audit WHERE ts < ?")
      try
        ps.setTimestamp(1, Timestamp.from(olderThan))
        ps.executeUpdate()
      finally ps.close()
    }

  // --- Statement history stubs (implemented in Task 2) -------------------- //

  override def appendStatements(events: List[StatementEvent]): Unit =
    throw new UnsupportedOperationException("implemented in a later task")

  override def searchStatements(q: StatementQuery): List[StatementRow] =
    throw new UnsupportedOperationException("implemented in a later task")

  override def purgeStatements(olderThan: Instant): Int =
    throw new UnsupportedOperationException("implemented in a later task")

  // --- Rollup stubs (implemented in Task 3) -------------------------------- //

  override def rollupWatermark(): Option[Instant] =
    throw new UnsupportedOperationException("implemented in a later task")

  override def recomputeRollups(fromExclusive: Option[Instant], toInclusive: Instant): Unit =
    throw new UnsupportedOperationException("implemented in a later task")

  override def advanceRollupWatermark(to: Instant): Unit =
    throw new UnsupportedOperationException("implemented in a later task")

  override def queryRollups(q: RollupQuery): List[RollupBucket] =
    throw new UnsupportedOperationException("implemented in a later task")

  override def purgeRollups(granularity: String, olderThan: Instant): Int =
    throw new UnsupportedOperationException("implemented in a later task")

  override def close(): Unit = if !dataSource.isClosed then dataSource.close()
