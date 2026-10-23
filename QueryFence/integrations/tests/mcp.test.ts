import { afterEach, expect, it, vi } from "vitest";
import { spawn, ChildProcessWithoutNullStreams } from "node:child_process";
import { createInterface } from "node:readline";
import { createServer, Server } from "node:http";
import { once } from "node:events";
import { McpProtocol, PROTOCOL_VERSION, tools } from "../mcp/protocol.js";
import { QueryFenceClient } from "../client/queryfence.js";
const meta = {
  "io.modelcontextprotocol/protocolVersion": PROTOCOL_VERSION,
  "io.modelcontextprotocol/clientCapabilities": {},
  "io.modelcontextprotocol/clientInfo": {
    name: "queryfence-protocol-test",
    version: "1",
  },
};
let child: ChildProcessWithoutNullStreams | undefined;
let server: Server | undefined;
afterEach(async () => {
  vi.unstubAllGlobals();
  if (child) {
    child.stdin.end();
    child.kill();
    child = undefined;
  }
  if (server) {
    server.closeAllConnections();
    await new Promise<void>((r) => server!.close(() => r()));
    server = undefined;
  }
});
async function wire() {
  let used = false;
  const calls: string[] = [];
  server = createServer(async (req, res) => {
    calls.push(req.method + " " + req.url);
    if (req.headers.authorization !== "Bearer ephemeral-wire-token") {
      res.writeHead(401);
      res.end('{"code":"AUTH_REQUIRED"}');
      return;
    }
    if (req.url?.endsWith("/execute")) {
      let body = "";
      for await (const c of req) body += c;
      if (used || JSON.parse(body).approvalToken !== "approved-test") {
        res.writeHead(409);
        res.end(
          '{"code":"PLAN_STATE","message":"Approval invalid or consumed"}',
        );
        return;
      }
      used = true;
      res.setHeader("Content-Type", "application/json");
      res.end('{"rows":[{"label":"HOME","value":42}]}');
      return;
    }
    res.setHeader("Content-Type", "application/json");
    res.end(JSON.stringify({ synthetic: true, role: "ANALYST" }));
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const port = (server.address() as { port: number }).port;
  child = spawn(process.execPath, ["dist/mcp/server.js"], {
    env: {
      ...process.env,
      QUERYFENCE_API_URL: `http://127.0.0.1:${port}/api/v2`,
      QUERYFENCE_TOKEN: "ephemeral-wire-token",
      QUERYFENCE_TOKEN_FILE: "",
    },
    stdio: "pipe",
  });
  const replies = new Map<unknown, (value: any) => void>();
  const output: unknown[] = [];
  createInterface({ input: child.stdout }).on("line", (line) => {
    const value = JSON.parse(line);
    output.push(value);
    replies.get(value.id)?.(value);
  });
  let next = 0;
  function send(
    method: string,
    params: Record<string, unknown> = {},
    modern = true,
  ): Promise<any> {
    const id = ++next;
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(
        () => reject(new Error("MCP response timeout")),
        4000,
      );
      replies.set(id, (value) => {
        clearTimeout(timeout);
        resolve(value);
      });
      child!.stdin.write(
        JSON.stringify({
          jsonrpc: "2.0",
          id,
          method,
          params: { ...(modern ? { _meta: meta } : {}), ...params },
        }) + "\n",
      );
    });
  }
  return { send, calls, output };
}
it("speaks current newline-delimited JSON-RPC with discovery and deterministic tool schemas", async () => {
  const { send } = await wire();
  const discovered = await send("server/discover");
  expect(discovered.result.supportedVersions).toContain(PROTOCOL_VERSION);
  expect(discovered.result.resultType).toBe("complete");
  const list = await send("tools/list");
  expect(list.result.tools.map((t: any) => t.name)).toEqual(
    tools.map((t) => t.name),
  );
  expect(
    list.result.tools.some(
      (t: any) => t.name.includes("approve") && !t.name.includes("approved"),
    ),
  ).toBe(false);
  expect(
    (await send("tools/call", { name: "get_schema", arguments: {} })).result
      .structuredContent.synthetic,
  ).toBe(true);
});
it("requires current metadata, rejects unknown SQL fields and never calls an approval endpoint", async () => {
  const { send, calls } = await wire();
  expect((await send("tools/list", {}, false)).error.code).toBe(-32602);
  expect(
    (
      await send("tools/list", {
        _meta: {
          ...meta,
          "io.modelcontextprotocol/protocolVersion": "1900-01-01",
        },
      })
    ).error.code,
  ).toBe(-32022);
  expect(
    (
      await send("tools/call", {
        name: "create_query_plan",
        arguments: {
          question: "Revenue by region all time",
          sql: "DROP TABLE orders",
        },
      })
    ).error.code,
  ).toBe(-32602);
  expect(
    (await send("tools/call", { name: "approve_query_plan", arguments: {} }))
      .error.code,
  ).toBe(-32602);
  expect(calls).toHaveLength(0);
});
it("forwards only existing approval tokens and reports replay rejection as a tool error", async () => {
  const { send, calls, output } = await wire();
  const args = {
    planId: "00000000-0000-0000-0000-000000000001",
    approvalToken: "approved-test",
    confirmed: true,
  };
  const result = await send("tools/call", {
    name: "execute_approved_plan",
    arguments: args,
  });
  expect(result.result.structuredContent.rows[0].value).toBe(42);
  expect(
    (
      await send("tools/call", {
        name: "execute_approved_plan",
        arguments: args,
      })
    ).result.isError,
  ).toBe(true);
  expect(calls.every((c) => !c.endsWith("/approve"))).toBe(true);
  expect(JSON.stringify(output)).not.toContain("ephemeral-wire-token");
});
it("supports explicitly negotiated legacy stdio clients without pretending they use the current protocol", async () => {
  const { send } = await wire();
  const init = await send(
    "initialize",
    {
      protocolVersion: "2025-11-25",
      capabilities: {},
      clientInfo: { name: "legacy-test", version: "1" },
    },
    false,
  );
  expect(init.result.protocolVersion).toBe("2025-11-25");
  expect(
    (await send("tools/list", {}, false)).result.tools.length,
  ).toBeGreaterThan(0);
});
it("cancels an in-flight tool without sending a result after cancellation", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(
      (_url, options) =>
        new Promise((_resolve, reject) =>
          options.signal.addEventListener("abort", () =>
            reject(new Error("cancelled")),
          ),
        ),
    ),
  );
  const protocol = new McpProtocol(
    new QueryFenceClient({
      apiUrl: "http://localhost:18080/api/v2",
      getAccessToken: () => "ephemeral-cancel-token",
    }),
  );
  const pending = protocol.handle({
    jsonrpc: "2.0",
    id: 9,
    method: "tools/call",
    params: { _meta: meta, name: "get_schema", arguments: {} },
  });
  await new Promise((r) => setTimeout(r, 10));
  await protocol.handle({
    jsonrpc: "2.0",
    method: "notifications/cancelled",
    params: { requestId: 9 },
  });
  expect(await pending).toBeNull();
});
