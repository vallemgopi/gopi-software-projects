import { readFileSync } from "node:fs";
import { QueryFenceClient } from "../client/queryfence.js";
import { McpProtocol } from "./protocol.js";
let token: string | undefined;
try {
  token = process.env.QUERYFENCE_TOKEN_FILE
    ? JSON.parse(readFileSync(process.env.QUERYFENCE_TOKEN_FILE, "utf8")).token
    : process.env.QUERYFENCE_TOKEN;
} catch {
  process.stderr.write(
    "Credential file is unreadable or invalid. Contents were not logged.\n",
  );
  process.exit(1);
}
if (!token) {
  process.stderr.write(
    "Configure QUERYFENCE_TOKEN_FILE or QUERYFENCE_TOKEN. No credential was provided.\n",
  );
  process.exit(1);
}
const protocol = new McpProtocol(
  new QueryFenceClient({
    apiUrl: process.env.QUERYFENCE_API_URL || "http://localhost:18080/api/v2",
    getAccessToken: () => token,
  }),
);
let buffer = "";
let dropping = false;
const send = (value: unknown) => {
  if (value !== null) process.stdout.write(JSON.stringify(value) + "\n");
};
process.stdin.setEncoding("utf8");
process.stdin.on("data", (chunk: string) => {
  buffer += chunk;
  let newline;
  while ((newline = buffer.indexOf("\n")) >= 0) {
    const line = buffer.slice(0, newline);
    buffer = buffer.slice(newline + 1);
    if (dropping) {
      dropping = false;
      continue;
    }
    if (Buffer.byteLength(line) > 65536) {
      send({
        jsonrpc: "2.0",
        id: null,
        error: { code: -32600, message: "Message exceeds 64 KiB" },
      });
      continue;
    }
    if (!line.trim()) continue;
    try {
      void protocol.handle(JSON.parse(line)).then(send);
    } catch {
      send({
        jsonrpc: "2.0",
        id: null,
        error: { code: -32700, message: "Parse error" },
      });
    }
  }
  if (Buffer.byteLength(buffer) > 65536) {
    buffer = "";
    dropping = true;
    send({
      jsonrpc: "2.0",
      id: null,
      error: { code: -32600, message: "Message exceeds 64 KiB" },
    });
  }
});
process.stdin.on("end", () => process.exit(0));
