package ai.starlake.quack.model

import java.time.Instant

/** One ATTACH-able external catalog under a tenant-db. The `alias` field
  * is the DuckDB catalog name; ACL grants reference it as the first
  * segment of a 3-part table ref. `setupSql` is user-typed and may
  * include INSTALL/LOAD/CREATE SECRET/ATTACH statements, with
  * `{{alias}}` and `{{secret.NAME}}` placeholders resolved at node
  * spawn. */
final case class FederatedSource(
    id:          String,
    tenantDbId:  String,
    alias:       String,
    setupSql:    String,
    description: Option[String]  = None,
    disabled:    Boolean         = false,
    createdAt:   Option[Instant] = None
)