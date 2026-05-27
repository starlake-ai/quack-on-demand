package ai.starlake.acl.model

import java.time.Instant

enum DenyReason {
  case ParseError(message: String)
  case NoMatchingGrant(table: TableRef, user: UserIdentity)
  case ViewResolutionCycle(chain: List[TableRef])
  case UnknownView(viewRef: TableRef)
  case UnsupportedStatement(statementType: String)
  case UnqualifiedTable(tableName: String, missingPart: String)
  case ViewParseError(viewRef: TableRef, message: String)
  case CallbackError(table: TableRef, message: String)

  case ExpiredGrant(table: TableRef, user: UserIdentity, expiredAt: Instant)
  case MaxViewDepthExceeded(path: List[TableRef])
}
