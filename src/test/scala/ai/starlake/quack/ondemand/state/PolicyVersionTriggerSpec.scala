package ai.starlake.quack.ondemand.state

import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.Connection
import scala.util.Try

/** Liquibase 0050: every RBAC write bumps exactly its own tenant's policy version, once per row, to
  * a value above every version issued before, and nothing else does. Needs the local Postgres of
  * TestPostgres.
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

  /** The last value the version sequence issued. */
  private def lastIssued(c: Connection): Long =
    val st = c.createStatement()
    try
      val rs = st.executeQuery("SELECT last_value FROM qodstate_policy_version_seq")
      rs.next()
      rs.getLong(1)
    finally st.close()

  /** Which tenants' versions moved while `sqls` ran, and how many versions the sequence issued
    * meanwhile (one per bumped row). A version never moves down, and one that moves lands above
    * every version issued before.
    */
  private def delta(c: Connection)(sqls: String*): (Set[String], Long) =
    val before = versions(c)
    val issued = lastIssued(c)
    exec(c)(sqls*)
    val after = versions(c)
    val moved = after.collect { case (t, v) if before.get(t).exists(_ != v) => t }.toSet
    after.foreach((t, v) => before.get(t).foreach(b => withClue(s"$t: ")(v should be >= b)))
    moved.foreach(t => withClue(s"$t: ")(after(t) should be > issued))
    (moved, lastIssued(c) - issued)

  private val AcmeOnce = (Set("acme"), 1L)
  private val Nothing  = (Set.empty[String], 0L)

  "policy_version" should "start a new tenant above every version issued" in withDb { (c, _) =>
    val issued = lastIssued(c)
    issued should be > 0L
    exec(c)("INSERT INTO qodstate_tenant (id, display_name) VALUES ('initech', 'initech')")
    versions(c)("initech") should be > issued
    versions(c)("initech") should be > versions(c).removed("initech").values.max
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
      ((Set("acme", "globex"), 2L))
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

  it should "leave a recreated id above the deleted row's last version" in withDb { (c, _) =>
    exec(c)("INSERT INTO qodstate_role (id, tenant_id, name) VALUES ('r-g2', 'globex', 'x')")
    val last = versions(c)("globex")
    exec(c)(
      "DELETE FROM qodstate_user WHERE tenant = 'globex'",
      "DELETE FROM qodstate_tenant WHERE id = 'globex'",
      "INSERT INTO qodstate_tenant (id, display_name) VALUES ('globex', 'globex')"
    )
    versions(c)("globex") should be > last
    versions(c)("globex") should be > versions(c)("acme")
  }

  "applying 0050 to a control plane that already holds tenants" should
    "give each existing tenant its own version, below the next one issued" in withDb { (c, _) =>
      // Undo 0050 on the populated database, then let Liquibase apply it again: the upgrade path.
      // 0051's and 0052's tables carry triggers on 0050's function, so they go first and come back
      // too (0052 also replaces the function, with its user_id arm).
      exec(c)(
        "DROP TABLE qodstate_user_s3_credential",
        "DELETE FROM databasechangelog WHERE id LIKE '0052-%'",
        "DROP TABLE qodstate_tenant_location",
        "DELETE FROM databasechangelog WHERE id LIKE '0051-%'"
      )
      val triggered = List(
        "qodstate_role",
        "qodstate_group",
        "qodstate_pool_permission",
        "qodstate_role_permission",
        "qodstate_role_column_policy",
        "qodstate_role_row_policy",
        "qodstate_user_role",
        "qodstate_group_role",
        "qodstate_user_group",
        "qodstate_user"
      )
      exec(c)(
        triggered.flatMap(t =>
          List(
            s"DROP TRIGGER qod_policy_version_ins_del ON $t",
            s"DROP TRIGGER qod_policy_version_upd ON $t"
          )
        ) ++ List(
          "DROP TRIGGER qod_policy_version_acl ON qodstate_tenant",
          "DROP FUNCTION qod_bump_policy_version()",
          "DROP FUNCTION qod_bump_tenant_policy_version()",
          "ALTER TABLE qodstate_tenant DROP COLUMN policy_version",
          "DROP SEQUENCE qodstate_policy_version_seq",
          "DELETE FROM databasechangelog WHERE id LIKE '0050-%'",
          "INSERT INTO qodstate_tenant (id, display_name) VALUES ('initech', 'initech')"
        )*
      )
      val url = c.getMetaData.getURL
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val v = versions(c)
      v.keySet shouldBe Set("acme", "globex", "initech")
      v.values.toSet.size shouldBe 3
      v.values.min should be > 0L
      v.values.max shouldBe lastIssued(c)
      delta(c)(
        "INSERT INTO qodstate_role (id, tenant_id, name) VALUES ('r-4', 'acme', 'w')"
      ) shouldBe
        AcmeOnce
    }

  "policyVersions" should "read every tenant's version" in withDb { (c, store) =>
    exec(c)("INSERT INTO qodstate_role (id, tenant_id, name) VALUES ('r-3', 'acme', 'z')")
    store.policyVersions() shouldBe versions(c)
    store.policyVersions()("acme") should be > store.policyVersions()("globex")
  }

  "a user's S3 credential" should "bump exactly its user's tenant, and a superuser's nothing" in
    withDb { (c, _) =>
      val id = "a" * 32
      delta(c)(
        "INSERT INTO qodstate_user_s3_credential (id, user_id, scope, access_key_id, " +
          s"secret_access_key) VALUES ('$id', 'u-a', 's3://acme-etl/', 'k', 's')"
      ) shouldBe AcmeOnce
      delta(c)(
        s"UPDATE qodstate_user_s3_credential SET secret_access_key = 't' WHERE id = '$id'"
      ) shouldBe AcmeOnce
      delta(c)(
        s"UPDATE qodstate_user_s3_credential SET secret_access_key = 't' WHERE id = '$id'"
      ) shouldBe Nothing
      delta(c)(s"DELETE FROM qodstate_user_s3_credential WHERE id = '$id'") shouldBe AcmeOnce
      exec(c)(
        "INSERT INTO qodstate_user (id, tenant, username, password_hash) " +
          "VALUES ('u-root', NULL, 'root', 'x')"
      )
      delta(c)(
        "INSERT INTO qodstate_user_s3_credential (id, user_id, scope, access_key_id, " +
          s"secret_access_key) VALUES ('${"b" * 32}', 'u-root', 's3://root-etl/', 'k', 's')"
      ) shouldBe Nothing
    }

  it should "go with its user, the user's own delete bumping the tenant" in withDb { (c, _) =>
    exec(c)(
      "INSERT INTO qodstate_user_s3_credential (id, user_id, scope, access_key_id, " +
        s"secret_access_key) VALUES ('${"c" * 32}', 'u-g', 's3://globex-etl/', 'k', 's')"
    )
    delta(c)("DELETE FROM qodstate_user WHERE id = 'u-g'")._1 shouldBe Set("globex")
    val st = c.createStatement()
    try
      val rs = st.executeQuery("SELECT count(*) FROM qodstate_user_s3_credential")
      rs.next()
      rs.getInt(1) shouldBe 0
    finally st.close()
  }

  /** Runs `sql` and checks that every tenant's version moved above every version issued before. */
  private def bumpsEveryTenant(c: Connection)(sql: String): Unit =
    val before = versions(c)
    val issued = lastIssued(c)
    exec(c)(sql)
    val after = versions(c)
    before.keySet.foreach { t =>
      withClue(s"$t after $sql: ")(after(t) should be > issued)
      withClue(s"$t after $sql: ")(after(t) should be > before(t))
    }

  // Liquibase 0054 (CONTRACT Addendum 4 item 23): the data folders are a deny set every tenant's
  // verdicts depend on.
  "a database data folder" should "bump every tenant when a tenant-db comes, goes or moves" in
    withDb { (c, _) =>
      bumpsEveryTenant(c)(
        "INSERT INTO qodstate_tenant_db (id, tenant_id, name, kind, data_path) " +
          "VALUES ('td-1', 'acme', 'acme_db', 'ducklake', '/data/acme_db/')"
      )
      bumpsEveryTenant(c)(
        "UPDATE qodstate_tenant_db SET data_path = '/data/elsewhere/' WHERE id = 'td-1'"
      )
      bumpsEveryTenant(c)(
        "UPDATE qodstate_tenant_db SET metastore_params = '{\"dataPath\":\"/x/\"}'::jsonb " +
          "WHERE id = 'td-1'"
      )
      delta(c)("UPDATE qodstate_tenant_db SET disabled = true WHERE id = 'td-1'") shouldBe Nothing
      bumpsEveryTenant(c)("DELETE FROM qodstate_tenant_db WHERE id = 'td-1'")
    }

  it should "bump every tenant when a managed prefix is added, purged or removed" in withDb {
    (c, _) =>
      bumpsEveryTenant(c)(
        "INSERT INTO qodstate_managed_prefix (id, tenant, tenant_db_name, prefix, created_at) " +
          "VALUES ('mp-1', 'acme', 'acme_db', 's3://b/acme_db-01234567/', now())"
      )
      // A tombstone keeps the prefix in the deny set until it is purged.
      delta(c)(
        "UPDATE qodstate_managed_prefix SET deleted_at = now(), purge_eligible_at = now() " +
          "WHERE id = 'mp-1'"
      ) shouldBe Nothing
      bumpsEveryTenant(c)("UPDATE qodstate_managed_prefix SET purged_at = now() WHERE id = 'mp-1'")
      bumpsEveryTenant(c)("DELETE FROM qodstate_managed_prefix WHERE id = 'mp-1'")
  }
