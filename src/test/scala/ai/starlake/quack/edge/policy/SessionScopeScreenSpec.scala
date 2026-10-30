package ai.starlake.quack.edge.policy

import ai.starlake.quack.edge.sql.{
  AccessGate,
  Allowed,
  CatalogWriteScreen,
  Denied,
  GateOutcome,
  PostgresAclValidator,
  ValidationContext
}

import ai.starlake.acl.model.Config
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacRole, RbacUser, RolePermission}
import ai.starlake.quack.route.StatementClassifier
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The ACL qualifies an unqualified table name against the pool's default schema, and the router
  * pins that schema with a `USE <db>.<schema>` prelude. A statement that moves the session's schema
  * or search path INSIDE the same batch runs after that prelude, so the node resolves later bare
  * names somewhere the ACL never checked. Every batch shape below was confirmed against a real
  * DuckDB 1.5.6 and 2.0 CLI to read `db.other.t` (or another catalog's `t`) after `USE db.def`,
  * while the validator authorized `db.def.t`.
  */
class SessionScopeScreenSpec extends AnyFlatSpec with Matchers with OptionValues:

  // ---- the screen itself ---------------------------------------------------------------------

  private val bypassBatches = List(
    "SET search_path='db.other'; SELECT * FROM t",
    "SET search_path = 'db.other'; SELECT * FROM t",
    "SET search_path TO 'db.other'; SELECT * FROM t",
    "SET search_path=db.other; SELECT * FROM t",
    "SET SESSION search_path='db.other'; SELECT * FROM t",
    "SET search_path='db.other,db.def'; SELECT * FROM t",
    "SET search_path='oc.main'; SELECT * FROM t",
    "SET search_path='other'; SELECT * FROM t",
    "SET \"search_path\"='db.other'; SELECT * FROM t",
    "SET\"search_path\"='db.other'; SELECT * FROM t",
    "set Search_Path='db.other'; select * from t",
    "SET schema='other'; SELECT * FROM t",
    "SET SESSION schema='other'; SELECT * FROM t",
    "SET schema='db.other'; SELECT * FROM t",
    "PRAGMA search_path='db.other'; SELECT * FROM t",
    "RESET search_path; SELECT * FROM t",
    "RESET schema; SELECT * FROM t",
    "RESET ALL; SELECT * FROM t",
    "USE db.other; SELECT * FROM t",
    "USE other; SELECT * FROM t",
    "USE oc; SELECT * FROM t",
    "USE\"db\".\"other\"; SELECT * FROM t",
    "INSERT INTO t VALUES (1); USE db.other; DELETE FROM t",
    // trailing: harmless on a fresh node connection, but it would leak into a connection the
    // session keeps (a client transaction on the native front door), so it is refused too
    "SELECT * FROM t; USE db.other",
    // hidden from a first-token check by leading trivia
    "/* c */ SET search_path='db.other'; SELECT * FROM t",
    "-- c\nUSE db.other; SELECT * FROM t",
    "\u00a0\u200bSET search_path='db.other'; SELECT * FROM t",
    "SET/**/search_path='db.other'; SELECT * FROM t",
    "SET\u00a0search_path='db.other'; SELECT * FROM t",
    // the separator a naive quote tracker would swallow
    "SELECT $$'$$; USE db.other; SELECT * FROM t",
    "SELECT e'\\''; USE db.other; SELECT * FROM t",
    "SELECT 1 /* a /* b */ ; c */; USE db.other; SELECT * FROM t",
    // a `$` that continues an identifier opens no dollar quote, so the trailing `$$` hides nothing
    "SELECT 1 AS x$$; SET search_path='db.other'; SELECT * FROM t; -- $$",
    "SELECT 1 AS x$$; SET schema='other'; SELECT * FROM t; -- $$",
    "SELECT 1 AS x$a$; USE other; SELECT * FROM t; -- $a$",
    "SELECT 1 AS x$$; SET search_path='db.other'; DELETE FROM t; -- $$",
    // DuckDB 2.0: a backtick quotes the setting name, glued to the keyword or not, and EXPLAIN
    // ANALYZE executes the SET it carries (each run on the 2.0 CLI, moving the search path)
    "SET `search_path` = 'db.other'; SELECT * FROM t",
    "SET`search_path`='db.other'; SELECT * FROM t",
    "SET SESSION`schema`='other'; SELECT * FROM t",
    "RESET `search_path`; SELECT * FROM t",
    "PRAGMA `search_path`='db.other'; SELECT * FROM t",
    "EXPLAIN ANALYZE SET search_path = 'db.other'; SELECT * FROM t",
    "EXPLAIN (ANALYZE) SET schema = 'other'; SELECT * FROM t"
  )

  "SessionScopeScreen" should "refuse every batch that moves the schema or search path" in:
    bypassBatches.foreach { sql =>
      withClue(sql)(SessionScopeScreen.find(sql) shouldBe defined)
    }

  it should "tell the caller to send the statement on its own" in:
    val reason = SessionScopeScreen.find("SET search_path='db.other'; SELECT * FROM t").value
    reason should include("own statement")
    reason should include("SET search_path='db.other'")

  it should "admit a lone USE / SET search_path / SET schema / RESET, with or without a semicolon" in:
    List(
      "USE db.other",
      "USE db.other;",
      "USE db.other; -- switch",
      "SET search_path='db.other'",
      "SET schema='other';",
      "RESET search_path",
      "PRAGMA search_path='db.other'"
    ).foreach(sql => withClue(sql)(SessionScopeScreen.find(sql) shouldBe None))

  it should "admit batches whose SET statements do not touch name resolution" in:
    List(
      "SET threads=4; SELECT * FROM t",
      "SET VARIABLE x = 1; SELECT getvariable('x')",
      // a VARIABLE named search_path sets a variable, not the setting (DuckDB 1.5.6: still reads
      // db.def.t afterwards)
      "SET VARIABLE search_path = 'db.other'; SELECT * FROM t",
      "BEGIN; INSERT INTO t VALUES (1); COMMIT",
      "SELECT 1; SELECT 2"
    ).foreach(sql => withClue(sql)(SessionScopeScreen.find(sql) shouldBe None))

  it should "not mistake a quoted or commented USE for a statement" in:
    List(
      "SELECT 'x; USE db.other'; SELECT * FROM t",
      "SELECT \"USE db.other;\" FROM t; SELECT 1",
      "SELECT $$; USE db.other$$; SELECT 1",
      "SELECT 1 /* ; USE db.other */; SELECT 2",
      "SELECT 1; -- USE db.other\nSELECT 2",
      "SELECT * FROM useful; SELECT * FROM settings"
    ).foreach(sql => withClue(sql)(SessionScopeScreen.find(sql) shouldBe None))

  // DuckDB 1.5.6 runs the first submission as ONE statement (U+00A0 is a space to its pre-pass, so
  // the `$$` opens a string holding the SET) and the second as three (U+00AD continues the
  // identifier `AS<U+00AD>$$`, so the SET runs): both run through the 1.5.6 CLI.
  it should "split after a no-break space or a soft hyphen the way DuckDB does" in:
    SessionScopeScreen.find(
      "SELECT 1 AS\u00a0$$; SET search_path='db.other'; SELECT * FROM t; -- $$"
    ) shouldBe None
    SessionScopeScreen.find(
      "SELECT 1 AS\u00ad$$; SET search_path='db.other'; SELECT * FROM t; -- $$"
    ) shouldBe defined

  // ---- through the shared access gate -----------------------------------------------------------

  private val cfg = Config.forDuckDB(Some("db"), Some("def"), Set("db"))

  "AccessGate" should "refuse a scope-changing batch as NOT wildcard-coverable" in:
    AccessGate.evaluate(
      "SET search_path='db.other'; SELECT * FROM t",
      cfg,
      "db",
      false,
      clientDataScans = false
    ) match
      case GateOutcome.Refuse(msg, false, _) => msg should include("own statement")
      case other                             => fail(other.toString)

  it should "still admit a lone SET search_path as control flow" in:
    AccessGate.evaluate(
      "SET search_path='db.other'",
      cfg,
      "db",
      false,
      clientDataScans = false
    ) shouldBe
      a[GateOutcome.NothingGated]

  // ---- through the QoD validator: the reported bypass, end to end --------------------------------

  private def effectiveWith(perms: List[RolePermission]): EffectiveSet =
    EffectiveSet(
      user = RbacUser(id = "u-1", tenant = Some("t-1"), username = "alice", kind = "user"),
      roles = List(RbacRole(id = "role-1", tenantId = "t-1", name = "analyst")),
      groups = Nil,
      permissions = perms,
      poolPerms = Nil
    )

  private def perm(c: String, s: String, t: String, verb: String) =
    RolePermission(s"rp-$c-$s-$t-$verb", "role-1", c, s, t, verb)

  private val onlyDefaultT = effectiveWith(List(perm("db", "def", "t", "ALL")))

  private val validator =
    new PostgresAclValidator(tenantCatalogs = { case "t-1" => Set("db"); case _ => Set.empty })

  private def ctx(sql: String, eff: EffectiveSet) =
    ValidationContext(
      username = eff.user.username,
      database = "t-1/db/p-1",
      statement = sql,
      peer = "conn-1",
      defaultDatabase = Some("db"),
      defaultSchema = Some("def"),
      effectiveSet = Some(eff)
    )

  "PostgresAclValidator" should "deny a user granted only db.def.t every batch that reaches another t" in:
    bypassBatches.foreach { sql =>
      withClue(sql)(validator.validate(ctx(sql, onlyDefaultT)) shouldBe a[Denied])
    }

  it should "still allow that user the same statements sent one at a time" in:
    validator.validate(ctx("SET search_path='db.other'", onlyDefaultT)) shouldBe Allowed
    // One-part only: JSqlParser cannot parse a two-part `USE db.other`, which was already a parse
    // error (wildcard-coverable) before this screen existed.
    validator.validate(ctx("USE other", onlyDefaultT)) shouldBe Allowed
    validator.validate(ctx("SELECT * FROM t", onlyDefaultT)) shouldBe Allowed

  it should "deny the batch even to a tenant-wide *.*.* ALL holder" in:
    // `SET search_path='oc.main'` reaches a catalog the tenant wildcard does not cover.
    val wildcard = effectiveWith(List(perm("*", "*", "*", "ALL")))
    validator.validate(ctx("SET search_path='oc.main'; SELECT * FROM t", wildcard)) shouldBe
      a[Denied]

  it should "leave a superuser's batch alone" in:
    val su = EffectiveSet(RbacUser("u-su", None, "admin", "admin"), Nil, Nil, Nil, Nil)
    validator.validate(ctx("SET search_path='db.other'; SELECT * FROM t", su)) shouldBe Allowed

  // ---- the read-only catalog screen ------------------------------------------------------------

  // The screen binds callers the ACL does not check (superusers, acl.enabled=false), for whom a
  // scope-changing batch is legitimate; ACL-checked callers are refused by the validator before the
  // screen runs, and the node attaches a read-only catalog READ_ONLY. So the screen keeps its
  // previous behavior and does not refuse a batch merely for moving the schema.
  "CatalogWriteScreen" should "not refuse a read-only scope-changing batch" in:
    val roCfg = Config.forDuckDB(Some("db"), Some("def"), Set("db", "ro"))
    CatalogWriteScreen.screen(
      "USE ro.main; SELECT * FROM t",
      StatementClassifier.default.classify,
      Set("ro"),
      roCfg
    ) shouldBe None
