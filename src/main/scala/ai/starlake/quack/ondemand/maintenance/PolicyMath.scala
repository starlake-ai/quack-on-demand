package ai.starlake.quack.ondemand.maintenance

import ai.starlake.quack.model.{EffectivePolicy, MaintenancePolicy}

/** Pure policy resolution (spec section 3): field-wise merge, table > schema > tenantdb > built-in
  * defaults. Rows for other scopes are ignored by the selectors below.
  */
object PolicyMath:

  def effective(
      rows: List[MaintenancePolicy],
      schema: Option[String],
      table: Option[String]
  ): EffectivePolicy =
    val db = rows.find(_.scopeKind == "tenantdb")
    val sc =
      schema.flatMap(s => rows.find(r => r.scopeKind == "schema" && r.scopeSchema.contains(s)))
    val tbl = (schema, table) match
      case (Some(s), Some(t)) =>
        rows.find(r =>
          r.scopeKind == "table" && r.scopeSchema.contains(s) && r.scopeTable.contains(t)
        )
      case _ => None
    // precedence order, most specific first
    val chain                                                     = List(tbl, sc, db).flatten
    def pick[A](f: MaintenancePolicy => Option[A], default: A): A =
      chain.flatMap(r => f(r)).headOption.getOrElse(default)
    val d = EffectivePolicy.defaults
    EffectivePolicy(
      enabled = pick(_.enabled, d.enabled),
      retentionDays = pick(_.retentionDays, d.retentionDays),
      compactionEnabled = pick(_.compactionEnabled, d.compactionEnabled),
      targetFileSize = pick(_.targetFileSize, d.targetFileSize),
      smallFileMinCount = pick(_.smallFileMinCount, d.smallFileMinCount),
      rewriteDeleteThreshold = pick(_.rewriteDeleteThreshold, d.rewriteDeleteThreshold),
      cleanupGraceDays = pick(_.cleanupGraceDays, d.cleanupGraceDays),
      orphanMinAgeDays = pick(_.orphanMinAgeDays, d.orphanMinAgeDays),
      cron = pick(_.cron, d.cron)
    )

  /** Deterministic per-lake cadence stagger, 0..59 minutes (spec section 3). */
  def staggerMinutes(tenant: String, tenantDb: String): Int =
    math.abs(s"$tenant/$tenantDb".hashCode % 60)
