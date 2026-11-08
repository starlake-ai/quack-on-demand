package ai.starlake.quack.ondemand.security

import ai.starlake.quack.model.{Pool, RoleDistribution, Tenant, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.state.{
  ControlPlaneSnapshot,
  LiquibaseRunner,
  PostgresControlPlaneStore
}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.sql.DriverManager
import scala.util.{Try, Using}

class LiveAuditFactsSpec extends AnyFlatSpec with Matchers:

  private def withDb(test: (PostgresControlPlaneStore, String) => Unit): Unit =
    if !TestPostgres.reachable then cancel("local Postgres not reachable (SL_TEST_PG_*); skipping")
    val db = s"qodaudit_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$db"""")
    try
      val url = TestPostgres.dbUrl(db)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      test(new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass), db)
    finally Try(TestPostgres.dropDatabase(db))

  private val Seed =
    """INSERT INTO qodstate_tenant (id, display_name) VALUES ('acme', 'acme');
      |INSERT INTO qodstate_role (id, tenant_id, name, builtin) VALUES
      |  ('r-all', 'acme', 'qod_all_tables', true), ('r-an', 'acme', 'analyst', false);
      |INSERT INTO qodstate_group (id, tenant_id, name, builtin) VALUES ('g-all', 'acme', 'qod_all_pools', true);
      |INSERT INTO qodstate_user (id, tenant, username, password_hash, kind, enabled) VALUES
      |  ('u1', 'acme', 'wide', 'x', 'user', true),
      |  ('u2', 'acme', 'narrow', 'x', 'user', true),
      |  ('u3', 'acme', 'gone', 'x', 'user', false),
      |  ('u5', 'acme', 'off-wide', 'x', 'user', false);
      |INSERT INTO qodstate_user_role (user_id, role_id) VALUES ('u1', 'r-all'), ('u2', 'r-all'), ('u2', 'r-an'), ('u5', 'r-all');
      |INSERT INTO qodstate_user_group (user_id, group_id) VALUES ('u1', 'g-all'), ('u2', 'g-all'), ('u5', 'g-all');
      |INSERT INTO qodstate_pat (id, user_id, name, token_hash, created_at, expires_at, revoked_at) VALUES
      |  ('p1', 'u3', 'ci', 'h1', now(), NULL, NULL),
      |  ('p2', 'u3', 'revoked', 'h2', now(), NULL, now()),
      |  ('p3', 'u3', 'expired', 'h3', now(), now() - interval '1 day', NULL),
      |  ('p4', 'u1', 'live-enabled', 'h4', now(), NULL, NULL);
      |INSERT INTO qodstate_tenant_db (id, tenant_id, name, kind, encrypted) VALUES
      |  ('d1', 'acme', 'acme_sales', 'ducklake', false), ('d2', 'acme', 'acme_vault', 'ducklake', true);
      |INSERT INTO qodstate_tenant_db (id, tenant_id, name, kind, encrypted) VALUES
      |  ('d4', 'acme', 'acme_mem', 'memory', false);
      |INSERT INTO qodstate_tenant_db (id, tenant_id, name, kind, encrypted, branch_of) VALUES
      |  ('d3', 'acme', 'acme_sales__br_b1', 'ducklake', false, 'd1');
      |INSERT INTO qodstate_federated_source (id, tenant_db_id, alias, source_type, read_only) VALUES
      |  ('s1', 'd1', 'pg', 'postgres', false), ('s2', 'd1', 'ro', 'postgres', true),
      |  ('s3', 'd1', 'bare', 'postgres', false);
      |INSERT INTO qodstate_federated_secret (id, federated_source_id, name, value) VALUES
      |  ('x1', 's1', 'password', 'v'), ('x2', 's2', 'password', 'v');
      |INSERT INTO qodstate_branch (id, tenant, parent_db_id, parent_db_name, name, tenant_db_id,
      |  tenant_db_name, pool_name, data_path, fork_snapshot, owner_user, status) VALUES
      |  ('b1', 'acme', 'd1', 'acme_sales', 'feature', 'd3', 'acme_sales__br_b1', '__br_b1',
      |   '/srv/data/sales__br_b1/', 1, 'wide', 'open'),
      |  ('b2', 'acme', 'd1', 'acme_sales', 'done', 'd3', 'acme_sales__br_b2', '__br_b2',
      |   '/srv/data/sales__br_b2/', 1, 'wide', 'merged');
      |INSERT INTO qodstate_branch (id, tenant, parent_db_id, parent_db_name, name, tenant_db_id,
      |  tenant_db_name, pool_name, data_path, fork_snapshot, owner_user, status, expires_at) VALUES
      |  ('b3', 'acme', 'd1', 'acme_sales', 'ttl', 'd3', 'acme_sales__br_b3', '__br_b3',
      |   '/srv/data/sales__br_b3/', 1, 'wide', 'open', now() + interval '1 day');
      |INSERT INTO qodstate_fleet_server (name, advertise_host, node_port, approved, approved_by) VALUES
      |  ('srv-1', '10.0.0.1', 21900, true, 'upgrade');
      |INSERT INTO qodstate_fleet_server (name, advertise_host, node_port, approved, approved_by, approved_source) VALUES
      |  ('srv-2', '10.0.0.2', 21900, true, 'admin', '10.0.0.2');
      |INSERT INTO qodstate_fleet_server (name, advertise_host, node_port, approved) VALUES
      |  ('srv-3', '10.0.0.3', 21900, false);""".stripMargin

  private def facts(store: PostgresControlPlaneStore, tds: List[TenantDb] = Nil) =
    LiveAuditFacts(
      store.jdbcDataSource,
      () => ControlPlaneSnapshot(tenantDbs = tds),
      td => td.dataPath,
      t => t == "acme"
    )

  "LiveAuditFacts" should "read each stored fact by name and count" in withDb { (store, db) =>
    TestPostgres.psql(db, Seed)
    val f = facts(store)
    f.permissiveBuiltinUsers() shouldBe List("acme" -> 1)
    f.dormantPatsOfDisabledUsers() shouldBe 1
    f.writableSourcesWithSecrets() shouldBe List("acme/acme_sales/pg")
    f.liveBranchesWithoutTtl() shouldBe List("acme/acme_sales/feature")
    f.unboundApprovedServers() shouldBe List("srv-1")
    f.unencryptedDatabases() shouldBe List("acme" -> 1)
    f.oidcTenants() shouldBe Nil // the snapshot passed holds no tenant
  }

  it should "list only the tenants whose sign-in is OIDC" in withDb { (store, _) =>
    val f = LiveAuditFacts(
      store.jdbcDataSource,
      () => ControlPlaneSnapshot(tenants = List(Tenant("globex"), Tenant("acme"))),
      td => td.dataPath,
      t => t == "acme"
    )
    f.oidcTenants() shouldBe List("acme")
  }

  it should "find local data paths and untrusted initSql from the snapshot" in withDb {
    (store, _) =>
      val local =
        TenantDb("d1", "acme", "acme_sales", TenantDbKind.DuckLake, Map.empty, "/srv/data/sales")
      val remote =
        TenantDb("d2", "acme", "acme_vault", TenantDbKind.DuckLake, Map.empty, "s3://bucket/vault/")
      facts(store, List(local, remote)).localDatabases() shouldBe List("acme/acme_sales")
      val untrusted   = local.copy(initSql = "SET threads = 2", initSqlTrusted = false)
      val trusted     = remote.copy(initSql = "SET threads = 2", initSqlTrusted = true)
      val otherTenant = untrusted.copy(id = "d9", tenantId = "globex")
      val pool        =
        Pool("p1", "acme", "d2", "bi", 1, RoleDistribution(0, 0, 1), initSql = "SET threads = 1")
      val withPool = LiveAuditFacts(
        store.jdbcDataSource,
        () =>
          ControlPlaneSnapshot(
            tenantDbs = List(untrusted, trusted, otherTenant),
            pools = List(pool)
          ),
        td => td.dataPath,
        _ => false
      )
      withPool.untrustedInitSql() shouldBe List(
        "acme/database 'acme_sales'",
        "acme/pool 'bi' of database 'acme_vault'",
        "globex/database 'acme_sales'"
      )
  }

  it should "say whether the control-plane login is a superuser" in withDb { (store, _) =>
    val expected = Using.resource(
      DriverManager.getConnection(
        TestPostgres.dbUrl("postgres"),
        TestPostgres.pgUser,
        TestPostgres.pgPass
      )
    ) { c =>
      Using.resource(
        c.createStatement()
          .executeQuery("SELECT rolsuper FROM pg_roles WHERE rolname = current_user")
      ) { rs =>
        rs.next()
        rs.getBoolean(1)
      }
    }
    facts(store).controlPlaneLoginIsSuperuser() shouldBe expected
  }
