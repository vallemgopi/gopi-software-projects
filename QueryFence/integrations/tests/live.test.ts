import { expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { spawn } from "node:child_process";
import { createInterface } from "node:readline";
import { PROTOCOL_VERSION } from "../mcp/protocol.js";
import { QueryFenceClient } from "../client/queryfence.js";
it.skipIf(
  !process.env.QUERYFENCE_LIVE_CLIENT_FILE ||
    !process.env.QUERYFENCE_LIVE_REVIEWER_FILE,
)(
  "runs a real MCP plan through separate human-review credentials and rejects replay",
  async () => {
    const apiUrl =
      process.env.QUERYFENCE_API_URL || "http://localhost:18080/api/v2";
    const reviewerToken = JSON.parse(
      readFileSync(process.env.QUERYFENCE_LIVE_REVIEWER_FILE!, "utf8"),
    ).token;
    const reviewer = new QueryFenceClient({
      apiUrl,
      getAccessToken: () => reviewerToken,
    });
    const child = spawn(process.execPath, ["dist/mcp/server.js"], {
      env: {
        ...process.env,
        QUERYFENCE_API_URL: apiUrl,
        QUERYFENCE_TOKEN_FILE: process.env.QUERYFENCE_LIVE_CLIENT_FILE,
      },
      stdio: "pipe",
    });
    const waiters = new Map<number, (value: any) => void>();
    let next = 0;
    createInterface({ input: child.stdout }).on("line", (line) => {
      const value = JSON.parse(line);
      waiters.get(value.id)?.(value);
    });
    function call(name: string, args: Record<string, unknown>): Promise<any> {
      const id = ++next;
      return new Promise((resolve, reject) => {
        const timer = setTimeout(
          () => reject(new Error("Live MCP timeout")),
          10000,
        );
        waiters.set(id, (value) => {
          clearTimeout(timer);
          resolve(value);
        });
        child.stdin.write(
          JSON.stringify({
            jsonrpc: "2.0",
            id,
            method: "tools/call",
            params: {
              _meta: {
                "io.modelcontextprotocol/protocolVersion": PROTOCOL_VERSION,
                "io.modelcontextprotocol/clientCapabilities": {},
              },
              name,
              arguments: args,
            },
          }) + "\n",
        );
      });
    }
    try {
      const planned = await call("create_query_plan", {
        question: "Revenue by category last 30 days",
      });
      expect(planned.result.isError).toBe(false);
      const p = planned.result.structuredContent;
      expect(p.status).toBe("REVIEWED");
      const token = JSON.parse(
        readFileSync(process.env.QUERYFENCE_LIVE_CLIENT_FILE!, "utf8"),
      ).token;
      const client = new QueryFenceClient({
        apiUrl,
        getAccessToken: () => token,
      });
      await expect(
        client.approve(p.planId, p.reviewDigest, true),
      ).rejects.toThrow(/INSUFFICIENT_SCOPE/);
      const inspected = await reviewer.review(p.planId);
      expect(inspected.reviewDigest).toBe(p.reviewDigest);
      // This automated local test explicitly confirms a synthetic SELECT. A real host must obtain human consent here.
      const approval = await reviewer.approve(p.planId, p.reviewDigest, true);
      const args = {
        planId: p.planId,
        approvalToken: approval.approvalToken,
        confirmed: true,
      };
      const result = await call("execute_approved_plan", args);
      expect(result.result.structuredContent.rows.length).toBeGreaterThan(0);
      expect((await call("execute_approved_plan", args)).result.isError).toBe(
        true,
      );
    } finally {
      child.stdin.end();
      child.kill();
    }
  },
  20000,
);
