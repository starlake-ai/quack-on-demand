"""One httpx client for the manager REST plane.

Auth: X-API-Key carries either the static api_key or the session JWT written
by `qod login` (api_key wins when both are set, matching apiKeyGuard which
accepts either).
"""

from __future__ import annotations

from typing import Any

import httpx

from .config import Settings


class ApiError(Exception):
    def __init__(self, status: int, error: str, message: str):
        self.status = status
        self.error = error
        self.message = message
        super().__init__(f"{status} {error}: {message}")


class RestClient:
    def __init__(self, settings: Settings):
        self._settings = settings

    def request(
        self,
        method: str,
        path: str,
        params: dict | None = None,
        body: Any | None = None,
        text: bool = False,
    ) -> Any:
        settings = self._settings
        headers = {}
        key = settings.api_key or settings.token
        if key:
            headers["X-API-Key"] = key
        query = {}
        for name, value in (params or {}).items():
            if value is None:
                continue
            query[name] = "true" if value is True else "false" if value is False else str(value)
        kwargs: dict = {"params": query, "headers": headers, "timeout": 30.0}
        if isinstance(body, str):
            kwargs["content"] = body.encode()
            headers["Content-Type"] = "text/plain"
        elif body is not None:
            kwargs["json"] = body
        response = httpx.request(method, settings.manager_url + path, **kwargs)
        if response.is_success:
            if text:
                return response.text
            if not response.content:
                return None
            return response.json()
        try:
            payload = response.json()
            error, message = payload.get("error", "error"), payload.get("message", response.text)
        except ValueError:
            error, message = "error", response.text or response.reason_phrase
        if response.status_code == 401 and not settings.api_key:
            message = "session expired or invalid, run qod login"
        raise ApiError(response.status_code, error, message)
