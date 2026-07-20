from qod_cli.registry import Coverage, covers, REGISTRY
from tests._parity import Gaps, compute_gaps, load_operations

FIXTURE = {
    "components": {"schemas": {
        "SuspendReq": {"properties": {"tenant": {}, "tenantDb": {}, "pool": {}}},
    }},
    "paths": {
        "/api/pool/suspend": {"post": {"requestBody": {"content": {"application/json": {
            "schema": {"$ref": "#/components/schemas/SuspendReq"}}}}}},
        "/api/pool/list": {"get": {"parameters": []}},
        "/api/telemetry/usage": {"get": {"parameters": [
            {"name": "from", "in": "query"}, {"name": "to", "in": "query"}]}},
        "/api/auth/oidc/callback": {"get": {"parameters": [{"name": "code", "in": "query"}]}},
    },
}

def ops():
    return load_operations(FIXTURE)

def test_load_operations_resolves_refs_and_query_params():
    o = ops()
    assert o[("POST", "/api/pool/suspend")] == {"tenant", "tenantDb", "pool"}
    assert o[("GET", "/api/pool/list")] == set()
    assert o[("GET", "/api/telemetry/usage")] == {"from", "to"}

def test_gaps_flags_missing_operation_and_params():
    reg = [Coverage("GET", "/api/pool/list", {}, "pool.list_")]
    g = compute_gaps(ops(), reg, exclusions={"/api/auth/oidc/callback"})
    assert ("POST", "/api/pool/suspend") in g.missing_operations
    assert ("GET", "/api/telemetry/usage") in g.missing_operations
    assert ("GET", "/api/auth/oidc/callback") not in g.missing_operations
    assert not g.empty()

def test_param_union_across_entries_counts_as_covered():
    reg = [
        Coverage("GET", "/api/telemetry/usage", {"from": "--from"}, "a"),
        Coverage("GET", "/api/telemetry/usage", {"to": "--to"}, "b"),
        Coverage("POST", "/api/pool/suspend",
                 {"tenant": "--tenant", "tenantDb": "--db", "pool": "--pool"}, "c"),
        Coverage("GET", "/api/pool/list", {}, "d"),
    ]
    g = compute_gaps(ops(), reg, exclusions={"/api/auth/oidc/callback"})
    assert g.empty(), (g.missing_operations, g.missing_params)

def test_partial_params_reported():
    reg = [Coverage("POST", "/api/pool/suspend", {"tenant": "--tenant"}, "c"),
           Coverage("GET", "/api/pool/list", {}, "d"),
           Coverage("GET", "/api/telemetry/usage", {"from": "--from", "to": "--to"}, "e")]
    g = compute_gaps(ops(), reg, exclusions={"/api/auth/oidc/callback"})
    assert g.missing_params[("POST", "/api/pool/suspend")] == {"tenantDb", "pool"}

def test_covers_decorator_records_and_passes_through():
    before = len(REGISTRY)
    @covers("post", "/api/x", {"a": "--a"})
    def cmd(): return 42
    assert cmd() == 42
    assert REGISTRY[before].method == "POST" and REGISTRY[before].path == "/api/x"
