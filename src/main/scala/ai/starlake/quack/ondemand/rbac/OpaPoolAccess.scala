package ai.starlake.quack.ondemand.rbac

import ai.starlake.quack.model.{PoolKey, Tenant}
import ai.starlake.quack.ondemand.state.RbacUser

/** Why a handshake authorization failed. `Unavailable` = the deciding authority (OPA) could not be
  * reached; edges surface it as retryable UNAVAILABLE instead of a permission error.
  */
enum HandshakeDenial(val message: String):
  case Denied(msg: String)      extends HandshakeDenial(msg)
  case Unavailable(msg: String) extends HandshakeDenial(msg)

/** Everything an edge knows about a handshake, for gate 4 and the OPA input. `superuserAdmissible`
  * says whether the credential may bind a superuser (`tenant IS NULL`) row: false when the
  * credential was validated by a TENANT realm, and false for a REST or MCP caller that is not
  * itself a superuser (`ExecCaller.superuserAdmissible`). It has no default, so every construction
  * states the caller's realm. `edge` names the front door (`flightsql`, `quack`, `mcp`) and lands
  * in the OPA input's `client.edge`. `restriction` is the caller's personal-access-token scope (the
  * routed executor's); a tenant principal's effective set is attenuated by it BEFORE gate 4, so an
  * opa tenant's connect decision sees only the token's roles. The FlightSQL / Quack wires have no
  * token concept and leave it Unrestricted.
  */
final case class AuthzRequest(
    tenant: String,
    pool: String,
    username: String,
    jwtRoles: Set[String] = Set.empty,
    jwtGroups: Set[String] = Set.empty,
    jwtClaims: Map[String, String] = Map.empty,
    superuserAdmissible: Boolean,
    edge: String = "",
    restriction: ai.starlake.quack.ondemand.auth.TokenRestriction =
      ai.starlake.quack.ondemand.auth.TokenRestriction.Unrestricted
)

/** Gate-4 seam for `opa` tenants. Lives in `ondemand` so PoolSupervisor does not depend on the edge
  * package; implemented by `edge.opa.OpaAuthorizer`.
  */
trait OpaPoolAccess:
  def isOpa(tenant: Tenant): Boolean
  def connect(
      tenant: Tenant,
      key: PoolKey,
      parentPools: List[String],
      user: RbacUser,
      eff: EffectiveSet,
      edge: String
  ): Either[HandshakeDenial, Unit]
