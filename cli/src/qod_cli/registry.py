"""Coverage registry and @covers decorator for REST API parity tracking."""

from dataclasses import dataclass
from typing import Callable, TypeVar

F = TypeVar("F", bound=Callable)


@dataclass(frozen=True)
class Coverage:
    """Records coverage of a single OpenAPI operation by a CLI command."""

    method: str  # upper-case
    path: str  # OpenAPI path template, e.g. "/api/pool/suspend"
    params: dict[str, str]  # request field / query param name -> CLI option string
    command: str  # module-qualified function name, for error messages


REGISTRY: list[Coverage] = []


def covers(method: str, path: str, params: dict[str, str] | None = None) -> Callable[[F], F]:
    """Decorator to record REST operation coverage in the registry.

    Args:
        method: HTTP method (case-insensitive, normalized to upper-case)
        path: OpenAPI path template
        params: Mapping of operation parameters to CLI option names

    Returns:
        Decorator that records coverage and passes the function through unchanged
    """

    def decorator(fn: F) -> F:
        coverage = Coverage(
            method=method.upper(),
            path=path,
            params=params or {},
            command=f"{fn.__module__}.{fn.__qualname__}",
        )
        REGISTRY.append(coverage)
        return fn

    return decorator
