package ai.starlake.acl.policy

enum ResourceLookupResult:
  case BaseTable
  case View(sql: String)
  case Unknown
