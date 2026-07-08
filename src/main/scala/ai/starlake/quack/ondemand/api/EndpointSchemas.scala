package ai.starlake.quack.ondemand.api

import sttp.tapir._
import sttp.tapir.generic.auto._

/** Explicit tapir Schemas for response types whose generic auto-derivation is too heavy in Scala 3:
  * magnolia chokes on the bigger NodeInfo case class outright, and the placement DTO chain
  * (transitively reachable from PoolResponse and CreatePoolRequest) blows the inline budget when
  * `auto._` expands it twice. Materialized once here; endpoint modules that need them do
  * `import EndpointSchemas.given`.
  */
object EndpointSchemas:
  given Schema[NodeInfo]                 = Schema.derived
  given Schema[NodeTolerationDto]        = Schema.derived
  given Schema[NodePlacementDto]         = Schema.derived
  given Schema[PoolCohortDto]            = Schema.derived
  given Schema[PoolResponse]             = Schema.derived
  given Schema[PoolListResponse]         = Schema.derived
  given Schema[CreatePoolRequest]        = Schema.derived
  given Schema[SetPoolResourcesRequest]  = Schema.derived
  given Schema[SetPoolTemplateRequest]   = Schema.derived
  given Schema[ActiveStatementInfo]      = Schema.derived
  given Schema[ActiveStatementsResponse] = Schema.derived
  given Schema[KillStatementRequest]     = Schema.derived
  given Schema[KillStatementResponse]    = Schema.derived
  given Schema[AuditEventEntry]          = Schema.derived
  given Schema[AuditListResponse]        = Schema.derived
  given Schema[AuditActionsResponse]     = Schema.derived
  given Schema[TrendBucketEntry]         = Schema.derived
  given Schema[TrendsResponse]           = Schema.derived
  given Schema[StatementHistoryRowEntry] = Schema.derived
  given Schema[StatementSearchResponse]  = Schema.derived
  given Schema[UsageDayEntry]            = Schema.derived
  given Schema[UsageGroupEntry]          = Schema.derived
  given Schema[UsageResponse]            = Schema.derived
