package ai.starlake.acl.model

import java.time.Instant

final case class Grant(
    target: GrantTarget,
    principals: List[Principal],
    expires: Option[Instant] = None,
    authorized: Boolean = false
)
