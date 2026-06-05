// src/main/scala/ai/starlake/quack/ondemand/manifest/ManifestImporter.scala
package ai.starlake.quack.ondemand.manifest

import ai.starlake.quack.ondemand.state.ControlPlaneStore

object ManifestImporter:

  type ValidationResult = Either[List[String], Unit]

  def validate(m: ConfigManifest, store: ControlPlaneStore): ValidationResult =
    val errs = scala.collection.mutable.ListBuffer.empty[String]

    if m.apiVersion != ConfigManifest.ApiVersion then
      errs += s"apiVersion '${m.apiVersion}' is not supported (expected '${ConfigManifest.ApiVersion}')"

    // Tenants in YAML + tenants already in the DB form the set of valid tenants
    // a user/role/group may reference.
    val tenantsInYaml = m.tenants.map(_.name).toSet
    val tenantsInDb   = store.listTenants().map(_.displayName).toSet
    val knownTenants  = tenantsInYaml ++ tenantsInDb

    // Dup detection
    def dup[A, K](xs: List[A], key: A => K, label: String): Unit =
      xs.groupBy(key).collect { case (k, vs) if vs.size > 1 =>
        errs += s"duplicate $label: $k"
      }
    dup(m.tenants,        _.name,                       "tenant name")
    dup(m.roles,          r => (r.tenant, r.name),      "role (tenant, name)")
    dup(m.groups,         g => (g.tenant, g.name),      "group (tenant, name)")
    dup(m.users,          u => (u.tenant, u.username),  "user (tenant, username)")

    // Per-tenant nested duplicates
    m.tenants.foreach { t =>
      dup(t.tenantDbs, _.name, s"tenant-db within ${t.name}")
      dup(t.pools,     _.name, s"pool within ${t.name}")
    }

    // Cross-references
    m.users.foreach { u =>
      u.tenant.foreach { tn =>
        if !knownTenants.contains(tn) then
          errs += s"user '${u.username}': tenant '$tn' not in YAML or DB"
      }
      val rolesInTenant = m.roles.filter(_.tenant == u.tenant.getOrElse("")).map(_.name).toSet
      val groupsInTenant = m.groups.filter(_.tenant == u.tenant.getOrElse("")).map(_.name).toSet
      u.roles.foreach { r =>
        if !rolesInTenant.contains(r) then
          errs += s"user '${u.username}' references role '$r' not defined in tenant '${u.tenant.getOrElse("(superuser)")}'"
      }
      u.groups.foreach { g =>
        if !groupsInTenant.contains(g) then
          errs += s"user '${u.username}' references group '$g' not defined in tenant '${u.tenant.getOrElse("(superuser)")}'"
      }
    }

    m.roles.foreach { r =>
      if !knownTenants.contains(r.tenant) then
        errs += s"role '${r.name}': tenant '${r.tenant}' not in YAML or DB"
    }
    m.groups.foreach { g =>
      if !knownTenants.contains(g.tenant) then
        errs += s"group '${g.name}': tenant '${g.tenant}' not in YAML or DB"
      val rolesInTenant = m.roles.filter(_.tenant == g.tenant).map(_.name).toSet
      g.roles.foreach { rn =>
        if !rolesInTenant.contains(rn) then
          errs += s"group '${g.name}' references role '$rn' not defined in tenant '${g.tenant}'"
      }
    }

    // Pools reference their tenant-db by name (sibling check, no DB lookup needed)
    m.tenants.foreach { t =>
      val dbNames = t.tenantDbs.map(_.name).toSet
      t.pools.foreach { p =>
        if !dbNames.contains(p.tenantDb) then
          errs += s"tenant '${t.name}' pool '${p.name}': tenantDb '${p.tenantDb}' not declared under tenant"
      }
    }

    if errs.isEmpty then Right(()) else Left(errs.toList)