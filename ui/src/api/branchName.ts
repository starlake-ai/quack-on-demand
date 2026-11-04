/** The create form's `pattern` for a branch name: the server's rule (`BranchNames.validateName`).
  * The hyphen is escaped because a browser compiles a `pattern` with the `v` flag, where a bare `-`
  * in a class is a syntax error: the browser then ignores the attribute, and says so only in the
  * console. */
export const BranchNamePattern = '[a-z][a-z0-9_\\-]{0,47}';
