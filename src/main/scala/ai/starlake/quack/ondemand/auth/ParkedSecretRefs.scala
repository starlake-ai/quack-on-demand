package ai.starlake.quack.ondemand.auth

import ai.starlake.quack.model.Tenant
import com.typesafe.scalalogging.LazyLogging

/** The secret references changeset 0048 parked. A `clientSecretRef` stored before 0048 has no
  * recorded author, and a reference names a secret of the manager's own environment, which only a
  * superuser may point a tenant at, so the changeset moved each one to [[ParkedKey]]. Nothing reads
  * that key, so the tenant's SSO gets no secret from it. The first `tenant/setAuth` write whose
  * result holds a secret source (the reference restored by a superuser, or the tenant's own client
  * secret) drops the key, and with it this report; until then every boot logs one ERROR per tenant
  * holding it.
  */
object ParkedSecretRefs extends LazyLogging:

  val ParkedKey: String = "clientSecretRefUnconfirmed"

  /** One message per tenant holding [[ParkedKey]], in the order given: the tenant, the parked
    * reference and the restore step. A control character of the stored reference prints as `?`, so
    * a stored value cannot shape the log.
    */
  def messages(tenants: List[Tenant]): List[String] =
    tenants.flatMap { t =>
      t.authConfig.get(ParkedKey).map { raw =>
        val ref = raw.map(c => if c.isControl then '?' else c)
        s"tenant '${t.id}': clientSecretRef '$ref' was stored before changeset 0048, which " +
          "parked it because its author is not recorded; nothing reads it, so the tenant's SSO " +
          "gets no client secret from it. Confirm the reference is meant for this tenant, then " +
          s"a superuser runs: qod tenant set-auth ${t.id} --auth-provider ${t.authProvider} " +
          "--auth-config issuerUrl=... --auth-config clientId=... " +
          s"--auth-config clientSecretRef=$ref"
      }
    }

  /** Logs every [[messages]] line at ERROR: the root log level defaults to ERROR (`QOD_LOG_LEVEL`),
    * and operator-facing misconfiguration is logged at ERROR so it survives that quiet default.
    */
  def report(tenants: List[Tenant]): Unit = messages(tenants).foreach(m => logger.error(m))
