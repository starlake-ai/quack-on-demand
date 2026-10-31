package ai.starlake.quack.model

/** The code points a path or a scope must never carry where a person reads it (a refusal, an admin
  * listing, an audit target): a control character, a format character (bidi overrides and isolates,
  * zero-width marks, the supplementary plane's tag characters U+E0001 and U+E0020-U+E007F), a line
  * or paragraph separator, or a lone surrogate. Any of them can make the text displayed differ from
  * the bytes it names. Judged per code point, so a supplementary character is judged whole, never
  * as its two surrogate halves.
  */
object UnshownCodePoints:

  def isUnshown(cp: Int): Boolean =
    Character.isISOControl(cp) || {
      val t = Character.getType(cp)
      t == Character.FORMAT || t == Character.LINE_SEPARATOR ||
      t == Character.PARAGRAPH_SEPARATOR || t == Character.SURROGATE
    }

  /** Whether `s` holds any such code point. */
  def holdsAny(s: String): Boolean = s.codePoints.anyMatch(cp => isUnshown(cp))
