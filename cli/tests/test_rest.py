import httpx
import pytest
import respx

from qod_cli.config import Settings
from qod_cli.rest import ApiError, RestClient

BASE = "http://mgr:20900"


def client(**kw) -> RestClient:
    return RestClient(Settings(manager_url=BASE, **kw))


@respx.mock
def test_get_json_with_token_header():
    route = respx.get(f"{BASE}/api/tenant/list").mock(
        return_value=httpx.Response(200, json={"tenants": []})
    )
    data = client(token="jwt1").request("GET", "/api/tenant/list")
    assert data == {"tenants": []}
    assert route.calls.last.request.headers["X-API-Key"] == "jwt1"


@respx.mock
def test_api_key_wins_over_token():
    route = respx.get(f"{BASE}/health").mock(return_value=httpx.Response(200, json={}))
    client(api_key="static", token="jwt").request("GET", "/health")
    assert route.calls.last.request.headers["X-API-Key"] == "static"


@respx.mock
def test_none_params_dropped_and_bools_lowered():
    route = respx.get(f"{BASE}/api/audit/list").mock(return_value=httpx.Response(200, json={}))
    client().request("GET", "/api/audit/list", params={"limit": 10, "actor": None, "noTenant": True})
    q = dict(route.calls.last.request.url.params)
    assert q == {"limit": "10", "noTenant": "true"}


@respx.mock
def test_post_json_body():
    route = respx.post(f"{BASE}/api/tenant/create").mock(
        return_value=httpx.Response(200, json={"id": "acme"})
    )
    client().request("POST", "/api/tenant/create", body={"id": "acme"})
    assert route.calls.last.request.headers["content-type"] == "application/json"


@respx.mock
def test_string_body_sent_raw():
    route = respx.post(f"{BASE}/api/manifest/import").mock(
        return_value=httpx.Response(200, json={"tenants": 1})
    )
    client().request("POST", "/api/manifest/import", body="tenants: []\n")
    assert route.calls.last.request.content == b"tenants: []\n"


@respx.mock
def test_text_response():
    respx.get(f"{BASE}/api/manifest/export").mock(
        return_value=httpx.Response(200, text="tenants: []\n")
    )
    assert client().request("GET", "/api/manifest/export", text=True) == "tenants: []\n"


@respx.mock
def test_empty_body_returns_none():
    respx.post(f"{BASE}/api/tenant/delete").mock(return_value=httpx.Response(200))
    assert client().request("POST", "/api/tenant/delete", body={"name": "x"}) is None


@respx.mock
def test_error_response_raises_api_error():
    respx.post(f"{BASE}/api/pool/create").mock(
        return_value=httpx.Response(409, json={"error": "conflict", "message": "pool exists"})
    )
    with pytest.raises(ApiError) as exc:
        client().request("POST", "/api/pool/create", body={})
    assert exc.value.status == 409
    assert "pool exists" in str(exc.value)


@respx.mock
def test_401_with_token_hints_login():
    respx.get(f"{BASE}/api/auth/whoami").mock(
        return_value=httpx.Response(401, json={"error": "unauthorized", "message": "expired"})
    )
    with pytest.raises(ApiError) as exc:
        client(token="old").request("GET", "/api/auth/whoami")
    assert "run qod login" in exc.value.message


@respx.mock
def test_connection_error_maps_to_api_error():
    respx.get(f"{BASE}/health").mock(side_effect=httpx.ConnectError("refused"))
    with pytest.raises(ApiError) as exc:
        client().request("GET", "/health")
    assert exc.value.error == "connection_error"


@respx.mock
def test_non_dict_error_body_maps_cleanly():
    respx.get(f"{BASE}/health").mock(return_value=httpx.Response(500, json=["boom"]))
    with pytest.raises(ApiError) as exc:
        client().request("GET", "/health")
    assert exc.value.status == 500
