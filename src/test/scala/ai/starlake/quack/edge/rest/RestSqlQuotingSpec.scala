package ai.starlake.quack.edge.rest

import ai.starlake.quack.edge.adapter.TestArrow
import ai.starlake.quack.model.SqlLiterals
import io.circe.parser.parse as parseJson
import org.apache.arrow.vector.ipc.ArrowReader
import org.duckdb.{DuckDBConnection, DuckDBResultSet}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager
import scala.collection.mutable.ListBuffer
import scala.util.{Random, Using}

/** A hostile corpus plus 10 000 seeded strings, each used both as a filter value and as a probed
  * column name, through the whole pure pipeline (parse, resolve against a REAL probe, render), and
  * checked by three independent checks:
  *
  *   1. a structural scanner that mirrors [[SqlLiterals]] quoting must see only a closed set of
  *      keywords and punctuation outside quotes, and exactly the expected literals and identifiers
  *      inside them;
  *   2. executed in in-process DuckDB, `eq` returns exactly the matching row, `not.eq` the rest,
  *      and a sentinel table survives;
  *   3. `json_serialize_sql` parses the text as exactly one statement.
  */
class RestSqlQuotingSpec extends AnyFlatSpec with Matchers:

  private val Corpus = List(
    "' OR '1'='1",
    "'; DROP TABLE sentinel; --",
    "\"; DROP TABLE sentinel; --",
    "x'); DROP TABLE sentinel; SELECT ('",
    "\\'; DROP TABLE sentinel; --",
    "''",
    "'",
    "\"",
    "\"\"",
    "\\",
    "/* comment */",
    "*/ DROP TABLE sentinel /*",
    "-- trailing",
    "$$ DROP TABLE sentinel $$",
    "$tag$ x $tag$",
    "E'\\x27'",
    "ʼ OR 1=1",
    "＇ OR 1=1",
    "a\nb\r\nc\td",
    "  ",
    "\u0001\u001f\u007f",
    "%_%",
    "(a,b)",
    ")) OR ((1=1",
    "1; ATTACH 'x' AS y",
    "🦆 quack",
    " ",
    "SELECT * FROM sentinel",
    "AT (VERSION => 1)"
  )

  private val Alphabet: IndexedSeq[String] =
    Vector(
      "'",
      "\"",
      "\\",
      ";",
      "-",
      "/",
      "*",
      "(",
      ")",
      ",",
      ".",
      "%",
      "_",
      "$",
      "=",
      " ",
      "\n",
      "\t",
      "\r",
      "a",
      "Z",
      "0",
      "9",
      "é",
      "ʼ",
      "＇",
      " ",
      "🦆",
      "\u0001",
      "`",
      "[",
      "]",
      "{",
      "}",
      "?",
      ":",
      "|",
      "&",
      "!",
      "#",
      "@",
      "+",
      "<",
      ">"
    )

  private def randomString(rnd: Random): String =
    val len = 1 + rnd.nextInt(24)
    (1 to len).map(_ => Alphabet(rnd.nextInt(Alphabet.size))).mkString

  // ---- check 1: a scanner mirroring SqlLiterals quoting ---------------------------------------

  private enum Tok:
    case Lit(v: String)
    case Ident(v: String)
    case Bare(v: String)

  /** Splits SQL into single-quoted literals (`''` escapes), double-quoted identifiers (`""`
    * escapes) and bare tokens; `None` when a quote is left open.
    */
  private def scan(sql: String): Option[List[Tok]] =
    val out                     = ListBuffer.empty[Tok]
    var i                       = 0
    var ok                      = true
    def quoted(q: Char): String =
      val sb     = new StringBuilder
      var closed = false
      i += 1
      while !closed && i < sql.length do
        if sql.charAt(i) == q then
          if i + 1 < sql.length && sql.charAt(i + 1) == q then { sb.append(q); i += 2 }
          else { closed = true; i += 1 }
        else { sb.append(sql.charAt(i)); i += 1 }
      if !closed then ok = false
      sb.toString
    while ok && i < sql.length do
      val c = sql.charAt(i)
      if c == ' ' then i += 1
      else if c == '\'' then out += Tok.Lit(quoted('\''))
      else if c == '"' then out += Tok.Ident(quoted('"'))
      else if c.isLetterOrDigit || c == '_' then
        val j = (i until sql.length)
          .find(k => !(sql.charAt(k).isLetterOrDigit || sql.charAt(k) == '_'))
          .getOrElse(sql.length)
        out += Tok.Bare(sql.substring(i, j)); i = j
      else
        val two = if i + 1 < sql.length then sql.substring(i, i + 2) else ""
        if Set("<>", "<=", ">=", "=>").contains(two) then { out += Tok.Bare(two); i += 2 }
        else { out += Tok.Bare(c.toString); i += 1 }
    Option.when(ok)(out.toList)

  private val Keywords = Set(
    "SELECT",
    "FROM",
    "WHERE",
    "AND",
    "NOT",
    "IN",
    "IS",
    "NULL",
    "TRUE",
    "FALSE",
    "LIKE",
    "lower",
    "starts_with",
    "contains",
    "CAST",
    "AS",
    "ORDER",
    "BY",
    "ASC",
    "DESC",
    "NULLS",
    "FIRST",
    "LAST",
    "LIMIT",
    "OFFSET",
    "AT",
    "VERSION",
    "VARCHAR",
    "INTEGER",
    "DECIMAL"
  )
  private val Punct = Set("(", ")", ",", ".", "=", "<>", "<", ">", "<=", ">=", "=>")

  private def structurallySafe(sql: String, literals: Set[String], idents: Set[String]): Unit =
    val toks = scan(sql)
    withClue(sql)(toks should not be empty)
    toks.get.foreach {
      case Tok.Bare(b) =>
        withClue(sql)(
          (Keywords.contains(b) || Punct.contains(b) || b.forall(_.isDigit)) shouldBe true
        )
      case Tok.Lit(v)   => withClue(sql)(literals should contain(v))
      case Tok.Ident(v) => withClue(sql)(idents should contain(v))
    }

  // ---- DuckDB fixture ------------------------------------------------------------------------

  private def probeOf(conn: DuckDBConnection, sql: String): Vector[ProbedColumn] =
    Using.resource(conn.createStatement()) { st =>
      val rs     = st.executeQuery(sql).asInstanceOf[DuckDBResultSet]
      val reader = rs.arrowExportStream(TestArrow.sharedAllocator, 1024L).asInstanceOf[ArrowReader]
      try ProbedColumn.fromArrow(reader.getVectorSchemaRoot.getSchema)
      finally reader.close()
    }

  private def ids(conn: DuckDBConnection, sql: String): Set[Int] =
    Using.resource(conn.createStatement()) { st =>
      Using.resource(st.executeQuery(sql)) { rs =>
        Iterator.continually(rs.next()).takeWhile(identity).map(_ => rs.getInt(1)).toSet
      }
    }

  private def singleStatement(conn: DuckDBConnection, sql: String): Unit =
    Using.resource(conn.createStatement()) { st =>
      Using.resource(
        st.executeQuery(s"SELECT json_serialize_sql(${SqlLiterals.duckdbLiteral(sql)})")
      ) { rs =>
        rs.next()
        val json = parseJson(rs.getString(1)).toOption.get
        withClue(sql) {
          json.hcursor.get[Boolean]("error") shouldBe Right(false)
          json.hcursor.downField("statements").values.map(_.size) shouldBe Some(1)
        }
      }
    }

  /** One input `s`, used as the column name AND as the value, through every check. */
  private def check(conn: DuckDBConnection, s: String): Unit =
    val colName = s
    val ident   = SqlLiterals.duckdbIdent(colName)
    Using.resource(conn.createStatement()) { st =>
      st.execute(s"CREATE OR REPLACE TABLE main.t (row_id INTEGER, $ident VARCHAR)")
    }
    Using.resource(conn.prepareStatement("INSERT INTO main.t VALUES (?, ?)")) { ps =>
      for (id, v) <- List(1 -> s, 2 -> (s + "~"), 3 -> ("~" + s)) do
        ps.setInt(1, id); ps.setString(2, v); ps.executeUpdate()
    }
    val target = RestSql.Target("memory", "main", "t", None)
    val probe  = probeOf(conn, RestSql.probe(target))
    probe.map(_.name) shouldBe Vector("row_id", colName)

    val quotedItem = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    // Without `*` a pattern has no wildcard, so like must behave as equality on the stripped text,
    // and ilike too (rows 2 and 3 differ from it by a `~` whatever the case); edge wildcards
    // must behave as prefix and contains. Each form is rendered as LIKE when the text holds no
    // literal % or _, and as =, starts_with or contains otherwise.
    val stripped  = s.replace("*", "")
    val exact     = if stripped == s then Set(1) else Set.empty[Int]
    val likeCases =
      if stripped.isEmpty then Nil
      else
        List(
          s"like.$stripped"     -> exact,
          s"ilike.$stripped"    -> exact,
          s"not.like.$stripped" -> (Set(1, 2, 3) -- exact)
        ) ++ Option
          .when(stripped == s)(List(s"like.$s*" -> Set(1, 2), s"like.*$s*" -> Set(1, 2, 3)))
          .getOrElse(Nil)
    val cases = List(
      s"eq.$s"                -> Set(1),
      s"not.eq.$s"            -> Set(2, 3),
      s"in.($quotedItem)"     -> Set(1),
      s"not.in.($quotedItem)" -> Set(2, 3)
    ) ++ likeCases
    val literals = Set(s, stripped, stripped + "%", "%" + stripped + "%")
    for (value, expected) <- cases do
      val sql = RestQuery
        .parse(Seq("select" -> "row_id", colName -> value))
        .flatMap(RestResolver.resolve(_, probe))
        .map(RestSql.render(target, _, 100))
        .fold(e => fail(s"${e.code} for case ${value.take(6)}"), identity)
      structurallySafe(sql, literals, Set("memory", "main", "t", "row_id", colName))
      withClue(sql)(ids(conn, sql) shouldBe expected)
      singleStatement(conn, sql)
    ids(conn, "SELECT 1 FROM main.sentinel") shouldBe Set(1)

  private def eligible(s: String): Boolean =
    s.nonEmpty && !RestQuery.isReservedName(s) &&
      RestResolver.asciiLower(s) != "row_id" && !s.contains('\u0000')

  "the pure pipeline" should "keep a hostile corpus inside literals and identifiers" in {
    Class.forName("org.duckdb.DuckDBDriver")
    Using.resource(DriverManager.getConnection("jdbc:duckdb:").asInstanceOf[DuckDBConnection]) {
      conn =>
        Using.resource(conn.createStatement())(_.execute("CREATE TABLE main.sentinel AS SELECT 1"))
        Corpus.filter(eligible).foreach(check(conn, _))
    }
  }

  it should "keep 10 000 seeded random strings inside literals and identifiers" in {
    val seed = sys.env.get("REST_INJECTION_SEED").map(_.toLong).getOrElse(Random.nextLong())
    info(s"seed = $seed")
    println(s"RestSqlQuotingSpec seed = $seed")
    val rnd = new Random(seed)
    Class.forName("org.duckdb.DuckDBDriver")
    Using.resource(DriverManager.getConnection("jdbc:duckdb:").asInstanceOf[DuckDBConnection]) {
      conn =>
        Using.resource(conn.createStatement())(_.execute("CREATE TABLE main.sentinel AS SELECT 1"))
        var n = 0
        while n < 10000 do
          val s = randomString(rnd)
          if eligible(s) then
            withClue(s"seed=$seed input=${s.map(c => f"\\u${c.toInt}%04x").mkString}: ")(
              check(conn, s)
            )
            n += 1
    }
  }
