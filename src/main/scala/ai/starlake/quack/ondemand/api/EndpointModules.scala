package ai.starlake.quack.ondemand.api

/** Single registry of every endpoint-definition object. Consumed by reflection in
  * `ai.starlake.quack.docs.DocEndpoints` (OpenAPI generation) and by `TenantScopeCompletenessSpec`
  * (the fail-open guardrail for URL-tenant scoping). A new endpoint object that is wired in
  * ManagerServer but missing here is invisible to the API docs AND exempt from the guardrail -
  * always register it.
  */
object EndpointModules:
  val all: List[AnyRef] = List(
    Endpoints,
    PoolEndpoints,
    NodeEndpoints,
    TenantEndpoints,
    AuthEndpoints,
    TelemetryEndpoints,
    FederatedSourceEndpoints,
    CatalogEndpoints,
    TagEndpoints,
    RbacEndpoints
  )
