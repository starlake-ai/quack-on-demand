package ai.starlake.acl.model

import ai.starlake.acl.policy.ResolutionMode

final case class AclPolicy(
    grants: List[Grant],
    mode: ResolutionMode = ResolutionMode.Strict
)
