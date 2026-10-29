package ai.starlake.quack.it.authz

/** The authorization cases with the ACL off: the gateway's table and file screens do not run, so
  * every grant, view and file refusal is the node's. Manager on 20953 (REST) / 31353 (FlightSQL) /
  * 29496 (front door), relay 20963, nodes 23220-23229.
  */
class NodeEnforcedAuthzE2ESpec
    extends AuthzEndToEndCases("authz_node", 20953, 31353, 29496, 20963, 23220, 23229):
  protected def nodeOnly: Boolean = true
