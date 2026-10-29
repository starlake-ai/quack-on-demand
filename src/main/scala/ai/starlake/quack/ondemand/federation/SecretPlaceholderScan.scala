package ai.starlake.quack.ondemand.federation

/** Where each `{{secret.NAME}}` of a `sql` federated source's template sits (CONTRACT Addendum 4
  * item 29, amended): inside a single-quoted SQL literal, where the substitution's quote doubling
  * keeps any value a value, or anywhere else, where a value could end the literal, the comment or
  * the identifier around it and become SQL of the node's trusted init.
  *
  * The scan mirrors DuckDB v2.0.0's tokenizer (`src/parser/peg/tokenizer/base_tokenizer.cpp`,
  * `TokenizeInputInternal`) for the states that decide it: single-quoted strings with `''` doubling
  * and no backslash escapes, quoted identifiers (`"` and the backtick, doubled delimiter), line
  * comments ending at `\n` or `\r`, nested block comments. Where a construct's extent could differ
  * between DuckDB's tokenizer and its other parser, or depends on rules the scan would have to
  * re-implement, it fails closed: a quote right after `E` / `e` (an escape string, where a
  * backslash in the value would escape the closing quote), and any `$` outside strings, comments
  * and identifiers (a dollar-quoted string or a parameter) make every placeholder of the template
  * unquoted. So does a literal never closed. Only a well-formed placeholder counts; a stray `{{` is
  * the blob builder's own refusal.
  */
object SecretPlaceholderScan:

  private val Placeholder = """\{\{secret\.([A-Za-z0-9_]+)\}\}""".r

  private val PlainToken = "[A-Za-z0-9_.-]+".r

  /** Whether `value` may stand outside a literal in a template stored before the rule (item 29c): a
    * plain token (`PORT {{secret.X}}` with `5432`) holding no `--` and neither starting nor ending
    * with `-`, so that neither the value alone nor the value against a `-` of the template opens a
    * comment.
    */
  def plainValue(value: String): Boolean =
    PlainToken.matches(value) && !value.contains("--") && !value.startsWith("-") &&
      !value.endsWith("-")

  /** A template character that may not touch an unquoted placeholder (item 29c): a quote (a value
    * `E` before `'` makes an escape string), `-`, `/`, `*` (a comment), `$`, or a letter or digit
    * (the value would join the token beside it).
    */
  private def glues(c: Char): Boolean = "'\"`-/*$".indexOf(c) >= 0 || c.isLetterOrDigit

  /** The distinct names of the placeholders of `template` that do not sit inside a closed
    * single-quoted literal, in order of first appearance; empty when every one does.
    */
  def unquoted(template: String): List[String] =
    unquotedMatches(template).map(_.group(1)).distinct

  /** The first of `values` (name, resolved value) that the stored `template` may not have
    * substituted (item 29c): an unquoted placeholder whose value is not [[plainValue]], or one that
    * some occurrence of it outside a literal has a gluing character right before or after. None
    * when every value may be substituted; a placeholder inside a literal is always substituted,
    * quoted.
    */
  def refusedAtRender(template: String, values: List[(String, String)]): Option[String] =
    val outside   = unquotedMatches(template)
    val misplaced = outside
      .filter(m =>
        (m.start > 0 && glues(template.charAt(m.start - 1))) ||
          (m.end < template.length && glues(template.charAt(m.end)))
      )
      .map(_.group(1))
      .toSet
    val names = outside.map(_.group(1)).toSet
    values.collectFirst {
      case (name, value) if names(name) && (misplaced(name) || !plainValue(value)) => name
    }

  private def unquotedMatches(template: String): List[scala.util.matching.Regex.Match] =
    val inLiteral = literalSpans(template)
    Placeholder
      .findAllMatchIn(template)
      .filterNot(m => inLiteral.exists { case (s, e) => m.start > s && m.end <= e })
      .toList

  /** The half-open spans `[open quote, closing quote)` of the closed plain single-quoted literals
    * of `template`, or none at all when the template holds a construct the scan does not judge (see
    * the object comment): then nothing counts as inside a literal.
    */
  private def literalSpans(sql: String): List[(Int, Int)] =
    val spans            = List.newBuilder[(Int, Int)]
    var ambiguous        = false
    var i                = 0
    val n                = sql.length
    def at(k: Int): Char = if k < n then sql.charAt(k) else '\u0000'
    while i < n && !ambiguous do
      val c = sql.charAt(i)
      if c == '\'' then
        if i > 0 && (sql.charAt(i - 1) == 'E' || sql.charAt(i - 1) == 'e') then ambiguous = true
        else
          var j      = i + 1
          var closed = false
          while j < n && !closed do
            if sql.charAt(j) == '\'' then
              if at(j + 1) == '\'' then j += 2
              else closed = true
            else j += 1
          if closed then
            spans += ((i, j))
            i = j + 1
          else ambiguous = true
      else if c == '"' || c == '`' then
        var j      = i + 1
        var closed = false
        while j < n && !closed do
          if sql.charAt(j) == c then
            if at(j + 1) == c then j += 2
            else closed = true
          else j += 1
        i = j + 1
      else if c == '-' && at(i + 1) == '-' then
        var j = i + 2
        while j < n && sql.charAt(j) != '\n' && sql.charAt(j) != '\r' do j += 1
        i = j + 1
      else if c == '/' && at(i + 1) == '*' then
        var depth = 1
        var j     = i + 2
        while j < n && depth > 0 do
          if sql.charAt(j) == '/' && at(j + 1) == '*' then
            depth += 1
            j += 2
          else if sql.charAt(j) == '*' && at(j + 1) == '/' then
            depth -= 1
            j += 2
          else j += 1
        i = j
      else if c == '$' then ambiguous = true
      else i += 1
    if ambiguous then Nil else spans.result()
