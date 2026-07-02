// Run the 22 TPC-H benchmark queries against the Quack-on-Demand FlightSQL edge
// and report the row count, latency, and first-row preview for each.
//
// Run:  npm run tpch                  # all 22 queries
//       npm run tpch -- 1 6 14        # just queries 1, 6 and 14
//
// The target schema defaults to `tpch1` (override with QOD_TPCH_SCHEMA); all
// other connection settings come from the same QOD_* env vars query.ts uses
// (see README). The defaults connect as the superuser admin to acme/bi.
import { Table } from "apache-arrow";
import { QodClient, configFromEnv, rowToObject } from "./client.js";
import { TPCH_QUERIES, qualify } from "./tpch-queries.js";

const SCHEMA = process.env.QOD_TPCH_SCHEMA ?? "tpch1";

// Optional positional args select a subset of queries by id.
const selected = process.argv.slice(2).map(Number).filter((n) => !Number.isNaN(n));
const queries =
  selected.length > 0
    ? TPCH_QUERIES.filter((q) => selected.includes(q.id))
    : TPCH_QUERIES;

function previewRow(table: Table): string {
  if (table.numRows === 0) return "(no rows)";
  return JSON.stringify(rowToObject(table.toArray()[0], table));
}

async function main(): Promise<void> {
  const client = await QodClient.connect(configFromEnv());
  console.log(`Connecting to ${client.describe()}`);
  console.log(`Running ${queries.length} TPC-H query(ies) against schema '${SCHEMA}'\n`);

  let ok = 0;
  let failed = 0;
  let totalMs = 0;

  for (const q of queries) {
    const label = `Q${String(q.id).padStart(2, "0")} ${q.title}`;
    const sql = qualify(q.sql, SCHEMA);
    const startedNs = process.hrtime.bigint();
    try {
      const table = await client.query(sql);
      const ms = Number(process.hrtime.bigint() - startedNs) / 1e6;
      totalMs += ms;
      ok += 1;
      console.log(
        `${label.padEnd(42)} ${ms.toFixed(0).padStart(6)} ms  ` +
          `${String(table.numRows).padStart(6)} rows  ${previewRow(table)}`,
      );
    } catch (err: any) {
      failed += 1;
      console.log(`${label.padEnd(42)} FAILED  ${err?.message ?? err}`);
    }
  }

  console.log(
    `\n${ok} ok, ${failed} failed, ${totalMs.toFixed(0)} ms total ` +
      `(${(totalMs / Math.max(ok, 1)).toFixed(0)} ms avg)`,
  );
  client.close();
  if (failed > 0) process.exit(1);
}

main().catch((err) => {
  console.error("\ntpch run failed:", err?.message ?? err);
  process.exit(1);
});