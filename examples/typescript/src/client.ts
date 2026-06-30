// Reusable FlightSQL client for Quack-on-Demand.
//
// Node has no first-party Flight SQL driver, so this talks to the edge over
// raw gRPC (@grpc/grpc-js) using a minimal slice of the Flight protocol
// (src/proto/), then decodes the Arrow result stream with apache-arrow.
//
// The flow every Flight SQL client follows:
//   1. GetFlightInfo with a FlightDescriptor whose cmd is an Any-wrapped
//      CommandStatementQuery -> the edge returns a FlightInfo with endpoints,
//      each carrying an opaque ticket.
//   2. DoGet(ticket) for each endpoint -> a stream of FlightData (an Arrow IPC
//      message header + body split across two fields).
//   3. Reassemble the Arrow IPC stream from those chunks and decode it.
//
// The edge authenticates every RPC from the call headers (tenant, pool,
// authorization), so there is no separate handshake step.
import * as grpc from "@grpc/grpc-js";
import * as protoLoader from "@grpc/proto-loader";
import protobuf from "protobufjs";
import * as tls from "node:tls";
import * as path from "node:path";
import { fileURLToPath } from "node:url";
import { tableFromIPC, Table } from "apache-arrow";

const here = path.dirname(fileURLToPath(import.meta.url));
const protoPath = (name: string) => path.join(here, "proto", name);

const ANY_TYPE_URL =
  "type.googleapis.com/arrow.flight.protocol.sql.CommandStatementQuery";

export interface QodConfig {
  host: string;
  port: number;
  user: string;
  password: string;
  tenant: string;
  pool: string;
  superuser: boolean;
  tls: boolean;
  tlsVerify: boolean;
}

// Build a config from QOD_* env vars, falling back to a local edge
// (127.0.0.1:31338) and the superuser admin against the acme tenant's bi pool.
export function configFromEnv(): QodConfig {
  return {
    host: process.env.QOD_HOST ?? "127.0.0.1",
    port: Number(process.env.QOD_PORT ?? "31338"),
    user: process.env.QOD_USER ?? "admin",
    password: process.env.QOD_PASSWORD ?? "admin",
    tenant: process.env.QOD_TENANT ?? "acme",
    pool: process.env.QOD_POOL ?? "bi",
    superuser: (process.env.QOD_SUPERUSER ?? "true") === "true",
    tls: (process.env.QOD_TLS ?? "true") === "true",
    tlsVerify: (process.env.QOD_TLS_VERIFY ?? "false") === "true",
  };
}

interface FlightClient extends grpc.Client {
  GetFlightInfo(
    descriptor: unknown,
    metadata: grpc.Metadata,
    cb: (err: grpc.ServiceError | null, info: any) => void,
  ): void;
  DoGet(ticket: unknown, metadata: grpc.Metadata): grpc.ClientReadableStream<any>;
}

// Pull the server's self-signed leaf certificate off the wire and return it as
// PEM, so it can be pinned as the gRPC root. Combined with a no-op
// checkServerIdentity this is the JS equivalent of "skip verification".
function fetchServerCertPem(host: string, port: number): Promise<string> {
  const isIp = /^[\d.]+$/.test(host) || host.includes(":");
  return new Promise((resolve, reject) => {
    const socket = tls.connect(
      { host, port, rejectUnauthorized: false, ...(isIp ? {} : { servername: host }) },
      () => {
        const cert = socket.getPeerCertificate(true);
        socket.end();
        if (!cert || !cert.raw) {
          reject(new Error("server presented no certificate"));
          return;
        }
        const b64 = cert.raw.toString("base64").match(/.{1,64}/g)!.join("\n");
        resolve(`-----BEGIN CERTIFICATE-----\n${b64}\n-----END CERTIFICATE-----\n`);
      },
    );
    socket.on("error", reject);
  });
}

// A FlightData chunk carries an Arrow IPC message split into its flatbuffer
// header (data_header) and its body (data_body). Reassemble the encapsulated
// IPC message format the Arrow stream reader expects:
//   [continuation 0xFFFFFFFF][int32 LE header length, padded to 8][header][body]
function encapsulate(header: Buffer, body: Buffer): Buffer {
  const padded = (header.length + 7) & ~7;
  const prefix = Buffer.alloc(8 + padded); // padding bytes left as zero
  prefix.writeUInt32LE(0xffffffff, 0);
  prefix.writeInt32LE(padded, 4);
  header.copy(prefix, 8);
  return Buffer.concat([prefix, body]);
}

// 8-byte end-of-stream marker (continuation + zero length).
const EOS = Buffer.from([0xff, 0xff, 0xff, 0xff, 0x00, 0x00, 0x00, 0x00]);

export class QodClient {
  private constructor(
    private readonly client: FlightClient,
    private readonly cfg: QodConfig,
    private readonly any: protobuf.Type,
    private readonly cmd: protobuf.Type,
  ) {}

