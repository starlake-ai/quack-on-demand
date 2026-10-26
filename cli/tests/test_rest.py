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
def test_a_qod_api_key_in_the_environment_is_never_sent(monkeypatch):
    from qod_cli.config import load_settings

    monkeypatch.setenv("QOD_API_KEY", "stale-key-value")
    route = respx.get(f"{BASE}/health").mock(return_value=httpx.Response(200, json={}))
    RestClient(load_settings(overrides={"manager_url": BASE})).request("GET", "/health")
    sent = "X-API-Key" in route.calls.last.request.headers
    assert not sent


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


@pytest.mark.parametrize("token", ["expired-session", "qod_pat_revoked", ""])
@respx.mock
def test_401_hint_names_qod_login_and_qod_token(token):
    respx.get(f"{BASE}/api/tenant/list").mock(
        return_value=httpx.Response(401, json={"error": "unauthorized", "message": "nope"})
    )
    with pytest.raises(ApiError) as exc:
        client(token=token).request("GET", "/api/tenant/list")
    assert exc.value.message == "run qod login, or set QOD_TOKEN to a personal access token"


@pytest.mark.parametrize(
    "path",
    [
        "/api/auth/login",
        "/api/auth/change-password",
        "/api/auth/forgot-password",
        "/api/auth/reset-password",
    ],
)
@pytest.mark.parametrize("token", ["stale-session", ""])
@respx.mock
def test_401_from_a_credential_endpoint_keeps_the_server_message(path, token):
    # These endpoints check the credential in the body: their 401 means that credential is
    # wrong, not that a session expired, so "run qod login" would send `qod login` in a circle.
    respx.post(f"{BASE}{path}").mock(
        return_value=httpx.Response(
            401, json={"error": "invalid_credentials", "message": "Authentication failed"}
        )
    )
    with pytest.raises(ApiError) as exc:
        client(token=token).request("POST", path, body={})
    assert exc.value.error == "invalid_credentials"
    assert exc.value.message == "Authentication failed"


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


@respx.mock
def test_error_response_keeps_details_actions_and_retryable():
    body = {"error": "server_active", "message": "server 'a' is active", "details": {"server": "a"},
            "actions": [{"where": "manager", "command": "qod fleet drain a"}], "retryable": False}
    respx.post(f"{BASE}/api/fleet/server/remove").mock(return_value=httpx.Response(409, json=body))
    with pytest.raises(ApiError) as exc:
        client().request("POST", "/api/fleet/server/remove", body={"name": "a"})
    assert exc.value.details == {"server": "a"}
    assert exc.value.actions == [{"where": "manager", "command": "qod fleet drain a"}]
    assert exc.value.retryable is False
