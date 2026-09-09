package ai.starlake.quack.edge.admin

import ai.starlake.quack.edge.{QueryResult, RouterFailure}
import ai.starlake.quack.model.PoolKey
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacGroup, RbacRole, RbacUser}
import ai.starlake.quack.ondemand.{PoolSupervisor, SupervisorError}
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging

/** Executes parsed AdminCommands against the control plane. All mutations delegate to the existing
  * PoolSupervisor methods, so expression validation (jsqlparser), effective-cache invalidation, and
  * HA propagation apply unchanged. Fail-closed: any resolution or authorization failure returns a
  * RouterFailure; nothing here ever reaches a DuckDB node.
  */
final class AdminStatementExecutor(supervisor: PoolSupervisor) extends LazyLogging:

  private final case class Ctx(tenantId: String, tenantName: String, superuser: Boolean)

  def execute(
      user: String,
      poolKey: PoolKey,
      sql: String,
      effectiveSet: Option[EffectiveSet]
  ): IO[Either[RouterFailure, QueryResult]] =
    AdminSqlParser.parse(sql) match
      case Left(err)  => IO.pure(Left(RouterFailure.BadRequest(s"admin statement: $err")))
      case Right(cmd) =>
        authorize(poolKey, effectiveSet) match
          case Left(f)    => IO.pure(Left(f))
          case Right(ctx) =>
            logger.info(
              s"sql-admin user=$user tenant=${ctx.tenantName} " +
                s"cmd=${cmd.getClass.getSimpleName}"
            )
            run(ctx, cmd)

  /** Superuser (tenant IS NULL) anywhere; tenant admin only within the session tenant.
    * RbacUser.role is the free-text admin/user label, not an RBAC role.
    */
  private def authorize(
      poolKey: PoolKey,
      eff: Option[EffectiveSet]
  ): Either[RouterFailure, Ctx] =
    eff match
      case None    => Left(RouterFailure.AccessDenied("admin_required: no principal context"))
      case Some(e) =>
        supervisor.getTenant(poolKey.tenant).orElse(supervisor.getTenantById(poolKey.tenant)) match
          case None    => Left(RouterFailure.Internal(s"unknown tenant '${poolKey.tenant}'"))
          case Some(t) =>
            val superuser   = e.user.tenant.isEmpty
            val tenantAdmin = e.user.role == "admin" && e.user.tenant.contains(t.id)
            if superuser || tenantAdmin then Right(Ctx(t.id, poolKey.tenant, superuser))
            else Left(RouterFailure.AccessDenied("admin_required"))

  private def toFailure(e: SupervisorError): RouterFailure = e match
    case SupervisorError.NotFound(m)        => RouterFailure.NotFound(m)
    case SupervisorError.AlreadyExists(m)   => RouterFailure.AlreadyExists(m)
    case SupervisorError.Conflict(m)        => RouterFailure.BadRequest(m)
    case SupervisorError.InvalidArgument(m) => RouterFailure.BadRequest(m)
    case SupervisorError.InvalidName(m)     => RouterFailure.BadRequest(m)
    case SupervisorError.InvalidEmail(m)    => RouterFailure.BadRequest(m)
    case SupervisorError.QuotaExceeded(m)   => RouterFailure.BadRequest(m)
    case SupervisorError.Internal(m)        => RouterFailure.Internal(m)

  private def mut[A](op: IO[Either[SupervisorError, A]])(
      render: A => QueryResult
  ): IO[Either[RouterFailure, QueryResult]] =
    op.map(_.left.map(toFailure).map(render))

  private def roleByName(ctx: Ctx, name: String): Either[RouterFailure, RbacRole] =
    supervisor
      .listRoles(ctx.tenantId)
      .find(_.name == name)
      .toRight(RouterFailure.NotFound(s"unknown_role: $name"))

  private def userByName(ctx: Ctx, name: String): Either[RouterFailure, RbacUser] =
    supervisor
      .findUser(Some(ctx.tenantId), name)
      .toRight(RouterFailure.NotFound(s"unknown_user: $name"))

  private def groupByName(ctx: Ctx, name: String): Either[RouterFailure, RbacGroup] =
    supervisor
      .listGroups(ctx.tenantId)
      .find(_.name == name)
      .toRight(RouterFailure.NotFound(s"unknown_group: $name"))

  /** Resolves a principal to (userId, groupId) with exactly one side populated. */
  private def principalIds(
      ctx: Ctx,
      p: Principal
  ): Either[RouterFailure, (Option[String], Option[String])] =
    p match
      case Principal.User(name)  => userByName(ctx, name).map(u => (Some(u.id), None))
      case Principal.Group(name) => groupByName(ctx, name).map(g => (None, Some(g.id)))

  private def revokedResult(n: Int): QueryResult =
    AdminResults.table(
      List("status", "revoked"),
      List(List(Some("ok"), Some(n.toString)))
    )

  private def run(ctx: Ctx, cmd: AdminCommand): IO[Either[RouterFailure, QueryResult]] =
    cmd match
      case AdminCommand.CreateRole(name) =>
        mut(supervisor.createRole(ctx.tenantId, name))(r =>
          AdminResults.ok(s"role ${r.name} created")
        )

      case AdminCommand.DropRole(name, ifExists) =>
        roleByName(ctx, name) match
          case Left(_) if ifExists => IO.pure(Right(AdminResults.ok(s"role $name absent")))
          case Left(f)             => IO.pure(Left(f))
          case Right(r)            =>
            mut(supervisor.deleteRole(r.id))(_ => AdminResults.ok(s"role $name dropped"))

      case AdminCommand.GrantRoleTo(role, to) =>
        val resolved = for
          r   <- roleByName(ctx, role)
          ids <- principalIds(ctx, to)
        yield (r, ids)
        resolved match
          case Left(f)                    => IO.pure(Left(f))
          case Right((r, (Some(uid), _))) =>
            mut(supervisor.addUserRole(uid, r.id))(_ => AdminResults.ok(s"role $role granted"))
          case Right((r, (_, Some(gid)))) =>
            mut(supervisor.addGroupRole(gid, r.id))(_ => AdminResults.ok(s"role $role granted"))
          case Right(_) => IO.pure(Left(RouterFailure.Internal("unresolved principal")))

      case AdminCommand.RevokeRoleFrom(role, from) =>
        val resolved = for
          r   <- roleByName(ctx, role)
          ids <- principalIds(ctx, from)
        yield (r, ids)
        resolved match
          case Left(f)                    => IO.pure(Left(f))
          case Right((r, (Some(uid), _))) =>
            mut(supervisor.removeUserRole(uid, r.id))(_ => AdminResults.ok(s"role $role revoked"))
          case Right((r, (_, Some(gid)))) =>
            mut(supervisor.removeGroupRole(gid, r.id))(_ => AdminResults.ok(s"role $role revoked"))
          case Right(_) => IO.pure(Left(RouterFailure.Internal("unresolved principal")))

      case AdminCommand.AlterGroupAddUser(group, user) =>
        val resolved = for
          g <- groupByName(ctx, group)
          u <- userByName(ctx, user)
        yield (g, u)
        resolved match
          case Left(f)       => IO.pure(Left(f))
          case Right((g, u)) =>
            mut(supervisor.addUserGroup(u.id, g.id))(_ =>
              AdminResults.ok(s"user $user added to group $group")
            )

      case AdminCommand.AlterGroupDropUser(group, user) =>
        val resolved = for
          g <- groupByName(ctx, group)
          u <- userByName(ctx, user)
        yield (g, u)
        resolved match
          case Left(f)       => IO.pure(Left(f))
          case Right((g, u)) =>
            mut(supervisor.removeUserGroup(u.id, g.id))(_ =>
              AdminResults.ok(s"user $user removed from group $group")
            )

      case AdminCommand.GrantTable(verb, ref, role) =>
        roleByName(ctx, role) match
          case Left(f)  => IO.pure(Left(f))
          case Right(r) =>
            val dup = supervisor
              .listRolePermissions(r.id)
              .exists(p =>
                p.catalogName == ref.catalog && p.schemaName == ref.schema &&
                  p.tableName == ref.table && p.verb == verb
              )
            if dup then IO.pure(Right(AdminResults.ok(s"grant already present")))
            else
              mut(supervisor.grantRolePermission(r.id, ref.catalog, ref.schema, ref.table, verb))(
                _ => AdminResults.ok(s"granted $verb on $ref to role $role")
              )

      case AdminCommand.RevokeTable(verbOpt, ref, role) =>
        roleByName(ctx, role) match
          case Left(f)  => IO.pure(Left(f))
          case Right(r) =>
            val matches = supervisor
              .listRolePermissions(r.id)
              .filter(p =>
                p.catalogName == ref.catalog && p.schemaName == ref.schema &&
                  p.tableName == ref.table && verbOpt.forall(_ == p.verb)
              )
            matches
              .foldLeft(IO.pure(Right(()): Either[RouterFailure, Unit])) { (acc, p) =>
                acc.flatMap {
                  case Left(f)  => IO.pure(Left(f))
                  case Right(_) =>
                    supervisor.revokeRolePermission(p.id).map(_.left.map(toFailure))
                }
              }
              .map(_.map(_ => revokedResult(matches.size)))

      case other =>
        IO.pure(Left(RouterFailure.Internal(s"not yet implemented: $other"))) // Task 7
