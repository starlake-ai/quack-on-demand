package ai.starlake.quack.ondemand.api

import sttp.model.StatusCode
import sttp.tapir.*

/** SCIM 2.0 provisioning surface (RFC 7643/7644), tenant-scoped: an enterprise IdP (Okta, Entra,
  * Google) points its provisioning connector at `/api/scim/v2/{tenant}` with a bearer credential
  * and drives user and group lifecycle from there.
  *
  * Wire shape deliberately diverges from the rest of the API:
  *   - Bodies are raw strings, not circe `jsonBody`: SCIM clients send
  *     `Content-Type: application/scim+json`, which tapir's JSON codec would refuse, and SCIM
  *     errors must be the RFC's own envelope, not [[Dtos.ErrorResponse]]. Handlers parse and
  *     render with circe directly.
  *   - Credentials ride `Authorization: Bearer` (the SCIM standard transport; accepted for these
  *     paths by ManagerServer's guard) as well as the usual X-API-Key / cookie pair.
  *
  * The tenant path capture is named `tenant` so `TenantScopeGuard.extractTenant` enforces the URL
  * perimeter (see TenantScopeCompletenessSpec).
  */
object ScimEndpoints:

  /** RFC 7235: the auth scheme token is case-insensitive. Shared by ManagerServer's guard and
    * `scimAuth` below so a credential admitted by one is never invisible to the other.
    */
  def stripBearer(headerValue: String): Option[String] =
    val prefix = "Bearer "
    Option.when(
      headerValue.length > prefix.length &&
        headerValue.substring(0, prefix.length).equalsIgnoreCase(prefix)
    )(headerValue.substring(prefix.length).trim)

  private val ScimContentType = header("Content-Type", "application/scim+json")

  /** X-API-Key header, `Authorization: Bearer` header, or session cookie -- first match wins. */
  val scimAuth: EndpointInput[Option[String]] =
    header[Option[String]]("X-API-Key")
      .and(header[Option[String]]("Authorization"))
      .and(cookie[Option[String]](SessionTokenStore.CookieName))
      .map { case (h, bearer, c) =>
        h.orElse(bearer.flatMap(stripBearer)).orElse(c)
      }(t => (t, None, None))

  private val base = endpoint
    .in("api" / "scim" / "v2" / path[String]("tenant"))
    .errorOut(statusCode.and(stringBody).and(ScimContentType))

  private def outJson[I](e: Endpoint[Unit, I, (StatusCode, String), Unit, Any]) =
    e.out(stringBody).out(ScimContentType)

  // ---------------- Users ----------------

  val listUsers = outJson(
    base.get
      .in("Users")
      .in(query[Option[String]]("filter"))
      .in(query[Option[Int]]("startIndex"))
      .in(query[Option[Int]]("count"))
      .in(scimAuth)
  )

  val getUser = outJson(base.get.in("Users" / path[String]("id")).in(scimAuth))

  val createUser = outJson(base.post.in("Users").in(stringBody).in(scimAuth))
    .out(statusCode(StatusCode.Created))

  val replaceUser =
    outJson(base.put.in("Users" / path[String]("id")).in(stringBody).in(scimAuth))

  val patchUser =
    outJson(base.patch.in("Users" / path[String]("id")).in(stringBody).in(scimAuth))

  val deleteUser = base.delete
    .in("Users" / path[String]("id"))
    .in(scimAuth)
    .out(statusCode(StatusCode.NoContent))

  // ---------------- Groups ----------------

  val listGroups = outJson(
    base.get
      .in("Groups")
      .in(query[Option[String]]("filter"))
      .in(query[Option[Int]]("startIndex"))
      .in(query[Option[Int]]("count"))
      .in(scimAuth)
  )

  val getGroup = outJson(base.get.in("Groups" / path[String]("id")).in(scimAuth))

  val createGroup = outJson(base.post.in("Groups").in(stringBody).in(scimAuth))
    .out(statusCode(StatusCode.Created))

  val replaceGroup =
    outJson(base.put.in("Groups" / path[String]("id")).in(stringBody).in(scimAuth))

  val patchGroup =
    outJson(base.patch.in("Groups" / path[String]("id")).in(stringBody).in(scimAuth))

  val deleteGroup = base.delete
    .in("Groups" / path[String]("id"))
    .in(scimAuth)
    .out(statusCode(StatusCode.NoContent))

  // ---------------- Discovery ----------------

  val serviceProviderConfig = outJson(base.get.in("ServiceProviderConfig").in(scimAuth))
  val resourceTypes         = outJson(base.get.in("ResourceTypes").in(scimAuth))
  val schemas               = outJson(base.get.in("Schemas").in(scimAuth))
