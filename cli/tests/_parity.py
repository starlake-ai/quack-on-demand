"""OpenAPI spec parsing and coverage gap computation for parity testing."""

from dataclasses import dataclass, field


def load_operations(spec: dict) -> dict[tuple[str, str], set[str]]:
    """Extract (METHOD, path) -> parameter-name set from OpenAPI spec.

    Resolves $ref through components/schemas (one level deep).
    Includes query parameters and requestBody JSON schema top-level properties.

    Args:
        spec: OpenAPI specification dict

    Returns:
        Mapping from (METHOD, path) to set of parameter names
    """
    operations = {}
    schemas = spec.get("components", {}).get("schemas", {})

    for path, path_item in spec.get("paths", {}).items():
        for method, operation in path_item.items():
            if method not in ("get", "post", "put", "delete", "patch", "head", "options"):
                continue

            method_upper = method.upper()
            params = set()

            # Collect query parameters (and path params for future-proofing)
            for param in operation.get("parameters", []):
                if param.get("in") in ("query", "path"):
                    params.add(param.get("name"))

            # Collect requestBody schema properties
            request_body = operation.get("requestBody", {})
            content = request_body.get("content", {})
            json_content = content.get("application/json", {})
            schema = json_content.get("schema", {})

            # Resolve $ref if present
            if "$ref" in schema:
                ref = schema["$ref"]
                if ref.startswith("#/components/schemas/"):
                    schema_name = ref.split("/")[-1]
                    schema = schemas.get(schema_name, {})

            # Collect top-level properties from the resolved schema
            for prop_name in schema.get("properties", {}).keys():
                params.add(prop_name)

            operations[(method_upper, path)] = params

    return operations


@dataclass
class Gaps:
    """Records coverage gaps in CLI commands."""

    missing_operations: list[tuple[str, str]] = field(default_factory=list)
    missing_params: dict[tuple[str, str], set[str]] = field(default_factory=dict)

    def empty(self) -> bool:
        """Return True if there are no gaps."""
        return not self.missing_operations and not self.missing_params


def compute_gaps(
    operations: dict[tuple[str, str], set[str]],
    registry: list,
    exclusions: set[str],
) -> Gaps:
    """Compute coverage gaps between OpenAPI spec and registry.

    Args:
        operations: Result from load_operations()
        registry: List of Coverage objects
        exclusions: Set of paths to exclude (all methods)

    Returns:
        Gaps object with missing_operations and missing_params
    """
    gaps = Gaps()

    # Build registry index by (method, path)
    registry_by_op = {}
    for coverage in registry:
        key = (coverage.method, coverage.path)
        if key not in registry_by_op:
            registry_by_op[key] = []
        registry_by_op[key].append(coverage)

    # Check each operation
    for (method, path), required_params in operations.items():
        # Skip excluded paths
        if path in exclusions:
            continue

        # Check if operation is covered
        if (method, path) not in registry_by_op:
            gaps.missing_operations.append((method, path))
            continue

        # Check if all params are covered
        covered_entries = registry_by_op[(method, path)]

        # Check if any single entry covers all params
        single_entry_covers = False
        for coverage in covered_entries:
            if required_params.issubset(coverage.params.keys()):
                single_entry_covers = True
                break

        if single_entry_covers:
            continue

        # Check if union across entries covers all params
        union_params = set()
        for coverage in covered_entries:
            union_params.update(coverage.params.keys())

        if not required_params.issubset(union_params):
            gaps.missing_params[(method, path)] = required_params - union_params

    return gaps
