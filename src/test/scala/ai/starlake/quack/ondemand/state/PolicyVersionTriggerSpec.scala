package ai.starlake.quack.ondemand.state

import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.Connection
import scala.util.Try

/** Liquibase 0050: every RBAC write bumps exactly its own tenant's policy version, once per row,
  * and nothing else does. Needs the local Postgres of TestPostgres.
  */
class PolicyVersionTriggerSpec extends AnyFlatSpec with Matchers:
  TestPostgres.dropStrayTestDatabases("qodpv")

  private def withDb(test: (Connection, PostgresControlPlaneStore) => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodpv_${java.util.UUID.randomUUID().toString.take(8)}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val store = new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try
        val c = store.jdbcDataSource.getConnection
        try
          exec(c)(
            "INSERT INTO qodstate_tenant (id, display_name) VALUES ('acme', 'acme'), ('globex', 'globex')",
            "INSERT INTO qodstate_role (id, tenant_id, name) VALUES ('r-a', 'acme', 'analyst'), ('r-g', 'globex', 'analyst')",
            "INSERT INTO qodstate_group (id, tenant_id, name) VALUES ('g-a', 'acme', 'bi'), ('g-g', 'globex', 'bi')",
            "INSERT INTO qodstate_user (id, tenant, username, password_hash) VALUES ('u-a', 'acme', 'alice', 'x'), ('u-g', 'globex', 'gina', 'x')"
          )
          test(c, store)
        finally c.close()
      finally store.close()
    finally Try(TestPostgres.dropDatabase(dbName))

  private def exec(c: Connection)(sqls: String*): Unit =
    sqls.foreach { sql =>
      val st = c.createStatement()
      try st.execute(sql)
      finally st.close()
    }

  private def versions(c: Connection): Map[String, Long] =
    val st = c.createStatement()
    try
      val rs = st.executeQuery("SELECT id, policy_version FROM qodstate_tenant")
      val b  = Map.newBuilder[String, Long]
      while rs.next() do b += rs.getString(1) -> rs.getLong(2)
      b.result()
    finally st.close()

  /** How much each tenant's version moved while `sqls` ran. */
  private def delta(c: Connection)(sqls: String*): Map[String, Long] =
    val before = versions(c)
    exec(c)(sqls*)
    val after = versions(c)
    after.map((t, v) => t -> (v - before.getOrElse(t, 0L)))

  private val AcmeOnce = Map("acme" -> 1L, "globex" -> 0L)
  private val Nothing  = Map("acme" -> 0L, "globex" -> 0L)

  "policy_version" should "start at 0 for a new tenant" in withDb { (c, _) =>
    exec(c)("INSERT INTO qodstate_tenant (id, display_name) VALUES ('initech', 'initech')")
    versions(c)("initech") shouldBe 0L
  }

  "a role" should "bump its tenant on insert, update and delete, and no other tenant" in
    withDb { (c, _) =>
      delta(c)(
        "INSERT INTO qodstate_role (id, tenant_id, name) VALUES ('r-2', 'acme', 'x')"
      ) shouldBe
        AcmeOnce
      delta(c)("UPDATE qodstate_role SET name = 'y' WHERE id = 'r-2'") shouldBe AcmeOnce
      delta(c)("DELETE FROM qodstate_role WHERE id = 'r-2'") shouldBe AcmeOnce
    }

  "a group" should "bump its tenant on insert, update and delete" in withDb { (c, _) =>
    delta(c)(
      "INSERT INTO qodstate_group (id, tenant_id, name) VALUES ('g-2', 'acme', 'x')"
    ) shouldBe
      AcmeOnce
    delta(c)("UPDATE qodstate_group SET description = 'd' WHERE id = 'g-2'") shouldBe AcmeOnce
    delta(c)("DELETE FROM qodstate_group WHERE id = 'g-2'") shouldBe AcmeOnce
  }

  "a pool permission" should "bump its tenant on insert, update and delete" in withDb { (c, _) =>
    delta(c)(
      "INSERT INTO qodstate_pool_permission (id, tenant_id, group_id) VALUES ('pp-1', 'acme', 'g-a')"
    ) shouldBe AcmeOnce
    delta(c)(
      "UPDATE qodstate_pool_permission SET group_id = NULL, user_id = 'u-a' WHERE id = 'pp-1'"
    ) shouldBe AcmeOnce
    delta(c)("DELETE FROM qodstate_pool_permission WHERE id = 'pp-1'") shouldBe AcmeOnce
  }

  "a role permission" should "bump the tenant of its role" in withDb { (c, _) =>
    delta(c)(
      "INSERT INTO qodstate_role_permission (id, role_id, catalog_name, schema_name, table_name, verb) " +
        "VALUES ('p-1', 'r-a', 'acme_db', 'main', '*', 'RO')"
    ) shouldBe AcmeOnce
    delta(c)("UPDATE qodstate_role_permission SET verb = 'ALL' WHERE id = 'p-1'") shouldBe AcmeOnce
    delta(c)("DELETE FROM qodstate_role_permission WHERE id = 'p-1'") shouldBe AcmeOnce
  }

  "column and row policies" should "bump the tenant of their role" in withDb { (c, _) =>
    delta(c)(
      "INSERT INTO qodstate_role_column_policy (id, role_id, catalog_name, schema_name, table_name, " +
        "column_name, action) VALUES ('cp-1', 'r-a', 'acme_db', 'main', 't', 'ssn', 'deny')"
    ) shouldBe AcmeOnce
    delta(c)("UPDATE qodstate_role_column_policy SET action = 'mask' WHERE id = 'cp-1'") shouldBe
      AcmeOnce
    delta(c)("DELETE FROM qodstate_role_column_policy WHERE id = 'cp-1'") shouldBe AcmeOnce
    delta(c)(
      "INSERT INTO qodstate_role_row_policy (id, role_id, catalog_name, schema_name, table_name, " +
        "predicate_sql) VALUES ('rp-1', 'r-a', 'acme_db', 'main', 't', 'region = 1')"
    ) shouldBe AcmeOnce
    delta(c)(
      "UPDATE qodstate_role_row_policy SET predicate_sql = 'region = 2' WHERE id = 'rp-1'"
    ) shouldBe
      AcmeOnce
    delta(c)("DELETE FROM qodstate_role_row_policy WHERE id = 'rp-1'") shouldBe AcmeOnce
  }

  "the membership edges" should "bump the tenant of the role or group they reach" in withDb {
    (c, _) =>
      delta(c)("INSERT INTO qodstate_user_role (user_id, role_id) VALUES ('u-a', 'r-a')") shouldBe
        AcmeOnce
      delta(c)("DELETE FROM qodstate_user_role WHERE user_id = 'u-a'") shouldBe AcmeOnce
      delta(c)("INSERT INTO qodstate_user_group (user_id, group_id) VALUES ('u-a', 'g-a')") shouldBe
        AcmeOnce
      delta(c)("DELETE FROM qodstate_user_group WHERE user_id = 'u-a'") shouldBe AcmeOnce
      delta(c)("INSERT INTO qodstate_group_role (group_id, role_id) VALUES ('g-a', 'r-a')") shouldBe
        AcmeOnce
      delta(c)("DELETE FROM qodstate_group_role WHERE group_id = 'g-a'") shouldBe AcmeOnce
  }

  "a user" should "bump its tenant on insert, delete and an authorization-relevant update" in
    withDb { (c, _) =>
      delta(c)(
        "INSERT INTO qodstate_user (id, tenant, username, password_hash) VALUES ('u-2', 'acme', 'bob', 'x')"
      ) shouldBe AcmeOnce
      delta(c)("UPDATE qodstate_user SET enabled = false WHERE id = 'u-2'") shouldBe AcmeOnce
      delta(c)("UPDATE qodstate_user SET kind = 'admin' WHERE id = 'u-2'") shouldBe AcmeOnce
      delta(c)("UPDATE qodstate_user SET username = 'robert' WHERE id = 'u-2'") shouldBe AcmeOnce
      delta(c)("DELETE FROM qodstate_user WHERE id = 'u-2'") shouldBe AcmeOnce
    }

  it should "not bump on a login counter, a password or a timestamp" in withDb { (c, _) =>
    delta(c)(
      "UPDATE qodstate_user SET failed_attempts = failed_attempts + 1, locked_at = now() WHERE id = 'u-a'",
      "UPDATE qodstate_user SET password_hash = 'y', updated_at = now() WHERE id = 'u-a'"
    ) shouldBe Nothing
  }

  it should "bump both tenants when it moves between them" in withDb { (c, _) =>
    delta(c)("UPDATE qodstate_user SET tenant = 'globex' WHERE id = 'u-a'") shouldBe
      Map("acme" -> 1L, "globex" -> 1L)
  }

  it should "bump nothing for a superuser row" in withDb { (c, _) =>
    delta(c)(
      "INSERT INTO qodstate_user (id, tenant, username, password_hash) VALUES ('u-su', NULL, 'root', 'x')",
      "UPDATE qodstate_user SET enabled = false WHERE id = 'u-su'",
      "DELETE FROM qodstate_user WHERE id = 'u-su'"
    ) shouldBe Nothing
  }

  "an update that changes nothing" should "not bump" in withDb { (c, _) =>
    delta(c)(
      "UPDATE qodstate_role SET name = name WHERE id = 'r-a'",
      "UPDATE qodstate_group SET name = name WHERE id = 'g-a'"
    ) shouldBe Nothing
  }

  "the tenant's ACL and OPA settings" should "each bump it, other tenant columns not" in withDb {
    (c, _) =>
      delta(c)("UPDATE qodstate_tenant SET acl_mode = 'opa' WHERE id = 'acme'") shouldBe AcmeOnce
      delta(c)("UPDATE qodstate_tenant SET opa_url = 'http://opa:8181' WHERE id = 'acme'") shouldBe
        AcmeOnce
      delta(c)(
        "UPDATE qodstate_tenant SET opa_policy_path = 'acme/authz' WHERE id = 'acme'"
      ) shouldBe
        AcmeOnce
      delta(c)("UPDATE qodstate_tenant SET opa_token = 't' WHERE id = 'acme'") shouldBe AcmeOnce
      delta(c)(
        "UPDATE qodstate_tenant SET opa_send_statement_text = true WHERE id = 'acme'"
      ) shouldBe
        AcmeOnce
      delta(c)(
        "UPDATE qodstate_tenant SET display_name = 'Acme', disabled = true WHERE id = 'acme'",
        "UPDATE qodstate_tenant SET acl_mode = 'opa' WHERE id = 'acme'"
      ) shouldBe Nothing
  }

  "deleting a tenant" should "cascade into its RBAC rows without an error" in withDb { (c, _) =>
    exec(c)(
      "INSERT INTO qodstate_role_permission (id, role_id, catalog_name, schema_name, table_name, verb) " +
        "VALUES ('p-g', 'r-g', 'g_db', 'main', '*', 'RO')",
      "DELETE FROM qodstate_user WHERE tenant = 'globex'",
      "DELETE FROM qodstate_tenant WHERE id = 'globex'"
    )
    versions(c).keySet shouldBe Set("acme")
  }

  "policyVersions" should "read every tenant's version" in withDb { (c, store) =>
    exec(c)("INSERT INTO qodstate_role (id, tenant_id, name) VALUES ('r-3', 'acme', 'z')")
    store.policyVersions() shouldBe versions(c)
    store.policyVersions()("acme") should be > store.policyVersions()("globex")
  }
