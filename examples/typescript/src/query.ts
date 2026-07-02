// Run a single SQL statement against the Quack-on-Demand FlightSQL edge and
// print the Arrow result.
//
// Run:  npm run query
//       npm run query -- "SELECT count(*) FROM tpch1.customer"
//
// See client.ts for the gRPC + Arrow plumbing and src/proto/ for the protocol
// subset. Connection settings come from QOD_* env vars (see README).
import { QodClient, configFromEnv, rowToObject } from "./client.js";

const SQL =
  process.argv[2] ??
  process.env.QOD_SQL ??
  "SELECT * FROM (VALUES (1, 'duck'), (2, 'quack'), (3, 'lake')) AS t(id, label)";

async function main(): Promise<void> {
  const client = await QodClient.connect(configFromEnv());
  console.log(`Connecting to ${client.describe()}`);
  console.log(`SQL: ${SQL}\n`);

  const table = await client.query(SQL);

  const columns = table.schema.fields.map((f) => `${f.name}: ${f.type}`).join(", ");
  console.log(`columns: ${columns}`);
  console.log(`rows: ${table.numRows}\n`);
  for (const row of table.toArray().slice(0, 100)) {
    console.log(JSON.stringify(rowToObject(row, table)));
  }

  client.close();
}

main().catch((err) => {
  console.error("\nquery failed:", err?.message ?? err);
  process.exit(1);
});