package ai.starlake.quack.edge.policy

import ai.starlake.acl.model.{Config, DenyReason}
import ai.starlake.acl.parser.{SqlParser, StatementResult, TableAccess, TableExtractor, Verb}
import ai.starlake.quack.edge.authz.{FunctionClass, FunctionClasses}
import ai.starlake.quack.edge.meta.MetadataFilterRewriter
import ai.starlake.sql.QuotedIdentifier

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
    val (readers, others)               = markers.partition(isFileReader)
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
      Scope.Reaches(gated, readers.flatMap(TableExtractor.tableFunctionName).toSet)

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
