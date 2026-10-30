package ai.starlake.quack.it.authz

/** The authorization cases on the one stack there is since Phase D: the node alone judges grants,
  * views, files, settings and catalogs, so every refusal carries the node's `QoD: ` prefix. Manager
  * on 20953 (REST) / 31353 (FlightSQL) / 29496 (front door), relay 20963, nodes 23220-23229.
  */
class NodeEnforcedAuthzE2ESpec
    extends AuthzEndToEndCases("authz_node", 20953, 31353, 29496, 20963, 23220, 23229)
