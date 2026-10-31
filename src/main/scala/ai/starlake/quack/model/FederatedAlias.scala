package ai.starlake.quack.model

import java.util.Locale

/** The one case fold for a federated catalog alias.
  *
  * Aliases are compared by exact string equality in several places that do NOT have an
  * `equalsIgnoreCase` behind them -- the read-only set a session handle carries is matched by the
  * node against the catalogs a plan writes, which it lowercases itself, and `AttachStatusRegistry`
  * keys its failures by the folded alias so a record and its lookup have to agree. Under a Turkish
  * or Azeri default locale `"I".toLowerCase` is the dotless `i` (U+0131), so a fold that consults
  * the default locale folds one side of such a comparison away from the other and the match
  * silently stops happening. For the read-only set that direction fails OPEN: the set never
  * matches and the write is admitted (a folded alias outside `[a-z0-9_]` would also make the mint
  * refuse the handle).
  *
  * Every alias fold in the manager therefore goes through here rather than spelling
  * `toLowerCase(Locale.ROOT)` out again, so the drift cannot reappear one site at a time.
  * [[Names.normalizeOrError]], which is what WRITE paths run an alias through, folds the same way.
  *
  * `Character.toLowerCase` (what `AttachErrorRedactor.foldCase` uses per character, to keep a byte
  * index stable) is locale-independent by construction and needs no helper.
  */
object FederatedAlias:

  /** Locale-independent lowercase fold of one alias. */
  def fold(alias: String): String = alias.toLowerCase(Locale.ROOT)

  /** The read-only catalog set every session handle carries (`ReadOnlyCatalogs`, decision 24: the
    * node refuses every principal's write into it), derived from a tenant-db's federated
    * sources. Lives here so the fold that decides whether a denial matches is testable on
    * its own.
    *
    * DISABLED sources are deliberately included: a disabled source's alias stays ATTACHed on
    * running nodes until the pool recycles, so its read-only flag must keep applying until then.
    */
  def readOnlySet(sources: List[FederatedSource]): Set[String] =
    sources.filter(_.readOnly).map(s => fold(s.alias)).toSet
