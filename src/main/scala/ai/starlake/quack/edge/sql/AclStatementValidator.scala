package ai.starlake.quack.edge.sql

import ai.starlake.acl.api.{AclSql, AclSqlConfig, SqlContext}
import ai.starlake.acl.model.{TenantId, UserIdentity}
import ai.starlake.acl.store.{AclChangeDetector, AclStore, AclStoreFactory}
import ai.starlake.acl.watcher.TenantListener
import ai.starlake.gizmo.proxy.catalog.DuckLakeCatalogResolver
import ai.starlake.gizmo.proxy.config.{AclConfig, SessionConfig}
import com.typesafe.scalalogging.LazyLogging

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

class AclStatementValidator(aclConfig: AclConfig, sessionConfig: SessionConfig)
    extends StatementValidator,
      AutoCloseable,
      LazyLogging:

  private val (aclSql, changeDetector, store, catalogResolver) = initAclSql()

  private def initAclSql(): (AclSql, Option[AclChangeDetector], AclStore, DuckLakeCatalogResolver) =
    val (aclStore, detector) = AclStoreFactory.create(aclConfig)
    val resolver = new DuckLakeCatalogResolver(sessionConfig)
    val viewResolver = (tenant: TenantId, ref: ai.starlake.acl.model.TableRef) =>
      resolver.resolve(tenant, ref)
    val aclSqlConfig = AclSqlConfig(maxTenants = Some(aclConfig.maxTenants))
    val api = new AclSql(aclStore, viewResolver, aclSqlConfig)

    if aclConfig.watcher.enabled then
      val listener = new TenantListener:
        override def onInvalidate(tenantId: TenantId): Unit =
          logger.info(s"ACL grants reloaded for tenant: $tenantId")
          api.invalidateTenant(tenantId)
        override def onNewTenant(tenantId: TenantId): Unit =
          logger.info(s"New ACL tenant detected: $tenantId")
        override def onTenantDeleted(tenantId: TenantId): Unit =
          logger.warn(s"ACL tenant deleted: $tenantId")
          api.invalidateTenant(tenantId)
      detector.start(listener)
      logger.info(s"ACL change detector started for ${aclConfig.basePath}")
      (api, Some(detector), aclStore, resolver)
    else
      logger.info("ACL change detection disabled")
      detector.close()
      (api, None, aclStore, resolver)

  override def close(): Unit =
    changeDetector.foreach { d =>
      logger.info("Stopping ACL change detector")
      d.close()
    }
    try store.close()
    catch case e: Exception => logger.warn(s"Error closing ACL store: ${e.getMessage}")
    try catalogResolver.close()
    catch case e: Exception => logger.warn(s"Error closing catalog resolver: ${e.getMessage}")

  override def validate(context: ValidationContext): ValidationResult =
    // If the ACL base path does not exist, allow all queries (backward compatible)
    val basePath = aclConfig.basePath
    if !basePath.startsWith("s3://") && !basePath.startsWith("gs://") && !basePath.startsWith("az://") then
      if !Files.exists(Paths.get(basePath)) then
        logger.debug(s"ACL base path '$basePath' does not exist, allowing query")
        return Allowed

    val tenantStr = sessionConfig.aclTenant

    TenantId.parse(tenantStr) match
      case Left(err) =>
        logger.error(s"Invalid tenant ID '$tenantStr': $err")
        Denied(s"Invalid tenant: $err")
      case Right(tenantId) =>
        val groups = extractGroups(context)
        val user = UserIdentity(context.username, groups)

        val sqlContext = SqlContext(
          defaultDatabase = Some(sessionConfig.slProjectId).filter(_.nonEmpty),
          dialect = aclConfig.dialect
        )

        logger.info(
          s"ACL check: tenant=$tenantStr, user=${context.username}, groups=${groups.mkString(",")}, database=${sessionConfig.slProjectId}"
        )

        aclSql.checkAccess(tenantId, context.statement, user, trace = false, sqlContext) match
          case Right(outcome) if outcome.isAllowed =>
            logger.info(s"ACL ALLOWED: ${outcome.summary}")
            Allowed
          case Right(outcome) =>
            val reason = outcome.summary
            logger.warn(s"ACL DENIED: $reason")
            Denied(reason)
          case Left(error) =>
            logger.error(s"ACL error: ${error.message}")
            Denied(s"Authorization error: ${error.message}")

  private def extractGroups(context: ValidationContext): Set[String] =
    context.claims.get(aclConfig.groupsClaim) match
      case Some(claim) =>
        try
          Option(claim.asList(classOf[String]))
            .map(_.asScala.toSet)
            .getOrElse(Set.empty)
        catch
          case _: Exception =>
            Option(claim.asString())
              .map(_.split(",").map(_.trim).toSet)
              .getOrElse(Set.empty)
      case None =>
        // Fallback: use the "role" claim if present
        context.claims
          .get("role")
          .flatMap(c => Option(c.asString()))
          .map(Set(_))
          .getOrElse(Set.empty)
