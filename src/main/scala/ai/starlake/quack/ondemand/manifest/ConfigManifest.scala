// src/main/scala/ai/starlake/quack/ondemand/manifest/ConfigManifest.scala
package ai.starlake.quack.ondemand.manifest

import io.circe.{Codec, Decoder, Encoder, HCursor}
import io.circe.generic.semiauto.{deriveCodec, deriveDecoder, deriveEncoder}

import java.time.Instant

final case class ExportedFrom(managerVersion: String, hostname: String)

final case class ManifestRoleDistribution(writeonly: Int, readonly: Int, dual: Int)

final case class ManifestNodeToleration(
    key: String,
    operator: String = "Equal",
    value: Option[String] = None,
    effect: Option[String] = None
)

final case class ManifestNodePlacement(
    nodeSelector: Map[String, String] = Map.empty,
    tolerations: List[ManifestNodeToleration] = Nil
)

final case class ManifestPoolCohort(
    placement: ManifestNodePlacement = ManifestNodePlacement(),
    distribution: ManifestRoleDistribution
)

final case class ManifestFederatedSecret(
    name: String,
    value: Option[String] = None,
    externalRef: Option[String] = None
)

final case class ManifestFederatedSource(
    alias: String,
    setupSql: String,
    description: Option[String] = None,
    disabled: Boolean = false,
    secrets: List[ManifestFederatedSecret] = Nil
)

final case class ManifestTenantDb(
    name: String,
    kind: String = "ducklake",
    metastore: Map[String, String] = Map.empty,
    dataPath: String = "",
    objectStore: Map[String, String] = Map.empty,
    defaultDatabase: Option[String] = None,
    defaultSchema: Option[String] = None,
    federatedSources: List[ManifestFederatedSource] = Nil
)

final case class ManifestPool(
    name: String,
    tenantDb: String,
    roleDistribution: ManifestRoleDistribution,
    maxConcurrentPerNode: Int = 0,
    disabled: Boolean = false,
    // Optional placement plan. When empty, the importer creates the
    // pool with no cohorts (single placement-less group). The
    // supervisor ignores cohorts when the runtime backend can't
    // honor placement (e.g. local mode).
    cohorts: List[ManifestPoolCohort] = Nil
)

final case class ManifestTenant(
    name: String,
    disabled: Boolean = false,
    authProvider: String = "db",
    authConfig: Map[String, String] = Map.empty,
    tenantDbs: List[ManifestTenantDb] = Nil,
    pools: List[ManifestPool] = Nil
)

final case class ManifestTablePermission(
    catalog: String,
    schema: String,
    table: String,
    verb: String
)

final case class ManifestRole(
    tenant: String,
    name: String,
    description: Option[String] = None,
    permissions: List[ManifestTablePermission] = Nil
)

final case class ManifestGroup(
    tenant: String,
    name: String,
    description: Option[String] = None,
    roles: List[String] = Nil
)

final case class ManifestPoolGrant(pool: Option[String])

final case class ManifestUser(
    tenant: Option[String], // None = superuser
    username: String,
    password: Option[String] = None, // omitted on export; bcrypt-or-plaintext on import
    role: String = "user",
    enabled: Boolean = true,
    roles: List[String] = Nil,
    groups: List[String] = Nil,
    poolGrants: List[ManifestPoolGrant] = Nil
)

final case class ConfigManifest(
    apiVersion: String,
    kind: String,
    exportedAt: Instant,
    exportedFrom: ExportedFrom,
    tenants: List[ManifestTenant] = Nil,
    roles: List[ManifestRole] = Nil,
    groups: List[ManifestGroup] = Nil,
    users: List[ManifestUser] = Nil
)

