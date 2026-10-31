package ai.starlake.quack.ondemand.bootstrap

import ai.starlake.quack.model.{Names, PoolKey, TenantDb}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.api.{
  CreatePoolRequest,
  ErrorResponse,
  PoolHandlers,
  TenantDbHandlers,
  TenantDbRequest,
  TenantHandlers,
  TenantRequest
}
import ai.starlake.quack.ondemand.api.Dtos.given
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.telemetry.AuditActor
import cats.data.EitherT
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import io.circe.{CursorOp, Decoder, DecodingFailure, Json, ParsingFailure}
import io.circe.parser.decode
import sttp.model.StatusCode

import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.Locale

/** Boot-time provisioning for `qod serve`. The launcher writes the three bodies its attach mode
  * posts (`tenant`, `database`, `pool`) to a spec file and passes the file's absolute path in
  * `QOD_SERVE_PROVISION` (env, or a JVM system property like `QOD_BOOTSTRAP_YAML`). The leader runs
  * this after `restore()` and before DuckLake init and reconcile
  * ([[ai.starlake.quack.boot.LeaderDuties]]), so what it creates is initialized and spawned in the
  * same boot.
  *
  * Add-only. Each object the spec names is created only when missing (the tenant by id, the
  * database by its composed name, the pool by tenant, database and name), through the same create
  * function as REST, as the manager itself (`SessionScope.Superuser`, audited as `system`). One
  * that exists and matches is left alone. One that exists and differs is a collision, refused with
  * what differs and the way out: a database of another kind, `dataPath` or `initSql` (what
  * `qod serve` compared before it provisioned at boot), or a pool name already serving another
  * database of the tenant. Nothing is ever updated or deleted, and the first refusal stops the run.
  *
  * The database body may carry object-store credentials. The spec is read once per run and never
  * logged, audited or echoed: log lines, audit rows and the outcome name objects and reasons only.
  * A collision's reason quotes the existing row, never the spec's kind, `dataPath` or `initSql`.
  *
  * The outcome goes to `serve-provision.outcome.json` beside the spec, written to a temporary file
  * and renamed: `{"status":"ok","created":[...]}` (only what this run created, possibly nothing) or
  * `{"status":"failed","step":"spec|tenant|database|pool","reason":"..."}`. Each creation is logged
  * at INFO and a refusal at ERROR. Nothing here ever fails the boot: the manager stays up so the
  * operator can read why and fix it.
  *
  * Unlike [[DemoBootstrapHook]], whose manifest import replaces rows wholesale and runs only on an
  * empty store, this adds to whatever the store already holds.
  */
