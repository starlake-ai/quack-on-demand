package ai.starlake.quack.edge.admin

import ai.starlake.quack.edge.{QueryResult, RouterFailure}
import ai.starlake.quack.model.PoolKey
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{
  RbacGroup,
  RbacRole,
  RbacUser,
  RoleColumnPolicy,
  RoleRowPolicy
}
import ai.starlake.quack.ondemand.{PoolSupervisor, SupervisorError}
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging

/** Executes parsed AdminCommands against the control plane. All mutations delegate to the existing
  * PoolSupervisor methods, so expression validation (jsqlparser), effective-cache invalidation, and
  * HA propagation apply unchanged. Fail-closed: any resolution or authorization failure returns a
  * RouterFailure; nothing here ever reaches a DuckDB node.
  */
final class AdminStatementExecutor(
    supervisor: PoolSupervisor,
    createUserFn: AdminStatementExecutor.CreateUserFn = AdminStatementExecutor.unwiredCreateUser,
    alterPasswordFn: AdminStatementExecutor.AlterPasswordFn =
      AdminStatementExecutor.unwiredAlterPassword
) extends LazyLogging:

  private final case class Ctx(
      tenantId: String,
      tenantName: String,
      superuser: Boolean,
      sessionUser: String
  )

  def execute(
      user: String,
      poolKey: PoolKey,
      sql: String,
      effectiveSet: Option[EffectiveSet]
  ): IO[Either[RouterFailure, QueryResult]] =
    // Nothing below runs until the returned IO is actually evaluated: parsing, authorization
    // (an in-memory tenant-cache lookup, cheap but still deferred), and the log line all sit
    // behind this IO.defer rather than firing eagerly on the caller's thread at construction
    // time. The genuinely blocking work (JDBC-backed store reads in run()'s resolution
    // helpers) is pushed further, onto IO.blocking, at the call sites below.
    IO.defer {
      AdminSqlParser.parse(sql) match
        case Left(err)  => IO.pure(Left(RouterFailure.BadRequest(s"admin statement: $err")))
        case Right(cmd) =>
          authorize(user, poolKey, effectiveSet) match
            case Left(f)    => IO.pure(Left(f))
            case Right(ctx) =>
              logger.info(
                s"sql-admin user=$user tenant=${ctx.tenantName} " +
                  s"cmd=${cmd.getClass.getSimpleName}"
              )
              run(ctx, cmd)
    }

  /** Superuser (tenant IS NULL) anywhere; tenant admin only within the session tenant.
    * RbacUser.role is the free-text admin/user label, not an RBAC role.
    */
  private def authorize(
      user: String,
      poolKey: PoolKey,
      eff: Option[EffectiveSet]
  ): Either[RouterFailure, Ctx] =
    eff match
      case None    => Left(RouterFailure.AccessDenied("admin_required: no principal context"))
      case Some(e) =>
        // Both getTenant and getTenantById are id lookups against the in-memory tenant cache
        // (getTenant lowercases its argument and matches on `id` despite the name; it never
        // consults displayName). Don't "simplify" this into a display-name lookup - that
        // reopens the cross-tenant display-name collision path the id-only match closes.
        supervisor.getTenant(poolKey.tenant).orElse(supervisor.getTenantById(poolKey.tenant)) match
          case None    => Left(RouterFailure.Internal(s"unknown tenant '${poolKey.tenant}'"))
          case Some(t) =>
            val superuser   = e.user.tenant.isEmpty
            val tenantAdmin = e.user.role == "admin" && e.user.tenant.contains(t.id)
            if superuser || tenantAdmin then Right(Ctx(t.id, poolKey.tenant, superuser, user))
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

  // Resolution reads (listRoles / findUser / listGroups / listRolePermissions) hit the
  // control-plane store, which is JDBC-backed in production - IO.blocking pushes them onto
  // the blocking pool instead of running on whatever thread evaluates this IO.

  private def roleByName(ctx: Ctx, name: String): IO[Either[RouterFailure, RbacRole]] =
    IO.blocking(supervisor.listRoles(ctx.tenantId).find(_.name == name))
      .map(_.toRight(RouterFailure.NotFound(s"unknown_role: $name")))

  private def userByName(ctx: Ctx, name: String): IO[Either[RouterFailure, RbacUser]] =
    IO.blocking(supervisor.findUser(Some(ctx.tenantId), name))
      .map(_.toRight(RouterFailure.NotFound(s"unknown_user: $name")))

  private def groupByName(ctx: Ctx, name: String): IO[Either[RouterFailure, RbacGroup]] =
    IO.blocking(supervisor.listGroups(ctx.tenantId).find(_.name == name))
      .map(_.toRight(RouterFailure.NotFound(s"unknown_group: $name")))

  /** Resolves a principal to (userId, groupId) with exactly one side populated. */
  private def principalIds(
      ctx: Ctx,
      p: Principal
  ): IO[Either[RouterFailure, (Option[String], Option[String])]] =
    p match
      case Principal.User(name)  => userByName(ctx, name).map(_.map(u => (Some(u.id), None)))
      case Principal.Group(name) => groupByName(ctx, name).map(_.map(g => (None, Some(g.id))))

  /** Resolves a role by name, then a principal, in sequence - shared by GrantRoleTo and
    * RevokeRoleFrom, whose only difference is which mutator they call once resolved.
    */
  private def resolveRoleAndPrincipal(
      ctx: Ctx,
      role: String,
      p: Principal
  ): IO[Either[RouterFailure, (RbacRole, (Option[String], Option[String]))]] =
    roleByName(ctx, role).flatMap {
      case Left(f)  => IO.pure(Left(f))
      case Right(r) => principalIds(ctx, p).map(_.map(ids => (r, ids)))
    }

  /** Resolves a group then a user, in sequence - shared by ALTER GROUP ADD/DROP USER. */
  private def resolveGroupAndUser(
      ctx: Ctx,
      group: String,
      user: String
  ): IO[Either[RouterFailure, (RbacGroup, RbacUser)]] =
    groupByName(ctx, group).flatMap {
      case Left(f)  => IO.pure(Left(f))
      case Right(g) => userByName(ctx, user).map(_.map(u => (g, u)))
    }

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
        roleByName(ctx, name).flatMap {
          case Left(_) if ifExists => IO.pure(Right(AdminResults.ok(s"role $name absent")))
          case Left(f)             => IO.pure(Left(f))
          case Right(r)            =>
            mut(supervisor.deleteRole(r.id))(_ => AdminResults.ok(s"role $name dropped"))
        }

      case AdminCommand.GrantRoleTo(role, to) =>
        resolveRoleAndPrincipal(ctx, role, to).flatMap {
          case Left(f)                    => IO.pure(Left(f))
          case Right((r, (Some(uid), _))) =>
            mut(supervisor.addUserRole(uid, r.id))(_ => AdminResults.ok(s"role $role granted"))
          case Right((r, (_, Some(gid)))) =>
            mut(supervisor.addGroupRole(gid, r.id))(_ => AdminResults.ok(s"role $role granted"))
          case Right(_) => IO.pure(Left(RouterFailure.Internal("unresolved principal")))
        }

      case AdminCommand.RevokeRoleFrom(role, from) =>
        resolveRoleAndPrincipal(ctx, role, from).flatMap {
          case Left(f)                    => IO.pure(Left(f))
          case Right((r, (Some(uid), _))) =>
            mut(supervisor.removeUserRole(uid, r.id))(_ => AdminResults.ok(s"role $role revoked"))
          case Right((r, (_, Some(gid)))) =>
            mut(supervisor.removeGroupRole(gid, r.id))(_ => AdminResults.ok(s"role $role revoked"))
          case Right(_) => IO.pure(Left(RouterFailure.Internal("unresolved principal")))
        }

      case AdminCommand.AlterGroupAddUser(group, user) =>
        resolveGroupAndUser(ctx, group, user).flatMap {
          case Left(f)       => IO.pure(Left(f))
          case Right((g, u)) =>
            mut(supervisor.addUserGroup(u.id, g.id))(_ =>
              AdminResults.ok(s"user $user added to group $group")
            )
        }

      case AdminCommand.AlterGroupDropUser(group, user) =>
        resolveGroupAndUser(ctx, group, user).flatMap {
          case Left(f)       => IO.pure(Left(f))
          case Right((g, u)) =>
            mut(supervisor.removeUserGroup(u.id, g.id))(_ =>
              AdminResults.ok(s"user $user removed from group $group")
            )
        }

      case AdminCommand.GrantTable(verb, ref, role) =>
        roleByName(ctx, role).flatMap {
          case Left(f)  => IO.pure(Left(f))
          case Right(r) =>
            IO.blocking(supervisor.listRolePermissions(r.id)).flatMap { perms =>
              val dup = perms.exists(p =>
                p.catalogName == ref.catalog && p.schemaName == ref.schema &&
                  p.tableName == ref.table && p.verb == verb
              )
              if dup then IO.pure(Right(AdminResults.ok(s"grant already present")))
              else
                mut(supervisor.grantRolePermission(r.id, ref.catalog, ref.schema, ref.table, verb))(
                  _ => AdminResults.ok(s"granted $verb on $ref to role $role")
                )
            }
        }

      case AdminCommand.RevokeTable(verbOpt, ref, role) =>
        roleByName(ctx, role).flatMap {
          case Left(f)  => IO.pure(Left(f))
          case Right(r) =>
            IO.blocking(supervisor.listRolePermissions(r.id)).flatMap { perms =>
              val matches = perms.filter(p =>
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
            }
        }

      case AdminCommand.GrantPool(target, to) =>
        resolvePoolAndPrincipal(ctx, target, to).flatMap {
          case Left(f)                  => IO.pure(Left(f))
          case Right((pid, (uid, gid))) =>
            mut(supervisor.grantPoolPermission(ctx.tenantId, Some(pid), uid, gid))(_ =>
              AdminResults.ok(s"pool ${target.pool} granted")
            )
        }

      case AdminCommand.RevokePool(target, from) =>
        resolvePoolAndPrincipal(ctx, target, from).flatMap {
          case Left(f)                  => IO.pure(Left(f))
          case Right((pid, (uid, gid))) =>
            IO.blocking(
              supervisor
                .listPoolPermissions(Some(ctx.tenantId), uid, gid)
                .filter(_.poolId.contains(pid))
            ).flatMap { matches =>
              matches
                .foldLeft(IO.pure(Right(()): Either[RouterFailure, Unit])) { (acc, p) =>
                  acc.flatMap {
                    case Left(f)  => IO.pure(Left(f))
                    case Right(_) =>
                      supervisor.revokePoolPermission(p.id).map(_.left.map(toFailure))
                  }
                }
                .map(_.map(_ => revokedResult(matches.size)))
            }
        }

      case AdminCommand.CreateRowPolicy(ref, role, pred, orReplace) =>
        roleByName(ctx, role).flatMap {
          case Left(f)  => IO.pure(Left(f))
          case Right(r) =>
            supervisor.listRowPoliciesByRole(r.id).flatMap { existing =>
              existing.find(p =>
                p.catalogName == ref.catalog && p.schemaName == ref.schema &&
                  p.tableName == ref.table
              ) match
                case Some(p) if orReplace =>
                  mut(supervisor.updateRowPolicy(p.id, pred))(_ =>
                    AdminResults.ok(s"row policy on $ref replaced")
                  )
                case Some(_) =>
                  IO.pure(
                    Left(
                      RouterFailure.AlreadyExists(
                        s"row policy already exists on $ref for role $role; use CREATE OR REPLACE"
                      )
                    )
                  )
                case None =>
                  mut(supervisor.createRowPolicy(r.id, ref.catalog, ref.schema, ref.table, pred))(
                    _ => AdminResults.ok(s"row policy on $ref created")
                  )
            }
        }

      case AdminCommand.DropRowPolicy(ref, role, ifExists) =>
        roleByName(ctx, role).flatMap {
          case Left(_) if ifExists => IO.pure(Right(AdminResults.ok("row policy absent")))
          case Left(f)             => IO.pure(Left(f))
          case Right(r)            =>
            supervisor.listRowPoliciesByRole(r.id).flatMap { existing =>
              existing.find(p =>
                p.catalogName == ref.catalog && p.schemaName == ref.schema &&
                  p.tableName == ref.table
              ) match
                case Some(p) =>
                  mut(supervisor.deleteRowPolicy(p.id))(_ =>
                    AdminResults.ok(s"row policy on $ref dropped")
                  )
                case None if ifExists => IO.pure(Right(AdminResults.ok("row policy absent")))
                case None             =>
                  IO.pure(Left(RouterFailure.NotFound(s"not_found: row policy on $ref for $role")))
            }
        }

      case AdminCommand.CreateColumnPolicy(ref, col, role, action, transform, orReplace) =>
        roleByName(ctx, role).flatMap {
          case Left(f)  => IO.pure(Left(f))
          case Right(r) =>
            supervisor.listColumnPoliciesByRole(r.id).flatMap { existing =>
              existing.find(p =>
                p.catalogName == ref.catalog && p.schemaName == ref.schema &&
                  p.tableName == ref.table && p.columnName == col
              ) match
                case Some(p) if orReplace =>
                  mut(supervisor.updateColumnPolicy(p.id, action, transform))(_ =>
                    AdminResults.ok(s"column policy on $ref.$col replaced")
                  )
                case Some(_) =>
                  IO.pure(
                    Left(
                      RouterFailure.AlreadyExists(
                        s"column policy already exists on $ref.$col for role $role; " +
                          "use CREATE OR REPLACE"
                      )
                    )
                  )
                case None =>
                  mut(
                    supervisor.createColumnPolicy(
                      r.id,
                      ref.catalog,
                      ref.schema,
                      ref.table,
                      col,
                      action,
                      transform
                    )
                  )(_ => AdminResults.ok(s"column policy on $ref.$col created"))
            }
        }

      case AdminCommand.DropColumnPolicy(ref, col, role, ifExists) =>
        roleByName(ctx, role).flatMap {
          case Left(_) if ifExists => IO.pure(Right(AdminResults.ok("column policy absent")))
          case Left(f)             => IO.pure(Left(f))
          case Right(r)            =>
            supervisor.listColumnPoliciesByRole(r.id).flatMap { existing =>
              existing.find(p =>
                p.catalogName == ref.catalog && p.schemaName == ref.schema &&
                  p.tableName == ref.table && p.columnName == col
              ) match
                case Some(p) =>
                  mut(supervisor.deleteColumnPolicy(p.id))(_ =>
                    AdminResults.ok(s"column policy on $ref.$col dropped")
                  )
                case None if ifExists => IO.pure(Right(AdminResults.ok("column policy absent")))
                case None             =>
                  IO.pure(
                    Left(RouterFailure.NotFound(s"not_found: column policy on $ref.$col for $role"))
                  )
            }
        }

      case AdminCommand.CreateUser(name, password, admin) =>
        val role = if admin then "admin" else "user"
        mut(createUserFn(ctx.tenantId, name, password, role))(u =>
          AdminResults.ok(s"user ${u.username} created")
        )

      case AdminCommand.AlterUserPassword(name, password) =>
        userByName(ctx, name).flatMap {
          case Left(f)  => IO.pure(Left(f))
          case Right(_) =>
            mut(alterPasswordFn(ctx.tenantId, name, password))(_ =>
              AdminResults.ok(s"password updated for $name")
            )
        }

      case AdminCommand.DropUser(name, ifExists) =>
        if name == ctx.sessionUser then
          IO.pure(Left(RouterFailure.BadRequest("cannot drop the current session user")))
        else
          userByName(ctx, name).flatMap {
            case Left(_) if ifExists => IO.pure(Right(AdminResults.ok(s"user $name absent")))
            case Left(f)             => IO.pure(Left(f))
            case Right(u)            =>
              mut(supervisor.deleteUser(u.id))(_ => AdminResults.ok(s"user $name dropped"))
          }

      case AdminCommand.ShowRoles =>
        IO.blocking {
          val rows = supervisor
            .listRoles(ctx.tenantId)
            .map(r => List(Some(r.id), Some(r.name), r.description))
          Right(AdminResults.table(List("id", "name", "description"), rows))
        }

      case AdminCommand.ShowGrants(role) =>
        roleByName(ctx, role).flatMap {
          case Left(f)  => IO.pure(Left(f))
          case Right(r) =>
            IO.blocking {
              val rows = supervisor
                .listRolePermissions(r.id)
                .map(p =>
                  List(
                    Some(p.id),
                    Some(p.catalogName),
                    Some(p.schemaName),
                    Some(p.tableName),
                    Some(p.verb)
                  )
                )
              Right(AdminResults.table(List("id", "catalog", "schema", "table", "verb"), rows))
            }
        }

      case AdminCommand.ShowRowPolicies(filter) =>
        allRowPolicies(ctx).flatMap { all =>
          IO.blocking {
            val filtered =
              applyFilter(all, filter)(p => (p.catalogName, p.schemaName, p.tableName))
            Right(
              AdminResults.table(
                List("role", "catalog", "schema", "table", "predicate"),
                filtered.map { case (r, p) =>
                  List(
                    Some(r.name),
                    Some(p.catalogName),
                    Some(p.schemaName),
                    Some(p.tableName),
                    Some(p.predicateSql)
                  )
                }
              )
            )
          }
        }

      case AdminCommand.ShowColumnPolicies(filter) =>
        allColumnPolicies(ctx).flatMap { all =>
          IO.blocking {
            val filtered =
              applyFilter(all, filter)(p => (p.catalogName, p.schemaName, p.tableName))
            Right(
              AdminResults.table(
                List("role", "catalog", "schema", "table", "column", "action", "transform"),
                filtered.map { case (r, p) =>
                  List(
                    Some(r.name),
                    Some(p.catalogName),
                    Some(p.schemaName),
                    Some(p.tableName),
                    Some(p.columnName),
                    Some(p.action),
                    p.transformSql
                  )
                }
              )
            )
          }
        }

      case AdminCommand.ShowPoolGrants(principalOpt) =>
        val idsIO: IO[Either[RouterFailure, (Option[String], Option[String])]] =
          principalOpt match
            case None    => IO.pure(Right((None, None)))
            case Some(p) => principalIds(ctx, p)
        idsIO.flatMap {
          case Left(f)           => IO.pure(Left(f))
          case Right((uid, gid)) =>
            IO.blocking {
              val rows = supervisor
                .listPoolPermissions(Some(ctx.tenantId), uid, gid)
                .map { p =>
                  val userName  = p.userId.flatMap(supervisor.findUserById).map(_.username)
                  val groupName = p.groupId
                    .flatMap(g => supervisor.listGroups(ctx.tenantId).find(_.id == g).map(_.name))
                  List(Some(p.id), p.poolId, userName, groupName)
                }
              Right(AdminResults.table(List("id", "poolId", "user", "group"), rows))
            }
        }

  /** `ON POOL bi` resolves within the session tenant; `ON POOL tpch.bi` also matches the qualifier
    * against PoolKey.tenantDb, either verbatim or as "<tenant>_<qualifier>". A pure, synchronous
    * function - callers push the supervisor reads it makes (list/poolId) onto IO.blocking rather
    * than this helper doing so itself.
    */
  private def resolvePoolId(ctx: Ctx, target: PoolTarget): Either[RouterFailure, String] =
    val keys = supervisor
      .list()
      .map(_.key)
      .filter { k =>
        k.tenant == ctx.tenantId && k.pool == target.pool &&
        target.qualifier.forall(q => k.tenantDb == q || k.tenantDb == s"${ctx.tenantId}_$q")
      }
    keys match
      case Nil =>
        val shown = target.qualifier.fold(target.pool)(q => s"$q.${target.pool}")
        Left(RouterFailure.NotFound(s"unknown_pool: $shown"))
      case k :: Nil =>
        supervisor.poolId(k).toRight(RouterFailure.Internal(s"pool id missing for $k"))
      case _ =>
        Left(
          RouterFailure.BadRequest(
            s"ambiguous pool '${target.pool}': qualify as <tenantDb>.${target.pool}"
          )
        )

  /** Resolves the target pool, then a principal, in sequence - shared by GrantPool and RevokePool.
    * resolvePoolId's supervisor reads run on IO.blocking; principalIds is already IO-returning and
    * is simply flatMapped, matching resolveRoleAndPrincipal above.
    */
  private def resolvePoolAndPrincipal(
      ctx: Ctx,
      target: PoolTarget,
      p: Principal
  ): IO[Either[RouterFailure, (String, (Option[String], Option[String]))]] =
    IO.blocking(resolvePoolId(ctx, target)).flatMap {
      case Left(f)    => IO.pure(Left(f))
      case Right(pid) => principalIds(ctx, p).map(_.map(ids => (pid, ids)))
    }

  private def allRowPolicies(ctx: Ctx): IO[List[(RbacRole, RoleRowPolicy)]] =
    IO.blocking(supervisor.listRoles(ctx.tenantId)).flatMap { roles =>
      roles.foldLeft(IO.pure(List.empty[(RbacRole, RoleRowPolicy)])) { (acc, r) =>
        acc.flatMap(got => supervisor.listRowPoliciesByRole(r.id).map(ps => got ++ ps.map((r, _))))
      }
    }

  private def allColumnPolicies(ctx: Ctx): IO[List[(RbacRole, RoleColumnPolicy)]] =
    IO.blocking(supervisor.listRoles(ctx.tenantId)).flatMap { roles =>
      roles.foldLeft(IO.pure(List.empty[(RbacRole, RoleColumnPolicy)])) { (acc, r) =>
        acc.flatMap(got =>
          supervisor.listColumnPoliciesByRole(r.id).map(ps => got ++ ps.map((r, _)))
        )
      }
    }

  /** The ON filter matches the STORED tuple exactly, wildcards included (documented). `key`
    * extracts (catalog, schema, table) from the policy row so this stays shared between row and
    * column policies, which have no common supertype for those fields.
    */
  private def applyFilter[A](
      all: List[(RbacRole, A)],
      filter: PolicyFilter
  )(key: A => (String, String, String)): List[(RbacRole, A)] =
    filter match
      case PolicyFilter.All           => all
      case PolicyFilter.ForRole(name) => all.filter(_._1.name == name)
      case PolicyFilter.OnTable(ref)  =>
        all.filter { case (_, a) =>
          val (c, s, t) = key(a)
          c == ref.catalog && s == ref.schema && t == ref.table
        }

object AdminStatementExecutor:
  /** (tenantId, username, password, role) -> created user. Wired in Main over
    * PoolSupervisor.createUser + the boot UserStore with failIfExists = true; the default keeps
    * test/unwired constructions compiling and fail-closed.
    */
  type CreateUserFn = (String, String, String, String) => IO[Either[SupervisorError, RbacUser]]

  val unwiredCreateUser: CreateUserFn =
    (_, _, _, _) => IO.pure(Left(SupervisorError.Internal("user creation is not wired")))

  /** (tenantId, username, newPassword) -> unit. Wired in Main over the same per-(tenant, username)
    * rotation path REST user/update uses (clears lockout columns as part of the write); unwired
    * default fails closed.
    */
  type AlterPasswordFn = (String, String, String) => IO[Either[SupervisorError, Unit]]

  val unwiredAlterPassword: AlterPasswordFn =
    (_, _, _) => IO.pure(Left(SupervisorError.Internal("password rotation is not wired")))