object ConfigManifest:
  val ApiVersion: String = "quack-on-demand/v1"
  val Kind: String       = "ConfigManifest"

  // Case classes with no defaulted fields keep the derived codec.
  given Codec[ExportedFrom]             = deriveCodec
  given Codec[ManifestRoleDistribution] = deriveCodec
  given Codec[ManifestTablePermission]  = deriveCodec
  given Codec[ManifestPoolGrant]        = deriveCodec

  // Placement codecs: every field but `key` (tolerations) and
  // `distribution` (cohorts) is optional in YAML.
  given Codec[ManifestNodeToleration] = Codec.from(
    Decoder.instance { (c: HCursor) =>
      for
        key      <- c.get[String]("key")
        operator <- c.getOrElse[String]("operator")("Equal")
        value    <- c.getOrElse[Option[String]]("value")(None)
        effect   <- c.getOrElse[Option[String]]("effect")(None)
      yield ManifestNodeToleration(key, operator, value, effect)
    },
    deriveEncoder[ManifestNodeToleration]
  )
  given Codec[ManifestNodePlacement] = Codec.from(
    Decoder.instance { (c: HCursor) =>
      for
        nodeSelector <- c.getOrElse[Map[String, String]]("nodeSelector")(Map.empty)
        tolerations  <- c.getOrElse[List[ManifestNodeToleration]]("tolerations")(Nil)
      yield ManifestNodePlacement(nodeSelector, tolerations)
    },
    deriveEncoder[ManifestNodePlacement]
  )
  given Codec[ManifestPoolCohort] = Codec.from(
    Decoder.instance { (c: HCursor) =>
      for
        placement    <- c.getOrElse[ManifestNodePlacement]("placement")(ManifestNodePlacement())
        distribution <- c.get[ManifestRoleDistribution]("distribution")
      yield ManifestPoolCohort(placement, distribution)
    },
    deriveEncoder[ManifestPoolCohort]
  )

  // Case classes with default values get hand-rolled decoders that honor
  // those defaults when the field is omitted in YAML. circe's deriveCodec
  // generates strict decoders that always require every field, regardless
  // of Scala 3 defaults. The encoder side is still derived.
  //
  // Pattern: `Codec.from(handDecoder, deriveEncoder[T])`. The decoder uses
  // `c.getOrElse[T](name)(default)` for every field that the case class
  // declares a default for; required fields stay `c.get[T](name)`.

  given Codec[ManifestFederatedSecret] = Codec.from(
    Decoder.instance { (c: HCursor) =>
      for
        name        <- c.get[String]("name")
        value       <- c.getOrElse[Option[String]]("value")(None)
        externalRef <- c.getOrElse[Option[String]]("externalRef")(None)
      yield ManifestFederatedSecret(name, value, externalRef)
    },
    deriveEncoder[ManifestFederatedSecret]
  )

  given Codec[ManifestFederatedSource] = Codec.from(
    Decoder.instance { (c: HCursor) =>
      for
        alias       <- c.get[String]("alias")
        setupSql    <- c.get[String]("setupSql")
        description <- c.getOrElse[Option[String]]("description")(None)
        disabled    <- c.getOrElse[Boolean]("disabled")(false)
        secrets     <- c.getOrElse[List[ManifestFederatedSecret]]("secrets")(Nil)
      yield ManifestFederatedSource(alias, setupSql, description, disabled, secrets)
    },
    deriveEncoder[ManifestFederatedSource]
  )

  given Codec[ManifestTenantDb] = Codec.from(
    Decoder.instance { (c: HCursor) =>
      for
        name             <- c.get[String]("name")
        kind             <- c.getOrElse[String]("kind")("ducklake")
        metastore        <- c.getOrElse[Map[String, String]]("metastore")(Map.empty)
        dataPath         <- c.getOrElse[String]("dataPath")("")
        objectStore      <- c.getOrElse[Map[String, String]]("objectStore")(Map.empty)
        defaultDatabase  <- c.getOrElse[Option[String]]("defaultDatabase")(None)
        defaultSchema    <- c.getOrElse[Option[String]]("defaultSchema")(None)
        federatedSources <- c.getOrElse[List[ManifestFederatedSource]]("federatedSources")(Nil)
      yield ManifestTenantDb(
        name,
        kind,
        metastore,
        dataPath,
        objectStore,
        defaultDatabase,
        defaultSchema,
        federatedSources
      )
    },
    deriveEncoder[ManifestTenantDb]
  )

  given Codec[ManifestPool] = Codec.from(
    Decoder.instance { (c: HCursor) =>
      for
        name                 <- c.get[String]("name")
        tenantDb             <- c.get[String]("tenantDb")
        roleDistribution     <- c.get[ManifestRoleDistribution]("roleDistribution")
        maxConcurrentPerNode <- c.getOrElse[Int]("maxConcurrentPerNode")(0)
        disabled             <- c.getOrElse[Boolean]("disabled")(false)
        cohorts              <- c.getOrElse[List[ManifestPoolCohort]]("cohorts")(Nil)
      yield ManifestPool(name, tenantDb, roleDistribution, maxConcurrentPerNode, disabled, cohorts)
    },
    deriveEncoder[ManifestPool]
  )

  given Codec[ManifestTenant] = Codec.from(
    Decoder.instance { (c: HCursor) =>
      for
        name         <- c.get[String]("name")
        disabled     <- c.getOrElse[Boolean]("disabled")(false)
        authProvider <- c.getOrElse[String]("authProvider")("db")
        authConfig   <- c.getOrElse[Map[String, String]]("authConfig")(Map.empty)
        tenantDbs    <- c.getOrElse[List[ManifestTenantDb]]("tenantDbs")(Nil)
        pools        <- c.getOrElse[List[ManifestPool]]("pools")(Nil)
      yield ManifestTenant(name, disabled, authProvider, authConfig, tenantDbs, pools)
    },
    deriveEncoder[ManifestTenant]
  )

  given Codec[ManifestRole] = Codec.from(
    Decoder.instance { (c: HCursor) =>
      for
        tenant      <- c.get[String]("tenant")
        name        <- c.get[String]("name")
        description <- c.getOrElse[Option[String]]("description")(None)
        permissions <- c.getOrElse[List[ManifestTablePermission]]("permissions")(Nil)
      yield ManifestRole(tenant, name, description, permissions)
    },
    deriveEncoder[ManifestRole]
  )

  given Codec[ManifestGroup] = Codec.from(
    Decoder.instance { (c: HCursor) =>
      for
        tenant      <- c.get[String]("tenant")
        name        <- c.get[String]("name")
        description <- c.getOrElse[Option[String]]("description")(None)
        roles       <- c.getOrElse[List[String]]("roles")(Nil)
      yield ManifestGroup(tenant, name, description, roles)
    },
    deriveEncoder[ManifestGroup]
  )

  given Codec[ManifestUser] = Codec.from(
    Decoder.instance { (c: HCursor) =>
      for
        tenant     <- c.get[Option[String]]("tenant")
        username   <- c.get[String]("username")
        password   <- c.getOrElse[Option[String]]("password")(None)
        role       <- c.getOrElse[String]("role")("user")
        enabled    <- c.getOrElse[Boolean]("enabled")(true)
        roles      <- c.getOrElse[List[String]]("roles")(Nil)
        groups     <- c.getOrElse[List[String]]("groups")(Nil)
        poolGrants <- c.getOrElse[List[ManifestPoolGrant]]("poolGrants")(Nil)
      yield ManifestUser(tenant, username, password, role, enabled, roles, groups, poolGrants)
    },
    deriveEncoder[ManifestUser]
  )

  given Codec[ConfigManifest] = Codec.from(
    Decoder.instance { (c: HCursor) =>
      for
        apiVersion   <- c.get[String]("apiVersion")
        kind         <- c.get[String]("kind")
        exportedAt   <- c.get[Instant]("exportedAt")
        exportedFrom <- c.get[ExportedFrom]("exportedFrom")
        tenants      <- c.getOrElse[List[ManifestTenant]]("tenants")(Nil)
        roles        <- c.getOrElse[List[ManifestRole]]("roles")(Nil)
        groups       <- c.getOrElse[List[ManifestGroup]]("groups")(Nil)
        users        <- c.getOrElse[List[ManifestUser]]("users")(Nil)
      yield ConfigManifest(
        apiVersion,
        kind,
        exportedAt,
        exportedFrom,
        tenants,
        roles,
        groups,
        users
      )
    },
    deriveEncoder[ConfigManifest]
  )
