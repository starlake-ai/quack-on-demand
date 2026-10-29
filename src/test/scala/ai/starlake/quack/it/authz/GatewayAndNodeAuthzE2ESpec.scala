package ai.starlake.quack.it.authz

/** The authorization cases with the ACL on: the gateway's screens and the node both enforce, so
  * every allowed case passes both. Manager on 20954 (REST) / 31354 (FlightSQL) / 29497 (front
  * door), relay 20964, nodes 23230-23239.
  */
class GatewayAndNodeAuthzE2ESpec
    extends AuthzEndToEndCases("authz_layered", 20954, 31354, 29497, 20964, 23230, 23239):
  protected def nodeOnly: Boolean = false
