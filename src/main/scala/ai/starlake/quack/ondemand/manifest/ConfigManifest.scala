// src/main/scala/ai/starlake/quack/ondemand/manifest/ConfigManifest.scala
package ai.starlake.quack.ondemand.manifest

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

import java.time.Instant

final case class ExportedFrom(managerVersion: String, hostname: String)

final case class ManifestRoleDistribution(writeonly: Int, readonly: Int, dual: Int)

final case class ManifestTenantDb(
    name:        String,
    metastore:   Map[String, String] = Map.empty,
    objectStore: Map[String, String] = Map.empty
)

final case class ManifestPool(
    name:                  String,
    tenantDb:              String,
    roleDistribution:      ManifestRoleDistribution,
    maxConcurrentPerNode:  Int     = 0,
    disabled:              Boolean = false
)

final case class ManifestIdentity(
    username:     String,
    externalId:   Option[String] = None,
    attributes:   Map[String, String] = Map.empty
)

final case class ManifestTenant(
    name:         String,
    disabled:     Boolean             = false,
    authProvider: String              = "db",
    authConfig:   Map[String, String] = Map.empty,
    tenantDbs:    List[ManifestTenantDb] = Nil,
    pools:        List[ManifestPool]     = Nil,
    identities:   List[ManifestIdentity] = Nil
)

final case class ManifestTablePermission(
    catalog: String, schema: String, table: String, verb: String
)

final case class ManifestRole(
    tenant:      String,
    name:        String,
    description: Option[String] = None,
    permissions: List[ManifestTablePermission] = Nil
)

final case class ManifestGroup(
    tenant:      String,
    name:        String,
    description: Option[String] = None,
    roles:       List[String]   = Nil
)

final case class ManifestPoolGrant(pool: Option[String])

final case class ManifestUser(
    tenant:     Option[String],          // None = superuser
    username:   String,
    password:   Option[String] = None,   // omitted on export; bcrypt-or-plaintext on import
    role:       String         = "user",
    enabled:    Boolean        = true,
    roles:      List[String]   = Nil,
    groups:     List[String]   = Nil,
    poolGrants: List[ManifestPoolGrant] = Nil
)

final case class ConfigManifest(
    apiVersion:   String,
    kind:         String,
    exportedAt:   Instant,
    exportedFrom: ExportedFrom,
    tenants:      List[ManifestTenant] = Nil,
    roles:        List[ManifestRole]   = Nil,
    groups:       List[ManifestGroup]  = Nil,
    users:        List[ManifestUser]   = Nil
)

object ConfigManifest:
  val ApiVersion: String = "quack-on-demand/v1"
  val Kind:       String = "ConfigManifest"

  given Codec[ExportedFrom]             = deriveCodec
  given Codec[ManifestRoleDistribution] = deriveCodec
  given Codec[ManifestTenantDb]         = deriveCodec
  given Codec[ManifestPool]             = deriveCodec
  given Codec[ManifestIdentity]         = deriveCodec
  given Codec[ManifestTenant]           = deriveCodec
  given Codec[ManifestTablePermission]  = deriveCodec
  given Codec[ManifestRole]             = deriveCodec
  given Codec[ManifestGroup]            = deriveCodec
  given Codec[ManifestPoolGrant]        = deriveCodec
  given Codec[ManifestUser]             = deriveCodec
  given Codec[ConfigManifest]           = deriveCodec