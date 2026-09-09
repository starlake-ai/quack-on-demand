package ai.starlake.quack.edge.rls

/** String/identifier-literal-aware scan for SQL constructs that are unsafe once a validated
  * predicate or transform expression is spliced textually into a larger statement.
  * [[RowPolicyRewriter]] and [[ai.starlake.quack.edge.cls.ColumnPolicyRewriter]] both embed the
  * create-time-validated string directly into generated SQL (`"(" + predicate + ")"`, joined with
  * `" OR "` for row policies); jsqlparser's own parse of the predicate/transform in isolation
  * proves nothing about what happens once it is concatenated. A trailing line comment (two hyphens)
  * can swallow the rewriter's own closing parenthesis, a block comment (slash-star, star-slash) can
  * hide arbitrary following text, and a bare semicolon can smuggle a second statement past a parser
  * that only validates the first one - and jsqlparser accepts (and silently truncates at) all three
  * rather than failing the parse, so none of them get caught by the parse step alone.
  *
  * The scan tracks single-quoted string-literal state (honouring the `''` escape) and double-quoted
  * identifier state so a comment marker or semicolon that appears INSIDE a literal or a quoted
  * identifier (e.g. `note = '--x'`, `"weird--name"`) is never flagged - those characters are
  * ordinary content there, not comment/statement syntax. Mirrors the equivalent scan in
  * [[ai.starlake.quack.edge.admin.AdminSqlParser.parenExpression]].
  *
  * Shared by [[RowPredicateValidator]] and [[ai.starlake.quack.edge.cls.TransformSqlValidator]] so
  * both create-time validators enforce the same splice-safety class.
  */
private[edge] object SqlCommentScan:

  /** True if `sql` contains a line comment or a block comment marker outside any string literal or
    * quoted identifier.
    */
  def hasComment(sql: String): Boolean = scan(sql).comment

  /** True if `sql` contains a bare `;` outside any string literal or quoted identifier. */
  def hasSemicolon(sql: String): Boolean = scan(sql).semicolon

  private final case class Findings(comment: Boolean, semicolon: Boolean)

  private def scan(sql: String): Findings =
    var inStr     = false
    var inQuote   = false
    var comment   = false
    var semicolon = false
    var j         = 0
    val n         = sql.length
    while j < n do
      val c = sql.charAt(j)
      if inStr then
        if c == '\'' then if j + 1 < n && sql.charAt(j + 1) == '\'' then j += 1 else inStr = false
        j += 1
      else if inQuote then
        if c == '"' then inQuote = false
        j += 1
      else if c == '\'' then { inStr = true; j += 1 }
      else if c == '"' then { inQuote = true; j += 1 }
      else if c == '-' && j + 1 < n && sql.charAt(j + 1) == '-' then { comment = true; j += 1 }
      else if c == '/' && j + 1 < n && sql.charAt(j + 1) == '*' then { comment = true; j += 1 }
      else if c == ';' then { semicolon = true; j += 1 }
      else j += 1
    Findings(comment, semicolon)