  static async connect(cfg: QodConfig): Promise<QodClient> {
    const creds = await QodClient.credentials(cfg);

    const pkgDef = protoLoader.loadSync(protoPath("flight.proto"), {
      keepCase: true, // keep snake_case field names (data_header, data_body, ...)
      longs: String,
      enums: String, // DescriptorType comes back as 'CMD' etc.
      defaults: true,
      oneofs: true,
    });
    const proto = grpc.loadPackageDefinition(pkgDef) as any;
    const FlightService = proto.arrow.flight.protocol.FlightService;

    // grpc-js refuses to use an IP literal as the TLS server name. When we are
    // skipping verification anyway, override the SSL target name with a
    // placeholder hostname so the handshake proceeds against an IP target.
    const options: grpc.ClientOptions = {};
    if (cfg.tls && !cfg.tlsVerify) {
      options["grpc.ssl_target_name_override"] = "quack-on-demand";
      options["grpc.default_authority"] = "quack-on-demand";
    }
    const client: FlightClient = new FlightService(
      `${cfg.host}:${cfg.port}`,
      creds,
      options,
    );

    const root = protobuf.loadSync(protoPath("flightsql.proto"));
    const any = root.lookupType("arrow.flight.protocol.sql.Any");
    const cmd = root.lookupType("arrow.flight.protocol.sql.CommandStatementQuery");
    return new QodClient(client, cfg, any, cmd);
  }

  private static async credentials(cfg: QodConfig): Promise<grpc.ChannelCredentials> {
    if (!cfg.tls) return grpc.credentials.createInsecure();
    if (cfg.tlsVerify) return grpc.credentials.createSsl();
    const pem = await fetchServerCertPem(cfg.host, cfg.port);
    return grpc.credentials.createSsl(Buffer.from(pem), null, null, {
      checkServerIdentity: () => undefined, // self-signed cert: skip hostname/IP match
    });
  }

  describe(): string {
    const proto = this.cfg.tls ? "grpc+tls" : "grpc";
    const su = this.cfg.superuser ? " (superuser)" : "";
    return `${proto}://${this.cfg.host}:${this.cfg.port} as ${this.cfg.user}@${this.cfg.tenant}/${this.cfg.pool}${su}`;
  }

  // The edge reads these headers on every RPC. authorization is HTTP Basic;
  // tenant/pool route the query; superuser=true selects the system realm and
  // bypasses the per-statement ACL gate.
  private metadata(): grpc.Metadata {
    const md = new grpc.Metadata();
    md.set("tenant", this.cfg.tenant);
    md.set("pool", this.cfg.pool);
    const basic = Buffer.from(`${this.cfg.user}:${this.cfg.password}`).toString("base64");
    md.set("authorization", `Basic ${basic}`);
    if (this.cfg.superuser) md.set("superuser", "true");
    return md;
  }

  // Build the FlightDescriptor.cmd: an Any-wrapped CommandStatementQuery.
  private command(sql: string): Buffer {
    const inner = this.cmd.encode({ query: sql }).finish();
    // protobufjs exposes proto fields as camelCase, so `type_url` is `typeUrl`.
    const any = this.any.encode({ typeUrl: ANY_TYPE_URL, value: inner }).finish();
    return Buffer.from(any);
  }

  // Run one SQL statement and return the decoded Arrow table.
  async query(sql: string): Promise<Table> {
    const info = await new Promise<any>((resolve, reject) => {
      this.client.GetFlightInfo(
        { type: "CMD", cmd: this.command(sql) },
        this.metadata(),
        (err, resp) => (err ? reject(err) : resolve(resp)),
      );
    });

    const messages: Buffer[] = [];
    for (const endpoint of info.endpoint ?? []) {
      await new Promise<void>((resolve, reject) => {
        const stream = this.client.DoGet(
          { ticket: endpoint.ticket.ticket },
          this.metadata(),
        );
        stream.on("data", (fd: any) => {
          const header: Buffer = fd.data_header ?? Buffer.alloc(0);
          const body: Buffer = fd.data_body ?? Buffer.alloc(0);
          if (header.length > 0) messages.push(encapsulate(header, body));
        });
        stream.on("end", resolve);
        stream.on("error", reject);
      });
    }

    return tableFromIPC(Buffer.concat([...messages, EOS]));
  }

  close(): void {
    this.client.close();
  }
}

// Convert one Arrow row into a plain JSON-friendly object. Int64 columns come
// back as BigInt and Decimal columns as DecimalBigNum objects, neither of which
// JSON.stringify renders cleanly, so both are coerced to their string form.
export function rowToObject(row: any, table: Table): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const field of table.schema.fields) {
    const value = row[field.name];
    out[field.name] =
      typeof value === "bigint"
        ? value.toString()
        : value != null && typeof value === "object"
          ? String(value)
          : value;
  }
  return out;
}