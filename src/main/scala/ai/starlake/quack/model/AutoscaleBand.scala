package ai.starlake.quack.model

/** Validation for the owner-declared demand scale-out band. Shared by the REST handlers and the
  * manifest importer so both surfaces refuse the same shapes.
  *
  * `minNodes == maxNodes` is legal and means "hold exactly this size, never scale": it is the
  * per-pool way to opt out of the sweep while keeping the band recorded.
  */
object AutoscaleBand:
  def validate(
      minNodes: Option[Int],
      maxNodes: Option[Int],
      dist: RoleDistribution,
      size: Int,
      hardCap: Int
  ): Option[String] =
    (minNodes, maxNodes) match
      case (None, None)         => None
      case (Some(mn), Some(mx)) =>
        val writeCapable = dist.writeonly + dist.dual
        if mn < 1 then Some("minNodes must be >= 1")
        else if mn > mx then Some("minNodes must be <= maxNodes")
        else if mx > hardCap then Some(s"maxNodes must not exceed autoscale.hardCap ($hardCap)")
        else if mn < writeCapable then
          Some(
            s"minNodes ($mn) must cover the write-capable nodes (writeonly + dual = $writeCapable)"
          )
        else if size < mn || size > mx then
          Some(s"size ($size) must lie inside [minNodes, maxNodes] = [$mn, $mx]")
        else None
      case _ => Some("minNodes and maxNodes must be set together")
