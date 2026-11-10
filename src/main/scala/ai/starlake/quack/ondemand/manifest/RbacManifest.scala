package ai.starlake.quack.ondemand.manifest

import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.manifest.ConfigManifest.given
import ai.starlake.quack.ondemand.state.ControlPlaneStore
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.{Codec, Json}

import java.time.Instant

/** The RBAC subset of a manifest for one tenant: what `mode=rbac` imports and exports. Its roles
  * and groups are the whole truth for that tenant's custom RBAC, except for groups an identity
  * provider provisions through SCIM, which stay outside it in both directions.
  */
final case class RbacManifest(
    roles: List[ManifestRole] = Nil,
    groups: List[ManifestGroup] = Nil
):

  /** This subset as a manifest for the importer. The header fields are never read by an import. */
  def toManifest(tenantId: String): ConfigManifest =
    ConfigManifest(
      apiVersion = ConfigManifest.ApiVersion,
      kind = ConfigManifest.Kind,
      exportedAt = Instant.EPOCH,
      exportedFrom = ExportedFrom("rbac", "rbac"),
      roles = roles.map(_.copy(tenant = tenantId)),
      groups = groups.map(_.copy(tenant = tenantId))
    )

object RbacManifest:
  private given Configuration     = Configuration.default.withDefaults
  given Codec[RbacManifest]       = ConfiguredCodec.derived
  private val SectionsOutsideRbac = List("tenants", "users")
  private val PoolGrantsField     = "poolGrants"

  /** A refused RBAC import or export: an HTTP 400 with this code. */
  final case class Refusal(code: String, message: String)

  /** Decodes a parsed file, refusing any section an RBAC import must not touch. */
  def parse(json: Json): Either[Refusal, RbacManifest] =
    SectionsOutsideRbac.find(section => holdsEntries(json, section)) match
      case Some(section) =>
        Left(Refusal("rbac_section_refused", s"an RBAC import cannot carry '$section'"))
      case None =>
        json.as[RbacManifest].left.map(e => Refusal("invalid-yaml", e.getMessage))

  private def holdsEntries(json: Json, field: String): Boolean =
    json.hcursor.downField(field).focus.exists(v => !v.isNull && !v.asArray.exists(_.isEmpty))

  /** Everything that makes `bundle` unfit for `tenant`, before anything is written. */
  def refusal(
      bundle: RbacManifest,
      tenant: Tenant,
      store: ControlPlaneStore
  ): Option[Refusal] =
    def foreign(name: String) = name != tenant.id && name != tenant.displayName
    val idpGroups = store.listGroups(tenant.id).filter(_.externalId.isDefined).map(_.name).toSet
    bundle.roles
      .find(r => foreign(r.tenant))
      .map(r => Refusal("rbac_tenant_mismatch", s"role '${r.name}' names tenant '${r.tenant}'"))
      .orElse(
        bundle.groups
          .find(g => foreign(g.tenant))
          .map(g =>
            Refusal("rbac_tenant_mismatch", s"group '${g.name}' names tenant '${g.tenant}'")
          )
      )
      .orElse(
        bundle.groups
          .find(_.poolGrants.nonEmpty)
          .map(g =>
            Refusal(
              "rbac_pool_grants_refused",
              s"group '${g.name}' carries pool grants, which are topology"
            )
          )
      )
      .orElse(
        bundle.groups
          .find(g => idpGroups(g.name))
          .map(g =>
            Refusal(
              "scim_group_conflict",
              s"group '${g.name}' is provisioned by an identity provider"
            )
          )
      )

  /** The tenant's RBAC subset of a full export: custom roles and groups, without pool grants or
    * SCIM provisioned groups. The exporter already sorts every list, so equal RBAC prints equal.
    */
  def of(manifest: ConfigManifest, tenant: Tenant, store: ControlPlaneStore): RbacManifest =
    def own(name: String) = name == tenant.id || name == tenant.displayName
    val idpGroups = store.listGroups(tenant.id).filter(_.externalId.isDefined).map(_.name).toSet
    RbacManifest(
      roles = manifest.roles.filter(r => own(r.tenant)),
      groups = manifest.groups
        .filter(g => own(g.tenant) && !idpGroups(g.name))
        .map(_.copy(poolGrants = Nil))
    )

  /** JSON of `rbac` with the always-empty pool grants left out, for printing. */
  def toJson(rbac: RbacManifest): Json =
    Json.obj(
      "roles" -> Codec[RbacManifest]
        .apply(rbac)
        .hcursor
        .downField("roles")
        .focus
        .getOrElse(Json.arr()),
      "groups" -> Json.fromValues(
        rbac.groups.map(g => Codec[ManifestGroup].apply(g).mapObject(_.remove(PoolGrantsField)))
      )
    )
