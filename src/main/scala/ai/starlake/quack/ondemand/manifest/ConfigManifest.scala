// src/main/scala/ai/starlake/quack/ondemand/manifest/ConfigManifest.scala
package ai.starlake.quack.ondemand.manifest

import io.circe.Codec
import io.circe.derivation.{Configuration, ConfiguredCodec}

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
    initSql: String = "",
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
    cohorts: List[ManifestPoolCohort] = Nil,
    /** Operator-authored per-pool init SQL prepended to the resolved federation blob and shipped to
      * spawn-quack-node.sh via $extraSetupSql. PRAGMAs / SET / INSTALL / LOAD live here; ATTACH
      * aliases live on federated sources. Empty by default.
      */
    initSql: String = "",
    // Kubernetes node-pod sizing (request=limit) and full pod template, round-tripped verbatim.
    // Non-secret operator config; empty by default. Kubernetes-only, ignored by the local backend.
    cpu: String = "",
    memory: String = "",
    podTemplateYaml: String = ""
)

final case class ManifestTenant(
    // The tenant slug key (e.g. "acme").
    name: String,
    // Free-form display label; defaults to `name` on import when blank.
    displayName: String = "",
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

final case class ManifestRoleColumnPolicy(
    catalog: String = "*",
    schema: String,
    table: String,
    column: String,
    action: String, // "deny" | "mask"
    transformSql: Option[String] = None
)

final case class ManifestRoleRowPolicy(
    catalog: String = "*",
    schema: String,
    table: String,
    predicateSql: String
)

final case class ManifestRole(
    tenant: String,
    name: String,
    description: Option[String] = None,
    permissions: List[ManifestTablePermission] = Nil,
    columnPolicies: List[ManifestRoleColumnPolicy] = Nil,
    rowPolicies: List[ManifestRoleRowPolicy] = Nil
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

  // Every manifest case class derives its Codec through circe's Scala 3
  // ConfiguredCodec with `useDefaults = true`, so a field omitted from the
  // YAML/JSON falls back to the case class's own default value instead of
  // failing to decode. This replaces ~150 lines of hand-rolled
  // `Codec.from(Decoder.instance(...), deriveEncoder[T])` bodies that each
  // re-implemented `c.getOrElse[T](name)(default)` per defaulted field --
  // circe's plain `deriveCodec` ignores Scala 3 default values and always
  // requires every field.
  given Configuration = Configuration.default.withDefaults

  given Codec[ExportedFrom]             = ConfiguredCodec.derived
  given Codec[ManifestRoleDistribution] = ConfiguredCodec.derived
  given Codec[ManifestTablePermission]  = ConfiguredCodec.derived
  given Codec[ManifestPoolGrant]        = ConfiguredCodec.derived
  given Codec[ManifestRoleColumnPolicy] = ConfiguredCodec.derived
  given Codec[ManifestRoleRowPolicy]    = ConfiguredCodec.derived
  given Codec[ManifestNodeToleration]   = ConfiguredCodec.derived
  given Codec[ManifestNodePlacement]    = ConfiguredCodec.derived
  given Codec[ManifestPoolCohort]       = ConfiguredCodec.derived
  given Codec[ManifestFederatedSecret]  = ConfiguredCodec.derived
  given Codec[ManifestFederatedSource]  = ConfiguredCodec.derived
  given Codec[ManifestTenantDb]         = ConfiguredCodec.derived
  given Codec[ManifestPool]             = ConfiguredCodec.derived
  given Codec[ManifestTenant]           = ConfiguredCodec.derived
  given Codec[ManifestRole]             = ConfiguredCodec.derived
  given Codec[ManifestGroup]            = ConfiguredCodec.derived
  given Codec[ManifestUser]             = ConfiguredCodec.derived
  given Codec[ConfigManifest]           = ConfiguredCodec.derived
