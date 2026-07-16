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

  /** How a query treats rows whose `tenant` column is NULL (system-scope audit events). The
    * statement/rollup tables never carry NULL tenants, so their queries use [[NullTenants.Ignore]].
    */
  private enum NullTenants:
    case Ignore  // tenant is never null in this table: no null handling at all
    case Exclude // hide null-tenant rows (tenant admins must not see system events)
    case Include // null-tenant rows visible (superuser), or selected exactly (noTenant=true)

  /** WHERE fragment + binder for the tenant-scope filter, shared by every query method so the
    * scoping SQL cannot drift between telemetry surfaces:
    *   - `Some(empty)` matches nothing (or exactly the null-tenant rows under `Include`),
    *   - `Some(ts)` pins to `tenant IN (...)` (OR IS NULL under `Include`),
    *   - `None` leaves the query unrestricted (or excludes null rows under `Exclude`).
    */
  private def tenantFragment(
      tenants: Option[Set[String]],
      nulls: NullTenants = NullTenants.Ignore
  ): Option[(String, (PreparedStatement, Int) => Int)] =
    tenants match
      case Some(ts) if ts.isEmpty =>
        val frag = if nulls == NullTenants.Include then "tenant IS NULL" else "FALSE"
        Some((frag, (_, i) => i))
      case Some(ts) =>
        val tenantList   = ts.toList
        val placeholders = tenantList.map(_ => "?").mkString(", ")
        val frag         =
          if nulls == NullTenants.Include then s"(tenant IN ($placeholders) OR tenant IS NULL)"
          else s"tenant IN ($placeholders)"
        Some(
          (
            frag,
            (ps, i) => {
              tenantList.zipWithIndex.foreach { case (t, o) => ps.setString(i + o, t) }
              i + tenantList.size
            }
          )
        )
      case None =>
        if nulls == NullTenants.Exclude then Some(("tenant IS NOT NULL", (_, i) => i))
        else None

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
      val nulls = if q.includeNullTenant then NullTenants.Include else NullTenants.Exclude
      tenantFragment(q.tenants, nulls).foreach(parts += _)

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

  // --- Statement history -------------------------------------------------- //

  override def appendStatements(events: List[StatementEvent]): Unit =
    if events.nonEmpty then
      withConn { c =>
        val ps = c.prepareStatement(
          "INSERT INTO qodstate_stmt_history" +
            " (ts, username, tenant, pool, node_id, sql, status, duration_ms, prepare_ms, error)" +
            " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        )
        try
          events.foreach { e =>
            ps.setTimestamp(1, Timestamp.from(e.ts))
            ps.setString(2, e.username)
            ps.setString(3, e.tenant)
            ps.setString(4, e.pool)
            ps.setString(5, e.nodeId)
            ps.setString(6, e.sql)
            ps.setString(7, e.status)
            ps.setLong(8, e.durationMs)
            ps.setObject(
              9,
              e.prepareMs.map(java.lang.Long.valueOf).orNull,
              java.sql.Types.BIGINT
            )
            ps.setString(10, e.error.orNull)
            ps.addBatch()
          }
          ps.executeBatch()
        finally ps.close()
      }

  override def searchStatements(q: StatementQuery): List[StatementRow] =
    withConn { c =>
      // Each part: (sql_fragment, setter(ps, startIdx) => nextIdx).
      // Parts are collected in order, then the SQL is assembled and parameters bound
      // sequentially with a running counter.
      val parts = List.newBuilder[(String, (PreparedStatement, Int) => Int)]

      tenantFragment(q.tenants).foreach(parts += _)

      q.pool.foreach { v =>
        parts += (("pool = ?", (ps, i) => { ps.setString(i, v); i + 1 }))
      }
      q.user.foreach { v =>
        parts += (("username = ?", (ps, i) => { ps.setString(i, v); i + 1 }))
      }
      q.status.foreach { v =>
        parts += (("status = ?", (ps, i) => { ps.setString(i, v); i + 1 }))
      }
      q.q.foreach { v =>
        val pat = s"%$v%"
        parts += (("sql LIKE ?", (ps, i) => { ps.setString(i, pat); i + 1 }))
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

      val builtParts = parts.result()
      val whereSql   =
        if builtParts.isEmpty then ""
        else builtParts.map(_._1).mkString(" WHERE ", " AND ", "")
      val limitVal = math.max(1, math.min(q.limit, 500))
      val sql      =
        "SELECT id, ts, username, tenant, pool, node_id, sql," +
          " status, duration_ms, prepare_ms, error" +
          s" FROM qodstate_stmt_history$whereSql ORDER BY id DESC LIMIT $limitVal"
      val ps = c.prepareStatement(sql)
      try
        builtParts.foldLeft(1) { case (idx, (_, setter)) => setter(ps, idx) }
        val rs  = ps.executeQuery()
        val out = ListBuffer.empty[StatementRow]
        while rs.next() do
          val prepMs = {
            val v = rs.getLong("prepare_ms")
            if rs.wasNull() then None else Some(v)
          }
          out += StatementRow(
            rs.getLong("id"),
            StatementEvent(
              rs.getTimestamp("ts").toInstant,
              rs.getString("username"),
              rs.getString("tenant"),
              rs.getString("pool"),
              rs.getString("node_id"),
              rs.getString("sql"),
              rs.getLong("duration_ms"),
              prepMs,
              rs.getString("status"),
              Option(rs.getString("error"))
            )
          )
        out.toList
      finally ps.close()
    }

  override def purgeStatements(olderThan: Instant): Int =
    withConn { c =>
      val ps = c.prepareStatement("DELETE FROM qodstate_stmt_history WHERE ts < ?")
      try
        ps.setTimestamp(1, Timestamp.from(olderThan))
        ps.executeUpdate()
      finally ps.close()
    }

  // --- Rollup watermark ----------------------------------------------------- //

  override def rollupWatermark(): Option[Instant] =
    withConn { c =>
      val ps = c.prepareStatement(
        "SELECT last_rolled_ts FROM qodstate_rollup_watermark WHERE id = 1"
      )
      try
        val rs = ps.executeQuery()
        if rs.next() then Some(rs.getTimestamp(1).toInstant) else None
      finally ps.close()
    }

  override def advanceRollupWatermark(to: Instant): Unit =
    withConn { c =>
      val ps = c.prepareStatement(
        "INSERT INTO qodstate_rollup_watermark (id, last_rolled_ts) VALUES (1, ?)" +
          " ON CONFLICT (id) DO UPDATE SET last_rolled_ts = EXCLUDED.last_rolled_ts"
      )
      try
        ps.setTimestamp(1, Timestamp.from(to))
        ps.executeUpdate()
      finally ps.close()
    }

  // --- Rollup recompute ----------------------------------------------------- //

  /** Fetch the minimum ts in qodstate_stmt_history, or None when the table is empty. Used as the
    * `oldestRaw` by-name argument to [[RollupMath.hourWindow]] when no watermark is present.
    */
  private def fetchOldestRaw(): Option[Instant] =
    withConn { c =>
      val ps = c.prepareStatement("SELECT min(ts) FROM qodstate_stmt_history")
      try
        val rs = ps.executeQuery()
        if rs.next() then
          val ts = rs.getTimestamp(1)
          if rs.wasNull() then None else Some(ts.toInstant)
        else None
      finally ps.close()
    }

  override def recomputeRollups(fromExclusive: Option[Instant], toInclusive: Instant): Unit =
    RollupMath.hourWindow(fromExclusive, toInclusive, fetchOldestRaw()) match
      case None           => ()
      case Some((lo, hi)) =>
        val (dlo, dhi) = RollupMath.dayWindow((lo, hi))
        withConn { c =>
          c.setAutoCommit(false)
          try
            // -- Delete + insert hourly buckets -------------------------------- //
            val delHour = c.prepareStatement(
              "DELETE FROM qodstate_stmt_rollup" +
                " WHERE granularity = 'hour' AND bucket_start >= ? AND bucket_start < ?"
            )
            try
              delHour.setTimestamp(1, Timestamp.from(lo))
              delHour.setTimestamp(2, Timestamp.from(hi))
              delHour.executeUpdate()
            finally delHour.close()

            val insHour = c.prepareStatement(
              "INSERT INTO qodstate_stmt_rollup" +
                " (bucket_start, granularity, tenant, pool, username," +
                "  stmt_count, error_count, denied_count, engine_ms_sum," +
                "  p50_ms, p95_ms, p99_ms)" +
                // date_trunc on a timestamptz truncates in the session timezone; the
                // AT TIME ZONE 'UTC' round-trip pins truncation to UTC boundaries so
                // bucket_start matches RollupMath.hourFloor regardless of server TZ.
                " SELECT date_trunc('hour', ts AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'," +
                "   'hour', tenant, pool, ''," +
                "   count(*)," +
                "   count(*) FILTER (WHERE status NOT IN ('ok', 'denied'))," +
                "   count(*) FILTER (WHERE status = 'denied')," +
                "   COALESCE(sum(duration_ms), 0)," +
                "   percentile_cont(0.5)  WITHIN GROUP (ORDER BY duration_ms)," +
                "   percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms)," +
                "   percentile_cont(0.99) WITHIN GROUP (ORDER BY duration_ms)" +
                " FROM qodstate_stmt_history" +
                " WHERE ts >= ? AND ts < ?" +
                " GROUP BY 1, tenant, pool"
            )
            try
              insHour.setTimestamp(1, Timestamp.from(lo))
              insHour.setTimestamp(2, Timestamp.from(hi))
              insHour.executeUpdate()
            finally insHour.close()

            // -- Delete + insert daily buckets --------------------------------- //
            val delDay = c.prepareStatement(
              "DELETE FROM qodstate_stmt_rollup" +
                " WHERE granularity = 'day' AND bucket_start >= ? AND bucket_start < ?"
            )
            try
              delDay.setTimestamp(1, Timestamp.from(dlo))
              delDay.setTimestamp(2, Timestamp.from(dhi))
              delDay.executeUpdate()
            finally delDay.close()

            val insDay = c.prepareStatement(
              "INSERT INTO qodstate_stmt_rollup" +
                " (bucket_start, granularity, tenant, pool, username," +
                "  stmt_count, error_count, denied_count, engine_ms_sum," +
                "  p50_ms, p95_ms, p99_ms)" +
                " SELECT date_trunc('day', ts AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'," +
                "   'day', tenant, pool, username," +
                "   count(*)," +
                "   count(*) FILTER (WHERE status NOT IN ('ok', 'denied'))," +
                "   count(*) FILTER (WHERE status = 'denied')," +
                "   COALESCE(sum(duration_ms), 0)," +
                "   NULL, NULL, NULL" +
                " FROM qodstate_stmt_history" +
                " WHERE ts >= ? AND ts < ?" +
                " GROUP BY 1, tenant, pool, username"
            )
            try
              insDay.setTimestamp(1, Timestamp.from(dlo))
              insDay.setTimestamp(2, Timestamp.from(dhi))
              insDay.executeUpdate()
            finally insDay.close()

            c.commit()
          catch
            case t: Throwable =>
              try c.rollback()
              catch case _: Throwable => ()
              throw t
          finally c.setAutoCommit(true)
        }

  // --- Rollup query --------------------------------------------------------- //

  override def queryRollups(q: RollupQuery): List[RollupBucket] =
    withConn { c =>
      val parts = List.newBuilder[(String, (PreparedStatement, Int) => Int)]

      parts += (("granularity = ?", (ps, i) => { ps.setString(i, q.granularity); i + 1 }))

      tenantFragment(q.tenants).foreach(parts += _)

      q.pool.foreach { v =>
        parts += (("pool = ?", (ps, i) => { ps.setString(i, v); i + 1 }))
      }
      q.from.foreach { v =>
        parts += ((
          "bucket_start >= ?",
          (ps, i) => { ps.setTimestamp(i, Timestamp.from(v)); i + 1 }
        ))
      }
      q.to.foreach { v =>
        parts += ((
          "bucket_start < ?",
          (ps, i) => { ps.setTimestamp(i, Timestamp.from(v)); i + 1 }
        ))
      }

      val builtParts = parts.result()
      val whereSql   = builtParts.map(_._1).mkString(" WHERE ", " AND ", "")
      val sql        =
        "SELECT bucket_start, granularity, tenant, pool, username," +
          " stmt_count, error_count, denied_count, engine_ms_sum, p50_ms, p95_ms, p99_ms" +
          s" FROM qodstate_stmt_rollup$whereSql" +
          " ORDER BY bucket_start ASC, tenant ASC, pool ASC, username ASC"
      val ps = c.prepareStatement(sql)
      try
        builtParts.foldLeft(1) { case (idx, (_, setter)) => setter(ps, idx) }
        val rs  = ps.executeQuery()
        val out = ListBuffer.empty[RollupBucket]
        while rs.next() do
          def optDouble(col: String): Option[Double] =
            val v = rs.getDouble(col)
            if rs.wasNull() then None else Some(v)
          out += RollupBucket(
            bucketStart = rs.getTimestamp("bucket_start").toInstant,
            granularity = rs.getString("granularity"),
            tenant = rs.getString("tenant"),
            pool = rs.getString("pool"),
            username = rs.getString("username"),
            stmtCount = rs.getLong("stmt_count"),
            errorCount = rs.getLong("error_count"),
            deniedCount = rs.getLong("denied_count"),
            engineMsSum = rs.getLong("engine_ms_sum"),
            p50Ms = optDouble("p50_ms"),
            p95Ms = optDouble("p95_ms"),
            p99Ms = optDouble("p99_ms")
          )
        out.toList
      finally ps.close()
    }

  // --- Rollup purge --------------------------------------------------------- //

  override def purgeRollups(granularity: String, olderThan: Instant): Int =
    withConn { c =>
      val ps = c.prepareStatement(
        "DELETE FROM qodstate_stmt_rollup WHERE granularity = ? AND bucket_start < ?"
      )
      try
        ps.setString(1, granularity)
        ps.setTimestamp(2, Timestamp.from(olderThan))
        ps.executeUpdate()
      finally ps.close()
    }

  override def queryUsage(q: UsageQuery): UsageResult =
    require(
      q.groupBy == "tenant" || q.groupBy == "pool" || q.groupBy == "user",
      s"unknown groupBy: ${q.groupBy}"
    )
    withConn { c =>
      val keyCols = q.groupBy match
        case "pool" => "tenant, pool"
        case "user" => "tenant, username"
        case _      => "tenant"
      val keyCount = if q.groupBy == "tenant" then 1 else 2

      // Tenant-scope fragment is shared by the aggregate query and the dataStart query.
      val tenantPart = tenantFragment(q.tenants)

      val parts = List.newBuilder[(String, (PreparedStatement, Int) => Int)]
      parts += (("granularity = 'day'", (_, i) => i))
      tenantPart.foreach(parts += _)
      q.pool.foreach { v =>
        parts += (("pool = ?", (ps, i) => { ps.setString(i, v); i + 1 }))
      }
      parts += ((
        "bucket_start >= ?",
        (ps, i) => { ps.setTimestamp(i, Timestamp.from(q.from)); i + 1 }
      ))
      parts += ((
        "bucket_start < ?",
        (ps, i) => { ps.setTimestamp(i, Timestamp.from(q.to)); i + 1 }
      ))

      val builtParts = parts.result()
      val whereSql   = builtParts.map(_._1).mkString(" WHERE ", " AND ", "")
      val sql        =
        s"SELECT $keyCols, bucket_start," +
          " sum(stmt_count), sum(error_count), sum(denied_count), sum(engine_ms_sum)" +
          s" FROM qodstate_stmt_rollup$whereSql" +
          s" GROUP BY $keyCols, bucket_start" +
          s" ORDER BY $keyCols, bucket_start"
      val ps = c.prepareStatement(sql)
      try
        builtParts.foldLeft(1) { case (i, (_, bind)) => bind(ps, i) }
        val rs   = ps.executeQuery()
        val rows = ListBuffer.empty[UsageMath.DayRow]
        try
          while rs.next() do
            val tenant = rs.getString(1)
            val second = if keyCount == 2 then rs.getString(2) else ""
            rows += UsageMath.DayRow(
              tenant = tenant,
              pool = if q.groupBy == "pool" then second else "",
              username = if q.groupBy == "user" then second else "",
              day = rs.getTimestamp(keyCount + 1).toInstant,
              statements = rs.getLong(keyCount + 2),
              errors = rs.getLong(keyCount + 3),
              denied = rs.getLong(keyCount + 4),
              engineMs = rs.getLong(keyCount + 5)
            )
        finally rs.close()

        // dataStart: oldest daily bucket in the same tenant scope (period and pool ignored;
        // it marks the retention edge, not the current filter).
        val dsParts = List(("granularity = 'day'", (_: PreparedStatement, i: Int) => i)) ++
          tenantPart.toList
        val dsWhere = dsParts.map(_._1).mkString(" WHERE ", " AND ", "")
        val dsPs    =
          c.prepareStatement(s"SELECT min(bucket_start) FROM qodstate_stmt_rollup$dsWhere")
        val dataStart =
          try
            dsParts.foldLeft(1) { case (i, (_, bind)) => bind(dsPs, i) }
            val dsRs = dsPs.executeQuery()
            try
              if dsRs.next() then Option(dsRs.getTimestamp(1)).map(_.toInstant)
              else None
            finally dsRs.close()
          finally dsPs.close()

        UsageResult(UsageMath.fold(q.groupBy, rows.toList), dataStart)
      finally ps.close()
    }

  override def close(): Unit = if !dataSource.isClosed then dataSource.close()
