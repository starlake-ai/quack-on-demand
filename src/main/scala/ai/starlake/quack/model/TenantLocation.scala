package ai.starlake.quack.model

/** One local folder a tenant authorized for its non-superuser users' file access (in-engine
  * authorization, decision 15; `qodstate_tenant_location`, Liquibase 0051), under the operator's
  * root. `location` is stored as [[TenantLocationRules]] normalized it: an absolute folder ending
  * in `/`. S3 is not a tenant location: each user reaches S3 with keys of their own (decision 23,
  * [[UserS3Credential]]). `createdAt` is epoch milliseconds.
  */
final case class TenantLocation(
    id: String,
    tenantId: String,
    kind: String,
    location: String,
    createdAt: Long,
    createdBy: Option[String]
)

object TenantLocation:
  val Local: String = "local"
