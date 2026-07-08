package ai.starlake.quack.model

import java.time.Instant

/** A named handle on a DuckLake snapshot, scoped to one (tenant, tenantDb). `isProtected` pins the
  * snapshot and every file it references against future expiry (EPIC P2): the maintenance service
  * must consult PinnedSetResolver before reaping anything. Wire JSON calls this field "protected";
  * the Scala name avoids the keyword.
  */
final case class SnapshotTag(
    id: String,
    tenant: String,
    tenantDb: String,
    name: String,
    snapshotId: Long,
    isProtected: Boolean = false,
    createdBy: Option[String] = None,
    createdAt: Option[Instant] = None
)
