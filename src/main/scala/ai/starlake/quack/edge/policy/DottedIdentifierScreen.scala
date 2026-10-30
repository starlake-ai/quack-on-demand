package ai.starlake.quack.edge.policy

import ai.starlake.sql.SqlLexer
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.select.PlainSelect

import java.util.Locale

/** Refuses a quoted identifier JSqlParser does not read as the one name DuckDB reads, outside an
  * alias: a name holding a dot, an `@`, a bracket, an escaped quote, a quote of the other style at
  * an edge, or anything else JSqlParser splits or strips.
  *
  * DuckDB reads `"a.b"` (or `` `a.b` ``) as ONE identifier: `SELECT * FROM "a.b"` reads the table
  * literally named `a.b` in the default schema. JSqlParser splits the quoted name on its dots
  * instead, so the access check, the row-policy rewrite and every other parser-based screen see
  * schema `a`, table `b` (`"a.b.c"`: catalog, schema, table), in every table position: reads,
  * INSERT / UPDATE / DELETE / MERGE / TRUNCATE targets, ALTER, CREATE TABLE / VIEW. A principal
  * granted on schema `a` would read, write and alter `main."a.b"`, and create tables in the default
  * schema (checked on the DuckDB 2.0 CLI with both `a.b` and `main."a.b"` present). It splits
  * `"secret@x"` the same way at the `@` (an Oracle database link: the check sees table `secret`
  * while DuckDB reads `secret@x`), and keeps an escaped quote doubled (`"a""b"` is checked as
  * `a""b`, DuckDB reads `a"b`). It strips SQL Server brackets and a quote of the other style at a
  * name's edges (`"[secret]"`, `"secret]"`, `` "`secret`" `` are all checked as `secret`, while
  * DuckDB 2.0 reads the table literally named `[secret]`). Tracking which positions JSqlParser
  * splits would mean modelling its grammar, so the rule is lexical and covers every position: each
  * double-quoted or backtick-quoted identifier token, read from
  * [[ai.starlake.sql.SqlLexer SqlLexer]] tokens so a string literal or a comment never counts, is
  * read back through JSqlParser as a table name and refused unless that reading is the one name
  * DuckDB reads (the token's body, its doubled delimiters undone; a doubled delimiter is always
  * refused, JSqlParser keeping it doubled). No character list to keep current: a name of plain
  * ASCII letters, digits and `_` skips the read-back. DottedIdentifierScreenSpec probes every
  * printable ASCII character in both quoting styles.
  *
  * Two exceptions. A name directly after the keyword `AS` is an alias (`SELECT x AS "t.x"`, `FROM t
  * AS "a.b"`), the shape BI tools generate for column labels; in DuckDB's grammar `AS` is never
  * followed by a table reference, so an alias names no table the check could misread. A name that
  * starts with a URL scheme (`FROM "s3://b/x.parquet"`, the scheme holding no dot) is a remote
  * replacement scan reading the object, judged as a path by
  * [[ai.starlake.quack.edge.sql.LockdownScreen]]; split on its dots its first part holds the `://`,
  * so it is the file reference it is (`DuckDBDialectMapper.isFileReference`), which
  * [[ai.starlake.quack.edge.sql.FileAccessScreen]] refuses to every tenant-scoped principal,
  * `*.*.* ALL` included, like `FROM 's3://b/x.parquet'`. A `://` anywhere else is no exception
  * (`"other.x://"` would be checked as schema `other`).
  *
  * Runs for callers the ACL checks (QoD grants and OPA, through
  * [[ai.starlake.quack.edge.sql.AccessGate]]), for principals with column or row policies (the
  * protected-write guard), and on lockdown pools (every caller). Off lockdown, a table whose name
  * holds one of these stays reachable to superusers and to callers the ACL does not check.
  */
object DottedIdentifierScreen:

  val Reason: String =
    "a quoted identifier containing a dot, an @, a bracket, an escaped quote or another character " +
      "the access check reads differently is not supported: DuckDB reads \"a.b\" as one name, " +
      "which the access check cannot tell from a qualified a.b; rename the object or use an " +
      "alias without one"

  // A scheme with no dot, so JSqlParser's first split part always holds the `://`.
  private val UrlScheme = "^[A-Za-z][A-Za-z0-9+-]*://.*".r

  /** Some(reason) when `sql` holds such a quoted identifier outside an alias.
    *
    * `rewriteOf` is the statement the caller wrote when `sql` is an edge rewrite of it: only the
    * quoted identifiers the caller wrote are screened then. The others were printed by the rewriter
    * (a star expansion's catalog column names, policy SQL), every table name of a rewrite being
    * printed as the caller wrote it; a column name the gate misreads changes no table it checks.
    */
  def find(sql: String, rewriteOf: Option[String] = None): Option[String] =
    val written = rewriteOf.map(quotedIdentifiers(_).map(_._2).toSet)
    val hit     = quotedIdentifiers(sql).exists { (afterAs, token) =>
      !afterAs && written.forall(_.contains(token)) && misread(token)
    }
    Option.when(hit)(Reason)

  /** Every double-quoted or backtick-quoted identifier token of `sql`, as the access check parses
    * it (ParserInput: the engine view with each kept unicode space restored, position for
    * position), with whether it directly follows the keyword `AS`.
    */
  private def quotedIdentifiers(sql: String): List[(Boolean, String)] =
    if sql == null || (sql.indexOf('"') < 0 && sql.indexOf('`') < 0) then Nil
    else
      val view = SqlLexer.engineView(sql)
      val text = SqlLexer.keptSpacesRestored(view)
      val toks = SqlLexer.tokens(view).filterNot(_.isTrivia)
      toks.indices.toList.collect {
        case i if toks(i).kind == SqlLexer.Kind.Literal && isQuotedIdentifier(view, toks(i)) =>
          (i > 0 && isAs(view, toks(i - 1))) -> text.substring(toks(i).start, toks(i).end)
      }

  // A name JSqlParser cannot read differently: no separator, quote or bracket to act on.
  private val PlainName = "[A-Za-z0-9_]+".r

  /** Whether JSqlParser reads the quoted identifier `text` (delimiters included) as another name
    * than DuckDB does. Rather than list the characters it acts on, the name is read back through
    * JSqlParser as a table name (the reading the access check uses) and compared with the name
    * DuckDB reads; anything it splits, strips or cannot parse is a misread. An escaped quote is one
    * even in a URL: JSqlParser keeps it doubled.
    */
  private def misread(text: String): Boolean =
    val quote = text.charAt(0)
    val body  = text.drop(1).dropRight(1)
    if body.contains(s"$quote$quote") then true
    else if PlainName.matches(body) || UrlScheme.matches(body) then false
    else !readAsTable(text).contains(body)

  /** The one name JSqlParser reads `quoted` as in table position, None when it reads several parts
    * or none.
    */
  private def readAsTable(quoted: String): Option[String] =
    try
      CCJSqlParserUtil.parse(s"SELECT * FROM $quoted") match
        case ps: PlainSelect =>
          ps.getFromItem match
            case t: Table if t.getNameParts.size == 1 => Option(t.getUnquotedName)
            case _                                    => None
        case _ => None
    catch case scala.util.control.NonFatal(_) => None

  private def isQuotedIdentifier(view: String, t: SqlLexer.Token): Boolean =
    val c = view.charAt(t.start)
    c == '"' || c == '`'

  private def isAs(view: String, t: SqlLexer.Token): Boolean =
    t.kind == SqlLexer.Kind.Word && view.substring(t.start, t.end).toLowerCase(Locale.ROOT) == "as"
