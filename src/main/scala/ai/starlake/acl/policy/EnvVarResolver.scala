package ai.starlake.acl.policy

import ai.starlake.acl.AclError
import cats.data.{NonEmptyList, Validated, ValidatedNel}

object EnvVarResolver:

  private val EnvVarPattern = """\$\{([A-Za-z_][A-Za-z0-9_]*)\}""".r

  /** Resolve all `${VAR_NAME}` references in a string against the environment.
    *
    * Finds all distinct variable names in `input`. If any are unresolvable,
    * returns ALL unresolved names as errors (not just the first). If all are
    * resolved, performs replacement using `Regex.replaceAllIn` with
    * `Matcher.quoteReplacement` to safely handle `$` and `\` in values.
    *
    * @param input
    *   the raw string potentially containing `${VAR_NAME}` references
    * @param env
    *   a function that resolves variable names to their values; defaults to
    *   `System.getenv`
    * @return
    *   `Valid(resolvedString)` or `Invalid(NonEmptyList[AclError.UnresolvedVariable])`
    */
  def resolve(
      input: String,
      env: String => Option[String] = name => Option(System.getenv(name))
  ): ValidatedNel[AclError, String] =
    val varNames = EnvVarPattern.findAllMatchIn(input).map(_.group(1)).toList.distinct
    val unresolved = varNames.filter(name => env(name).isEmpty)

    if unresolved.isEmpty then
      val resolved = EnvVarPattern.replaceAllIn(
        input,
        m => java.util.regex.Matcher.quoteReplacement(env(m.group(1)).get)
      )
      Validated.validNel(resolved)
    else
      val errors = NonEmptyList.fromListUnsafe(
        unresolved.map(name => AclError.UnresolvedVariable(name): AclError)
      )
      Validated.invalid(errors)
