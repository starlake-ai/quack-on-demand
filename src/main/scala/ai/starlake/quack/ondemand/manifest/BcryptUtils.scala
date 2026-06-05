// src/main/scala/ai/starlake/quack/ondemand/manifest/BcryptUtils.scala
package ai.starlake.quack.ondemand.manifest

import at.favre.lib.crypto.bcrypt.BCrypt

object BcryptUtils:

  /** Canonical bcrypt hash format: `$<version>$<cost>$<22-char salt><31-char hash>`
    * where version is one of 2a / 2b / 2x / 2y, cost is two digits, and the
    * salt+hash tail is exactly 53 chars. Total length = 60. */
  private val BcryptRegex = "^\\$2[abxy]\\$\\d\\d\\$.{53}$".r

  def isBcrypt(s: String): Boolean = BcryptRegex.matches(s)

  /** If `s` already looks like a bcrypt hash, return it unchanged. Otherwise
    * hash it with the project's standard cost (12), matching `UserStore.upsertUser`. */
  def toHash(s: String): String =
    if isBcrypt(s) then s
    else BCrypt.withDefaults().hashToString(12, s.toCharArray)