package ai.starlake.quack.edge.policy

import ai.starlake.acl.model.{Config, DenyReason}
import ai.starlake.acl.parser.{SqlParser, StatementResult, TableAccess, TableExtractor, Verb}
import ai.starlake.quack.edge.authz.{FunctionClass, FunctionClasses}
import ai.starlake.quack.edge.meta.MetadataFilterRewriter
import ai.starlake.quack.edge.search.SearchFunctions
import ai.starlake.sql.{QuotedIdentifier, SqlLexer}

import java.util.Locale

/** Whether a statement an edge rewriter printed reaches anything the caller's statement did not
  * (spec decision 29). The column policy, row policy and metadata filter rewriters each print a NEW
  * statement, and the node runs that one: a rewrite reaching a table the original did not (the
  * column policy rewrite once schema-qualified a CTE reference, turning it into a read of the real
  * table of that name) would read what the caller never named, past the policy that covers it. The
  * node judges the rewritten statement's grants; this compares its reach with the original's, read
  * by the parser the rewriters use, under the same qualification config, so an unqualified name
  * qualifies identically on both sides.
  *
  * Parse only, no grant consulted. The reach of a statement ([[Scope.Reaches]]) is its (table,
  * verb) accesses, the session catalog's filterable metadata reads and the catalog functions of a
  * pure read set aside (the metadata filter introduces them, for SHOW TABLES), and the file reader
  * calls it makes (functions of class `File`, judged by the node on their locations; a rewrite may
  * neither add nor change one). Anything else the parser cannot resolve to a table, a parse error,
  * an ambiguous or unresolvable name, a catalog function inside a write, or a quoted name the
  * parser would misread ([[DottedIdentifierScreen]]) makes the scope [[Scope.Refused]]: a refused
  * original is not compared (the node judges the rewrite), a refused rewrite of a readable original
  * is refused.
  *
  * A search call (search spec ruling 3, the calls [[CatalogReachingCalls.namesTableByString]] holds
  * for) names its table by a string: its `table` argument, a single-quoted literal, is read as a
  * table name, qualified like any other, and is in the reach as a read; the call itself is no file
  * reader. A `table` argument that is not a constant, or not a table name, makes the scope refused,
  * and a rewrite of an original holding a search name is never left unjudged: when the original's
  * scope is refused, so is the rewrite. The search name is read by the one detector every search
  * step uses (`SearchFunctions.callsSearch`), which over-includes, so this also refuses a rewrite
  * of a search statement refused for another reason (a `range()` join) or of one holding a search
  * name only in a literal: over-refusal, the safe direction.
  */
