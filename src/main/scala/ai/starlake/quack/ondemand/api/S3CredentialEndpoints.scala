package ai.starlake.quack.ondemand.api

import Dtos.given
import Endpoints.authToken
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._

/** Users' own S3 credentials (in-engine authorization, decision 23), registered in
  * [[EndpointModules.all]].
  *
  *   - `/api/profile/s3/{list,add,remove}`: the caller's own, on the profile allowlist
  *     (`ManagerServer.isProfileApi`), so any session or personal access token, admin or not.
  *     Identity comes from the credential only: no route takes a user or tenant input.
  *   - `/api/user/s3/{list,remove}`: an admin's view of one user's credentials (a tenant admin for
  *     their own tenant's users only, TenantScopeCheck). No add: a credential is its owner's to
  *     give.
  */
object S3CredentialEndpoints:

  private val base = endpoint
    .in("api")
    .errorOut(statusCode.and(jsonBody[ErrorResponse]))

  val listMine: PublicEndpoint[
    (ProfileS3ListRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    S3CredentialListResponse,
    Any
  ] =
    base.post
      .in("profile" / "s3" / "list")
      .in(jsonBody[ProfileS3ListRequest])
      .in(authToken)
      .out(jsonBody[S3CredentialListResponse])

  val addMine: PublicEndpoint[
    (ProfileS3AddRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    S3CredentialDto,
    Any
  ] =
    base.post
      .in("profile" / "s3" / "add")
      .in(jsonBody[ProfileS3AddRequest])
      .in(authToken)
      .out(jsonBody[S3CredentialDto])

  val removeMine: PublicEndpoint[
    (ProfileS3RemoveRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    S3CredentialDto,
    Any
  ] =
    base.post
      .in("profile" / "s3" / "remove")
      .in(jsonBody[ProfileS3RemoveRequest])
      .in(authToken)
      .out(jsonBody[S3CredentialDto])

  val listForUser: PublicEndpoint[
    (UserS3ListRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    S3CredentialListResponse,
    Any
  ] =
    base.post
      .in("user" / "s3" / "list")
      .in(jsonBody[UserS3ListRequest])
      .in(authToken)
      .out(jsonBody[S3CredentialListResponse])

  val removeForUser: PublicEndpoint[
    (UserS3RemoveRequest, Option[String]),
    (sttp.model.StatusCode, ErrorResponse),
    S3CredentialDto,
    Any
  ] =
    base.post
      .in("user" / "s3" / "remove")
      .in(jsonBody[UserS3RemoveRequest])
      .in(authToken)
      .out(jsonBody[S3CredentialDto])
