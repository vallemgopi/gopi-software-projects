// @vitest-environment jsdom
import { afterEach, expect, it, vi } from "vitest";
import { QueryFenceWidget } from "../widget/query-fence.js";
import "../widget/query-fence.js";
afterEach(() => {
  document.body.replaceChildren();
  vi.unstubAllGlobals();
});
function setup() {
  const widget = document.createElement("query-fence") as QueryFenceWidget;
  document.body.append(widget);
  widget.configure({
    apiUrl: "http://localhost:18080/api/v2",
    theme: "dark",
    role: "ANALYST",
    allowedOrigins: [location.origin],
    getAccessToken: () => "ephemeral-widget-token",
  });
  return widget;
}
const review = {
  planId: "00000000-0000-0000-0000-000000000001",
  status: "REVIEWED",
  plan: { metric: "REVENUE" },
  sql: "SELECT category AS label FROM analytics.orders LIMIT ?",
  parameters: [20],
  reviewDigest: "test-digest",
  cost: {
    allowed: true,
    totalCost: 12,
    costBudget: 100,
    note: "Estimate only",
  },
};
it("rejects an unapproved embedding origin and keeps runtime credentials out of DOM attributes", () => {
  const widget = document.createElement("query-fence") as QueryFenceWidget;
  expect(() =>
    widget.configure({
      apiUrl: "http://localhost:18080/api/v2",
      theme: "light",
      role: "ANALYST",
      allowedOrigins: ["https://other.example"],
      getAccessToken: () => "secret",
    }),
  ).toThrow(/origin/);
  setup();
  expect(document.body.outerHTML).not.toContain("ephemeral-widget-token");
});
it("renders SQL as text and requires an explicit checkbox and existing approval before execution", async () => {
  const fetch = vi.fn(async (url: string) => ({
    ok: true,
    json: async () =>
      url.endsWith("/plan")
        ? review
        : url.endsWith("/approve")
          ? { approvalToken: "widget-approved" }
          : { rows: [{ label: "HOME", value: 42 }] },
  }));
  vi.stubGlobal("fetch", fetch);
  const widget = setup();
  widget
    .shadowRoot!.querySelector("form")!
    .dispatchEvent(new Event("submit", { cancelable: true }));
  await vi.waitFor(() =>
    expect(widget.shadowRoot!.textContent).toContain("Review this plan"),
  );
  const buttons = Array.from(widget.shadowRoot!.querySelectorAll("button"));
  const run = buttons.find((b) => b.textContent === "Run approved plan")!;
  expect(run.disabled).toBe(true);
  const check = widget.shadowRoot!.querySelector<HTMLInputElement>(
    "input[type=checkbox]",
  )!;
  check.checked = true;
  check.dispatchEvent(new Event("change"));
  run.click();
  await vi.waitFor(() =>
    expect(widget.shadowRoot!.textContent).toContain("Returned results"),
  );
  const urls = fetch.mock.calls.map((c) => c[0]);
  expect(urls.findIndex((u) => u.endsWith("/approve"))).toBeLessThan(
    urls.findIndex((u) => u.endsWith("/execute")),
  );
  expect(widget.outerHTML).not.toContain("ephemeral-widget-token");
});
it("does not offer execution for a budget-blocked plan", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => ({
      ok: true,
      json: async () => ({
        ...review,
        status: "BLOCKED",
        cost: { ...review.cost, allowed: false },
      }),
    })),
  );
  const widget = setup();
  widget
    .shadowRoot!.querySelector("form")!
    .dispatchEvent(new Event("submit", { cancelable: true }));
  await vi.waitFor(() =>
    expect(widget.shadowRoot!.textContent).toContain("Blocked by"),
  );
  expect(widget.shadowRoot!.querySelector("input[type=checkbox]")).toBeNull();
});
