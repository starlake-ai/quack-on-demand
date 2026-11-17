package ai.starlake.quack.edge.cls

import ai.starlake.quack.edge.sql.FunctionCalls
import ai.starlake.sql.SqlLexer
import net.sf.jsqlparser.expression.Alias
import net.sf.jsqlparser.schema.{Column, Table}
import net.sf.jsqlparser.statement.select.{
  FromItem,
  Join,
  ParenthesedFromItem,
  ParenthesedSelect,
  PlainSelect,
  Select,
  TableFunction,
  WithItem
}

import java.util.Locale
import scala.jdk.CollectionConverters._
import scala.util.Try

/** Spec 12 Phase 1, change 9. Before name resolution, each search call's input subquery is cut out
  * and rewritten as a statement of its own through the same column-policy rewrite, and the call is
  * replaced in the outer statement by a placeholder table whose columns are the function's fixed
  * outputs, which the resolver's metadata declares. The outer statement then resolves and masks as
  * usual, and `restore` puts each call back with its rewritten input and its constants
  * byte-identical.
  *
  * What is separated: a call of `qod_search` or `qod_search_matches` (the two the node registers;
  * `qod_knn` and `qod_hybrid` have no fixed outputs yet, so a call of either is never replaced and
  * fails closed) that is a FROM item of the statement's SELECT, of a join of it, of a parenthesized
  * join, or of a derived table in one of those places, at any depth (the Flight SQL prepare probe
  * wraps the client's query as a derived table). Its first argument, when it is a parenthesized
  * SELECT, is the input; every other argument is kept as written and holds no subquery.
  *
  * Everything else fails closed (Left), the column-policy step then denying with its fixed text: a
  * call the token detector finds that this step does not replace (a call in a CTE, an `IN` or other
  * expression subquery, a set-operation arm, another call's input, a string literal, a CTE or alias
  * column list), a statement holding a CTE, a call with `LATERAL` or an alias column list, an input
  * that is not one plain SELECT, and an input reading a name it does not bind itself (a correlated
  * input: as a statement of its own it would resolve, and mask, without the outer scope). An
  * unqualified column of the input that none of its own tables holds is the other half of that
  * rule, which needs the tables' columns and so is checked by the column-policy rewriter.
  */
