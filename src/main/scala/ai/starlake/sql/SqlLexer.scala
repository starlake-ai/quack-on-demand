package ai.starlake.sql

/** The ONE lexical reader behind every SQL screen: `SqlCommentStripper.stripComments`,
  * `StatementHead.split` and `StatementClassifier`'s word reader. Each disagreement between a
  * screen and DuckDB about where a literal or a comment ends is a bypass (a quote the screen opens
  * and DuckDB does not hides executable text from the screen), and three sibling readers with their
  * own character classes is how those disagreements kept regenerating. So this object ports
  * DuckDB's own front end rather than approximating it, from the DuckDB 2.0 source the bundled CLI
  * is built from (`src/parser/parser.cpp` `StripUnicodeSpaces` and
  * `src/parser/peg/tokenizer/base_tokenizer.cpp` `TokenizeInputInternal`, commit b15358c), every
  * rule below confirmed against that CLI:
  *
  *   1. A PRE-PASS ([[engineView]]) first turns U+00A0, U+2000..U+200B, U+202F, U+205F, U+2060,
  *      U+3000 and U+FEFF into ASCII spaces, but only outside what its own, simpler quote scan
  *      believes is a literal (`'...'` and `"..."` with doubling, `$tag$...$tag$`, `--` comments;
  *      it knows no block comment, no E-string escape and no backtick). So `SELECT 1 /* ' */ AS
  *      a<NBSP>$q$, 2 /* ' */` keeps both NBSPs and returns a column named `a<NBSP>$q$`. Every
  *      other character at or above U+0080 is an identifier character to the tokenizer.
  *   2. The TOKENIZER then reads, from a token start: `'...'` (`''` doubles, a backslash is
  *      ordinary); `e'...'`/`E'...'` (`''` doubles and a backslash escapes the next character),
  *      opened only when the `e` STARTS a token, so `ze'..'`, `a$e'..'` and `x<EURO>e'..'` are an
  *      identifier then a plain string while `1e'..'` is the number 1 then an escape string;
  *      `"..."` and, new in 2.0, backtick identifiers, each doubling its own delimiter;
  *      `$tag$...$tag$` (tag empty, or ASCII letters, digits, `_` and any non-ASCII, not starting
  *      with a digit), opened only at a token start, so a `$` inside an identifier (`a$q$`,
  *      `a<EURO>$q$`, `a<U+0001>$q$`) continues it; `--` comments ending at a line feed or a
  *      carriage return; nested block comments. An identifier continues through ASCII letters,
  *      digits, `_`, `$`, ASCII control characters and anything non-ASCII.
  *
  * Two deliberate departures, both toward leaving MORE text visible to a screen and both on
  * statements DuckDB rejects outright: a dollar opener with no closing tag is a lone `$` here (on
  * DuckDB it is an unterminated string), and an unterminated block comment or quote runs to the end
  * of the input.
  */
private[starlake] object SqlLexer:

  enum Kind:
    case Space, LineComment, BlockComment, Literal, Word, Number, Operator, Punct

  /** One token: `[start, end)` of the input. Tokens cover the input exactly, in order. A
    * `LineComment` stops AT its terminator, which follows as a `Space` token.
    */
  final case class Token(kind: Kind, start: Int, end: Int):
    def isTrivia: Boolean =
      kind == Kind.Space || kind == Kind.LineComment || kind == Kind.BlockComment

  /** The tokens of `sql` as DuckDB 2.0 splits it, after its unicode-space pre-pass. */
  def tokens(sql: String): Vector[Token] = scan(engineView(sql))

  /** `sql` as DuckDB's tokenizer receives it, position for position: each unicode space the
    * pre-pass converts becomes an ASCII space, and each one it KEEPS becomes a distinct stand-in (a
    * lone surrogate, which no decoded wire input can contain) that the tokenizer treats exactly as
    * it treats the kept character. The stand-ins make the view a fixpoint: the pre-pass finds
    * nothing to convert in it or in any slice of it, so a statement cut out of the view lexes alone
    * exactly as it lexed in place. A slice of the ORIGINAL text would not: the pre-pass state at a
    * statement boundary depends on everything before it.
    */
  def engineView(sql: String): String =
    if sql == null || !sql.exists(isPrepassSpace) then sql
    else
      val converted = prepassConverted(sql)
      val out       = sql.toCharArray
      var i         = 0
      while i < out.length do
        if converted(i) then out(i) = ' '
        else if isPrepassSpace(out(i)) then out(i) = standIn(out(i))
        i += 1
      new String(out)

  /** An [[engineView]] with each stand-in mapped back to the unicode space it stands for: the text
    * DuckDB's pre-pass hands the tokenizer, and so the text a literal's VALUE is read from. A space
    * the pre-pass kept (it believed the character sat inside a literal) is in the value as itself,
    * not as a stand-in and not as an ASCII space; one it converted is an ASCII space in the value
    * too, since the conversion ran before any quote was read. A literal body that will run as SQL
    * (`query('...')` runs one) must be read from this text: DuckDB pre-passes that SQL again, and a
    * kept space outside every literal of the BODY becomes a space there, where a stand-in would
    * read as an identifier character. A lone low surrogate in the input that happens to equal a
    * stand-in maps to a space character too; no decoded wire input carries one.
    */
  def keptSpacesRestored(view: String): String =
    if view == null || !view.exists(isStandIn) then view
    else view.map(c => if isStandIn(c) then PrepassSpaces(c - StandInBase) else c)

  /** Index just past the block comment opening at `from` (slash, star), tracking nesting depth. An
    * unterminated comment consumes the rest of the input (DuckDB rejects it).
    */
  def blockCommentEnd(sql: String, from: Int): Int = blockCommentScan(sql, from)._1

  /** [[blockCommentEnd]] and whether the outermost comment was closed. */
  private def blockCommentScan(sql: String, from: Int): (Int, Boolean) =
    var i     = from + 2
    var depth = 1
    while i < sql.length && depth > 0 do
      if i + 1 < sql.length && sql.charAt(i) == '/' && sql.charAt(i + 1) == '*' then
        depth += 1
        i += 2
      else if i + 1 < sql.length && sql.charAt(i) == '*' && sql.charAt(i + 1) == '/' then
        depth -= 1
        i += 2
      else i += 1
    (i, depth == 0)

  /** Whether `t`, a token of [[tokens]] over `sql`, is a quote or a block comment that runs to the
    * end of the input without its closing delimiter. DuckDB rejects such an input outright; a
    * reader that must not guess at the rest of it refuses it too. A dollar-quoted string is never
    * unterminated here (an opener with no closing tag is a lone `$`).
    */
  def unterminated(sql: String, t: Token): Boolean =
    t.end == sql.length && (t.kind match
      case Kind.BlockComment => !blockCommentScan(sql, t.start)._2
      case Kind.Literal      =>
        val c = sql.charAt(t.start)
        if c == '\'' then !stringScan(sql, t.start + 1, escape = false)._2
        else if c == '"' || c == '`' then !quotedIdentScan(sql, t.start)._2
        else if c == '$' then false
        else !stringScan(sql, t.start + 2, escape = c == 'e' || c == 'E')._2
      case _ => false)

  /** Index OF the terminator of the line comment opening at `from` (`--`), or `sql.length`. DuckDB
    * ends one at a line feed or a carriage return and at nothing else.
    */
  def lineCommentEnd(sql: String, from: Int): Int =
    var i = from + 2
    while i < sql.length && sql.charAt(i) != '\n' && sql.charAt(i) != '\r' do i += 1
    i

  // ---- the tokenizer (base_tokenizer.cpp), over the engine view ----

  private def scan(s: String): Vector[Token] =
    val n   = s.length
    val out = Vector.newBuilder[Token]
    var i   = 0
    while i < n do
      val c    = s.charAt(i)
      var kind = Kind.Punct
      var end  = i + 1
      if c == '\'' then
        kind = Kind.Literal
        end = stringEnd(s, i + 1, escape = false)
      else if c == '"' || c == '`' then
        kind = Kind.Literal
        end = quotedIdentEnd(s, i)
      else if c == ';' then ()
      else if c == '$' then
        dollarQuotedEnd(s, i) match
          case Some(e) =>
            kind = Kind.Literal
            end = e
          case None => ()
      else if c == '-' && i + 1 < n && s.charAt(i + 1) == '-' then
        kind = Kind.LineComment
        end = lineCommentEnd(s, i)
      else if c == '/' && i + 1 < n && s.charAt(i + 1) == '*' then
        kind = Kind.BlockComment
        end = blockCommentEnd(s, i)
      else if isSpace(c) then
        kind = Kind.Space
        while end < n && isSpace(s.charAt(end)) do end += 1
      else if c == ':' && i + 1 < n && (s.charAt(i + 1) == ':' || s.charAt(i + 1) == '=') then
        kind = Kind.Operator
        end = i + 2
      else if isSingleByteOperator(c) then ()
      else if isInitialNumber(c) then
        kind = Kind.Number
        end = numberEnd(s, i)
      else if isSpecialStringPrefix(c) && i + 1 < n && s.charAt(i + 1) == '\'' then
        kind = Kind.Literal
        end = stringEnd(s, i + 2, escape = c == 'e' || c == 'E')
      else if isAsciiOperator(c) then
        kind = Kind.Operator
        end = operatorEnd(s, i + 1)
      else
        kind = Kind.Word
        while end < n && (s.charAt(end) == '$' || isKeywordChar(s.charAt(end))) do end += 1
      out += Token(kind, i, end)
      i = end
    out.result()

  /** Index just past the quote closing the string whose body starts at `from`. `''` doubles; in an
    * escape string a backslash also escapes the character after it. Unterminated: end of input.
    */
  private def stringEnd(s: String, from: Int, escape: Boolean): Int =
    stringScan(s, from, escape)._1

  /** [[stringEnd]] and whether the closing quote was found. */
  private def stringScan(s: String, from: Int, escape: Boolean): (Int, Boolean) =
    var i      = from
    var closed = false
    while i < s.length && !closed do
      val c = s.charAt(i)
      if escape && c == '\\' && i + 1 < s.length then i += 2
      else if c == '\'' then
        if i + 1 < s.length && s.charAt(i + 1) == '\'' then i += 2
        else
          closed = true
          i += 1
      else i += 1
    (i, closed)

  /** Index just past the delimiter closing the `"` or backtick identifier opening at `from`, the
    * delimiter doubling. Unterminated: end of input.
    */
  private def quotedIdentEnd(s: String, from: Int): Int = quotedIdentScan(s, from)._1

  /** [[quotedIdentEnd]] and whether the closing delimiter was found. */
  private def quotedIdentScan(s: String, from: Int): (Int, Boolean) =
    val d      = s.charAt(from)
    var i      = from + 1
    var closed = false
    while i < s.length && !closed do
      if s.charAt(i) == d then
        if i + 1 < s.length && s.charAt(i + 1) == d then i += 2
        else
          closed = true
          i += 1
      else i += 1
    (i, closed)

  /** Index just past the closing tag of the dollar-quoted string opening at the token-initial `$`
    * at `from`, or None: `$` followed by a digit is a parameter, a tag broken by a non-tag
    * character is a lone `$`, and so (a departure, see the object scaladoc) is an opener with no
    * closing tag.
    */
  private def dollarQuotedEnd(s: String, from: Int): Option[Int] =
    if from + 1 >= s.length || isAsciiDigit(s.charAt(from + 1)) then None
    else
      var t = from + 1
      while t < s.length && s.charAt(t) != '$' && isDollarTagChar(s.charAt(t)) do t += 1
      if t >= s.length || s.charAt(t) != '$' then None
      else
        val tag   = s.substring(from, t + 1)
        val close = s.indexOf(tag, t + 1)
        if close < 0 then None else Some(close + tag.length)

  /** End of the number starting at `from`, DuckDB's NUMERIC state: digits and `.`, `_` between
    * digits, one exponent marker after a digit, a sign right after it; then back off to the last
    * digit or `.`, so `1e'x'` is `1` followed by `e'x'`.
    */
  private def numberEnd(s: String, from: Int): Int =
    var i       = from + 1
    var running = true
    while running && i < s.length do
      val c    = s.charAt(i)
      val prev = s.charAt(i - 1)
      if isInitialNumber(c) then i += 1
      else if c == '_' && i + 1 < s.length && isInitialNumber(s.charAt(i + 1)) then i += 1
      else if (c == 'e' || c == 'E') && prev != 'e' && prev != 'E' &&
        (isAsciiDigit(s.charAt(from)) || isAsciiDigit(prev))
      then i += 1
      else if (c == '+' || c == '-') && (prev == 'e' || prev == 'E') then i += 1
      else running = false
    if i < s.length then while !isInitialNumber(s.charAt(i - 1)) do i -= 1
    i

  /** End of an operator run continuing at `from`, which stops before a comment opener. */
  private def operatorEnd(s: String, from: Int): Int =
    var i = from
    while i < s.length && isOperatorContinuation(s.charAt(i)) &&
      !(i + 1 < s.length && s.charAt(i) == '-' && s.charAt(i + 1) == '-') &&
      !(i + 1 < s.length && s.charAt(i) == '/' && s.charAt(i + 1) == '*')
    do i += 1
    i

  private def isSpace(c: Char): Boolean =
    c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r'

  private def isAsciiDigit(c: Char): Boolean = c >= '0' && c <= '9'

  private def isInitialNumber(c: Char): Boolean = isAsciiDigit(c) || c == '.'

  private def isSpecialStringPrefix(c: Char): Boolean =
    c == 'e' || c == 'E' || c == 'n' || c == 'N' || c == 'x' || c == 'X' || c == 'b' || c == 'B'

  private def isSingleByteOperator(c: Char): Boolean =
    c == '(' || c == ')' || c == '{' || c == '}' || c == '[' || c == ']' || c == ',' ||
      c == ':' || c == '?' || c == '$' || c == '#'

  /** DuckDB's `StringUtil::CharacterIsOperator`: ASCII punctuation except `_`. */
  private def isAsciiOperator(c: Char): Boolean =
    c != '_' && ((c >= '!' && c <= '/') || (c >= ':' && c <= '@') || (c >= '[' && c <= '`') ||
      (c >= '{' && c <= '~'))

  /** DuckDB's `Tokenizer::CharacterIsOperator`, which continues an operator token. */
  private def isOperatorContinuation(c: Char): Boolean =
    c == '+' || c == '-' || c == '*' || c == '/' || c == '%' || c == '^' || c == '<' || c == '>' ||
      c == '=' || c == '~' || c == '!' || c == '@' || c == '&' || c == '|'

  /** An identifier (keyword) character: anything that is not a space, punctuation or one of
    * `' ; " .`, so ASCII letters, digits, `_`, ASCII control characters and all of non-ASCII. `$`
    * continues an identifier too, handled at the call site as DuckDB does.
    */
  private def isKeywordChar(c: Char): Boolean =
    c >= 0x80 || !(isSingleByteOperator(c) || isAsciiOperator(c) || isSpace(c))

  private def isDollarTagChar(c: Char): Boolean =
    (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || isAsciiDigit(c) || c == '_' || c >= 0x80

  // ---- the unicode-space pre-pass (parser.cpp StripUnicodeSpaces), over UTF-8 bytes ----

  private val PrepassSpaces: Vector[Char] =
    Vector('\u00A0') ++ ('\u2000' to '\u200B') ++
      Vector('\u202F', '\u205F', '\u2060', '\u3000', '\uFEFF')

  private def isPrepassSpace(c: Char): Boolean = PrepassSpaces.contains(c)

  private val StandInBase = '\uDC00'

  private def standIn(c: Char): Char = (StandInBase + PrepassSpaces.indexOf(c)).toChar

  private def isStandIn(c: Char): Boolean =
    c >= StandInBase && c < StandInBase + PrepassSpaces.length

  /** For each char of `sql`, whether DuckDB's pre-pass turns it into a space. A byte-for-byte port
    * (its loop bounds are byte offsets, so the emulation runs over the UTF-8 encoding).
    */
  private def prepassConverted(sql: String): Array[Boolean] =
    val n                          = sql.length
    val bytes                      = new Array[Int](n * 3)
    val owner                      = new Array[Int](n * 3)
    var q                          = 0
    def put(b: Int, ch: Int): Unit =
      bytes(q) = b
      owner(q) = ch
      q += 1
    var c = 0
    while c < n do
      val ch = sql.charAt(c)
      if ch < 0x80 then
        put(ch, c)
        c += 1
      else if ch < 0x800 then
        put(0xc0 | (ch >> 6), c)
        put(0x80 | (ch & 0x3f), c)
        c += 1
      else if Character.isHighSurrogate(ch) && c + 1 < n &&
        Character.isLowSurrogate(sql.charAt(c + 1))
      then
        val cp = Character.toCodePoint(ch, sql.charAt(c + 1))
        put(0xf0 | (cp >> 18), c)
        put(0x80 | ((cp >> 12) & 0x3f), c)
        put(0x80 | ((cp >> 6) & 0x3f), c)
        put(0x80 | (cp & 0x3f), c)
        c += 2
      else
        // a lone surrogate encodes as U+FFFD here; DuckDB refuses invalid UTF-8 outright
        val cp = if Character.isSurrogate(ch) then 0xfffd else ch.toInt
        put(0xe0 | (cp >> 12), c)
        put(0x80 | ((cp >> 6) & 0x3f), c)
        put(0x80 | (cp & 0x3f), c)
        c += 1
    val converted                 = new Array[Boolean](n)
    def mark(pos: Int): Unit      = converted(owner(pos)) = true
    def tagFirst(b: Int): Boolean =
      (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') || b == '_' || b >= 0x80
    def tagNext(b: Int): Boolean = tagFirst(b) || (b >= '0' && b <= '9')

    val Regular   = 0
    val InQuotes  = 1
    val InDollar  = 2
    val InComment = 3
    val Done      = 4
    var state     = Regular
    var pos       = 0
    var quote     = 0
    var tagStart  = 0
    var tagLen    = 0
    while state != Done do
      var next    = Done
      var looping = true
      if state == Regular then
        while looping && pos + 2 < q do
          val b = bytes(pos)
          if b == 0xc2 && bytes(pos + 1) == 0xa0 then mark(pos)
          if b == 0xe2 then
            val b1 = bytes(pos + 1)
            val b2 = bytes(pos + 2)
            if b1 == 0x80 then { if (b2 >= 0x80 && b2 <= 0x8b) || b2 == 0xaf then mark(pos) }
            else if b1 == 0x81 then { if b2 == 0x9f || b2 == 0xa0 then mark(pos) }
            pos += 1
          else if b == 0xe3 then
            if bytes(pos + 1) == 0x80 && bytes(pos + 2) == 0x80 then mark(pos)
            pos += 1
          else if b == 0xef then
            if bytes(pos + 1) == 0xbb && bytes(pos + 2) == 0xbf then mark(pos)
            pos += 1
          else if b == '"' || b == '\'' then
            quote = b
            pos += 1
            next = InQuotes
            looping = false
          else if b == '$' && (bytes(pos + 1) == '$' || tagFirst(bytes(pos + 1))) then
            pos += 1
            val start    = pos
            var outcome  = Done
            var scanning = true
            while scanning && pos + 2 < q do
              if bytes(pos) == '$' then
                tagStart = start
                tagLen = pos - start
                outcome = InDollar
                scanning = false
              else if !tagNext(bytes(pos)) then
                outcome = Regular
                scanning = false
              else pos += 1
            // a broken tag resumes the regular scan AT the breaking byte, without advancing
            if outcome != Regular then
              next = outcome
              looping = false
          else if b == '-' && bytes(pos + 1) == '-' then
            next = InComment
            looping = false
          else pos += 1
      else if state == InQuotes then
        while looping && pos + 1 < q do
          if bytes(pos) == quote then
            if bytes(pos + 1) == quote then pos += 2
            else
              pos += 1
              next = Regular
              looping = false
          else pos += 1
      else if state == InDollar then
        while looping && pos + 2 < q do
          if bytes(pos) == '$' && q - (pos + 1) >= tagLen + 1 &&
            bytes(pos + tagLen + 1) == '$' && sameBytes(bytes, pos + 1, tagStart, tagLen)
          then
            pos += tagLen + 1
            next = Regular
            looping = false
          else pos += 1
      else
        while looping && pos < q do
          if bytes(pos) == '\n' || bytes(pos) == '\r' then
            next = Regular
            looping = false
          else pos += 1
      state = next
    converted

  private def sameBytes(bytes: Array[Int], a: Int, b: Int, len: Int): Boolean =
    var k  = 0
    var eq = true
    while eq && k < len do
      eq = bytes(a + k) == bytes(b + k)
      k += 1
    eq
