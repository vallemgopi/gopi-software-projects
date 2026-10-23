export type ClientConfiguration = {
  apiUrl: string;
  getAccessToken: () => string | Promise<string>;
  role?: string;
};
export class QueryFenceClient {
  constructor(private readonly configuration: ClientConfiguration) {
    const u = new URL(configuration.apiUrl);
    if (
      u.protocol !== "https:" &&
      !(
        u.protocol === "http:" &&
        ["localhost", "127.0.0.1", "[::1]"].includes(u.hostname)
      )
    )
      throw new Error("Use HTTPS outside loopback.");
  }
  async request<T = Record<string, unknown>>(
    path: string,
    body?: unknown,
    method?: string,
    signal?: AbortSignal,
  ): Promise<T> {
    const token = await this.configuration.getAccessToken();
    if (!token) throw new Error("A runtime access token is required.");
    const response = await fetch(
      this.configuration.apiUrl.replace(/\/$/, "") + path,
      {
        method: method || (body === undefined ? "GET" : "POST"),
        headers: {
          Authorization: `Bearer ${token}`,
          "Content-Type": "application/json",
          ...(this.configuration.role
            ? { "X-QueryFence-Role": this.configuration.role }
            : {}),
        },
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: signal
          ? AbortSignal.any([signal, AbortSignal.timeout(40000)])
          : AbortSignal.timeout(40000),
      },
    );
    const data = await response.json();
    if (!response.ok)
      throw new Error(
        `${data.code || "API_ERROR"}: ${data.message || "Request failed."}`,
      );
    return data as T;
  }
  schema() {
    return this.request("/schema");
  }
  plan(question: string) {
    return this.request("/queries/plan", { question });
  }
  clarify(id: string, selections: Record<string, string>) {
    return this.request(`/queries/${encodeURIComponent(id)}/clarify`, {
      selections,
    });
  }
  edit(id: string, plan: unknown) {
    return this.request(`/queries/${encodeURIComponent(id)}`, plan, "PATCH");
  }
  review(id: string) {
    return this.request(`/queries/${encodeURIComponent(id)}`);
  }
  approve(id: string, reviewDigest: string, confirmed: true) {
    return this.request<{ approvalToken: string }>(
      `/queries/${encodeURIComponent(id)}/approve`,
      { reviewDigest, confirmed },
    );
  }
  execute(id: string, approvalToken: string, confirmed: true) {
    return this.request(`/queries/${encodeURIComponent(id)}/execute`, {
      approvalToken,
      confirmed,
    });
  }
  results(id: string, summary = false) {
    return this.request(
      `/queries/${encodeURIComponent(id)}/results?summary=${summary}`,
    );
  }
}
