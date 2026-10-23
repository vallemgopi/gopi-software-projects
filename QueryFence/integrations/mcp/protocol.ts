import { QueryFenceClient } from "../client/queryfence.js";
export const PROTOCOL_VERSION = "2026-07-28";
type Message = {
  jsonrpc?: string;
  id?: string | number;
  method?: string;
  params?: Record<string, unknown>;
};
type Tool = {
  name: string;
  description: string;
  inputSchema: {
    type: string;
    properties: Record<string, unknown>;
    required?: string[];
    additionalProperties: boolean;
  };
  annotations: Record<string, boolean>;
};
const idSchema = {
  type: "string",
  format: "uuid",
  pattern: "^[0-9a-fA-F-]{36}$",
};
function tool(
  name: string,
  description: string,
  properties: Record<string, unknown>,
  required: string[],
  readOnly = false,
): Tool {
  return {
    name,
    description,
    inputSchema: {
      type: "object",
      properties,
      required,
      additionalProperties: false,
    },
    annotations: {
      readOnlyHint: readOnly,
      destructiveHint: false,
      idempotentHint: readOnly,
      openWorldHint: false,
    },
  };
}
export const tools = [
  tool(
    "get_schema",
    "Read approved synthetic retail semantics and role permissions.",
    {},
    [],
    true,
  ),
  tool(
    "create_query_plan",
    "Propose a plan only. A clarification response requires user selections. Never returns executable model SQL.",
    { question: { type: "string", minLength: 1, maxLength: 500 } },
    ["question"],
  ),
  tool(
    "clarify_query_plan",
    "Submit user-selected options from an existing clarification request.",
    {
      planId: idSchema,
      selections: { type: "object", additionalProperties: { type: "string" } },
    },
    ["planId", "selections"],
  ),
  tool(
    "get_query_plan",
    "Retrieve an owned plan for human review. This does not approve it.",
    { planId: idSchema },
    ["planId"],
    true,
  ),
  tool(
    "execute_approved_plan",
    "Execute only an existing unexpired plan approved through a separate trusted human review channel. Cannot create approvals. Never infer or manufacture approval tokens.",
    {
      planId: idSchema,
      approvalToken: { type: "string", minLength: 1, maxLength: 100 },
      confirmed: { type: "boolean", const: true },
    },
    ["planId", "approvalToken", "confirmed"],
  ),
  tool(
    "get_query_results",
    "Read stored results and an optional results-only summary. Does not run a query.",
    { planId: idSchema, summary: { type: "boolean" } },
    ["planId"],
    true,
  ),
].sort((a, b) => a.name.localeCompare(b.name));
class RpcError extends Error {
  constructor(
    public code: number,
    message: string,
    public data?: unknown,
  ) {
    super(message);
  }
}
export class McpProtocol {
  private legacy = false;
  private pending = new Map<string | number, AbortController>();
  constructor(private readonly client: QueryFenceClient) {}
  async handle(input: unknown): Promise<unknown | null> {
    const m = input as Message;
    let id: string | number | null = null;
    let controller: AbortController | undefined;
    try {
      if (
        !m ||
        Array.isArray(m) ||
        m.jsonrpc !== "2.0" ||
        typeof m.method !== "string"
      )
        throw new RpcError(-32600, "Invalid JSON-RPC request");
      if (m.id === undefined) {
        if (m.method === "notifications/cancelled") {
          const requestId = m.params?.requestId;
          if (typeof requestId === "string" || typeof requestId === "number")
            this.pending.get(requestId)?.abort();
        }
        return null;
      }
      if (
        typeof m.id !== "string" &&
        !(typeof m.id === "number" && Number.isSafeInteger(m.id))
      )
        throw new RpcError(-32600, "Invalid request id");
      id = m.id;
      if (this.pending.has(id))
        throw new RpcError(-32600, "Request id already in use");
      const params = m.params || {};
      if (Array.isArray(params) || typeof params !== "object")
        throw new RpcError(-32602, "Request params must be an object");
      const meta = params._meta as Record<string, unknown> | undefined;
      const modern = !!meta?.["io.modelcontextprotocol/protocolVersion"];
      if (m.method === "initialize") {
        if (params.protocolVersion !== "2025-11-25")
          throw new RpcError(-32022, "Unsupported protocol version", {
            supported: [PROTOCOL_VERSION, "2025-11-25"],
            requested: params.protocolVersion,
          });
        this.legacy = true;
        return this.result(
          id,
          {
            protocolVersion: "2025-11-25",
            capabilities: { tools: {} },
            serverInfo: { name: "queryfence", version: "2.0.0" },
            instructions:
              "Use scoped credentials. Approval is out-of-band; no approval tool exists.",
          },
          false,
        );
      }
      if (modern) {
        const version = meta!["io.modelcontextprotocol/protocolVersion"];
        if (version !== PROTOCOL_VERSION)
          throw new RpcError(-32022, "Unsupported protocol version", {
            supported: [PROTOCOL_VERSION, "2025-11-25"],
            requested: version,
          });
        if (
          !meta!["io.modelcontextprotocol/clientCapabilities"] ||
          typeof meta!["io.modelcontextprotocol/clientCapabilities"] !==
            "object"
        )
          throw new RpcError(
            -32602,
            "Client capabilities metadata is required",
          );
      } else if (!this.legacy)
        throw new RpcError(
          -32602,
          "Per-request protocol version and client capabilities metadata are required",
        );
      controller = new AbortController();
      this.pending.set(id, controller);
      if (m.method === "server/discover")
        return this.result(
          id,
          {
            supportedVersions: [PROTOCOL_VERSION, "2025-11-25"],
            capabilities: { tools: {} },
            serverInfo: { name: "queryfence", version: "2.0.0" },
            instructions:
              "No approval tool. A trusted human review channel must approve the exact plan digest.",
            ttlMs: 0,
            cacheScope: "private",
          },
          modern,
        );
      if (m.method === "ping") return this.result(id, {}, modern);
      if (m.method === "tools/list") {
        if (params.cursor !== undefined)
          throw new RpcError(-32602, "No pagination cursor is supported");
        return this.result(
          id,
          { tools, ttlMs: 0, cacheScope: "private" },
          modern,
        );
      }
      if (m.method !== "tools/call")
        throw new RpcError(-32601, "Method not found");
      const selected = tools.find((t) => t.name === params.name);
      if (!selected) throw new RpcError(-32602, "Unknown tool");
      const args = (params.arguments || {}) as Record<string, unknown>;
      this.validate(selected, args);
      try {
        const data = await this.call(selected.name, args, controller.signal);
        if (controller.signal.aborted) return null;
        return this.result(
          id,
          {
            content: [{ type: "text", text: JSON.stringify(data) }],
            structuredContent: data,
            isError: false,
          },
          modern,
        );
      } catch (e) {
        if (controller.signal.aborted) return null;
        return this.result(
          id,
          {
            content: [
              {
                type: "text",
                text:
                  e instanceof Error && e.message.startsWith("API_")
                    ? e.message
                    : "QueryFence request was rejected or unavailable. Check credential scopes, role, plan state and approval.",
              },
            ],
            isError: true,
          },
          modern,
        );
      }
    } catch (e) {
      return {
        jsonrpc: "2.0",
        id,
        error: {
          code: e instanceof RpcError ? e.code : -32603,
          message: e instanceof RpcError ? e.message : "Internal adapter error",
          ...(e instanceof RpcError && e.data ? { data: e.data } : {}),
        },
      };
    } finally {
      if (id !== null && controller) this.pending.delete(id);
    }
  }
  private result(
    id: string | number,
    value: Record<string, unknown>,
    modern: boolean,
  ) {
    return {
      jsonrpc: "2.0",
      id,
      result: { ...(modern ? { resultType: "complete" } : {}), ...value },
    };
  }
  private validate(tool: Tool, args: Record<string, unknown>) {
    if (
      !args ||
      Array.isArray(args) ||
      typeof args !== "object" ||
      Object.keys(args).some(
        (k) => !Object.hasOwn(tool.inputSchema.properties, k),
      ) ||
      tool.inputSchema.required?.some((k) => !Object.hasOwn(args, k))
    )
      throw new RpcError(-32602, "Invalid tool arguments");
    if (
      "planId" in args &&
      (typeof args.planId !== "string" ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
          args.planId,
        ))
    )
      throw new RpcError(-32602, "Invalid plan id");
    if (
      "question" in args &&
      (typeof args.question !== "string" ||
        !args.question.trim() ||
        args.question.length > 500)
    )
      throw new RpcError(-32602, "Invalid question length");
    if (
      "approvalToken" in args &&
      (typeof args.approvalToken !== "string" ||
        !args.approvalToken ||
        args.approvalToken.length > 100 ||
        args.confirmed !== true)
    )
      throw new RpcError(
        -32602,
        "Explicit approval token and confirmation are required",
      );
    if ("summary" in args && typeof args.summary !== "boolean")
      throw new RpcError(-32602, "Summary must be boolean");
    if (
      "selections" in args &&
      (!args.selections ||
        typeof args.selections !== "object" ||
        Array.isArray(args.selections) ||
        Object.keys(args.selections).length > 5 ||
        !Object.values(args.selections).every(
          (v) => typeof v === "string" && v.length <= 40,
        ))
    )
      throw new RpcError(-32602, "Invalid selections");
  }
  private call(
    name: string,
    args: Record<string, unknown>,
    signal: AbortSignal,
  ) {
    const id = encodeURIComponent(String(args.planId));
    switch (name) {
      case "get_schema":
        return this.client.request("/schema", undefined, undefined, signal);
      case "create_query_plan":
        return this.client.request(
          "/queries/plan",
          { question: args.question },
          undefined,
          signal,
        );
      case "clarify_query_plan":
        return this.client.request(
          `/queries/${id}/clarify`,
          { selections: args.selections },
          undefined,
          signal,
        );
      case "get_query_plan":
        return this.client.request(
          `/queries/${id}`,
          undefined,
          undefined,
          signal,
        );
      case "execute_approved_plan":
        return this.client.request(
          `/queries/${id}/execute`,
          { approvalToken: args.approvalToken, confirmed: true },
          undefined,
          signal,
        );
      case "get_query_results":
        return this.client.request(
          `/queries/${id}/results?summary=${args.summary === true}`,
          undefined,
          undefined,
          signal,
        );
      default:
        throw new RpcError(-32602, "Unknown tool");
    }
  }
}
