package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.{Pool, RunningNode, Tenant, TenantDb}

import scala.collection.concurrent.TrieMap

/** Process-local fixture for unit tests that exercise the supervisor or
  * handlers without standing up a Postgres. Tracks the same per-entity
  * shape as [[PostgresControlPlaneStore]] and enforces FK RESTRICT on
  * parent deletes so callers can verify ordered teardown. */
final class InMemoryControlPlaneStore extends ControlPlaneStore:

  private val tenants   = TrieMap.empty[String, Tenant]
  private val tenantDbs = TrieMap.empty[String, TenantDb]
  private val pools     = TrieMap.empty[String, Pool]
  private val nodes     = TrieMap.empty[String, RunningNode]

  def upsertTenant(t: Tenant): Unit = tenants.put(t.id, t)
  def listTenants(): List[Tenant]   = tenants.values.toList.sortBy(_.displayName)
  def deleteTenant(id: String): Unit =
    if tenantDbs.values.exists(_.tenantId == id) then
      throw new java.sql.SQLException(s"tenant $id has tenant-db children")
    tenants.remove(id)

  def upsertTenantDb(t: TenantDb): Unit = tenantDbs.put(t.id, t)
  def listTenantDbs(tenantId: String): List[TenantDb] =
    tenantDbs.values.filter(_.tenantId == tenantId).toList.sortBy(_.name)
  def deleteTenantDb(id: String): Unit =
    if pools.values.exists(_.tenantDbId == id) then
      throw new java.sql.SQLException(s"tenant-db $id has pool children")
    tenantDbs.remove(id)

  def upsertPool(p: Pool): Unit = pools.put(p.id, p)
  def listPools(tenantDbId: String): List[Pool] =
    pools.values.filter(_.tenantDbId == tenantDbId).toList.sortBy(_.name)
  def deletePool(id: String): Unit =
    if nodeIndex.values.exists(_ == id) then
      throw new java.sql.SQLException(s"pool $id has node children")
    pools.remove(id)

  // node_id -> pool_id index (matches the Postgres FK shape).
  private val nodeIndex = TrieMap.empty[String, String]

  def upsertNode(n: RunningNode, poolId: String): Unit =
    nodes.put(n.nodeId, n)
    nodeIndex.put(n.nodeId, poolId)
  def listNodes(poolId: String): List[RunningNode] =
    nodeIndex.collect { case (nid, pid) if pid == poolId => nodes(nid) }
      .toList.sortBy(_.nodeId)
  def deleteNode(nodeId: String): Unit =
    nodes.remove(nodeId)
    nodeIndex.remove(nodeId)

  def snapshot(): ControlPlaneSnapshot = ControlPlaneSnapshot(
    tenants   = listTenants(),
    tenantDbs = tenantDbs.values.toList.sortBy(_.name),
    pools     = pools.values.toList.sortBy(_.name),
    nodes     = nodes.values.toList.sortBy(_.nodeId)
  )
