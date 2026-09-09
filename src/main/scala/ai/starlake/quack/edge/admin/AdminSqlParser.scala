package ai.starlake.quack.edge.admin

import java.util.Locale

/** Parser for the SQL admin dialect (spec 2026-09-09-sql-admin-dialect-design.md).
  *
  * Statement shells are hand-tokenized here; embedded expressions (row-policy predicates, mask
  * transforms) are sliced verbatim and validated downstream by the jsqlparser-based
  * RowPredicateValidator / TransformSqlValidator inside PoolSupervisor.
  *
  * `claims` decides interception: once a statement is claimed it is answered by the manager or
  * rejected - never forwarded to a DuckDB node.
  */
object AdminSqlParser:

  /** Single source of truth for the constant every logging/history sink substitutes for a
    * claim-shaped statement's raw text (a CREATE/ALTER USER ... PASSWORD statement's literal
    * included). Shared by [[ai.starlake.quack.edge.FlightProducerImpl]]'s `loggableSql` and
    * [[ai.starlake.quack.edge.FlightSqlRouter]]'s `record`/admin-dispatch history recording, so
    * every sink agrees on the placeholder and none of them can drift into logging the real SQL.
    */
  val RedactedPlaceholder: String = "<admin statement redacted>"

  /** Recognized privilege keywords for GRANT/REVOKE ON TABLE; shared by [[privileges]]. */
  private val PrivWords = Set("SELECT", "INSERT", "UPDATE", "DELETE", "DDL", "ALL")

  private final case class Tok(raw: String, upper: String, quoted: Boolean, start: Int, end: Int)

  // Bounded to the first few tokens: claims() runs on every statement on the FlightSQL hot
  // path and never needs more than the leading keywords to decide interception.
  private val ClaimsTokenBudget = 5

  def claims(sql: String): Boolean =
    tokenizeUpTo(sql, ClaimsTokenBudget) match
      // A tokenizer failure (unterminated quote/comment) leaves the statement unclaimed. That
      // is fail-closed downstream: DuckDB's own parser reports the syntax error, this dialect
      // never mistakenly intercepts a malformed statement it can't actually parse.
      case Left(_)     => false
      case Right(toks) =>
        def kw(k: Int): String =
          if k < toks.length && !toks(k).quoted then toks(k).upper else ""
        kw(0) match
          case "GRANT" | "REVOKE" => true
          case "CREATE"           =>
            kw(1) == "ROLE" || kw(1) == "USER" ||
            (kw(1) == "ROW" && kw(2) == "POLICY") ||
            (kw(1) == "COLUMN" && kw(2) == "POLICY") ||
            (kw(1) == "OR" && kw(2) == "REPLACE" &&
              (kw(3) == "ROW" || kw(3) == "COLUMN" || kw(3) == "ROLE"))
          case "DROP" =>
            kw(1) == "ROLE" || kw(1) == "USER" ||
            (kw(1) == "ROW" && kw(2) == "POLICY") ||
            (kw(1) == "COLUMN" && kw(2) == "POLICY")
          case "ALTER" => kw(1) == "GROUP" || kw(1) == "USER"
          case "SHOW"  =>
            kw(1) == "ROLES" || kw(1) == "GRANTS" || kw(1) == "USERS" ||
            (kw(1) == "ROW" && kw(2) == "POLICIES") ||
            (kw(1) == "COLUMN" && kw(2) == "POLICIES") ||
            (kw(1) == "POOL" && kw(2) == "GRANTS")
          case _ => false

  def parse(sql: String): Either[String, AdminCommand] =
    tokenize(sql).flatMap { all =>
      val toks = if all.nonEmpty && all.last.raw == ";" then all.dropRight(1) else all
      if toks.isEmpty then Left("empty statement")
      else new P(sql, toks).statement()
    }

  private def tokenize(sql: String): Either[String, Vector[Tok]] = tokenizeUpTo(sql, Int.MaxValue)

  /** The kind of construct [[scanRegion]] found starting at its given position. `Comment` covers
    * both line (`--`) and nested block (`/* */`) forms; `StrLit` covers single-quoted strings,
    * `E'...'` strings, and dollar-quoted strings (`$$...$$` / `$tag$...$tag$`) - all three are
    * string-literal tokens as far as callers are concerned; `Ident` is a double-quoted identifier.
    */
  private enum ScanKind:
    case StrLit, Ident, Comment

  /** Scans the single quoted-string / quoted-identifier / comment construct starting at `sql(i)`,
    * honoring every such form the dialect accepts:
    *   - single-quoted strings, `''` doubling escapes the quote
    *   - `E'...'` / `e'...'` strings, backslash-escaped (`\\` is a literal backslash, `\'` does not
    *     close the string; `''` doubling also does not close it)
    *   - dollar-quoted strings, `$$...$$` and tagged `$tag$...$tag$` (tag = ident chars)
    *   - double-quoted identifiers, `""` doubling escapes the quote
    *   - a line comment (`--` to end of line) or a nested block comment (`/* */`, DuckDB-matching
    *     depth counting - `/* /* */ */` is one comment, not two)
    *
    * Returns `None` when `sql(i)` starts none of these, so the caller falls back to its own
    * single-char handling (whitespace, identifiers, operators, a `$` that isn't a dollar-quote
    * open). Shared by [[tokenizeUpTo]] (which must skip comments and emit whole tokens for every
    * string/ident form) and `parenExpression` (which must skip the same regions when tracking paren
    * depth and the semicolon guard, and reject on sight the moment a comment is found).
    */
  private def scanRegion(sql: String, i: Int): Option[Either[String, (Int, ScanKind)]] =
    val n = sql.length
    val c = sql(i)
    if c == '-' && i + 1 < n && sql(i + 1) == '-' then
      var j = i
      while j < n && sql(j) != '\n' do j += 1
      Some(Right((j, ScanKind.Comment)))
    else if c == '/' && i + 1 < n && sql(i + 1) == '*' then
      var depth = 1
      var j     = i + 2
      while j < n && depth > 0 do
        if j + 1 < n && sql(j) == '/' && sql(j + 1) == '*' then { depth += 1; j += 2 }
        else if j + 1 < n && sql(j) == '*' && sql(j + 1) == '/' then { depth -= 1; j += 2 }
        else j += 1
      if depth > 0 then Some(Left("unterminated block comment"))
      else Some(Right((j, ScanKind.Comment)))
    else if c == '"' then
      var j      = i + 1
      var closed = false
      while j < n && !closed do
        if sql(j) == '"' then
          if j + 1 < n && sql(j + 1) == '"' then j += 2 else { closed = true; j += 1 }
        else j += 1
      if !closed then Some(Left("unterminated quoted identifier"))
      else Some(Right((j, ScanKind.Ident)))
    else if (c == 'E' || c == 'e') && i + 1 < n && sql(i + 1) == '\'' then
      var j      = i + 2
      var closed = false
      while j < n && !closed do
        if sql(j) == '\\' && j + 1 < n then j += 2
        else if sql(j) == '\'' then
          if j + 1 < n && sql(j + 1) == '\'' then j += 2 else { closed = true; j += 1 }
        else j += 1
      if !closed then Some(Left("unterminated string literal"))
      else Some(Right((j, ScanKind.StrLit)))
    else if c == '\'' then
      var j      = i + 1
      var closed = false
      while j < n && !closed do
        if sql(j) == '\'' then
          if j + 1 < n && sql(j + 1) == '\'' then j += 2 else { closed = true; j += 1 }
        else j += 1
      if !closed then Some(Left("unterminated string literal"))
      else Some(Right((j, ScanKind.StrLit)))
    else if c == '$' then
      var j = i + 1
      while j < n && (sql(j).isLetterOrDigit || sql(j) == '_') do j += 1
      if j < n && sql(j) == '$' then
        val tag      = sql.substring(i, j + 1)
        val closeIdx = sql.indexOf(tag, j + 1)
        if closeIdx < 0 then Some(Left("unterminated dollar-quoted string"))
        else Some(Right((closeIdx + tag.length, ScanKind.StrLit)))
      else None
    else None

  // Shared tokenizer core. `maxTokens` bounds how many tokens are collected before returning -
  // used by claims() to avoid a full-statement scan (per-token allocation and toUpperCase,
  // including on multi-megabyte string literals) on every statement of the hot path. Scanning
  // stops at the budget; the first maxTokens tokens are nonetheless correct because comment
  // handling inside the window is complete and no input past the window can affect them.
  private def tokenizeUpTo(sql: String, maxTokens: Int): Either[String, Vector[Tok]] =
    val toks                    = Vector.newBuilder[Tok]
    var i                       = 0
    val n                       = sql.length
    var count                   = 0
    var failure: Option[String] = None
    while i < n && failure.isEmpty && count < maxTokens do
      val c = sql(i)
      if c.isWhitespace then i += 1
      else
        scanRegion(sql, i) match
          case Some(Left(err))                      => failure = Some(err)
          case Some(Right((end, ScanKind.Comment))) => i = end
          case Some(Right((end, ScanKind.Ident)))   =>
            val s = sql.substring(i + 1, end - 1).replace("\"\"", "\"")
            toks += Tok(s, s.toUpperCase(Locale.ROOT), quoted = true, i, end)
            count += 1
            i = end
          case Some(Right((end, ScanKind.StrLit))) =>
            val s = sql.substring(i, end)
            toks += Tok(s, s.toUpperCase(Locale.ROOT), quoted = false, i, end)
            count += 1
            i = end
          case None =>
            if c.isLetter || c == '_' then
              var j = i
              while j < n && (sql(j).isLetterOrDigit || sql(j) == '_' || sql(j) == '$') do j += 1
              val s = sql.substring(i, j)
              toks += Tok(s, s.toUpperCase(Locale.ROOT), quoted = false, i, j)
              count += 1
              i = j
            else
              toks += Tok(c.toString, c.toString, quoted = false, i, i + 1)
              count += 1
              i += 1
    failure.toLeft(toks.result())

  private final class P(sql: String, toks: Vector[Tok]):
    private var i = 0

    private def eof: Boolean             = i >= toks.length
    private def peek(k: Int = 0): String =
      if i + k < toks.length then toks(i + k).upper else ""
    private def quotedAt(k: Int): Boolean = i + k < toks.length && toks(i + k).quoted
    private def peekQuoted: Boolean       = quotedAt(0)
    private def bump(): Unit              = i += 1

    private def kw(w: String): Either[String, Unit] =
      if peek() == w && !peekQuoted then { i += 1; Right(()) }
      else if eof then Left(s"expected $w at end of statement")
      else Left(s"expected $w, found '${toks(i).raw}'")

    private def optKw(w: String): Boolean =
      if peek() == w && !peekQuoted then { i += 1; true }
      else false

    private def ident(what: String): Either[String, String] =
      if eof then Left(s"expected $what at end of statement")
      else
        val t = toks(i)
        if t.quoted || t.raw.head.isLetter || t.raw.head == '_' then { i += 1; Right(t.raw) }
        else Left(s"expected $what, found '${t.raw}'")

    /** Expects a single-quoted string literal token; returns its content with '' unescaped. Must
      * reject a double-quoted IDENTIFIER whose content merely starts with `'` (e.g. `PASSWORD "'"`
      * or `PASSWORD "'abc"`) - those are `quoted = true` tokens from the tokenizer's `"..."` path,
      * not string literals, and would otherwise either throw (raw.length 1, substring(1, 0) is out
      * of range) or silently truncate the secret. `raw.length >= 2` is also required: every real
      * single-quoted literal token is at least `''` (the empty string), so this is defense in depth
      * alongside the `quoted` check, never reachable through the tokenizer's own `'...'` path.
      */
    private def stringLiteral(what: String): Either[String, String] =
      if eof || toks(i).quoted || toks(i).raw.length < 2 || !toks(i).raw.startsWith("'") then
        Left(s"expected $what as a quoted string literal")
      else
        val raw = toks(i).raw
        i += 1
        Right(raw.substring(1, raw.length - 1).replace("''", "'"))

    private def end(cmd: AdminCommand): Either[String, AdminCommand] =
      if eof then Right(cmd)
      else Left(s"unexpected trailing input: '${toks(i).raw}'")

    private def ifExistsOpt(): Boolean =
      if peek() == "IF" && !quotedAt(0) && peek(1) == "EXISTS" && !quotedAt(1) then { i += 2; true }
      else false

    private def principal(): Either[String, Principal] =
      if optKw("USER") then ident("user name").map(Principal.User.apply)
      else if optKw("GROUP") then ident("group name").map(Principal.Group.apply)
      else Left(s"expected USER or GROUP, found '${if eof then "<end>" else toks(i).raw}'")

    /** Comma-separated privilege list, mapped onto the stored verb space. */
    private def privileges(): Either[String, String] =
      var privs                = Set.empty[String]
      var fail: Option[String] = None
      var more                 = true
      while more && fail.isEmpty do
        val p = peek()
        if PrivWords(p) && !peekQuoted then
          bump()
          if p == "ALL" then { optKw("PRIVILEGES"); () }
          privs += p
        else
          fail = Some(
            s"expected a privilege (SELECT, INSERT, UPDATE, DELETE, DDL, ALL), " +
              s"found '${if eof then "<end>" else toks(i).raw}'"
          )
        if fail.isEmpty then more = optKw(",")
      fail.toLeft(privs).flatMap(mapVerb)

    private def mapVerb(privs: Set[String]): Either[String, String] =
      val dml = privs.intersect(Set("INSERT", "UPDATE", "DELETE"))
      if privs.contains("ALL") then
        if privs.size == 1 then Right("ALL")
        else Left("ALL cannot be combined with other privileges")
      else if privs.contains("DDL") then
        if dml.nonEmpty || privs.contains("SELECT") then
          Left("DDL cannot be combined with other privileges; use ALL")
        else Right("DDL")
      else if dml.nonEmpty then Right("RW")
      else if privs.contains("SELECT") then Right("RO")
      // Unreachable by construction: privileges() only ever reaches mapVerb with a non-empty
      // privs (the while loop's first iteration either fails closed or adds a PrivWords member),
      // and every PrivWords member is covered by one of the branches above.
      else Left("empty privilege list")

    private def optDot(): Boolean =
      if peek() == "." && !peekQuoted then { i += 1; true }
      else false

    private def refSegment(): Either[String, String] =
      if !eof && !toks(i).quoted && toks(i).raw == "*" then { i += 1; Right("*") }
      else if !eof && toks(i).quoted && toks(i).raw == "*" then
        Left("quoted \"*\" is a literal identifier, not the * wildcard")
      else ident("identifier or *")

    /** 1-3 dot-separated segments; missing leading segments pad with "*". */
    private def tableRef(): Either[String, TableRef] =
      refSegment().flatMap { s1 =>
        if optDot() then
          refSegment().flatMap { s2 =>
            if optDot() then refSegment().map(s3 => TableRef(s1, s2, s3))
            else Right(TableRef("*", s1, s2))
          }
        else Right(TableRef("*", "*", s1))
      }

    private def poolTarget(): Either[String, PoolTarget] =
      ident("pool name").flatMap { first =>
        if optDot() then ident("pool name").map(p => PoolTarget(Some(first), p))
        else Right(PoolTarget(None, first))
      }

    def statement(): Either[String, AdminCommand] =
      if optKw("GRANT") then grant()
      else if optKw("REVOKE") then revoke()
      else if optKw("CREATE") then create()
      else if optKw("DROP") then drop()
      else if optKw("ALTER") then alter()
      else if optKw("SHOW") then show()
      else Left(s"not an admin statement: '${toks(0).raw}'")

    private def grant(): Either[String, AdminCommand] =
      if optKw("ROLE") then
        for
          role <- ident("role name")
          _    <- kw("TO")
          p    <- principal()
          out  <- end(AdminCommand.GrantRoleTo(role, p))
        yield out
      else if optKw("CONNECT") then
        for
          _   <- kw("ON")
          _   <- kw("POOL")
          t   <- poolTarget()
          _   <- kw("TO")
          p   <- principal()
          out <- end(AdminCommand.GrantPool(t, p))
        yield out
      else
        for
          verb <- privileges()
          _    <- kw("ON")
          _ = optKw("TABLE")
          ref <- tableRef()
          _   <- kw("TO")
          _   <-
            if (peek() == "USER" || peek() == "GROUP") && !peekQuoted then
              Left(
                "table grants target roles; use GRANT ROLE <role> TO USER|GROUP <name> " +
                  "for membership"
              )
            else Right(())
          _ = optKw("ROLE")
          role <- ident("role name")
          out  <- end(AdminCommand.GrantTable(verb, ref, role))
        yield out

    private def revoke(): Either[String, AdminCommand] =
      if optKw("ROLE") then
        for
          role <- ident("role name")
          _    <- kw("FROM")
          p    <- principal()
          out  <- end(AdminCommand.RevokeRoleFrom(role, p))
        yield out
      else if optKw("CONNECT") then
        for
          _   <- kw("ON")
          _   <- kw("POOL")
          t   <- poolTarget()
          _   <- kw("FROM")
          p   <- principal()
          out <- end(AdminCommand.RevokePool(t, p))
        yield out
      else
        for
          verb <- privileges()
          _    <- kw("ON")
          _ = optKw("TABLE")
          ref <- tableRef()
          _   <- kw("FROM")
          _ = optKw("ROLE")
          role <- ident("role name")
          out  <- end(
            AdminCommand.RevokeTable(if verb == "ALL" then None else Some(verb), ref, role)
          )
        yield out

    /** Requires the current token to be '('; scans the RAW sql for the balanced closing paren,
      * treating every string-like construct the tokenizer accepts (single-quoted strings,
      * E-strings, dollar-quoted strings, double-quoted identifiers) as opaque via the shared
      * [[scanRegion]] scan, so a ')' or ';' inside one of them never affects paren depth or the
      * semicolon guard below. Rejects a bare ';' in the body - it has no legitimate use in an
      * extracted expression and would break the downstream textual splice. Rejects ANY comment in
      * the body - line or block, anywhere, including a comment-only body - with a single "comments
      * are not allowed in expressions" error, matching the create-time validators exactly; unlike
      * quotes, a comment is never opaque content here. Returns the trimmed body and advances the
      * token cursor past the closing paren. The body is passed verbatim to the jsqlparser-based
      * validators downstream - no re-tokenization here.
      */
    private def parenExpression(): Either[String, String] =
      if eof || toks(i).quoted || toks(i).raw != "(" then Left("expected ( after USING")
      else
        val open                    = toks(i).start
        var j                       = open
        var depth                   = 0
        var close                   = -1
        var failure: Option[String] = None
        while j < sql.length && close < 0 && failure.isEmpty do
          val c = sql(j)
          if c.isWhitespace then j += 1
          else
            scanRegion(sql, j) match
              // Unreachable in practice: parse() tokenizes the whole statement with this same
              // scanRegion before ever constructing a P and calling parenExpression, so an
              // unterminated string/comment here would already have failed the statement closed.
              // Kept as a defensive fallback rather than deleted.
              case Some(Left(err))                    => failure = Some(err)
              case Some(Right((_, ScanKind.Comment))) =>
                failure = Some("comments are not allowed in expressions")
              case Some(Right((end, _))) => j = end
              case None                  =>
                if c == ';' then failure = Some("semicolon not allowed in expression")
                else if c == '(' then { depth += 1; j += 1 }
                else if c == ')' then
                  depth -= 1
                  if depth == 0 then close = j
                  j += 1
                else j += 1
        failure match
          case Some(err) => Left(err)
          case None      =>
            if close < 0 then Left("unbalanced parentheses in expression")
            else
              val body = sql.substring(open + 1, close).trim
              if body.isEmpty then Left("empty expression")
              else
                while i < toks.length && toks(i).start <= close do i += 1
                Right(body)

    private def create(): Either[String, AdminCommand] =
      if optKw("ROLE") then
        for
          name <- ident("role name")
          out  <- end(AdminCommand.CreateRole(name))
        yield out
      else if optKw("USER") then
        for
          name <- ident("user name")
          _ = optKw("WITH")
          _  <- kw("PASSWORD")
          pw <- stringLiteral("password")
          _  <- if pw.isEmpty then Left("password must not be empty") else Right(())
          isAdmin = optKw("ADMIN")
          out <- end(AdminCommand.CreateUser(name, pw, isAdmin))
        yield out
      else
        val orReplaceE: Either[String, Boolean] =
          if optKw("OR") then kw("REPLACE").map(_ => true) else Right(false)
        orReplaceE.flatMap { orReplace =>
          if orReplace && peek() == "ROLE" && !peekQuoted then
            Left("OR REPLACE is not supported for ROLE")
          else if optKw("ROW") then
            for
              _ <- kw("POLICY")
              _ <- kw("ON")
              _ = optKw("TABLE")
              ref  <- tableRef()
              _    <- kw("FOR")
              _    <- kw("ROLE")
              role <- ident("role name")
              _    <- kw("USING")
              pred <- parenExpression()
              out  <- end(AdminCommand.CreateRowPolicy(ref, role, pred, orReplace))
            yield out
          else if optKw("COLUMN") then
            for
              _ <- kw("POLICY")
              _ <- kw("ON")
              _ = optKw("TABLE")
              ref  <- tableRef()
              _    <- kw("COLUMN")
              col  <- ident("column name")
              _    <- kw("FOR")
              _    <- kw("ROLE")
              role <- ident("role name")
              cmd  <- columnAction(ref, col, role, orReplace)
            yield cmd
          else if orReplace then
            Left("expected ROW POLICY or COLUMN POLICY after CREATE OR REPLACE")
          else Left("expected ROW POLICY or COLUMN POLICY after CREATE")
        }

    private def columnAction(
        ref: TableRef,
        col: String,
        role: String,
        orReplace: Boolean
    ): Either[String, AdminCommand] =
      if optKw("DENY") then
        end(AdminCommand.CreateColumnPolicy(ref, col, role, "deny", None, orReplace))
      else if optKw("MASK") then
        for
          _    <- kw("USING")
          expr <- parenExpression()
          out  <-
            end(AdminCommand.CreateColumnPolicy(ref, col, role, "mask", Some(expr), orReplace))
        yield out
      else Left("expected DENY or MASK USING (...) in column policy")

    private def drop(): Either[String, AdminCommand] =
      if optKw("ROLE") then
        val ifE = ifExistsOpt()
        for
          name <- ident("role name")
          out  <- end(AdminCommand.DropRole(name, ifE))
        yield out
      else if optKw("USER") then
        val ifE = ifExistsOpt()
        for
          name <- ident("user name")
          out  <- end(AdminCommand.DropUser(name, ifE))
        yield out
      else if optKw("ROW") then
        for
          _ <- kw("POLICY")
          ifE = ifExistsOpt()
          _ <- kw("ON")
          _ = optKw("TABLE")
          ref  <- tableRef()
          _    <- kw("FOR")
          _    <- kw("ROLE")
          role <- ident("role name")
          out  <- end(AdminCommand.DropRowPolicy(ref, role, ifE))
        yield out
      else if optKw("COLUMN") then
        for
          _ <- kw("POLICY")
          ifE = ifExistsOpt()
          _ <- kw("ON")
          _ = optKw("TABLE")
          ref  <- tableRef()
          _    <- kw("COLUMN")
          col  <- ident("column name")
          _    <- kw("FOR")
          _    <- kw("ROLE")
          role <- ident("role name")
          out  <- end(AdminCommand.DropColumnPolicy(ref, col, role, ifE))
        yield out
      else Left("expected ROLE, ROW POLICY or COLUMN POLICY after DROP")

    private def alter(): Either[String, AdminCommand] =
      if optKw("GROUP") then
        for
          group <- ident("group name")
          cmd   <-
            if optKw("ADD") then
              for
                _   <- kw("USER")
                u   <- ident("user name")
                out <- end(AdminCommand.AlterGroupAddUser(group, u))
              yield out
            else if optKw("DROP") then
              for
                _   <- kw("USER")
                u   <- ident("user name")
                out <- end(AdminCommand.AlterGroupDropUser(group, u))
              yield out
            else Left("expected ADD USER or DROP USER after ALTER GROUP <name>")
        yield cmd
      else if optKw("USER") then
        for
          name <- ident("user name")
          cmd  <- alterUserTail(name)
        yield cmd
      else Left("expected GROUP or USER after ALTER")

    /** Dispatches on what follows `ALTER USER <name>`: password rotation (`[WITH] PASSWORD`, WITH
      * is optional Postgres-style noise), `REQUIRE PASSWORD CHANGE` (flags the account without
      * touching the credential), or `ENABLE` / `DISABLE` (account flag flip).
      */
    private def alterUserTail(name: String): Either[String, AdminCommand] =
      if optKw("WITH") then
        for
          _   <- kw("PASSWORD")
          pw  <- stringLiteral("password")
          _   <- if pw.isEmpty then Left("password must not be empty") else Right(())
          out <- end(AdminCommand.AlterUserPassword(name, pw))
        yield out
      else if optKw("PASSWORD") then
        for
          pw  <- stringLiteral("password")
          _   <- if pw.isEmpty then Left("password must not be empty") else Right(())
          out <- end(AdminCommand.AlterUserPassword(name, pw))
        yield out
      else if optKw("REQUIRE") then
        for
          _   <- kw("PASSWORD")
          _   <- kw("CHANGE")
          out <- end(AdminCommand.AlterUserRequirePasswordChange(name))
        yield out
      else if optKw("ENABLE") then end(AdminCommand.AlterUserEnabled(name, enabled = true))
      else if optKw("DISABLE") then end(AdminCommand.AlterUserEnabled(name, enabled = false))
      else
        Left(
          "expected PASSWORD, REQUIRE PASSWORD CHANGE, ENABLE or DISABLE after ALTER USER <name>"
        )

    private def show(): Either[String, AdminCommand] =
      if optKw("ROLES") then end(AdminCommand.ShowRoles)
      else if optKw("USERS") then end(AdminCommand.ShowUsers)
      else if optKw("GRANTS") then
        for
          _   <- kw("FOR")
          cmd <-
            if optKw("ROLE") then ident("role name").flatMap(r => end(AdminCommand.ShowGrants(r)))
            else if optKw("USER") then
              ident("user name").flatMap(u => end(AdminCommand.ShowGrantsForUser(u)))
            else
              Left(
                s"expected ROLE or USER after SHOW GRANTS FOR, found " +
                  s"'${if eof then "<end>" else toks(i).raw}'"
              )
        yield cmd
      else if optKw("ROW") then
        for
          _   <- kw("POLICIES")
          f   <- policyFilter()
          out <- end(AdminCommand.ShowRowPolicies(f))
        yield out
      else if optKw("COLUMN") then
        for
          _   <- kw("POLICIES")
          f   <- policyFilter()
          out <- end(AdminCommand.ShowColumnPolicies(f))
        yield out
      else if optKw("POOL") then
        for
          _   <- kw("GRANTS")
          p   <- if optKw("FOR") then principal().map(Some(_)) else Right(None)
          out <- end(AdminCommand.ShowPoolGrants(p))
        yield out
      else Left("not an admin SHOW form")

    private def policyFilter(): Either[String, PolicyFilter] =
      if optKw("ON") then
        val _ = optKw("TABLE")
        tableRef().map(PolicyFilter.OnTable.apply)
      else if optKw("FOR") then
        for
          _ <- kw("ROLE")
          r <- ident("role name")
        yield PolicyFilter.ForRole(r)
      else Right(PolicyFilter.All)
