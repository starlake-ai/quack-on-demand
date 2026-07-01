"""Run a single SQL statement against the Quack-on-Demand FlightSQL edge and
print the Arrow result.

Run:  python query.py
      python query.py "SELECT count(*) FROM tpch1.customer"

See client.py for the ADBC plumbing. Connection settings come from QOD_* env
vars (see README).
"""

import json
import os
import sys

from client import QodClient, config_from_env, row_to_dict

SQL = (
    sys.argv[1]
    if len(sys.argv) > 1
    else os.environ.get(
        "QOD_SQL",
        "SELECT * FROM (VALUES (1, 'duck'), (2, 'quack'), (3, 'lake')) AS t(id, label)",
    )
)


def main() -> None:
    with QodClient.connect(config_from_env()) as client:
        print(f"Connecting to {client.describe()}")
        print(f"SQL: {SQL}\n")

        table = client.query(SQL)

        columns = ", ".join(f"{f.name}: {f.type}" for f in table.schema)
        print(f"columns: {columns}")
        print(f"rows: {table.num_rows}\n")
        for i in range(min(table.num_rows, 100)):
            print(json.dumps(row_to_dict(table, i)))


if __name__ == "__main__":
    try:
        main()
    except Exception as err:  # noqa: BLE001 - top-level CLI error reporting
        print(f"\nquery failed: {err}", file=sys.stderr)
        sys.exit(1)