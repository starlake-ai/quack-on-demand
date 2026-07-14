"""Profile file + env + flag precedence.

Resolution order per setting: explicit override (command flag) > QOD_* env
var > profile file > built-in default. The profile file is TOML at the
platform config dir (QOD_CONFIG_FILE overrides the full path) and is written
with mode 0600 because it can hold a session token and an opt-in SQL password.
"""

from __future__ import annotations

import os
import sys
from dataclasses import dataclass, fields
from pathlib import Path

import platformdirs
import tomli_w

if sys.version_info >= (3, 11):
    import tomllib
else:
    import tomli as tomllib

ENV_VARS = {
    "manager_url": "QOD_MANAGER_URL",
    "api_key": "QOD_API_KEY",
    "token": "QOD_TOKEN",
    "edge_host": "QOD_HOST",
    "edge_port": "QOD_PORT",
    "edge_tls": "QOD_TLS",
    "edge_tls_verify": "QOD_TLS_VERIFY",
    "tenant": "QOD_TENANT",
    "pool": "QOD_POOL",
    "sql_user": "QOD_USER",
    "sql_password": "QOD_PASSWORD",
    "superuser": "QOD_SUPERUSER",
}


@dataclass
class Settings:
    manager_url: str = "http://localhost:20900"
    api_key: str = ""
    token: str = ""
    edge_host: str = "localhost"
    edge_port: int = 31338
    edge_tls: bool = True
    edge_tls_verify: bool = False
    tenant: str = ""
    pool: str = ""
    sql_user: str = ""
    sql_password: str = ""
    superuser: bool = False


def config_path() -> Path:
    env = os.environ.get("QOD_CONFIG_FILE")
    if env:
        return Path(env)
    return Path(platformdirs.user_config_dir("qod")) / "config.toml"


def _read_file() -> dict:
    path = config_path()
    if not path.exists():
        return {}
    with path.open("rb") as f:
        return tomllib.load(f)


def _coerce(name: str, kind: type, raw) -> object:
    if kind is bool:
        if isinstance(raw, bool):
            return raw
        return str(raw).strip().lower() in ("true", "1", "yes")
    if kind is int:
        return int(raw)
    return str(raw)


def load_settings(profile: str = "default", overrides: dict | None = None) -> Settings:
    file_values = _read_file().get("profiles", {}).get(profile, {})
    overrides = overrides or {}
    values: dict = {}
    for f in fields(Settings):
        if f.name in overrides and overrides[f.name] is not None:
            raw = overrides[f.name]
        elif os.environ.get(ENV_VARS[f.name]) is not None:
            raw = os.environ[ENV_VARS[f.name]]
        elif f.name in file_values:
            raw = file_values[f.name]
        else:
            continue
        values[f.name] = _coerce(f.name, f.type if isinstance(f.type, type) else type(f.default), raw)
    return Settings(**values)


def save_profile(profile: str, values: dict) -> None:
    data = _read_file()
    profiles = data.setdefault("profiles", {})
    current = profiles.setdefault(profile, {})
    current.update({k: v for k, v in values.items() if v is not None})
    path = config_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as f:
        tomli_w.dump(data, f)
    os.chmod(path, 0o600)