object RewriteScope:

  enum Scope:
    case Refused(message: String)

    /** `readers`: the file reader calls, as the parser prints them (`read_parquet('/x')`). */
    case Reaches(gated: Set[TableAccess], readers: Set[String])

  /** The scope of `statement` under `config`. `sessionCatalog` must be the catalog `config` uses as
    * its default database, so the metadata set-aside and the qualification agree. `rewriteOf`: the
    * statement the caller wrote when `statement` is a rewrite of it (only the quoted names the
    * caller wrote are screened then, [[DottedIdentifierScreen.find]]). `clientDataScans`: true only
    * for a statement from the native Quack front door, the one edge that feeds a client data scan.
    */
  def scopeOf(
      statement: String,
      config: Config,
      sessionCatalog: String,
      clientDataScans: Boolean,
      rewriteOf: Option[String] = None
  ): Scope =
    DottedIdentifierScreen.find(statement, rewriteOf) match
      case Some(reason) => Scope.Refused(reason)
      case None         => parsed(statement, config, sessionCatalog, clientDataScans)

  /** Some(reason) when `rewritten`, printed by an edge rewriter from `original`, reaches a table, a
    * verb or a file reader call `original` does not, or cannot be read while `original` can.
    */
  def reachesBeyond(
      original: String,
      rewritten: String,
      config: Config,
      sessionCatalog: String,
      clientDataScans: Boolean
  ): Option[String] =
    scopeOf(original, config, sessionCatalog, clientDataScans) match
      // A search statement's rewrite is judged or refused, never passed unread (ruling 3).
      case Scope.Refused(message) if SearchFunctions.callsSearch(original) =>
        Some(s"the statement holds a search name and cannot be authorized ($message)")
      case Scope.Refused(_)                   => None
      case Scope.Reaches(authorized, readers) =>
        scopeOf(rewritten, config, sessionCatalog, clientDataScans, Some(original)) match
          case Scope.Refused(message) =>
            Some(s"the rewritten statement cannot be authorized ($message)")
          case Scope.Reaches(needed, neededReaders) =>
            val extra = (needed -- authorized).map(a => s"${a.table.canonical} (${a.verb})") ++
              (neededReaders -- readers)
            Option.when(extra.nonEmpty)(
              "the rewritten statement reaches what the original did not: " +
                extra.toList.sorted.mkString(", ")
            )

  /** The function name of a table function call as the parser prints it: its last name part,
    * unquoted and lowercased (`main."histogram"('x', b)` is `histogram`).
    */
  private[policy] def callName(call: String): String =
    val head = call.takeWhile(_ != '(').trim
    val last = if head.contains('.') then head.substring(head.lastIndexOf('.') + 1) else head
    QuotedIdentifier.unquote(last).toLowerCase(Locale.ROOT)

  private def parsed(
      statement: String,
      config: Config,
      sessionCatalog: String,
      clientDataScans: Boolean
  ): Scope =
    val statements  = SqlParser.extract(statement, config).statements
    val parseErrors = statements.collect { case StatementResult.ParseError(_, snippet, msg) =>
      s"parse error: $msg ($snippet)"
    }
    // A DuckDB 2.0 client's scan_data_from_quack_client as the source of a write is set aside only
    // for the native front door (TableExtractor.gatedUnsupported).
    val markers = statements.collect {
      case StatementResult.Extracted(_, _, a, _, u) if u.nonEmpty =>
        TableExtractor.gatedUnsupported(a, u, clientDataScans)
    }.flatten
    val (searchCalls, nonSearch) = markers.partition(isSearchCall)
    val searchTargets            =
      searchCalls.map(searchTarget(_, config, sessionCatalog, clientDataScans))
    val searchRefusals                  = searchTargets.collect { case Left(m) => m }
    val (readers, others)               = nonSearch.partition(isFileReader)
    val (catalogFunctions, unsupported) = others.partition(u =>
      TableExtractor.tableFunctionName(u).flatMap(TableExtractor.catalogFunctionCall).isDefined
    )
    val qualErrors =
      statements.collect { case StatementResult.Extracted(_, _, _, q, _) => q }.flatten
    val ambiguous = qualErrors.collect { case a: DenyReason.AmbiguousCatalogRef => a }
    val accesses  =
      statements.collect { case StatementResult.Extracted(_, _, a, _, _) => a }.flatten.toSet
    val pureRead = accesses.forall(_.verb == Verb.Read)
    if parseErrors.nonEmpty then Scope.Refused(parseErrors.mkString("; "))
    else if unsupported.nonEmpty then
      Scope.Refused(s"unsupported constructs (deny, fail-closed): ${unsupported.mkString(", ")}")
    else if searchRefusals.nonEmpty then Scope.Refused(searchRefusals.distinct.mkString("; "))
    else if ambiguous.nonEmpty then
      Scope.Refused(ambiguous.map(ProtectedWriteGuard.ambiguousNameReason).mkString("; "))
    else if qualErrors.nonEmpty then
      Scope.Refused(
        s"unresolvable table references (deny, fail-closed): ${qualErrors.mkString("; ")}"
      )
    else if catalogFunctions.nonEmpty && !pureRead then
      // The metadata filter rewrites reads only: a listing riding in a write would reach the node
      // unfiltered, so it is refused.
      Scope.Refused(
        "catalog functions are admitted in read-only statements only (deny, fail-closed): " +
          catalogFunctions.mkString(", ")
      )
    else
      val gated =
        if !pureRead then accesses
        else accesses.filterNot(filterableMetadataRead(_, sessionCatalog))
      Scope.Reaches(
        gated ++ searchTargets.collect { case Right(a) => a },
        readers.flatMap(TableExtractor.tableFunctionName).toSet
      )

  /** A table function marker naming a call that names its table by a string (a search function). */
  private def isSearchCall(marker: String): Boolean =
    TableExtractor
      .tableFunctionName(marker)
      .map(callName)
      .exists(CatalogReachingCalls.namesTableByString)

  /** The table the search call `marker` names by its second argument, as a read, qualified under
    * `config` exactly as a table the statement names in `FROM` (the probe `SELECT 1 FROM <name>`
    * goes through [[scopeOf]], the quoted-name screen included), or the refusal.
    */
  private def searchTarget(
      marker: String,
      config: Config,
      sessionCatalog: String,
      clientDataScans: Boolean
  ): Either[String, TableAccess] =
    val call       = TableExtractor.tableFunctionName(marker).getOrElse(marker)
    val name       = callName(call)
    val notName    = s"the table argument of $name is not a table name"
    val notLiteral = s"the table argument of $name is not a constant"
    tableLiteral(call).toRight(notLiteral).flatMap { value =>
      nameParts(value) match
        case None        => Left(notName)
        case Some(parts) =>
          // Built from the parsed parts, each quoted again, so the probe is never lexed as anything
          // but those parts; a part holding a dot is then refused by the quoted-name screen.
          val probe = parts.map(QuotedIdentifier.doubleQuoted).mkString(".")
          scopeOf(s"SELECT 1 FROM $probe", config, sessionCatalog, clientDataScans) match
            case Scope.Reaches(g, r) if r.isEmpty && g.size == 1 && g.head.verb == Verb.Read =>
              Right(g.head)
            case Scope.Refused(m) => Left(s"$notName ($m)")
            case _                => Left(notName)
    }

  /** The value of the second top-level argument of the call `call` (as the parser prints it) when
    * that argument is one single-quoted string literal and nothing else, read from DuckDB 2.0's own
    * tokens ([[SqlLexer]]); None for anything else: an expression, a cast, a bind parameter, a
    * column, a named argument, an escape or dollar-quoted string, a missing argument.
    */
  private def tableLiteral(call: String): Option[String] =
    val view                                         = SqlLexer.engineView(call)
    val toks                                         = SqlLexer.tokens(call).filterNot(_.isTrivia)
    def text(t: SqlLexer.Token): String              = view.substring(t.start, t.end)
    def punct(t: SqlLexer.Token, c: String): Boolean =
      t.kind == SqlLexer.Kind.Punct && text(t) == c
    val open = toks.indexWhere(punct(_, "("))
    if open < 0 then None
    else
      // Split the argument list at depth-one commas, up to the parenthesis that closes it.
      val args   = List.newBuilder[Vector[SqlLexer.Token]]
      var cur    = Vector.empty[SqlLexer.Token]
      var depth  = 1
      var i      = open + 1
      var closed = false
      while i < toks.length && !closed do
        val t = toks(i)
        if punct(t, "(") || punct(t, "[") || punct(t, "{") then
          depth += 1
          cur :+= t
        else if punct(t, ")") || punct(t, "]") || punct(t, "}") then
          depth -= 1
          if depth == 0 then
            args += cur
            closed = true
          else cur :+= t
        else if depth == 1 && punct(t, ",") then
          args += cur
          cur = Vector.empty
        else cur :+= t
        i += 1
      if !closed then None
      else
        args.result().lift(1).collect {
          case Vector(t)
              if t.kind == SqlLexer.Kind.Literal && view.charAt(t.start) == '\'' &&
                !SqlLexer.unterminated(view, t) =>
            val body = SqlLexer.keptSpacesRestored(view.substring(t.start + 1, t.end - 1))
            body.replace("''", "'")
        }

  /** The parts of `s`, unquoted, when it is one to three identifiers joined by `.` as DuckDB 2.0's
    * tokenizer reads it ([[SqlLexer]], over its unicode-space pre-pass) and nothing else: each part
    * a plain identifier token or a double-quoted one, with no space, comment, operator, number,
    * backtick or string between or inside them. A plain part holding any whitespace or space
    * character (`Character.isWhitespace`, `Character.isSpaceChar`: U+2028 is an identifier
    * character to the tokenizer) or a pre-pass stand-in is refused, so no part can read as two
    * names.
    */
  private def nameParts(s: String): Option[List[String]] =
    val view                            = SqlLexer.engineView(s)
    val toks                            = SqlLexer.tokens(s)
    def text(t: SqlLexer.Token): String = view.substring(t.start, t.end)
    def spaceLike(c: Char): Boolean     =
      Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isSurrogate(c)
    def ident(t: SqlLexer.Token): Option[String] =
      val raw = text(t)
      if t.kind == SqlLexer.Kind.Word && !raw.exists(spaceLike) then Some(raw)
      else if t.kind == SqlLexer.Kind.Literal && raw.length > 2 && raw.charAt(0) == '"' &&
        !SqlLexer.unterminated(view, t)
      then Some(QuotedIdentifier.unquote(SqlLexer.keptSpacesRestored(raw))).filter(_.nonEmpty)
      else None
    def separator(t: SqlLexer.Token): Boolean = !t.isTrivia && text(t) == "."
    // ident (. ident){0,2}: an odd count of tokens, identifiers at even positions.
    val shaped = toks.nonEmpty && toks.length % 2 == 1 && toks.length <= 5 &&
      toks.indices.forall(i => i % 2 == 0 || separator(toks(i)))
    if !shaped then None
    else
      val parts = toks.indices.collect { case i if i % 2 == 0 => ident(toks(i)) }.toList
      Option.when(parts.forall(_.isDefined))(parts.flatten)

  /** A table function marker naming a function of class `File` (a reader the node judges by its
    * locations, decisions 15 and 18).
    */
  private def isFileReader(marker: String): Boolean =
    TableExtractor
      .tableFunctionName(marker)
      .map(callName)
      .exists(n => FunctionClasses.of("system", "main", n, "table").contains(FunctionClass.File))

  /** A read of one of the session catalog's `information_schema` tables the metadata filter
    * narrows: SESSION catalog only, since the filter narrows nothing else.
    */
  private def filterableMetadataRead(a: TableAccess, sessionCatalog: String): Boolean =
    a.verb == Verb.Read &&
      a.table.schema.equalsIgnoreCase("information_schema") &&
      MetadataFilterRewriter.FilterableTables.contains(a.table.table.toLowerCase(Locale.ROOT)) &&
      a.table.database.equalsIgnoreCase(sessionCatalog)