object ServeProvisionHook extends LazyLogging:

  /** The variable naming the spec file's absolute path. */
  val EnvKey: String = "QOD_SERVE_PROVISION"

  /** The outcome file, written in the spec's directory. */
  val OutcomeFileName: String = "serve-provision.outcome.json"

  /** The spec: the bodies of `tenant/create`, `database/create` and `pool/create`. */
  final case class Spec(tenant: TenantRequest, database: TenantDbRequest, pool: CreatePoolRequest):
    // The database body may carry object-store credentials: a Spec is never printed whole.
    override def toString: String = "ServeProvisionHook.Spec(<redacted>)"

  private given Decoder[Spec] = Decoder.forProduct3("tenant", "database", "pool")(Spec.apply)

  /** Where a run stopped: the step and why. */
  final case class Refusal(step: String, reason: String)

  /** Right(what this run created, possibly nothing) or Left(the refusal that stopped it). */
  type Outcome = Either[Refusal, List[String]]

  /** One step: Right(Some(what it created)), Right(None) when it was already there, or the refusal.
    */
  private[bootstrap] type Step = IO[Either[Refusal, Option[String]]]

  /** A step whose own lookups throw stops the run at that step like a refusal, so the outcome is
    * still written; named by the exception's class only.
    */
  private[bootstrap] def guarded(step: String)(io: => Step): Step =
    IO.defer(io)
      .handleError(t => Left(Refusal(step, s"failed unexpectedly: ${t.getClass.getSimpleName}")))

  def run(
      env: String => Option[String],
      sup: PoolSupervisor,
      tenants: TenantHandlers,
      tenantDbs: TenantDbHandlers,
      pools: PoolHandlers
  ): IO[Unit] =
    env(EnvKey).map(_.trim).filter(_.nonEmpty) match
      case None       => IO.unit
      case Some(path) =>
        val spec = Paths.get(path).toAbsolutePath
        readSpec(spec)
          .flatMap {
            case Left(refusal) => IO.pure[Outcome](Left(refusal))
            case Right(s)      => provision(s, sup, tenants, tenantDbs, pools)
          }
          .flatTap {
            case Left(r) =>
              IO(logger.error(s"serve provision: the ${r.step} step was refused: ${r.reason}"))
            case Right(_) => IO.unit
          }
          .flatMap(outcome => IO.blocking(writeOutcome(spec, outcome)))
          // The class only: a message could quote what failed.
          .handleErrorWith(t =>
            IO(logger.error(s"serve provision: could not finish for $spec: ${t.getClass.getName}"))
          )

  /** The spec, or why it cannot be used. Never echoes the file's content: it may carry object-store
    * credentials.
    */
  private def readSpec(spec: Path): IO[Either[Refusal, Spec]] =
    IO.blocking(Files.readString(spec)).attempt.map {
      case Left(t) =>
        Left(
          Refusal("spec", s"cannot read the provisioning spec $spec: ${t.getClass.getSimpleName}")
        )
      case Right(body) =>
        decode[Spec](body).left.map {
          case _: ParsingFailure =>
            Refusal("spec", s"cannot read the provisioning spec $spec: not valid JSON")
          case d: DecodingFailure =>
            Refusal(
              "spec",
              s"cannot read the provisioning spec $spec: ${CursorOp.opsToPath(d.history)} is " +
                "missing or has the wrong type"
            )
        }
    }

  private def provision(
      spec: Spec,
      sup: PoolSupervisor,
      tenants: TenantHandlers,
      tenantDbs: TenantDbHandlers,
      pools: PoolHandlers
  ): IO[Outcome] =
    (for
      t <- EitherT(guarded("tenant")(tenantStep(spec.tenant, sup, tenants)))
      d <- EitherT(guarded("database")(databaseStep(spec.database, sup, tenantDbs)))
      p <- EitherT(guarded("pool")(poolStep(spec.pool, sup, pools)))
    yield List(t, d, p).flatten).value

  private def tenantStep(req: TenantRequest, sup: PoolSupervisor, tenants: TenantHandlers): Step =
    IO.delay(sup.getTenant(req.id)).flatMap {
      case Some(t) => kept(s"tenant ${t.id}")
      case None    =>
        created("tenant", tenants.createTenantAs(req, SessionScope.Superuser, AuditActor.System))(
          t => s"tenant ${t.id}"
        )
    }

  private def databaseStep(
      req: TenantDbRequest,
      sup: PoolSupervisor,
      tenantDbs: TenantDbHandlers
  ): Step =
    val tenant = req.tenant.toLowerCase(Locale.ROOT)
    IO.delay(
      Names
        .normalizeTenantDbName(req.tenant, req.name)
        .toOption
        .flatMap(sup.findTenantDb(req.tenant, _))
    ).flatMap {
      case Some(td) =>
        differs(td, req) match
          case Some(what) =>
            IO.pure(
              Left(
                Refusal(
                  "database",
                  s"$what; serve it under another name: qod serve <target> --name <other>"
                )
              )
            )
          case None => kept(s"database $tenant/${td.name}")
      case None =>
        created(
          "database",
          tenantDbs.createTenantDbAs(req, SessionScope.Superuser, AuditActor.System)
        )(td => s"database $tenant/${td.name}")
    }

  private def poolStep(req: CreatePoolRequest, sup: PoolSupervisor, pools: PoolHandlers): Step =
    val key = PoolKey(req.tenant, req.tenantDb, req.pool)
    IO.delay((sup.get(key), sup.findPoolKeyByTenantAndPoolName(key.tenant, key.pool))).flatMap {
      case (Some(_), _) => kept(s"pool $key")
      // Pool names are unique per tenant: this one already serves another database, so the
      // create could never succeed.
      case (None, Some(other)) =>
        IO.pure(
          Left(
            Refusal(
              "pool",
              s"pool '${key.pool}' already serves database '${other.tenantDb}', not " +
                s"'${key.tenantDb}'; serve it under another name: qod serve <target> " +
                "--pool <other>"
            )
          )
        )
      case (None, None) =>
        created("pool", pools.createPoolAs(req, SessionScope.Superuser, AuditActor.System))(_ =>
          s"pool $key"
        )
    }

  /** What makes an existing database another one than the spec's; None when kind, dataPath and
    * initSql all match. It quotes the existing row only: the reason lands in the ERROR log and the
    * outcome file, and nothing there echoes the spec.
    */
  private def differs(td: TenantDb, req: TenantDbRequest): Option[String] =
    val sameStorage = td.kind.wireValue == req.kind && td.dataPath == req.dataPath
    if sameStorage && td.initSql == req.initSql then None
    else if sameStorage then
      Some(
        s"database '${td.name}' already exists with the same dataPath and kind, but its views " +
          "point at different data (initSql differs)"
      )
    else
      Some(
        s"database '${td.name}' already serves kind='${td.kind.wireValue}' " +
          s"dataPath='${td.dataPath}', not the kind and dataPath this serve asked for"
      )

  /** An object the spec names that already exists: left as it is. */
  private def kept(name: String): Step =
    IO(logger.info(s"serve provision: $name already exists, left as is")).as(Right(None))

  /** One create through the shared REST path; its refusal, or any failure, stops the run. A 4xx
    * refusal is reported by its message, which names keys and objects, never values. A 5xx (or an
    * exception) can carry an exception's message, which could quote the spec or a row: it is
    * reported by its error code (or class) only, the detail staying in the manager log.
    */
  private def created[A](
      step: String,
      create: IO[Either[(StatusCode, ErrorResponse), A]]
  )(name: A => String): Step =
    create.attempt.flatMap {
      case Right(Right(a)) =>
        val n = name(a)
        IO(logger.info(s"serve provision: created $n")).as(Right(Some(n)))
      case Right(Left((status, err))) =>
        val reason =
          if status.isClientError then err.message
          else s"failed unexpectedly: ${err.error} (see the manager log)"
        IO.pure(Left(Refusal(step, reason)))
      case Left(t) =>
        IO.pure(Left(Refusal(step, s"failed unexpectedly: ${t.getClass.getSimpleName}")))
    }

  private def outcomeJson(outcome: Outcome): Json = outcome match
    case Right(created) =>
      Json.obj(
        "status"  -> Json.fromString("ok"),
        "created" -> Json.fromValues(created.map(Json.fromString))
      )
    case Left(r) =>
      Json.obj(
        "status" -> Json.fromString("failed"),
        "step"   -> Json.fromString(r.step),
        "reason" -> Json.fromString(r.reason)
      )

  /** Temporary file in the same directory, then a rename, so a reader never sees half a file. */
  private def writeOutcome(spec: Path, outcome: Outcome): Unit =
    val target = spec.resolveSibling(OutcomeFileName)
    val tmp    = Files.createTempFile(target.getParent, OutcomeFileName, ".tmp")
    try
      Files.writeString(tmp, outcomeJson(outcome).noSpaces)
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    finally Files.deleteIfExists(tmp)
