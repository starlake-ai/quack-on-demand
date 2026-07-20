package ai.starlake.quack.edge.sql

import scala.collection.mutable.ListBuffer

/** Statement screen for locked-down deployments (QOD_NODE_LOCKDOWN). Pure: the router consults it
  * only when the flag is on and the caller is not a superuser. Superuser detection is the caller's
  * job; with ACL disabled there is no effective set, so every caller screens as non-superuser
  * (fail closed; hosted deployments always run ACL on).
  *
  * Matching is token-based, deliberately not a full parse: the input is split on top-level
  * semicolons (quote- and comment-aware) and each statement is screened independently. Leading
  * trivia (whitespace, BOM, unicode spaces, line comments, nested block comments) is stripped
  * before the first-token check. A denied function name (bare or double-quoted) followed by an
  * open parenthesis is denied wherever it appears (subqueries included, EVERY occurrence), EXCEPT
  * when every path argument is a string literal with an object-store scheme. Anything the
  * tokenizer cannot prove safe is denied.
  */
object LockdownScreen:

  private val DeniedFirstTokens = Map(
    "attach"  -> "ATTACH is disabled on this deployment",
    "detach"  -> "DETACH is disabled on this deployment",
    "install" -> "INSTALL is disabled on this deployment",
    "load"    -> "LOAD is disabled on this deployment"
  )

  private val ProtectedSettings = Set(
    "disabled_filesystems", "allow_community_extensions", "allow_unsigned_extensions",
    "autoinstall_known_extensions", "autoload_known_extensions", "enable_external_access",
    "lock_configuration", "temp_directory", "extension_directory", "secret_directory",
    "allowed_directories", "allowed_paths"
  )

  private val DeniedFunctions = Set(
    "read_text", "read_blob", "glob", "read_csv", "read_csv_auto", "read_parquet",
    "read_json", "read_json_auto", "read_ndjson", "read_ndjson_auto", "parquet_scan", "getenv"
  )

  // read-style functions whose string-literal args may be remote URLs
  private val UrlExempt = DeniedFunctions - "glob" - "getenv" - "read_text" - "read_blob"

  private val RemoteSchemes = List("s3://", "gs://", "az://", "r2://", "http://", "https://")

  private val FirstToken = "^\\s*([a-zA-Z_]+)".r

  def screen(sql: String): Option[String] =
    splitStatements(sql).iterator.flatMap(screenOne).nextOption()

  private def screenOne(stmt: String): Option[String] =
    val lower = stripLeadingTrivia(stmt.toLowerCase)
    val first = FirstToken.findFirstMatchIn(lower).map(_.group(1))
    first.flatMap(DeniedFirstTokens.get) match
      case some @ Some(_) => some
      case None =>
        val settingHit = first match
          case Some("set") | Some("reset") | Some("pragma") =>
            ProtectedSettings
              .find(s => settingTargeted(lower, s))
              .map(s => s"setting '$s' is protected on this deployment")
          case _ => None
        // Settings statements still get the function scan: a denied function inside a
        // SET value must not slip through.
        settingHit.orElse(deniedFunctionIn(lower))

  /** Skips leading whitespace (including BOM, zero-width space and unicode space separators),
    * `--` line comments, and (nested) block comments so a comment prefix cannot hide the first
    * token. An unterminated block comment consumes the rest of the statement (nothing executable
    * remains, so the empty remainder screens clean).
    */
  private def stripLeadingTrivia(s: String): String =
    var i = 0
    var moved = true
    while moved do
      moved = false
      while i < s.length && isTriviaSpace(s(i)) do
        i += 1
        moved = true
      if i + 1 < s.length && s(i) == '-' && s(i + 1) == '-' then
        while i < s.length && s(i) != '\n' do i += 1
        moved = true
      else if i + 1 < s.length && s(i) == '/' && s(i + 1) == '*' then
        var depth = 1
        i += 2
        while i < s.length && depth > 0 do
          if i + 1 < s.length && s(i) == '/' && s(i + 1) == '*' then
            depth += 1
            i += 2
          else if i + 1 < s.length && s(i) == '*' && s(i + 1) == '/' then
            depth -= 1
            i += 2
          else i += 1
        if depth > 0 then i = s.length
        moved = true
    s.substring(i)

  private def isTriviaSpace(c: Char): Boolean =
    c.isWhitespace || c == '\uFEFF' || c == '\u200B' ||
      Character.getType(c) == Character.SPACE_SEPARATOR

  /** Splits the input on top-level semicolons: semicolons inside single-quoted strings,
    * double-quoted identifiers, line comments, or (nested) block comments do not split.
    */
  private def splitStatements(sql: String): List[String] =
    val out = ListBuffer.empty[String]
    val buf = new StringBuilder
    var i = 0
    var inSingle = false
    var inDouble = false
    var inLine = false
    var blockDepth = 0
    while i < sql.length do
      val c = sql(i)
      if inSingle then
        buf.append(c)
        if c == '\'' then inSingle = false
        i += 1
      else if inDouble then
        buf.append(c)
        if c == '"' then inDouble = false
        i += 1
      else if inLine then
        buf.append(c)
        if c == '\n' then inLine = false
        i += 1
      else if blockDepth > 0 then
        if i + 1 < sql.length && c == '/' && sql(i + 1) == '*' then
          blockDepth += 1
          buf.append("/*")
          i += 2
        else if i + 1 < sql.length && c == '*' && sql(i + 1) == '/' then
          blockDepth -= 1
          buf.append("*/")
          i += 2
        else
          buf.append(c)
          i += 1
      else
        c match
          case '\'' =>
            inSingle = true
            buf.append(c)
            i += 1
          case '"' =>
            inDouble = true
            buf.append(c)
            i += 1
          case '-' if i + 1 < sql.length && sql(i + 1) == '-' =>
            inLine = true
            buf.append("--")
            i += 2
          case '/' if i + 1 < sql.length && sql(i + 1) == '*' =>
            blockDepth = 1
            buf.append("/*")
            i += 2
          case ';' =>
            out += buf.toString
            buf.clear()
            i += 1
          case _ =>
            buf.append(c)
            i += 1
    out += buf.toString
    out.toList.filter(_.trim.nonEmpty)

  /** The setting name as a standalone word after the SET/RESET/PRAGMA keyword. */
  private def settingTargeted(lower: String, setting: String): Boolean =
    ("\\b" + setting + "\\b").r.findFirstIn(lower).isDefined

  /** A denied function name (bare or double-quoted) followed by '(' anywhere in the statement.
    * EVERY occurrence must pass the URL exemption or the statement is denied.
    */
  private def deniedFunctionIn(lower: String): Option[String] =
    DeniedFunctions.iterator.flatMap { fn =>
      val call = ("(?:\"" + fn + "\"|(?<![a-zA-Z0-9_])" + fn + ")\\s*\\(").r
      val occurrences = call.findAllMatchIn(lower).toList
      if occurrences.isEmpty then None
      else if UrlExempt.contains(fn) && occurrences.forall(m => allPathArgsRemote(lower, m.end))
      then None
      else Some(s"$fn over local paths is disabled on this deployment")
    }.nextOption()

  /** True when the argument list starting at `from` (index just past the open paren) proves every
    * path-shaped argument is a string literal carrying a remote scheme. Handles a bare literal
    * first argument (optionally followed by named args like `header = true`), or a list-literal
    * first argument (`['s3://..', 'gs://..']`) where every element must be a remote literal.
    * Anything the scanner cannot prove safe (non-literal args, unbalanced brackets, unterminated
    * strings) answers false (deny).
    */
  private def allPathArgsRemote(lower: String, from: Int): Boolean =
    val rest = lower.substring(from)
    val trimmed = rest.dropWhile(_.isWhitespace)
    if trimmed.startsWith("[") then
      val closing = trimmed.indexOf(']')
      if closing < 0 then false
      else
        val inner = trimmed.substring(1, closing)
        val elems = splitTopLevel(inner)
        elems.nonEmpty && elems.forall(e => isRemoteLiteral(e.trim))
    else
      // Bare first argument: it must itself be a remote string literal. Anything after it
      // (further positional args, or named args like `header = true`) doesn't matter for the
      // path-safety proof, but if the first thing isn't a quoted literal at all, fail closed.
      firstArg(trimmed) match
        case Some(lit) => isRemoteLiteral(lit.trim)
        case None      => false

  /** Extracts the first top-level, comma-separated argument text (up to the matching close-paren
    * or the first top-level comma), or None if the argument list is empty/unparseable.
    */
  private def firstArg(s: String): Option[String] =
    if s.isEmpty || s.startsWith(")") then None
    else
      val parts = splitTopLevel(s)
      parts.headOption

  /** Splits a comma-separated argument/element list on top-level commas only (commas inside a
    * quoted string, or inside nested brackets/parens, don't count). Stops at the first unmatched
    * closing paren/bracket (the end of the enclosing argument list).
    */
  private def splitTopLevel(s: String): List[String] =
    val buf = new StringBuilder
    val out = ListBuffer.empty[String]
    var depth = 0
    var inString = false
    var i = 0
    var stop = false
    while i < s.length && !stop do
      val c = s(i)
      if inString then
        buf.append(c)
        if c == '\'' then inString = false
      else
        c match
          case '\'' =>
            inString = true
            buf.append(c)
          case '(' | '[' =>
            depth += 1
            buf.append(c)
          case ')' | ']' =>
            if depth == 0 then stop = true
            else
              depth -= 1
              buf.append(c)
          case ',' if depth == 0 =>
            out += buf.toString
            buf.clear()
          case _ =>
            buf.append(c)
      i += 1
    if buf.nonEmpty || out.nonEmpty then out += buf.toString
    out.toList.filter(_.trim.nonEmpty)

  private def isRemoteLiteral(s: String): Boolean =
    s.startsWith("'") && s.endsWith("'") && s.length >= 2 &&
      RemoteSchemes.exists(sch => s.substring(1).startsWith(sch))
