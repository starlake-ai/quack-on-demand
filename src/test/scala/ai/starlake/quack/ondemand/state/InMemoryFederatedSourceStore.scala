package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.{FederatedSecret, FederatedSource}

import scala.collection.mutable

/** Process-local fixture for unit tests that exercise manifest federation
  * round-trips without standing up a Postgres. Extends [[FederatedSourceStore]]
  * and overrides every public method to delegate to in-memory maps so the JDBC
  * layer is never reached. The fake JDBC URL passed to the superclass constructor
  * is unused because all overriding methods bypass `withConn` entirely. */
final class InMemoryFederatedSourceStore
    extends FederatedSourceStore("jdbc:unused", "unused", "unused"):

  private val srcs: mutable.Map[String, FederatedSource] = mutable.LinkedHashMap.empty
  private val secs: mutable.Map[String, FederatedSecret] = mutable.LinkedHashMap.empty

  // ---------------- FederatedSource ----------------

  override def upsertSource(s: FederatedSource): Unit =
    srcs(s.id) = s

  override def deleteSource(id: String): Unit =
    srcs -= id
    secs.filterInPlace((_, sec) => sec.federatedSourceId != id)

  override def getSource(tenantDbId: String, alias: String): Option[FederatedSource] =
    srcs.values.find(s => s.tenantDbId == tenantDbId && s.alias == alias)

  override def listSources(tenantDbId: String): List[FederatedSource] =
    srcs.values.filter(_.tenantDbId == tenantDbId).toList.sortBy(_.alias)

  override def listEnabledSources(tenantDbId: String): List[FederatedSource] =
    listSources(tenantDbId).filterNot(_.disabled)

  // ---------------- FederatedSecret ----------------

  override def upsertSecret(s: FederatedSecret): Unit =
    secs(s.id) = s

  override def deleteSecret(sourceId: String, name: String): Unit =
    secs.filterInPlace((_, sec) => !(sec.federatedSourceId == sourceId && sec.name == name))

  override def getSecret(sourceId: String, name: String): Option[FederatedSecret] =
    secs.values.find(s => s.federatedSourceId == sourceId && s.name == name)

  override def listSecrets(sourceId: String): List[FederatedSecret] =
    secs.values.filter(_.federatedSourceId == sourceId).toList.sortBy(_.name)