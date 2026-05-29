package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.state.{AclGrant, AclGrantStore}
import cats.effect.IO
import sttp.model.StatusCode

import java.sql.SQLException
import java.time.format.DateTimeFormatter

/** CRUD over the slkstate_acl_grant table. Phase 1: store + serve grants.
  * Phase 2 (later) will wire these grants into the SQL validator. */
final class AclHandlers(store: AclGrantStore):

  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  private def toResponse(g: AclGrant): AclGrantResponse =
    AclGrantResponse(
      id          = g.id.getOrElse(0L),
      tenantId    = g.tenantId,
      principal   = g.principal,
      catalogName = g.catalogName,
      schemaName  = g.schemaName,
      tableName   = g.tableName,
      permission  = g.permission,
      grantedAt   = g.grantedAt
        .map(_.toString)
        .getOrElse(DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.now()))
    )

  private def validate(req: AclGrantRequest): Option[String] =
    if req.tenantId.isEmpty then Some("tenantId must be non-empty")
    else if req.principal.isEmpty then Some("principal must be non-empty")
    else if !AclGrant.ValidPermissions.contains(req.permission.toUpperCase) then
      Some(s"permission must be one of ${AclGrant.ValidPermissions.mkString(", ")}")
    else None

  private def toDomain(req: AclGrantRequest): AclGrant =
    AclGrant(
      id          = None,
      tenantId    = req.tenantId,
      principal   = req.principal,
      catalogName = req.catalogName,
      schemaName  = req.schemaName,
      tableName   = req.tableName,
      permission  = req.permission.toUpperCase,
      grantedAt   = None
    )

  def createGrant(req: AclGrantRequest): Out[AclGrantResponse] =
    validate(req) match
      case Some(err) =>
        IO.pure(Left((StatusCode.BadRequest, ErrorResponse("invalid_grant", err))))
      case None =>
        IO.blocking(store.insert(toDomain(req))).attempt.map {
          case Right(g) => Right(toResponse(g))
          case Left(sql: SQLException) if Option(sql.getSQLState).contains("23505") =>
            Left((StatusCode.Conflict,
                  ErrorResponse("exists", "grant already exists with these key fields")))
          case Left(t) =>
            Left((StatusCode.InternalServerError,
                  ErrorResponse("db_error", t.getMessage)))
        }

  def listGrants(tenant: Option[String]): Out[AclGrantListResponse] =
    IO.blocking(store.list(tenant)).attempt.map {
      case Right(rows) => Right(AclGrantListResponse(rows.map(toResponse)))
      case Left(t) =>
        Left((StatusCode.InternalServerError,
              ErrorResponse("db_error", t.getMessage)))
    }

  def deleteGrant(id: Long): Out[Unit] =
    IO.blocking(store.delete(id)).attempt.map {
      case Right(true)  => Right(())
      case Right(false) =>
        Left((StatusCode.NotFound, ErrorResponse("not_found", s"no grant with id=$id")))
      case Left(t) =>
        Left((StatusCode.InternalServerError,
              ErrorResponse("db_error", t.getMessage)))
    }

  /** Best-effort bulk insert. Rows are inserted one at a time so a single
    * conflict / validation error doesn't roll back the whole upload - the
    * response lists the rows that landed. */
  def uploadGrants(req: AclGrantBulkRequest): Out[AclGrantListResponse] = IO.blocking {
    val landed = scala.collection.mutable.ListBuffer.empty[AclGrant]
    req.grants.foreach { r =>
      if validate(r).isEmpty then
        try landed += store.insert(toDomain(r))
        catch case _: SQLException => () // skip duplicates / per-row errors
    }
    Right(AclGrantListResponse(landed.toList.map(toResponse)))
  }