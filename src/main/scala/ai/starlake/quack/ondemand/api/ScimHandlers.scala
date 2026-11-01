package ai.starlake.quack.ondemand.api

import java.util.Locale
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.{PoolSupervisor, SupervisorError}
import ai.starlake.quack.ondemand.rbac.UserMemberships
import ai.starlake.quack.ondemand.state.{RbacGroup, RbacUser, UserStore}
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import io.circe.{Json, JsonObject}
import sttp.model.StatusCode

/** SCIM 2.0 handlers over the existing RBAC store. Semantics that differ from a generic SCIM
  * server, all deliberate:
  *   - Only tenant-scoped principals are visible: the superuser realm (`tenant IS NULL`) is never
  *     listed, matched, or mutable through SCIM.
  *   - `userName` and a group's `displayName` are immutable (the store keys upserts by them); an
  *     attempted rename is refused with scimType `mutability`.
  *   - A create without a `password` gets an unguessable random one: IdP-provisioned users are
  *     expected to sign in through the tenant's OIDC SSO, and the email-reset flow can mint a
  *     password later if needed.
  *   - `active` maps to `enabled`, which both the REST login and the FlightSQL handshake enforce,
  *     so an IdP deactivation cuts query access, not just console access.
  */
final class ScimHandlers(
    sup: PoolSupervisor,
    userStore: UserStore,
    audit: AuditRecorder = AuditRecorder.noop
) extends LazyLogging:

  type Out[A] = IO[Either[(StatusCode, String), A]]

  private val ErrorSchema  = "urn:ietf:params:scim:api:messages:2.0:Error"
  private val ListSchema   = "urn:ietf:params:scim:api:messages:2.0:ListResponse"
  private val UserSchema   = "urn:ietf:params:scim:schemas:core:2.0:User"
  private val GroupSchema  = "urn:ietf:params:scim:schemas:core:2.0:Group"
  private val MaxPageCount = 500

  // ---------------- SCIM envelopes ----------------

  private def scimError(
      status: StatusCode,
      detail: String,
      scimType: Option[String] = None
  ): (StatusCode, String) =
    val fields = List(
      "schemas" -> Json.arr(Json.fromString(ErrorSchema)),
      "status"  -> Json.fromString(status.code.toString),
      "detail"  -> Json.fromString(detail)
    ) ++ scimType.map(t => "scimType" -> Json.fromString(t))
    (status, Json.fromFields(fields).noSpaces)

  private def listResponse(total: Int, startIndex: Int, resources: List[Json]): String =
    Json
      .fromFields(
        List(
          "schemas"      -> Json.arr(Json.fromString(ListSchema)),
          "totalResults" -> Json.fromInt(total),
          "startIndex"   -> Json.fromInt(startIndex),
          "itemsPerPage" -> Json.fromInt(resources.size),
          "Resources"    -> Json.arr(resources*)
        )
      )
      .noSpaces

  private def userJson(tenantSeg: String, u: RbacUser): Json =
    val fields = List(
      "schemas"  -> Json.arr(Json.fromString(UserSchema)),
      "id"       -> Json.fromString(u.id),
      "userName" -> Json.fromString(u.username),
      "active"   -> Json.fromBoolean(u.enabled)
    ) ++ u.externalId.map(e => "externalId" -> Json.fromString(e)) ++
      u.email.map(e =>
        "emails" -> Json.arr(
          Json.fromFields(
            List("value" -> Json.fromString(e), "primary" -> Json.fromBoolean(true))
          )
        )
      ) ++ List(
        "meta" -> Json.fromFields(
          List(
            "resourceType" -> Json.fromString("User"),
            "location"     -> Json.fromString(s"/api/scim/v2/$tenantSeg/Users/${u.id}")
          )
            ++ u.createdAt.map(t => "created" -> Json.fromString(t.toString))
            ++ u.updatedAt.map(t => "lastModified" -> Json.fromString(t.toString))
        )
      )
    Json.fromFields(fields)

  private def groupJson(
      tenantSeg: String,
      g: RbacGroup,
      memberList: List[(String, String)]
  ): Json =
    val members = memberList.map { (uid, username) =>
      Json.fromFields(
        List("value" -> Json.fromString(uid), "display" -> Json.fromString(username))
      )
    }
    val fields = List(
      "schemas"     -> Json.arr(Json.fromString(GroupSchema)),
      "id"          -> Json.fromString(g.id),
      "displayName" -> Json.fromString(g.name),
      "members"     -> Json.arr(members*)
    ) ++ g.externalId.map(e => "externalId" -> Json.fromString(e)) ++ List(
      "meta" -> Json.fromFields(
        List(
          "resourceType" -> Json.fromString("Group"),
          "location"     -> Json.fromString(s"/api/scim/v2/$tenantSeg/Groups/${g.id}")
        )
      )
    )
    Json.fromFields(fields)

  // ---------------- shared plumbing ----------------

  /** Resolve the path tenant (id or display name) and apply the session/PAT scope gate. */
  private def admit(
      tenantRaw: String,
      apiKey: Option[String]
  )(scopeOf: SessionScope.Resolver): Either[(StatusCode, String), String] =
    HandlerResolvers.resolveTenantId(sup, tenantRaw) match
      case None     => Left(scimError(StatusCode.NotFound, s"tenant not found: $tenantRaw"))
      case Some(id) =>
        TenantScopeCheck.reject(apiKey, id)(scopeOf) match
          case Some((status, e)) => Left(scimError(status, e.message))
          case None              => Right(id)

  /** The one filter shape IdPs actually send: `attr eq "value"`. Attribute names are
    * case-insensitive per RFC 7643. Anything else is refused as invalidFilter.
    */
  private def parseEqFilter(filter: String): Either[(StatusCode, String), (String, String)] =
    val Eq = """\s*(\w+)\s+eq\s+"((?:[^"\\]|\\.)*)"\s*""".r
    filter match
      case Eq(attr, value) => Right((attr.toLowerCase(Locale.ROOT), value.replace("\\\"", "\"")))
      case _               =>
        Left(
          scimError(
            StatusCode.BadRequest,
            s"unsupported filter (only 'attr eq \"value\"' is supported): $filter",
            Some("invalidFilter")
          )
        )

  private def page[A](items: List[A], startIndex: Option[Int], count: Option[Int]): (Int, List[A]) =
    val start = math.max(1, startIndex.getOrElse(1))
    val n     = math.min(MaxPageCount, math.max(0, count.getOrElse(100)))
    (start, items.slice(start - 1, start - 1 + n))

  private def parseBody(body: String): Either[(StatusCode, String), JsonObject] =
    io.circe.parser
      .parse(body)
      .toOption
      .flatMap(_.asObject)
      .toRight(scimError(StatusCode.BadRequest, "request body is not a JSON object"))

  private def str(o: JsonObject, key: String): Option[String] =
    o(key).flatMap(_.asString)

  private def bool(o: JsonObject, key: String): Option[Boolean] =
    o(key).flatMap(asBool)

  /** Entra sends booleans as the strings "True"/"False" in PATCH values; accept both. */
  private def asBool(j: Json): Option[Boolean] =
    j.asBoolean.orElse(j.asString.flatMap(_.toLowerCase(Locale.ROOT) match
      case "true"  => Some(true)
      case "false" => Some(false)
      case _       => None))

  /** Primary email first, else the first entry, from a SCIM `emails` array. */
  private def emailOf(o: JsonObject): Option[String] =
    o("emails").flatMap(_.asArray).flatMap { arr =>
      val objs = arr.toList.flatMap(_.asObject)
      objs
        .find(_("primary").flatMap(asBool).contains(true))
        .orElse(objs.headOption)
        .flatMap(e => e("value").flatMap(_.asString))
    }

  private def supError(e: SupervisorError): (StatusCode, String) = e match
    case SupervisorError.NotFound(msg)     => scimError(StatusCode.NotFound, msg)
    case SupervisorError.InvalidEmail(msg) =>
      scimError(StatusCode.BadRequest, msg, Some("invalidValue"))
    case SupervisorError.BuiltinProtected(msg) =>
      scimError(StatusCode.Conflict, msg, Some("mutability"))
    case SupervisorError.ReservedName(msg) =>
      scimError(StatusCode.BadRequest, msg, Some("invalidValue"))
    case other => scimError(StatusCode.BadRequest, other.toString, Some("invalidValue"))

  private def randomPassword(): String = SessionTokenStore.randomSecret()

  private def userInTenant(tenantId: String, id: String): Option[RbacUser] =
    sup.findUserById(id).filter(_.tenant.contains(tenantId))

  private def groupInTenant(tenantId: String, id: String): Option[RbacGroup] =
    sup.listGroups(tenantId).find(_.id == id)

  /** Per-group membership read for the single-group endpoints (list uses the batch path). */
  private def membersOf(g: RbacGroup): List[(String, String)] =
    sup.usersInGroup(g.id).flatMap(uid => sup.findUserById(uid).map(u => uid -> u.username))

  /** Response body for a group AFTER mutations: re-read so a just-written externalId or member set
    * is echoed, not the pre-mutation snapshot (IdPs reconcile the echo).
    */
  private def freshGroupJson(tenantRaw: String, tenantId: String, g: RbacGroup): String =
    val fresh = groupInTenant(tenantId, g.id).getOrElse(g)
    groupJson(tenantRaw, fresh, membersOf(fresh)).noSpaces

  // ---------------- Users ----------------

  def listUsers(
      tenantRaw: String,
      filter: Option[String],
      startIndex: Option[Int],
      count: Option[Int],
      apiKey: Option[String]
  )(scopeOf: SessionScope.Resolver): Out[String] = IO.blocking {
    admit(tenantRaw, apiKey)(scopeOf).flatMap { tenantId =>
      val all      = sup.listUsers(Some(tenantId)).sortBy(_.username)
      val filtered = filter match
        case None    => Right(all)
        case Some(f) =>
          parseEqFilter(f).map {
            case ("username", v)   => all.filter(_.username == v)
            case ("externalid", v) => all.filter(_.externalId.contains(v))
            case _                 => Nil
          }
      filtered.map { us =>
        val (start, pageItems) = page(us, startIndex, count)
        listResponse(us.size, start, pageItems.map(userJson(tenantRaw, _)))
      }
    }
  }

  def getUser(tenantRaw: String, id: String, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[String] = IO.blocking {
    admit(tenantRaw, apiKey)(scopeOf).flatMap { tenantId =>
      userInTenant(tenantId, id)
        .map(u => userJson(tenantRaw, u).noSpaces)
        .toRight(scimError(StatusCode.NotFound, s"user not found: $id"))
    }
  }

  def createUser(tenantRaw: String, body: String, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[String] =
    IO.blocking(admit(tenantRaw, apiKey)(scopeOf).flatMap(t => parseBody(body).map((t, _))))
      .flatMap {
        case Left(err)              => IO.pure(Left(err))
        case Right((tenantId, obj)) =>
          str(obj, "userName") match
            case None => IO.pure(Left(missing("userName")))
            case Some(userName) if sup.findUser(Some(tenantId), userName).isDefined =>
              IO.pure(
                Left(
                  scimError(
                    StatusCode.Conflict,
                    s"userName already exists: $userName",
                    Some("uniqueness")
                  )
                )
              )
            case Some(userName) =>
              val active     = bool(obj, "active").getOrElse(true)
              val externalId = str(obj, "externalId")
              val password   = str(obj, "password").getOrElse(randomPassword())
              // failIfExists: the pre-check above gives the clean 409; this closes the
              // check-then-act race so a retried/concurrent POST can never rotate an
              // existing user's password or demote their kind. active: false persists
              // atomically -- no enabled window, nothing to roll back.
              sup
                .createUser(
                  Some(tenantId),
                  userName,
                  password,
                  "user",
                  userStore,
                  // The IdP owns group membership and pushes it afterwards.
                  memberships = UserMemberships.IdpManaged,
                  email = emailOf(obj),
                  enabled = active,
                  failIfExists = true
                )
                .map {
                  case Left(SupervisorError.InvalidArgument(msg))
                      if msg.startsWith("user already exists") =>
                    Left(scimError(StatusCode.Conflict, msg, Some("uniqueness")))
                  case Left(e)  => Left(supError(e))
                  case Right(u) =>
                    externalId.foreach(e => sup.setUserExternalId(u.id, Some(e)))
                    audit.rest(
                      apiKey,
                      "control-plane",
                      AuditActions.UserCreate,
                      "ok",
                      tenant = Some(tenantId),
                      target = Some(userName),
                      detail = Map("via" -> "scim")
                    )
                    val fresh = sup.findUserById(u.id).getOrElse(u)
                    Right(userJson(tenantRaw, fresh).noSpaces)
                }
      }

  def replaceUser(tenantRaw: String, id: String, body: String, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[String] =
    IO.blocking(admit(tenantRaw, apiKey)(scopeOf).flatMap(t => parseBody(body).map((t, _))))
      .flatMap {
        case Left(err)              => IO.pure(Left(err))
        case Right((tenantId, obj)) =>
          userInTenant(tenantId, id) match
            case None    => IO.pure(Left(scimError(StatusCode.NotFound, s"user not found: $id")))
            case Some(u) =>
              str(obj, "userName") match
                case Some(name) if name != u.username =>
                  IO.pure(
                    Left(
                      scimError(StatusCode.BadRequest, "userName is immutable", Some("mutability"))
                    )
                  )
                case _ =>
                  // Divergence from strict PUT-replace semantics, on purpose: a body
                  // with no emails array leaves the stored address untouched instead
                  // of clearing it. IdP profiles routinely omit the mapping, and the
                  // email feeds password reset and lockout recovery -- silently
                  // NULLing it would strand those users.
                  applyUserChanges(
                    tenantRaw,
                    tenantId,
                    u,
                    active = bool(obj, "active").orElse(Some(true)),
                    email = emailOf(obj).map(e => Some(e)),
                    externalId = Some(str(obj, "externalId")),
                    password = str(obj, "password"),
                    apiKey = apiKey
                  )
      }

  /** PATCH: apply the operations IdPs actually send. Unknown attribute paths are logged and ignored
    * rather than refused: Entra and Okta both include cosmetic attributes (displayName, name.*)
    * that have no QoD backing, and failing the whole operation would wedge their retry loops.
    */
  def patchUser(tenantRaw: String, id: String, body: String, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[String] =
    IO.blocking(admit(tenantRaw, apiKey)(scopeOf).flatMap(t => parseBody(body).map((t, _))))
      .flatMap {
        case Left(err)              => IO.pure(Left(err))
        case Right((tenantId, obj)) =>
          userInTenant(tenantId, id) match
            case None    => IO.pure(Left(scimError(StatusCode.NotFound, s"user not found: $id")))
            case Some(u) =>
              val ops = obj("Operations").flatMap(_.asArray).map(_.toList.flatMap(_.asObject))
              ops match
                case None      => IO.pure(Left(missing("Operations")))
                case Some(ops) =>
                  var active: Option[Boolean]            = None
                  var email: Option[Option[String]]      = None
                  var externalId: Option[Option[String]] = None
                  var badRename                          = false
                  ops.foreach { op =>
                    val kind  = str(op, "op").map(_.toLowerCase(Locale.ROOT)).getOrElse("replace")
                    val path  = str(op, "path").map(_.toLowerCase(Locale.ROOT))
                    val value = op("value")
                    (kind, path) match
                      case ("remove", Some("externalid")) => externalId = Some(None)
                      case (_, Some("active"))            =>
                        value.flatMap(asBool).foreach(b => active = Some(b))
                      case (_, Some("externalid")) =>
                        value.flatMap(_.asString).foreach(v => externalId = Some(Some(v)))
                      case (_, Some("username")) =>
                        value.flatMap(_.asString).foreach(v => badRename = v != u.username)
                      case (_, Some(p)) if p.startsWith("emails") =>
                        // The value is the address itself (`emails[type eq "work"].value`), one
                        // email object, or the whole `emails` array (Okta, Entra).
                        value
                          .flatMap(_.asString)
                          .orElse(
                            value.flatMap(_.asObject).flatMap(o => o("value").flatMap(_.asString))
                          )
                          .orElse(
                            value.filter(_.isArray).flatMap(a => emailOf(JsonObject("emails" -> a)))
                          )
                          .foreach(v => email = Some(Some(v)))
                      case (_, None) =>
                        // No path: value is a partial resource object.
                        value.flatMap(_.asObject).foreach { vo =>
                          bool(vo, "active").foreach(b => active = Some(b))
                          str(vo, "externalId").foreach(v => externalId = Some(Some(v)))
                          emailOf(vo).foreach(v => email = Some(Some(v)))
                          str(vo, "userName").foreach(v => badRename = v != u.username)
                        }
                      case (_, Some(other)) =>
                        logger.debug(s"scim: ignoring unsupported user patch path '$other'")
                  }
                  if badRename then
                    IO.pure(
                      Left(
                        scimError(
                          StatusCode.BadRequest,
                          "userName is immutable",
                          Some("mutability")
                        )
                      )
                    )
                  else
                    applyUserChanges(
                      tenantRaw,
                      tenantId,
                      u,
                      active,
                      email,
                      externalId,
                      password = None,
                      apiKey = apiKey
                    )
      }

  private def applyUserChanges(
      tenantRaw: String,
      tenantId: String,
      u: RbacUser,
      active: Option[Boolean],
      email: Option[Option[String]],
      externalId: Option[Option[String]],
      password: Option[String],
      apiKey: Option[String]
  ): Out[String] =
    sup
      .updateUserPassword(u.id, password, None, userStore, email = email, enabled = active)
      .map {
        case Left(e)  => Left(supError(e))
        case Right(_) =>
          externalId.foreach(e => sup.setUserExternalId(u.id, e))
          audit.rest(
            apiKey,
            "control-plane",
            AuditActions.UserUpdate,
            "ok",
            tenant = Some(tenantId),
            target = Some(u.username),
            detail = Map("via" -> "scim")
          )
          val fresh = sup.findUserById(u.id).getOrElse(u)
          Right(userJson(tenantRaw, fresh).noSpaces)
      }

  def deleteUser(tenantRaw: String, id: String, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[Unit] =
    IO.blocking(admit(tenantRaw, apiKey)(scopeOf)).flatMap {
      case Left(err)       => IO.pure(Left(err))
      case Right(tenantId) =>
        userInTenant(tenantId, id) match
          case None    => IO.pure(Left(scimError(StatusCode.NotFound, s"user not found: $id")))
          case Some(u) =>
            sup.deleteUser(id).map {
              case Left(e)  => Left(supError(e))
              case Right(_) =>
                audit.rest(
                  apiKey,
                  "control-plane",
                  AuditActions.UserDelete,
                  "ok",
                  tenant = Some(tenantId),
                  target = Some(u.username),
                  detail = Map("via" -> "scim")
                )
                Right(())
            }
    }

  // ---------------- Groups ----------------

  def listGroups(
      tenantRaw: String,
      filter: Option[String],
      startIndex: Option[Int],
      count: Option[Int],
      apiKey: Option[String]
  )(scopeOf: SessionScope.Resolver): Out[String] = IO.blocking {
    admit(tenantRaw, apiKey)(scopeOf).flatMap { tenantId =>
      val all      = sup.listGroups(tenantId).sortBy(_.name)
      val filtered = filter match
        case None    => Right(all)
        case Some(f) =>
          parseEqFilter(f).map {
            case ("displayname", v) => all.filter(_.name == v)
            case ("externalid", v)  => all.filter(_.externalId.contains(v))
            case _                  => Nil
          }
      filtered.map { gs =>
        val (start, pageItems) = page(gs, startIndex, count)
        // One users query + one membership query for the whole page, instead of
        // 1 + members-per-group queries per group.
        val users      = sup.listUsers(Some(tenantId))
        val nameById   = users.map(u => u.id -> u.username).toMap
        val byUser     = sup.groupsByUsers(users.map(_.id))
        val membersFor = pageItems
          .map(g => g.id -> byUser.collect { case (uid, gids) if gids.contains(g.id) => uid })
          .toMap
        listResponse(
          gs.size,
          start,
          pageItems.map { g =>
            val ms = membersFor
              .getOrElse(g.id, Nil)
              .toList
              .flatMap(uid => nameById.get(uid).map(uid -> _))
              .sortBy(_._2)
            groupJson(tenantRaw, g, ms)
          }
        )
      }
    }
  }

  def getGroup(tenantRaw: String, id: String, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[String] = IO.blocking {
    admit(tenantRaw, apiKey)(scopeOf).flatMap { tenantId =>
      groupInTenant(tenantId, id)
        .map(g => groupJson(tenantRaw, g, membersOf(g)).noSpaces)
        .toRight(scimError(StatusCode.NotFound, s"group not found: $id"))
    }
  }

  def createGroup(tenantRaw: String, body: String, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[String] =
    IO.blocking(admit(tenantRaw, apiKey)(scopeOf).flatMap(t => parseBody(body).map((t, _))))
      .flatMap {
        case Left(err)              => IO.pure(Left(err))
        case Right((tenantId, obj)) =>
          str(obj, "displayName") match
            case None => IO.pure(Left(missing("displayName")))
            case Some(name) if sup.listGroups(tenantId).exists(_.name == name) =>
              IO.pure(
                Left(
                  scimError(
                    StatusCode.Conflict,
                    s"displayName already exists: $name",
                    Some("uniqueness")
                  )
                )
              )
            case Some(name) if unknownMember(tenantId, memberIds(obj)).isDefined =>
              IO.pure(Left(unknownMember(tenantId, memberIds(obj)).get))
            case Some(name) =>
              sup.createGroup(tenantId, name, None).flatMap {
                case Left(e)  => IO.pure(Left(supError(e)))
                case Right(g) =>
                  // Members and externalId in one write; a refused one (a member deleted since
                  // the check) takes the just-created group back out, so a refused POST leaves
                  // nothing behind.
                  sup
                    .setGroupState(g.id, memberIds(obj).toSet, Some(str(obj, "externalId")))
                    .flatMap[Either[(StatusCode, String), Unit]] {
                      case Left(e) =>
                        sup.deleteGroup(g.id).attempt.as(Left(supError(e)))
                      case Right(()) => IO.pure(Right(()))
                    }
                    .map {
                      case Left(err) => Left(err)
                      case Right(()) =>
                        audit.rest(
                          apiKey,
                          "control-plane",
                          AuditActions.GroupCreate,
                          "ok",
                          tenant = Some(tenantId),
                          target = Some(name),
                          detail = Map("via" -> "scim")
                        )
                        Right(freshGroupJson(tenantRaw, tenantId, g))
                    }
              }
      }

  def replaceGroup(tenantRaw: String, id: String, body: String, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[String] =
    IO.blocking(admit(tenantRaw, apiKey)(scopeOf).flatMap(t => parseBody(body).map((t, _))))
      .flatMap {
        case Left(err)              => IO.pure(Left(err))
        case Right((tenantId, obj)) =>
          groupInTenant(tenantId, id) match
            case None    => IO.pure(Left(scimError(StatusCode.NotFound, s"group not found: $id")))
            case Some(g) =>
              str(obj, "displayName") match
                case Some(name) if name != g.name =>
                  IO.pure(
                    Left(
                      scimError(
                        StatusCode.BadRequest,
                        "displayName is immutable",
                        Some("mutability")
                      )
                    )
                  )
                case _ if unknownMember(tenantId, memberIds(obj)).isDefined =>
                  IO.pure(Left(unknownMember(tenantId, memberIds(obj)).get))
                case _ =>
                  sup
                    .setGroupState(g.id, memberIds(obj).toSet, Some(str(obj, "externalId")))
                    .map(_.left.map(supError))
                    .map {
                      case Left(err) => Left(err)
                      case Right(()) =>
                        auditGroupUpdate(apiKey, tenantId, g)
                        Right(freshGroupJson(tenantRaw, tenantId, g))
                    }
      }

  def patchGroup(tenantRaw: String, id: String, body: String, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[String] =
    IO.blocking(admit(tenantRaw, apiKey)(scopeOf).flatMap(t => parseBody(body).map((t, _))))
      .flatMap {
        case Left(err)              => IO.pure(Left(err))
        case Right((tenantId, obj)) =>
          groupInTenant(tenantId, id) match
            case None    => IO.pure(Left(scimError(StatusCode.NotFound, s"group not found: $id")))
            case Some(g) =>
              val ops = obj("Operations").flatMap(_.asArray).map(_.toList.flatMap(_.asObject))
              ops match
                case None      => IO.pure(Left(missing("Operations")))
                case Some(ops) =>
                  // Every op is planned and checked before the first one is applied, so a
                  // refused PATCH changes nothing (RFC 7644 3.5.2: the operations are atomic).
                  planGroupPatch(tenantId, g, ops) match
                    case Left(err)   => IO.pure(Left(err))
                    case Right(plan) =>
                      val (members, externalId) =
                        finalGroupState(sup.usersInGroup(g.id).toSet, plan)
                      sup.setGroupState(g.id, members, externalId).map(_.left.map(supError)).map {
                        case Left(err) => Left(err)
                        case Right(()) =>
                          auditGroupUpdate(apiKey, tenantId, g)
                          Right(freshGroupJson(tenantRaw, tenantId, g))
                      }
      }

  def deleteGroup(tenantRaw: String, id: String, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[Unit] =
    IO.blocking(admit(tenantRaw, apiKey)(scopeOf)).flatMap {
      case Left(err)       => IO.pure(Left(err))
      case Right(tenantId) =>
        groupInTenant(tenantId, id) match
          case None    => IO.pure(Left(scimError(StatusCode.NotFound, s"group not found: $id")))
          case Some(g) =>
            sup.deleteGroup(id).map {
              case Left(e)  => Left(supError(e))
              case Right(_) =>
                audit.rest(
                  apiKey,
                  "control-plane",
                  AuditActions.GroupDelete,
                  "ok",
                  tenant = Some(tenantId),
                  target = Some(g.name),
                  detail = Map("via" -> "scim")
                )
                Right(())
            }
    }

  private def auditGroupUpdate(apiKey: Option[String], tenantId: String, g: RbacGroup): Unit =
    // No group.update audit action exists; membership moves are the meaningful mutation.
    audit.rest(
      apiKey,
      "control-plane",
      AuditActions.MembershipUserGroupAdd,
      "ok",
      tenant = Some(tenantId),
      target = Some(g.name),
      detail = Map("via" -> "scim")
    )

  /** One change a group PATCH makes, planned before any is applied. */
  private enum GroupStep:
    case Add(ids: List[String])
    case Replace(ids: List[String])
    case Remove(ids: List[String])
    case RemoveAll
    case ExternalId(value: Option[String])

  /** The refusal for the first id in `ids` that is no user of the tenant, if any. */
  private def unknownMember(tenantId: String, ids: List[String]): Option[(StatusCode, String)] =
    ids
      .find(uid => userInTenant(tenantId, uid).isEmpty)
      .map(uid =>
        scimError(StatusCode.BadRequest, s"member not found in tenant: $uid", Some("invalidValue"))
      )

  /** A group PATCH's operations as steps, or the refusal of the first op that cannot apply. RFC
    * 7644: attribute names and operators are case-insensitive, so the path is matched lowercased
    * and the member filter regex carries (?i); the filter matches the raw path, so a mixed-case
    * member id survives intact. An op with no path carries a partial Group: its `externalId` and
    * `members` (replaced, or added for an `add`), and a `displayName`, which may not change.
    */
  private def planGroupPatch(
      tenantId: String,
      g: RbacGroup,
      ops: List[JsonObject]
  ): Either[(StatusCode, String), List[GroupStep]] =
    val MemberFilter = """(?i)members\[value eq "([^"]+)"\]""".r
    val planned: Either[(StatusCode, String), List[GroupStep]] =
      ops.foldLeft[Either[(StatusCode, String), List[GroupStep]]](Right(Nil)) { (acc, op) =>
        acc.flatMap { steps =>
          val kind    = str(op, "op").map(_.toLowerCase(Locale.ROOT)).getOrElse("replace")
          val rawPath = str(op, "path").getOrElse("")
          val path    = rawPath.toLowerCase(Locale.ROOT)
          val ids     = op("value").map(memberIdsOf).getOrElse(Nil)
          val next: Either[(StatusCode, String), List[GroupStep]] = (kind, path) match
            case ("add", "members")     => Right(List(GroupStep.Add(ids)))
            case ("replace", "members") => Right(List(GroupStep.Replace(ids)))
            case ("remove", _) if MemberFilter.findFirstMatchIn(rawPath).isDefined =>
              Right(
                List(GroupStep.Remove(List(MemberFilter.findFirstMatchIn(rawPath).get.group(1))))
              )
            case ("remove", "members") =>
              Right(List(if ids.nonEmpty then GroupStep.Remove(ids) else GroupStep.RemoveAll))
            case (_, "externalid") =>
              Right(
                List(
                  GroupStep.ExternalId(
                    if kind == "remove" then None else op("value").flatMap(_.asString)
                  )
                )
              )
            case ("remove", "") =>
              // RFC 7644 3.5.2.2: a remove names its target.
              Left(scimError(StatusCode.BadRequest, "remove requires a path", Some("noTarget")))
            case (_, "") =>
              val vo = op("value").flatMap(_.asObject)
              if vo.flatMap(str(_, "displayName")).exists(_ != g.name) then
                Left(
                  scimError(StatusCode.BadRequest, "displayName is immutable", Some("mutability"))
                )
              else
                Right(vo.toList.flatMap { o =>
                  str(o, "externalId").map(v => GroupStep.ExternalId(Some(v))).toList ++
                    o("members")
                      .map(memberIdsOf)
                      .map(m => if kind == "add" then GroupStep.Add(m) else GroupStep.Replace(m))
                      .toList
                })
            case (_, other) =>
              logger.debug(s"scim: ignoring unsupported group patch '$other'")
              Right(Nil)
          next.map(steps ++ _)
        }
      }
    planned.flatMap { steps =>
      val joining = steps.flatMap {
        case GroupStep.Add(ids)     => ids
        case GroupStep.Replace(ids) => ids
        case _                      => Nil
      }
      unknownMember(tenantId, joining).toLeft(steps)
    }

  /** The group's members and externalId once `steps` applied in order to `current` (externalId None =
    * unchanged): what [[ai.starlake.quack.ondemand.PoolSupervisor.setGroupState]] writes in one go.
    */
  private def finalGroupState(
      current: Set[String],
      steps: List[GroupStep]
  ): (Set[String], Option[Option[String]]) =
    steps.foldLeft((current, Option.empty[Option[String]])) { case ((members, ext), step) =>
      step match
        case GroupStep.Add(ids)      => (members ++ ids, ext)
        case GroupStep.Replace(ids)  => (ids.toSet, ext)
        case GroupStep.Remove(ids)   => (members -- ids, ext)
        case GroupStep.RemoveAll     => (Set.empty, ext)
        case GroupStep.ExternalId(v) => (members, Some(v))
    }

  private def memberIds(o: JsonObject): List[String] =
    o("members").map(memberIdsOf).getOrElse(Nil)

  private def memberIdsOf(j: Json): List[String] =
    j.asArray
      .map(_.toList.flatMap(_.asObject).flatMap(m => m("value").flatMap(_.asString)))
      .getOrElse(Nil)

  // ---------------- Discovery ----------------

  def serviceProviderConfig(tenantRaw: String, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[String] = IO.blocking {
    admit(tenantRaw, apiKey)(scopeOf).map { _ =>
      Json
        .fromFields(
          List(
            "schemas" -> Json.arr(
              Json.fromString("urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig")
            ),
            "patch"  -> supported(true),
            "bulk"   -> supported(false),
            "filter" -> Json.fromFields(
              List(
                "supported"  -> Json.fromBoolean(true),
                "maxResults" -> Json.fromInt(MaxPageCount)
              )
            ),
            "changePassword"        -> supported(false),
            "sort"                  -> supported(false),
            "etag"                  -> supported(false),
            "authenticationSchemes" -> Json.arr(
              Json.fromFields(
                List(
                  "type"        -> Json.fromString("oauthbearertoken"),
                  "name"        -> Json.fromString("Bearer token"),
                  "description" -> Json.fromString(
                    "QoD personal access token as an OAuth bearer token"
                  )
                )
              )
            )
          )
        )
        .noSpaces
    }
  }

  def resourceTypes(tenantRaw: String, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[String] = IO.blocking {
    admit(tenantRaw, apiKey)(scopeOf).map { _ =>
      Json
        .arr(
          resourceType("User", "Users", UserSchema),
          resourceType("Group", "Groups", GroupSchema)
        )
        .noSpaces
    }
  }

  def schemas(tenantRaw: String, apiKey: Option[String])(
      scopeOf: SessionScope.Resolver
  ): Out[String] = IO.blocking {
    admit(tenantRaw, apiKey)(scopeOf).map { _ =>
      // Minimal but valid: IdPs use this to discover attribute support.
      Json
        .arr(
          Json.fromFields(
            List(
              "id"   -> Json.fromString(UserSchema),
              "name" -> Json.fromString("User")
            )
          ),
          Json.fromFields(
            List(
              "id"   -> Json.fromString(GroupSchema),
              "name" -> Json.fromString("Group")
            )
          )
        )
        .noSpaces
    }
  }

  private def supported(v: Boolean): Json =
    Json.fromFields(List("supported" -> Json.fromBoolean(v)))

  private def resourceType(name: String, endpoint: String, schema: String): Json =
    Json.fromFields(
      List(
        "schemas" -> Json.arr(
          Json.fromString("urn:ietf:params:scim:schemas:core:2.0:ResourceType")
        ),
        "id"       -> Json.fromString(name),
        "name"     -> Json.fromString(name),
        "endpoint" -> Json.fromString(s"/$endpoint"),
        "schema"   -> Json.fromString(schema)
      )
    )

  private def missing(attr: String): (StatusCode, String) =
    scimError(StatusCode.BadRequest, s"missing required attribute: $attr", Some("invalidValue"))