private[cls] object SearchSeparation:

  /** A placeholder is this prefix plus the shortest decimal suffix whose name occurs nowhere among
    * the statement's identifiers (quoted or not, compared ignoring case), so it shadows no table
    * the statement names and no real table can take its place in the resolver.
    */
  val PlaceholderPrefix: String = "__qod_search_call_"

  /** The fixed output columns, with their DuckDB types, of each function this step separates. */
  val Outputs: Map[String, List[(String, String)]] = Map(
    "qod_search"         -> List("rowid" -> "BIGINT", "score" -> "FLOAT", "rank" -> "INTEGER"),
    "qod_search_matches" -> List("rowid" -> "BIGINT", "score" -> "FLOAT")
  )

  /** One separated call: `call` its index in text order, `function` its name as written (qualified
    * or not), `input` the text of its input subquery without the parentheses (empty when its first
    * argument is no subquery), `constants` the text of its other arguments as written, from the end
    * of the input (or the opening parenthesis) to the closing one.
    */
  final case class Cut(call: Int, function: String, input: String, constants: String)

  /** The call as the client wrote it, split around its input: `head` + `input` + `tail` is the
    * call's text in the client's statement.
    */
  private[cls] final case class Span(
      head: String,
      input: String,
      tail: String,
      placeholder: String,
      addedAlias: String
  )

  /** `outer`: the statement with each call replaced by its placeholder; `placeholders`: each
    * placeholder name with the columns it declares.
    */
  final case class Separated(
      outer: String,
      cuts: List[Cut],
      placeholders: Map[String, List[(String, String)]]
  )(private val spans: Map[Int, Span]):

    /** The placeholder names in lower case. */
    def placeholderNames: Set[String] = placeholders.keySet.map(lower)

    /** `outerRewritten` (the column-policy rewrite of [[outer]]) with each placeholder replaced by
      * its call, the input of call `n` being `inputsRewritten(n)` (its own rewrite) or, when
      * absent, the input as written. Left when a placeholder is not found exactly once in a FROM
      * position, or is left in the result.
      */
    def restore(outerRewritten: String, inputsRewritten: Map[Int, String]): Either[String, String] =
      val view = SqlLexer.engineView(outerRewritten)
      val toks = SqlLexer.tokens(outerRewritten).filterNot(_.isTrivia)
      // (start, end, replacement) per placeholder, end past the alias this step added.
      val edits = cuts.map { cut =>
        val span = spans(cut.call)
        val name = lower(span.placeholder)
        val at   = toks.indices.filter(i => identifierOf(view, toks(i)).contains(name))
        if at.size != 1 then Left(Unrestorable)
        else
          val i = at.head
          // A FROM item: after FROM, JOIN, a comma or a parenthesized join's `(`, never qualified.
          val after = i > 0 && FromItemOpeners.contains(lower(text(view, toks(i - 1))))
          if !after then Left(Unrestorable)
          else if i + 1 < toks.length && text(view, toks(i + 1)) == "." then Left(Unrestorable)
          else
            val input = inputsRewritten.getOrElse(cut.call, span.input)
            val call  = span.head + input + span.tail
            // The alias this step gave an unaliased call is removed again: the call binds by its
            // own name, as the client wrote it.
            val end =
              if span.addedAlias.isEmpty then toks(i).end
              else
                val k =
                  if toks.lift(i + 1).exists(t => lower(text(view, t)) == "as") then i + 2
                  else i + 1
                toks.lift(k).filter(t => text(view, t) == span.addedAlias).map(_.end) match
                  case Some(e) => e
                  case None    => -1
            if end < 0 then Left(Unrestorable) else Right((toks(i).start, end, call))
      }
      edits.collectFirst { case Left(reason) => reason } match
        case Some(reason) => Left(reason)
        case None         =>
          val sorted = edits.collect { case Right(e) => e }.sortBy(-_._1)
          val out    = sorted.foldLeft(outerRewritten) { case (acc, (from, to, call)) =>
            acc.substring(0, from) + call + acc.substring(to)
          }
          val outView = SqlLexer.engineView(out)
          val left    = SqlLexer.tokens(out).exists { t =>
            identifierOf(outView, t).exists(placeholderNames.contains)
          }
          if left then Left(Unrestorable) else Right(out)

    /** A refusal's text with each placeholder read as the function it stands for. */
    def describe(reason: String): String =
      cuts.foldLeft(reason) { (acc, cut) =>
        acc.replaceAll(
          "(?i)" + java.util.regex.Pattern.quote(spans(cut.call).placeholder),
          java.util.regex.Matcher.quoteReplacement(cut.function)
        )
      }

  private val FromItemOpeners = Set("from", "join", ",", "(")

  private[cls] val Unrestorable: String =
    "the search call could not be put back after the column policy rewrite"

  private def lower(s: String): String   = s.toLowerCase(Locale.ROOT)
  private def unquote(s: String): String = ai.starlake.sql.QuotedIdentifier.unquote(s)

  private def text(view: String, t: SqlLexer.Token): String = view.substring(t.start, t.end)

  private def identifierOf(view: String, t: SqlLexer.Token): Option[String] =
    FunctionCalls.identifier(view, t)

  /** Right(None): the statement calls no search function. Left(reason): fail closed (a call the
    * token detector finds that the AST step does not replace, a correlated input, a call inside
    * another call's input, a CTE, an IN subquery or a set-operation arm holding a call).
    *
    * `sql` is the text the column-policy rewriter parses (the lexer-canonical rendering, which
    * [[separate(sql:String,detectorFlagged:Boolean,written:String)*]] distinguishes from the
    * client's own text). `detectorFlagged` is the router's flag, read from the client's own text: a
    * flagged statement in which this step finds no call is refused, never passed as a statement
    * with none.
    */
  def separate(sql: String, detectorFlagged: Boolean): Either[String, Option[Separated]] =
    separate(sql, detectorFlagged, sql)

  /** [[separate]] of `sql`, the canonical rendering of `written`, the client's statement: each
    * call's text is restored from `written`, so its constants are the client's bytes, and the text
    * of both must show the same calls.
    */
  def separate(
      sql: String,
      detectorFlagged: Boolean,
      written: String
  ): Either[String, Option[Separated]] =
    val scan     = FunctionCalls.scan(sql)
    val detected =
      scan.calls.count(c => ai.starlake.quack.edge.search.SearchFunctions.Names(c.name))
    val raw      = FunctionCalls.scan(written)
    val rawCount = raw.calls.count(c => ai.starlake.quack.edge.search.SearchFunctions.Names(c.name))
    if scan.truncated || raw.truncated then Left(NotSeparated)
    else if detected == 0 && rawCount == 0 then
      if detectorFlagged then Left(NotSeparated) else Right(None)
    else if detected != rawCount then Left(NotSeparated)
    else
      Try(ai.starlake.sql.JSqlStatements.parseFirst(sql)).toOption match
        case Some(stmt: Select) => separateParsed(sql, written, stmt, scan, raw, detected)
        case _                  => Left(NotSeparated)

  private[cls] val NotSeparated: String = "the search call could not be separated"

  private def separateParsed(
      sql: String,
      written: String,
      stmt: Select,
      scan: FunctionCalls.Scan,
      raw: FunctionCalls.Scan,
      detected: Int
  ): Either[String, Option[Separated]] =
    val nodes = StarExpansion.nodes(stmt)
    if nodes.exists(_.isInstanceOf[WithItem[?]]) then Left(NotSeparated)
    else
      val found = fromCalls(stmt)
      // Every call the detector counts must be one replaced here, and both texts must show exactly
      // those calls at top level, in the same order.
      val sites    = topLevelSites(sql)
      val rawSites = topLevelSites(written)
      if found.size != detected || sites.size != detected || rawSites.size != detected then
        Left(NotSeparated)
      else
        val taken = identifiersOf(scan) ++ identifiersOf(raw)
        val names = placeholderNames(taken, found.size)
        val built = found.zip(sites.zip(rawSites)).zip(names).zipWithIndex.map {
          case (((call, (site, rawSite)), ph), n) =>
            cutOf(sql, written, call, site, rawSite, ph, n)
        }
        built.collectFirst { case Left(reason) => reason } match
          case Some(reason) => Left(reason)
          case None         =>
            val cuts = built.collect { case Right(c) => c }
            // Replace in the tree, then print: the outer statement for the resolver.
            cuts.foreach { case (_, _, swap) => swap() }
            val builder = new java.lang.StringBuilder()
            stmt.accept(new net.sf.jsqlparser.util.deparser.StatementDeParser(builder))
            val outer        = builder.toString
            val placeholders = cuts.map { case (cut, span, _) =>
              span.placeholder -> Outputs(bareName(cut.function))
            }.toMap
            Right(
              Some(
                Separated(outer, cuts.map(_._1), placeholders)(
                  cuts.map { case (cut, span, _) => cut.call -> span }.toMap
                )
              )
            )

  /** The bare, unquoted, lowercased name of a function as written (`system.main.qod_search`). */
  private def bareName(written: String): String =
    val parts = written.split('.').toList
    lower(unquote(parts.lastOption.getOrElse(written).trim))

  /** A replaceable call: the node, its holder's setter, in text order. */
  private final case class FromCall(tf: TableFunction, set: FromItem => Unit)

  /** Every search call in a FROM slot this step replaces, in text order: the statement's SELECT,
    * its joins, parenthesized joins and derived tables in those slots, at any depth.
    */
  private def fromCalls(stmt: Select): List[FromCall] =
    def ofItem(item: FromItem, set: FromItem => Unit): List[FromCall] =
      item match
        case tf: TableFunction if isSearch(tf) => List(FromCall(tf, set))
        case ps: ParenthesedSelect             => ofSelect(ps.getSelect)
        case pfi: ParenthesedFromItem          =>
          ofItem(pfi.getFromItem, pfi.setFromItem) ++ ofJoins(pfi.getJoins)
        case _ => Nil
    def ofJoins(joins: java.util.List[Join]): List[FromCall] =
      Option(joins).map(_.asScala.toList).getOrElse(Nil).flatMap { j =>
        ofItem(j.getFromItem, fi => { j.setFromItem(fi); () })
      }
    def ofSelect(sel: Select): List[FromCall] =
      sel match
        case ps: PlainSelect       => ofItem(ps.getFromItem, ps.setFromItem) ++ ofJoins(ps.getJoins)
        case ps: ParenthesedSelect => ofSelect(ps.getSelect)
        case _                     => Nil
    ofSelect(stmt)

  private def isSearch(tf: TableFunction): Boolean =
    Option(tf.getFunction)
      .flatMap(f => Option(f.getMultipartName).map(_.asScala.toList))
      .flatMap(_.lastOption)
      .map(n => lower(unquote(n)))
      .exists(Outputs.contains)

  /** A call's place in the text: the index of the first token of its name, of its `(` and of its
    * matching `)`, in the non-trivia tokens of `sql`, and the token list.
    */
  private final case class Site(
      toks: Vector[SqlLexer.Token],
      nameFrom: Int,
      open: Int,
      close: Int,
      parts: Int
  )

  /** Every search call at the statement's own level of text (never in a literal body), in order. */
  private def topLevelSites(sql: String): List[Site] =
    val view                     = SqlLexer.engineView(sql)
    val toks                     = SqlLexer.tokens(sql).filterNot(_.isTrivia)
    def isPunct(i: Int, c: Char) =
      i >= 0 && i < toks.length && toks(i).kind == SqlLexer.Kind.Punct &&
        view.charAt(toks(i).start) == c
    toks.indices.toList.flatMap { i =>
      val name = identifierOf(view, toks(i))
      if name.exists(ai.starlake.quack.edge.search.SearchFunctions.Names) && isPunct(i + 1, '(')
      then
        // The qualifiers before the name (`system.main.`).
        var from  = i
        var parts = 1
        while from >= 2 && text(view, toks(from - 1)) == "." &&
          identifierOf(view, toks(from - 2)).isDefined
        do
          from -= 2
          parts += 1
        matching(view, toks, i + 1).map(close => Site(toks, from, i + 1, close, parts)).toList
      else Nil
    }

  /** The index of the `)` closing the `(` at `open`, or None when unbalanced. */
  private def matching(view: String, toks: Vector[SqlLexer.Token], open: Int): Option[Int] =
    var depth = 0
    var i     = open
    var found = -1
    while found < 0 && i < toks.length do
      val t = toks(i)
      if t.kind == SqlLexer.Kind.Punct then
        val c = view.charAt(t.start)
        if c == '(' then depth += 1
        else if c == ')' then
          depth -= 1
          if depth == 0 then found = i
      i += 1
    Option.when(found >= 0)(found)

  /** Every identifier of every text the detector scanned (the statement and each literal body),
    * lowercased: a placeholder takes none of them.
    */
  private def identifiersOf(scan: FunctionCalls.Scan): Set[String] =
    scan.texts.flatMap { t =>
      SqlLexer.tokens(t).flatMap(tok => identifierOf(SqlLexer.engineView(t), tok))
    }.toSet

  private def placeholderNames(taken: Set[String], n: Int): List[String] =
    Iterator.from(0).map(i => PlaceholderPrefix + i).filterNot(taken.contains).take(n).toList

  /** The cut of one call, its span and the tree edit replacing it by its placeholder. `site` is the
    * call in `sql` (what the rewriter parses), `rawSite` the same call in `written` (the client's
    * text, which the restore copies).
    */
  private def cutOf(
      sql: String,
      written: String,
      call: FromCall,
      site: Site,
      rawSite: Site,
      placeholder: String,
      n: Int
  ): Either[String, (Cut, Span, () => Unit)] =
    val f                      = call.tf.getFunction
    val args                   = Option(f.getParameters).map(_.asScala.toList).getOrElse(Nil)
    val alias                  = Option(call.tf.getAlias)
    val nameParts              = Option(f.getMultipartName).map(_.size).getOrElse(0)
    def holdsSelect(e: AnyRef) = StarExpansion.nodes(e).exists(_.isInstanceOf[Select])
    if call.tf.getPrefix != null then Left(NotSeparated)
    else if alias.exists(a => Option(a.getAliasColumns).exists(!_.isEmpty)) then Left(NotSeparated)
    else if nameParts != site.parts || nameParts != rawSite.parts then Left(NotSeparated)
    // Review m1: a call pairs with its text sites only when they show the same call.
    else if !sameCall(call.tf, args.size, sql, site) || !sameCall(
        call.tf,
        args.size,
        written,
        rawSite
      )
    then Left(NotSeparated)
    else if f.getNamedParameters != null || f.isDistinct || f.isAllColumns then Left(NotSeparated)
    else
      val input = args.headOption.collect { case ps: ParenthesedSelect => ps }
      val rest  = if input.isDefined then args.drop(1) else args
      val plain = input.forall(ps =>
        ps.getSelect match
          case inner: PlainSelect => unbound(inner, None).isEmpty
          case _                  => false
      )
      // Security review M2: the arguments after the input are constants (rule 7 on the node
      // judges them too); one holding a column reference or a subquery is refused here already.
      def holdsColumn(e: AnyRef) = StarExpansion.nodes(e).exists(_.isInstanceOf[Column])
      if rest.exists(a => holdsSelect(a) || holdsColumn(a)) || !plain then Left(NotSeparated)
      else
        for
          parsed <- pieces(sql, site, input.isDefined)
          raw    <- pieces(written, rawSite, input.isDefined)
        yield
          val (_, in, _)             = parsed
          val (rawHead, rawIn, tail) = raw
          val function               =
            written.substring(
              rawSite.toks(rawSite.nameFrom).start,
              rawSite.toks(rawSite.open).start
            )
          val added = if alias.isEmpty then f.getMultipartName.asScala.last else ""
          val cut   = Cut(n, function, in.trim, tail)
          val span  =
            Span(rawHead + leading(rawIn), rawIn.trim, trailing(rawIn) + tail, placeholder, added)
          val swap = () =>
            val t = new Table(placeholder)
            t.setAlias(alias.getOrElse(new Alias(added, false)))
            call.set(t)
          (cut, span, swap)

  /** True when `site` in `text` shows the call `tf`: the same last name part (lowercased), as many
    * top-level arguments, and the same alias (or none, the next token then ending the FROM item).
    */
  private def sameCall(tf: TableFunction, argCount: Int, sqlText: String, site: Site): Boolean =
    val view                      = SqlLexer.engineView(sqlText)
    val toks                      = site.toks
    def punct(i: Int, cs: String) =
      i < toks.length && toks(i).kind == SqlLexer.Kind.Punct && cs.indexOf(
        view.charAt(toks(i).start)
      ) >= 0
    val name = Option(tf.getFunction.getMultipartName)
      .map(_.asScala.toList)
      .getOrElse(Nil)
      .lastOption
      .map(n => lower(unquote(n)))
    var depth  = 0
    var commas = 0
    var i      = site.open + 1
    while i < site.close do
      if punct(i, "([{") then depth += 1
      else if punct(i, ")]}") then depth -= 1
      else if depth == 0 && punct(i, ",") then commas += 1
      i += 1
    val args    = if site.open + 1 == site.close then 0 else commas + 1
    val next    = site.close + 1
    val asAt    = toks.lift(next).exists(t => lower(text(view, t)) == "as")
    val after   = if asAt then next + 1 else next
    val written = toks.lift(after).flatMap(t => identifierOf(view, t))
    val alias   = Option(tf.getAlias).flatMap(a => Option(a.getName)).map(n => lower(unquote(n)))
    val aliasAgrees = alias match
      case Some(a) => written.contains(a)
      case None    => !asAt && written.forall(FromItemEnds.contains)
    name.isDefined && name == identifierOf(view, toks(site.open - 1)) && args == argCount &&
    aliasAgrees

  /** Words that can follow a FROM item with no alias. */
  private val FromItemEnds = Set(
    "join",
    "inner",
    "left",
    "right",
    "full",
    "outer",
    "cross",
    "natural",
    "positional",
    "asof",
    "anti",
    "semi",
    "on",
    "using",
    "where",
    "group",
    "having",
    "window",
    "qualify",
    "order",
    "limit",
    "offset",
    "union",
    "except",
    "intersect",
    "fetch"
  )

  /** The call of `site` in `text` as (head, input, tail): the input is the text inside the first
    * argument's parentheses, which must be followed by `,` or the call's `)`; with no input, the
    * head ends at the call's `(`.
    */
  private def pieces(
      text: String,
      site: Site,
      hasInput: Boolean
  ): Either[String, (String, String, String)] =
    val view                     = SqlLexer.engineView(text)
    val toks                     = site.toks
    def isPunct(i: Int, c: Char) =
      i < toks.length && toks(i).kind == SqlLexer.Kind.Punct && view.charAt(toks(i).start) == c
    val callFrom = toks(site.nameFrom).start
    val callTo   = toks(site.close).end
    if !hasInput then
      val open = toks(site.open).end
      Right((text.substring(callFrom, open), "", text.substring(open, callTo)))
    else if !isPunct(site.open + 1, '(') then Left(NotSeparated)
    else
      matching(view, toks, site.open + 1) match
        case Some(c) if c < site.close && (isPunct(c + 1, ',') || isPunct(c + 1, ')')) =>
          val from = toks(site.open + 1).end
          val to   = toks(c).start
          Right(
            (text.substring(callFrom, from), text.substring(from, to), text.substring(to, callTo))
          )
        case _ => Left(NotSeparated)

  private def leading(s: String): String  = s.takeWhile(_.isWhitespace)
  private def trailing(s: String): String = s.reverse.takeWhile(_.isWhitespace).reverse

  /** The first column reference of `input` that does not resolve inside it (a correlated input), or
    * None. Scope-aware, as DuckDB binds (security review I1): a reference resolves against the FROM
    * bindings of its own SELECT, then of each enclosing SELECT up to the input's root, never a
    * sibling's or a nested one's; a select-list alias counts only in its own SELECT. Past the root
    * it would bind to the outer statement's FROM items (a table in-out function's subquery argument
    * binds laterally), which the input's rewrite alone cannot mask. Fail closed: every FROM item of
    * the input must be a table (anything else is reported), a qualifier must name a binding, and,
    * with `columnsOf` (a table's bare name, lowercased, to its listed columns), an unqualified name
    * must be a column of a binding whose columns are all known, at the first level that holds one.
    * Without `columnsOf` (the separation, which has no catalog), unqualified names are not judged;
    * the column-policy rewriter judges them with the catalog's lists.
    */
  private[cls] def unbound(
      input: PlainSelect,
      columnsOf: Option[String => Option[List[String]]]
  ): Option[String] =
    // One SELECT's bindings: (name, the table's bare name).
    // A FROM table's binding: the name it binds, its bare table name and the parts it was written
    // with (lowercased, unquoted).
    final case class Binding(
        name: String,
        bare: String,
        schema: Option[String],
        catalog: Option[String],
        aliased: Boolean
    )
    final case class Level(bindings: List[Binding], aliases: Set[String])
    val seen = java.util.Collections.newSetFromMap(
      new java.util.IdentityHashMap[AnyRef, java.lang.Boolean]()
    )
    var found: Option[String]           = None
    def fail(why: String): Unit         = if found.isEmpty then found = Some(why)
    def part(s: String): Option[String] = Option(s).filter(_.nonEmpty).map(p => lower(unquote(p)))
    def levelOf(ps: PlainSelect): Level =
      val items = Option(ps.getFromItem).toList ++
        Option(ps.getJoins).map(_.asScala.toList).getOrElse(Nil).map(_.getFromItem)
      val bindings = items.flatMap {
        case t: Table if t.getName != null =>
          val bare  = lower(unquote(t.getName))
          val alias = Option(t.getAlias).flatMap(a => part(a.getName))
          // An aliased table binds its alias only: DuckDB reads its bare name as an outer one.
          List(
            Binding(
              alias.getOrElse(bare),
              bare,
              part(t.getSchemaName),
              Option(t.getDatabase).flatMap(d => part(d.getDatabaseName)),
              alias.isDefined
            )
          )
        case other =>
          fail(s"a FROM item other than a table: ${other.getClass.getSimpleName}")
          Nil
      }
      val aliases = Option(ps.getSelectItems).map(_.asScala.toList).getOrElse(Nil).flatMap { si =>
        Option(si.getAlias).flatMap(a => Option(a.getName)).map(n => lower(unquote(n)))
      }
      Level(bindings, aliases.toSet)
    def holds(b: Binding, column: String): Boolean =
      columnsOf match
        case None         => true
        case Some(lookup) =>
          column == "rowid" || lookup(b.bare).exists(_.exists(c => lower(c) == column))
    def resolves(c: Column, stack: List[Level]): Boolean =
      val column = lower(unquote(c.getColumnName))
      Option(c.getTable).flatMap(t => Option(t.getName)) match
        case Some(q) =>
          // Verification I1: the innermost level binding the qualifier must hold the column, and
          // a schema or catalog part must be the binding table's own, as written: else DuckDB
          // binds the reference to the outer statement's item of that name.
          val name     = lower(unquote(q))
          val schema   = part(c.getTable.getSchemaName)
          val catalog  = Option(c.getTable.getDatabase).flatMap(d => part(d.getDatabaseName))
          val matching = stack.iterator.map(_.bindings.filter(_.name == name)).find(_.nonEmpty)
          matching.exists(_.exists { b =>
            val partsAgree =
              (schema.isEmpty && catalog.isEmpty) ||
                (!b.aliased && b.schema == schema && (catalog.isEmpty || b.catalog == catalog))
            partsAgree && holds(b, column)
          })
        case None =>
          columnsOf match
            case None         => true
            case Some(lookup) =>
              val name = column
              if Constants(name) || stack.headOption.exists(_.aliases.contains(name)) then true
              else
                // The first level, innermost first, whose bindings hold the name, with every
                // binding's columns known up to it; an unknown list leaves it undecided.
                stack.iterator
                  .map { level =>
                    val lists = level.bindings.map(b => lookup(b.bare).map(_.map(lower)))
                    if lists.exists(_.isEmpty) then Some(false)
                    else if name == "rowid" && level.bindings.nonEmpty then Some(true)
                    else if lists.flatten.exists(_.contains(name)) then Some(true)
                    else None
                  }
                  .collectFirst { case Some(r) => r }
                  .getOrElse(false)
    def walk(value: Any, stack: List[Level]): Unit =
      value match
        case null                            => ()
        case it: java.lang.Iterable[?]       => it.forEach(walk(_, stack))
        case arr: Array[?]                   => arr.foreach(walk(_, stack))
        case e: java.util.Map.Entry[?, ?]    => walk(e.getKey, stack); walk(e.getValue, stack)
        case m: java.util.Map[?, ?]          => m.entrySet.forEach(walk(_, stack))
        case ps: PlainSelect if seen.add(ps) =>
          val inner = levelOf(ps) :: stack
          ai.starlake.acl.parser.AstChildren
            .accessors(ps.getClass)
            .foreach(m => walk(ai.starlake.acl.parser.AstChildren.read(m, ps), inner))
        case c: Column if seen.add(c) =>
          if !resolves(c, stack) then fail(s"an unbound reference: ${c.getFullyQualifiedName}")
        // A FROM table is a binding, never a reference.
        case _: Table                                                                    => ()
        case n: AnyRef if ai.starlake.acl.parser.AstChildren.isAstNode(n) && seen.add(n) =>
          ai.starlake.acl.parser.AstChildren
            .accessors(n.getClass)
            .foreach(m => walk(ai.starlake.acl.parser.AstChildren.read(m, n), stack))
        case _ => ()
    walk(input, Nil)
    found

  /** Names JSqlParser reads as a column that are constants to DuckDB. */
  private val Constants = Set("true", "false")
