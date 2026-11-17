package ai.starlake.quack.search

import java.util.Locale

/** The matrix of row and column policies for search (spec 12 Phase 1 item 22, Task 18), as data:
  * `(user, edge, statement, expectation)`. [[SearchEndToEndSpec]] runs every row on a real manager
  * and a real qod-node, through Flight SQL (JDBC, TLS) and the native Quack front door (the DuckDB
  * client's `ATTACH 'quack:...'`).
  *
  * Every expectation comes from the spec and from an independent reference, never from the code
  * under test: the rows are read by a superuser's plain SQL ([[Doc]]), the population is the one
  * spec 7.5 names for the principal (the table's live rows, or the live rows its row policy
  * allows), and BM25 is computed here in f64 ([[Bm25]]): N and df over the population, avgdl from
  * exact token counts, k1 = 1.2, b = 0.75, each term's contribution summed. Every body holds at
  * most 40 tokens, so a doc's length is its Tantivy fieldnorm (the codes are the identity up to
  * 40), and is made of lowercase ASCII words, so the analyzer (lower case, split on every
  * non-alphanumeric character) is the split below.
  *
  * Task 9 built the CLS search separation (no narrowing), so `clsmask` and `both` read masked
  * results, never a column-policy denial.
  */
object SearchEndToEndCases:

  enum Edge:
    case Flight, Quack

  /** The users of the matrix. `rowPolicy`: a row policy `f10 = 0` on `pages`, `pages_rw` and
    * `pages_behind`. `titleMasked`: a mask `'***'` on `pages.title` (and `pages_rw.title`).
    */
  enum User(val login: String, val rowPolicy: Boolean, val titleMasked: Boolean):
    case Plain     extends User("plain", false, false)
    case Rls10     extends User("rls10", true, false)
    case ClsMask   extends User("clsmask", false, true)
    case ClsSearch extends User("clssearch", false, false)
    case Both      extends User("both", true, true)
    case Other     extends User("other", false, false)
    case Admin     extends User("admin", false, false)

  /** The users who may search `pages` (a read grant, and no column policy on its search column). */
  val Searchers: List[User] = List(User.Plain, User.Rls10, User.ClsMask, User.Both, User.Admin)

  /** A live row as a superuser's plain SELECT reads it. */
  final case class Doc(rowid: Long, id: Long, title: String, body: String, f10: Int, cat: String)

  /** The mask on `title`. */
  val Mask = "***"

  val K1 = 1.2
  val B  = 0.75

  /** The corpus of `pages` (and the head of `pages_rw` and `pages_behind`): id i, title `t<i>`, a
    * body of 3 to 13 words drawn from a small vocabulary by a linear congruential generator seeded
    * with i (so terms repeat within a doc, lengths vary, and every residue of i mod 10 holds
    * matches), `f10` = i % 10 and `cat` one of three.
    */
  val Vocabulary: Vector[String] =
    Vector("lake", "duck", "river", "stone", "cloud", "quack", "north", "amber", "delta", "pine")
  val Cats: Vector[String] = Vector("alpha", "beta", "gamma")

  def body(i: Int): String =
    var x            = (i.toLong * 7919L + 13L) & 0x7fffffffL
    def next(): Long =
      x = (x * 1103515245L + 12345L) & 0x7fffffffL
      x >> 16
    val n = 3 + (next() % 11).toInt
    (0 until n).map(_ => Vocabulary((next() % Vocabulary.size).toInt)).mkString(" ")

  def valuesOf(ids: Range): String =
    ids
      .map(i => s"($i, 't$i', '${body(i)}', ${i % 10}, '${Cats(i % Cats.size)}')")
      .mkString(", ")

  /** The analyzer the reference assumes: lower case, split on every non-alphanumeric character. */
  def tokens(text: String): List[String] =
    text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+").filter(_.nonEmpty).toList

  /** A reference score and its accuracy bound (invariant 7: 1e-6 (1 + k1) max(1, idf) per matched
    * term), plus f32's own rounding of the returned FLOAT.
    */
  final case class Ref(score: Double, bound: Double):
    def tolerance: Double = bound + 2e-7 * math.abs(score) + 1e-7

  object Bm25:

    /** Each doc of `candidates` matching any term of `query`, scored over `population`. */
    def scores(population: Seq[Doc], candidates: Seq[Doc], query: String): Map[Long, Ref] =
      val terms     = tokens(query).distinct
      val docTokens = population.map(d => d.rowid -> tokens(d.body)).toMap
      val n         = population.size.toDouble
      val avgdl     = docTokens.values.map(_.size.toDouble).sum / n
      val df = terms.map(t => t -> population.count(d => docTokens(d.rowid).contains(t))).toMap
      candidates.flatMap { d =>
        val toks    = tokens(d.body)
        val matched = terms.filter(toks.contains)
        Option.when(matched.nonEmpty) {
          val parts = matched.map { t =>
            val tf  = toks.count(_ == t).toDouble
            val idf = math.log(1.0 + (n - df(t) + 0.5) / (df(t) + 0.5))
            val nrm = K1 * (1.0 - B + B * toks.size / avgdl)
            (idf * (1.0 + K1) * (tf / (tf + nrm)), 1e-6 * (1.0 + K1) * math.max(1.0, idf))
          }
          d.rowid -> Ref(parts.map(_._1).sum, parts.map(_._2).sum)
        }
      }.toMap

    /** The top `k` of `scored`: score descending, ties by rowid (invariant 7). */
    def top(scored: Map[Long, Ref], k: Int): List[(Long, Ref)] =
      scored.toList.sortBy((rowid, r) => (-r.score, rowid)).take(k)

  /** What a statement must answer. */
  sealed trait Expect

  object Expect:

    /** Ranked hits: rows `(key, [title,] score, rank)` in rank order, `key` the hit's id (a
      * join-back) or rowid (no join-back). `scored` holds every match in the population with its
      * key; `title` the title each key must read, when the statement selects it. `differs`: scores
      * the answer must NOT match (the table population, for a row-policy holder: spec 7.5).
      */
    final case class Ranked(
        scored: Map[Long, Ref],
        k: Int,
        title: Option[Long => String],
        differs: Option[Map[Long, Ref]] = None
    ) extends Expect

    /** Facet counts over every match, by category. */
    final case class Facets(counts: Map[String, Long]) extends Expect

    /** No row at all. */
    case object NoRows extends Expect

    /** A refusal: its Flight status code, and texts its message (Flight) or the DuckDB client's
      * error (front door) holds and must not hold.
      */
    final case class Refused(code: String, has: List[String], hasNot: List[String] = Nil)
        extends Expect

  /** One matrix row. `sql` and `expect` read the world the spec built (names, the rows). */
  final case class Case(
      user: User,
      edges: List[Edge],
      name: String,
      sql: World => String,
      expect: World => Expect
  )

  /** What the spec built: the catalog, the live rows of `pages` now and at snapshot `v0` (before
    * the delete), `v0`'s commit time (a timestamp DuckLake resolves to a real snapshot), and the
    * caps.
    */
  final case class World(
      catalog: String,
      pagesNow: List[Doc],
      pagesAtV0: List[Doc],
      v0: Long,
      v0Time: String,
      behindRows: Int,
      cap: Int
  ):
    def pages: String  = s"$catalog.main.pages"
    def behind: String = s"$catalog.main.pages_behind"
    def rw: String     = s"$catalog.main.pages_rw"

    /** The population spec 7.5 names for `u` over `docs`. */
    def population(u: User, docs: List[Doc]): List[Doc] =
      if u.rowPolicy then docs.filter(_.f10 == 0) else docs

    /** Every match of `query` over `u`'s population of `docs`, keyed by id. */
    def byId(u: User, docs: List[Doc], query: String, input: Doc => Boolean = _ => true) =
      val pop = population(u, docs)
      val ids = docs.map(d => d.rowid -> d.id).toMap
      Bm25.scores(pop, pop.filter(input), query).map((rowid, r) => ids(rowid) -> r)

    /** The title `u` reads for id `id`. */
    def titleFor(u: User)(id: Long): String = if u.titleMasked then Mask else s"t$id"

    /** The two forms of `search_index_behind` (Task 10, D5): the backlog for a table-population
      * principal, no count for the allowed population.
      */
    def behindText(u: User): String =
      if u.rowPolicy then
        s"Permission Error: QoD: search_index_behind: $behind has more unindexed rows than the " +
          s"search reads (cap $cap); retry once the indexer catches up"
      else
        s"Permission Error: QoD: search_index_behind: $behind has $behindRows unindexed live " +
          s"rows (cap $cap); retry once the indexer catches up"

  val Q1 = "duck"
  val Q2 = "duck river"

  private val Both = List(Edge.Flight, Edge.Quack)

  /** `qod_search` over `table` with its join-back. */
  def topSql(table: String, query: String, where: String = ""): String =
    s"SELECT p.id, p.title, h.score, h.rank FROM qod_search((SELECT rowid FROM $table$where), " +
      s"'$table', ['body'], '$query') h JOIN $table p ON p.rowid = h.rowid ORDER BY h.rank"

  /** `qod_search_matches` with a facet `GROUP BY` over its join-back. */
  def facetSql(table: String, query: String): String =
    s"SELECT p.cat, count(*) AS n FROM qod_search_matches((SELECT rowid FROM $table), " +
      s"'$table', ['body'], '$query') m JOIN $table p ON p.rowid = m.rowid " +
      "GROUP BY p.cat ORDER BY p.cat"

  /** `qod_search` with no join-back: rowids, scores and ranks. */
  def bareSql(table: String, query: String, at: String = ""): String =
    s"SELECT h.rowid, h.score, h.rank FROM qod_search((SELECT rowid FROM $table$at), " +
      s"'$table', ['body'], '$query') h ORDER BY h.rank"

  /** A facet over the matches with no join-back (the second function, for the refusal rows). */
  def matchesSql(table: String, query: String): String =
    s"SELECT count(m.rowid) AS n FROM qod_search_matches((SELECT rowid FROM $table), " +
      s"'$table', ['body'], '$query') m"

  /** Facet counts by category over every match of `query` in `u`'s population. */
  private def facets(w: World, u: User, query: String): Map[String, Long] =
    val hits = w.byId(u, w.pagesNow, query).keySet
    w.pagesNow.filter(d => hits(d.id)).groupBy(_.cat).view.mapValues(_.size.toLong).toMap

  private def ranked(w: World, u: User, query: String): Expect.Ranked =
    Expect.Ranked(
      w.byId(u, w.pagesNow, query),
      10,
      Some(w.titleFor(u)),
      // A row-policy holder's scores are the allowed population's, which differ from the table
      // population's on the same ids (spec 7.5).
      Option.when(u.rowPolicy)(w.byId(User.Plain, w.pagesNow, query))
    )

  private val Star =
    Expect.Refused("UNAUTHORIZED", List("access denied", "search_star_unsupported"))

  /** A refusal of the gateway's own component whose text is `own` (a policy rewriter or the guard):
    * `access denied` with that text, never the node's `Permission Error` nor the star screen's
    * refusal (review I1: each refusal row names the component that refused).
    */
  def gatewayDenied(own: String): Expect.Refused =
    Expect.Refused(
      "UNAUTHORIZED",
      List("access denied", own),
      List("Permission Error", SearchStarCode)
    )

  /** The star screen's code (`SearchStarScreen.Code`, spec 4.2 rule 10). */
  val SearchStarCode = "search_star_unsupported"

  /** The policy rewriters' texts for a statement they cannot parse, and ruling B's refusal. */
  val ColumnPolicyUnparsed = "column policy rewrite could not parse statement"
  val RowPolicyUnparsed    = "row policy rewrite could not parse statement"
  val RowPolicyTimeTravel  = "a time-travel clause (AT ...), which the row filter cannot keep"

  private val AtTimestamp =
    Expect.Refused("INVALID_ARGUMENT", List("search_at_timestamp_unsupported"))

  /** The rows every principal that may search `pages` runs, on both edges. */
  private def searcher(u: User): List[Case] = List(
    Case(
      u,
      Both,
      s"qod_search '$Q1' with its join-back",
      w => topSql(w.pages, Q1),
      ranked(_, u, Q1)
    ),
    Case(
      u,
      Both,
      s"qod_search '$Q2' with its join-back",
      w => topSql(w.pages, Q2),
      ranked(_, u, Q2)
    ),
    Case(
      u,
      Both,
      s"qod_search_matches '$Q2' with a facet GROUP BY",
      w => facetSql(w.pages, Q2),
      w => Expect.Facets(facets(w, u, Q2))
    ),
    // Policy holders are refused AT reads at the gateway (lead rulings, Task 18 fix round 1).
    // AT (VERSION => n) for any policy holder (rls10, clsmask, both) is pre-existing, on every
    // statement and fail closed: JSqlParser cannot parse DuckDB's AT (VERSION => n), so the
    // column-policy and row-policy rewriters deny the statement they cannot read; carrying the clause
    // through them is new capability, out of Phase 1. AT (TIMESTAMP => t) for a row-policy holder in
    // a search statement is refused by the wrap (ruling B), which would otherwise drop the clause.
    Case(
      u,
      Both,
      if u.rowPolicy || u.titleMasked then "AT (VERSION => v0) refused at the gateway"
      else "AT (VERSION => v0) answered at v0",
      w => bareSql(w.pages, Q1, s" AT (VERSION => ${w.v0})"),
      w =>
        // The column-policy step runs before the row-policy wrap: `both` reads its refusal.
        if u.titleMasked then gatewayDenied(ColumnPolicyUnparsed)
        else if u.rowPolicy then gatewayDenied(RowPolicyUnparsed)
        else
          Expect.Ranked(
            Bm25.scores(w.population(u, w.pagesAtV0), w.population(u, w.pagesAtV0), Q1),
            10,
            None
          )
    ),
    Case(
      u,
      Both,
      if u.rowPolicy then "AT (TIMESTAMP => t) refused at the gateway"
      else "AT (TIMESTAMP => t) refused",
      w => bareSql(w.pages, Q1, s" AT (TIMESTAMP => TIMESTAMPTZ '${w.v0Time}')"),
      _ => if u.rowPolicy then gatewayDenied(RowPolicyTimeTravel) else AtTimestamp
    ),
    Case(
      u,
      Both,
      "SELECT * refused",
      w =>
        s"SELECT * FROM qod_search((SELECT rowid FROM ${w.pages}), '${w.pages}', ['body'], '$Q1')",
      _ => Star
    ),
    Case(
      u,
      Both,
      "p.* refused",
      w =>
        s"SELECT p.*, h.score FROM qod_search((SELECT rowid FROM ${w.pages}), '${w.pages}', " +
          s"['body'], '$Q1') h JOIN ${w.pages} p ON p.rowid = h.rowid",
      _ => Star
    ),
    Case(
      u,
      Both,
      "a table over the caps answered UNAVAILABLE",
      w => bareSql(w.behind, Q1),
      w => Expect.Refused("UNAVAILABLE", List(w.behindText(u)))
    )
  )

  /** The input's `WHERE` on `title`: masked for a holder of the mask (Task 9), so `title = '***'`
    * keeps every row of the population and `title = 't<id>'` none; a principal with no mask reads
    * the real titles.
    */
  private def maskedInput(u: User): List[Case] = List(
    Case(
      u,
      Both,
      "the input's WHERE on title = '***'",
      w => topSql(w.pages, Q1, s" WHERE title = '$Mask'"),
      w =>
        if u.titleMasked then ranked(w, u, Q1)
        else Expect.NoRows
    ),
    Case(
      u,
      Both,
      "the input's WHERE on title = a real title",
      w => topSql(w.pages, Q1, s" WHERE title = 't${firstHit(w, u)}'"),
      w =>
        if u.titleMasked then Expect.NoRows
        else
          // The input keeps one row; the population stays the principal's (spec 7.5).
          val id = firstHit(w, u)
          Expect.Ranked(w.byId(u, w.pagesNow, Q1, _.id == id), 10, Some(w.titleFor(u)))
    )
  )

  /** The best hit of `Q1` in `u`'s population: a title the input filters on. */
  def firstHit(w: World, u: User): Long = Bm25.top(w.byId(u, w.pagesNow, Q1), 1).head._1

  /** A column policy on the search column (rule 5, at the decider): both functions refused. */
  private val clsSearch: List[Case] =
    val u = User.ClsSearch
    List(
      Case(
        u,
        Both,
        "qod_search refused by rule 5",
        w => topSql(w.pages, Q1),
        _ =>
          Expect.Refused(
            "UNAUTHORIZED",
            List("access denied", "search column body carries a column policy"),
            List("search_index_behind", SearchStarCode)
          )
      ),
      Case(
        u,
        Both,
        "qod_search_matches refused by rule 5",
        w => matchesSql(w.pages, Q1),
        _ =>
          Expect.Refused(
            "UNAUTHORIZED",
            List("access denied", "search column body carries a column policy"),
            List("search_index_behind", SearchStarCode)
          )
      ),
      Case(
        u,
        Both,
        "SELECT * refused",
        w =>
          s"SELECT * FROM qod_search((SELECT rowid FROM ${w.pages}), '${w.pages}', ['body'], '$Q1')",
        _ => Star
      ),
      // Its mask is on pages.body only: pages_behind's body is a column it may search.
      Case(
        u,
        Both,
        "a table over the caps answered UNAVAILABLE",
        w => bareSql(w.behind, Q1),
        w => Expect.Refused("UNAVAILABLE", List(w.behindText(u)))
      )
    )

  /** No grant on the search tables: refused, naming the table. */
  private val other: List[Case] =
    val u = User.Other
    List(
      Case(
        u,
        Both,
        "qod_search refused",
        w => topSql(w.pages, Q1),
        w => Expect.Refused("UNAUTHORIZED", List("access denied", s"lacks grants on ${w.pages}"))
      ),
      Case(
        u,
        Both,
        "qod_search_matches refused",
        w => matchesSql(w.pages, Q1),
        w => Expect.Refused("UNAUTHORIZED", List("access denied", s"lacks grants on ${w.pages}"))
      ),
      Case(
        u,
        Both,
        "SELECT * refused",
        w =>
          s"SELECT * FROM qod_search((SELECT rowid FROM ${w.pages}), '${w.pages}', ['body'], '$Q1')",
        _ => Star
      ),
      Case(
        u,
        Both,
        "a table over the caps refused",
        w => bareSql(w.behind, Q1),
        w =>
          Expect.Refused(
            "UNAUTHORIZED",
            List("access denied", s"lacks grants on ${w.behind}"),
            List("search_index_behind")
          )
      )
    )

  /** A whole-row reference is a star (security review O1, lead ruling): `SELECT p` reads the whole
    * row, which the row-policy wrap gives a `rowid` field, so it is refused for everyone.
    */
  private def wholeRowRef(u: User): Case =
    Case(
      u,
      Both,
      "a whole-row reference refused",
      w =>
        s"SELECT p FROM ${w.pages} p, qod_search((SELECT rowid FROM ${w.pages}), '${w.pages}', " +
          s"['body'], '$Q1') h WHERE p.rowid = h.rowid",
      _ => Star
    )

  /** Every row of the matrix that runs one statement. */
  val matrix: List[Case] =
    Searchers.flatMap(searcher) ++
      List(User.Plain, User.ClsMask, User.Both).flatMap(maskedInput) ++
      clsSearch ++ other ++ List(User.Plain, User.Rls10).map(wholeRowRef)
