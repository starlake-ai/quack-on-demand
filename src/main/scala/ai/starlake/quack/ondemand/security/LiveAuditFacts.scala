package ai.starlake.quack.ondemand.security

import ai.starlake.quack.NodeFilesConfig
import ai.starlake.quack.model.{Pool, Tenant, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.initsql.InitSqlTrustReport
import java.sql.{Connection, ResultSet}
import javax.sql.DataSource
import scala.util.Using

/** Read-only answers from the control plane: SQL on its pool (each query under a 5 s timeout) for
  * the stored rows, and the supervisor's cached tenants, tenant-dbs and pools (`tenants`,
  * `tenantDbs`, `pools`) for what the manager derives from them, so a request reads no snapshot of
  * the store. Counts and names only.
  */
final case class LiveAuditFacts(
    ds: DataSource,
    tenants: () => List[Tenant],
    tenantDbs: () => List[TenantDb],
    pools: () => List[Pool],
    dataPathOf: TenantDb => String,
    isOidcTenant: String => Boolean
) extends AuditFacts:

  private def rows[A](sql: String)(read: ResultSet => A): List[A] =
    Using.resource(ds.getConnection) { (c: Connection) =>
      c.setReadOnly(true)
      Using.resource(c.createStatement()) { st =>
        st.setQueryTimeout(5)
        Using.resource(st.executeQuery(sql)) { rs =>
          Iterator.continually(rs).takeWhile(_.next()).map(read).toList
        }
      }
    }

  def controlPlaneLoginIsSuperuser(): Boolean =
    rows("SELECT rolsuper FROM pg_roles WHERE rolname = current_user")(_.getBoolean(1)).headOption
      .contains(true)

  def permissiveBuiltinUsers(): List[(String, Int)] =
    rows(
      """SELECT u.tenant, count(*) FROM qodstate_user u
        |WHERE u.tenant IS NOT NULL AND u.enabled
        |  AND EXISTS (SELECT 1 FROM qodstate_user_role ur JOIN qodstate_role r ON r.id = ur.role_id
        |              WHERE ur.user_id = u.id AND r.builtin AND r.name = 'qod_all_tables')
        |  AND NOT EXISTS (SELECT 1 FROM qodstate_user_role ur JOIN qodstate_role r ON r.id = ur.role_id
        |                  WHERE ur.user_id = u.id AND NOT (r.builtin AND r.name = 'qod_all_tables'))
        |  AND EXISTS (SELECT 1 FROM qodstate_user_group ug JOIN qodstate_group g ON g.id = ug.group_id
        |              WHERE ug.user_id = u.id AND g.builtin AND g.name = 'qod_all_pools')
        |  AND NOT EXISTS (SELECT 1 FROM qodstate_user_group ug JOIN qodstate_group g ON g.id = ug.group_id
        |                  WHERE ug.user_id = u.id AND NOT (g.builtin AND g.name = 'qod_all_pools'))
        |GROUP BY u.tenant ORDER BY u.tenant""".stripMargin
    )(rs => rs.getString(1) -> rs.getInt(2))

  def dormantPatsOfDisabledUsers(): Int =
    rows(
      """SELECT count(*) FROM qodstate_pat p JOIN qodstate_user u ON u.id = p.user_id
        |WHERE NOT u.enabled AND p.revoked_at IS NULL AND (p.expires_at IS NULL OR p.expires_at > now())""".stripMargin
    )(_.getInt(1)).headOption.getOrElse(0)

  def writableSourcesWithSecrets(): List[String] =
    rows(
      """SELECT d.tenant_id, d.name, s.alias FROM qodstate_federated_source s
        |JOIN qodstate_tenant_db d ON d.id = s.tenant_db_id
        |WHERE NOT s.read_only
        |  AND EXISTS (SELECT 1 FROM qodstate_federated_secret x WHERE x.federated_source_id = s.id)
        |ORDER BY 1, 2, 3""".stripMargin
    )(rs => s"${rs.getString(1)}/${rs.getString(2)}/${rs.getString(3)}")

  def liveBranchesWithoutTtl(): List[String] =
    rows(
      """SELECT tenant, parent_db_name, name FROM qodstate_branch
        |WHERE status IN ('open', 'proposed') AND expires_at IS NULL ORDER BY 1, 2, 3""".stripMargin
    )(rs => s"${rs.getString(1)}/${rs.getString(2)}/${rs.getString(3)}")

  def unboundApprovedServers(): List[String] =
    rows(
      "SELECT name FROM qodstate_fleet_server WHERE approved AND approved_source IS NULL ORDER BY name"
    )(_.getString(1))

  def unencryptedDatabases(): List[(String, Int)] =
    rows(
      """SELECT tenant_id, count(*) FROM qodstate_tenant_db
        |WHERE kind IN ('ducklake', 'duckdb-file') AND NOT encrypted AND branch_of IS NULL
        |GROUP BY tenant_id ORDER BY tenant_id""".stripMargin
    )(rs => rs.getString(1) -> rs.getInt(2))

  def localDatabases(): List[String] =
    tenantDbs()
      .filter(td =>
        NodeFilesConfig
          .localDataPath(td.kind == TenantDbKind.InMemory, dataPathOf(td))
          .isDefined
      )
      .map(td => s"${td.tenantId}/${td.name}")
      .sorted

  def untrustedInitSql(): List[String] =
    InitSqlTrustReport
      .untrusted(tenantDbs(), pools())
      .map(r => s"${r.tenant}/${r.label}")
      .sorted

  def oidcTenants(): List[String] =
    tenants().map(_.id).filter(isOidcTenant).sorted
