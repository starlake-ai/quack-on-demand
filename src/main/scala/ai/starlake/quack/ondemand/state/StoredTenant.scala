package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.Tenant

final case class StoredTenant(name: String, metastore: Map[String, String])

object StoredTenant:
  def fromDomain(t: Tenant): StoredTenant = StoredTenant(t.name, t.metastore)
  def toDomain(s: StoredTenant): Tenant   = Tenant(s.name, s.metastore)
