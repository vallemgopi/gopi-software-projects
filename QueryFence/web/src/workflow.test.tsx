import {
  render,
  screen,
  cleanup,
  waitFor,
  within,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import App from "./App";
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});
it("switches demo role on backend requests and clears the previous approval", async () => {
  const fetch = server();
  const user = userEvent.setup();
  render(<App />);
  await user.click(screen.getByRole("button", { name: /Review query/ }));
  await user.click(await screen.findByRole("checkbox"));
  await user.selectOptions(screen.getByLabelText("Demo role"), "FINANCE");
  expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
  await waitFor(() =>
    expect(
      fetch.mock.calls.some(
        ([url, options]) =>
          url.endsWith("/schema") &&
          (options?.headers as Record<string, string>)?.["X-Demo-Role"] ===
            "FINANCE",
      ),
    ).toBe(true),
  );
});
const catalog = {
  catalogVersion: "test-catalog",
  role: "ANALYST",
  roles: ["ANALYST", "FINANCE", "OPERATIONS"],
  timeWindows: ["ALL_TIME", "LAST_30_DAYS"],
  metricDefinitions: [
    {
      key: "REVENUE",
      label: "Revenue",
      definition: "Test catalog definition from the backend.",
      unit: "amount",
    },
  ],
  permissions: {
    metrics: ["REVENUE", "ORDER_COUNT"],
    dimensions: ["CATEGORY", "REGION"],
    categories: ["HOME", "GROCERY"],
    regions: ["NORTH", "SOUTH"],
    categoryRequired: false,
    regionRequired: false,
  },
};
const review = {
  status: "REVIEWED",
  planId: "plan-1",
  reviewDigest: "digest-1",
  version: 1,
  role: "ANALYST",
  plan: {
    metric: "REVENUE",
    dimension: "CATEGORY",
    timeWindow: "LAST_30_DAYS",
    category: null,
    region: null,
    limit: 20,
    explanation: "Server interpretation",
  },
  sql: "SELECT category AS label FROM analytics.orders LIMIT ?",
  parameters: [20],
  expiresAt: "2026-12-01T12:00:00Z",
  planner: "demo",
  model: "Rules-based",
  cost: {
    totalCost: 20,
    estimatedRows: 5,
    estimatedScanRows: 1000,
    costBudget: 10000,
    scanRowBudget: 100000,
    allowed: true,
    note: "Estimate only; no runtime guarantee.",
  },
};
function server(options: { clarification?: boolean; blocked?: boolean } = {}) {
  let completed = false,
    baseline = false;
  const mock = vi.fn(async (url: string, init?: RequestInit) => {
    let body: any = {};
    if (url.endsWith("/health"))
      body = { planner: "demo", model: "Rules-based", providerStatus: "ready" };
    else if (url.endsWith("/schema")) body = catalog;
    else if (url.endsWith("/history")) body = [];
    else if (url.endsWith("/evaluations")) {
      if (init?.method === "POST") completed = true;
      const report = {
        datasetVersion: "fixture-only",
        planner: "demo",
        model: "fixture-model",
        measuredAt: "2026-01-01T00:00:00Z",
        questionCount: 50,
        metrics: { planFieldAccuracy: 0.75 },
        latencyMs: { mean: 1, p50: 1, p95: 2 },
        providerErrors: 0,
        outcomes: [],
      };
      const run = {
        id: "evaluation-1",
        status: "COMPLETED",
        dataset_version: "fixture-only",
        planner: "demo",
        model: "fixture-model",
        report,
      };
      body = { runs: completed ? [run] : [], baselines: baseline ? [run] : [] };
    } else if (url.endsWith("/baseline")) {
      baseline = true;
      body = { baseline: true };
    } else if (url.endsWith("/plan"))
      body = options.clarification
        ? {
            status: "CLARIFICATION_REQUIRED",
            clarificationId: "clarification-1",
            questions: [
              {
                field: "timeWindow",
                question: "Which time window?",
                options: ["ALL_TIME", "LAST_30_DAYS"],
              },
            ],
          }
        : options.blocked
          ? {
              ...review,
              status: "BLOCKED",
              cost: { ...review.cost, allowed: false },
            }
          : review;
    else if (url.endsWith("/clarify")) body = review;
    else if (init?.method === "PATCH")
      body = {
        ...review,
        planId: "plan-2",
        reviewDigest: "digest-2",
        version: 2,
        plan: JSON.parse(String(init.body)),
        sql: "SELECT category AS label FROM analytics.orders LIMIT ? -- revised preview",
        parameters: [3],
      };
    else if (url.endsWith("/approve"))
      body = { approvalToken: "explicit-test-approval" };
    else if (url.endsWith("/execute"))
      body = {
        planId: "plan-1",
        rows: [{ label: "HOME", value: 42 }],
        elapsedMs: 2,
      };
    else if (url.includes("/results?"))
      body = {
        planId: "plan-1",
        rows: [{ label: "HOME", value: 42 }],
        elapsedMs: 2,
        summary: {
          message: "Returned data only.",
          claims: [
            {
              row: 1,
              label: "HOME",
              value: 42,
              text: "Row 1 (HOME): Revenue = 42 amount.",
            },
          ],
          limitation: "Only one group; insufficient data for comparison.",
        },
      };
    return { ok: true, json: async () => body };
  });
  vi.stubGlobal("fetch", mock);
  return mock;
}
it("uses shared catalog definitions and resolves clarification before review", async () => {
  const fetch = server({ clarification: true });
  const user = userEvent.setup();
  render(<App />);
  expect(
    await screen.findByText("Test catalog definition from the backend."),
  ).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: /Review query/ }));
  expect(
    await screen.findByRole("heading", { name: "A little more detail" }),
  ).toBeInTheDocument();
  expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
  await user.selectOptions(
    screen.getByLabelText("Which time window?"),
    "ALL_TIME",
  );
  await user.click(
    screen.getByRole("button", { name: /Continue with selections/ }),
  );
  expect(await screen.findByText("Server interpretation")).toBeInTheDocument();
  expect(
    JSON.parse(
      String(fetch.mock.calls.find(([u]) => u.endsWith("/clarify"))?.[1]?.body),
    ),
  ).toEqual({ selections: { timeWindow: "ALL_TIME" } });
});
it("edits only supported controls, recompiles, and clears confirmation", async () => {
  const fetch = server();
  const user = userEvent.setup();
  render(<App />);
  await screen.findByText("Test catalog definition from the backend.");
  await user.click(screen.getByRole("button", { name: /Review query/ }));
  await user.click(await screen.findByRole("checkbox"));
  await user.click(screen.getByRole("button", { name: "Adjust plan" }));
  expect(screen.getByRole("checkbox")).not.toBeChecked();
  expect(screen.getByRole("button", { name: /Run confirmed/ })).toBeDisabled();
  await user.clear(screen.getByLabelText("Result limit"));
  await user.type(screen.getByLabelText("Result limit"), "3");
  await user.click(
    screen.getByRole("button", { name: "Save changes & recompile" }),
  );
  expect(await screen.findByText(/revised preview/)).toBeInTheDocument();
  expect(screen.getByRole("checkbox")).not.toBeChecked();
  expect(fetch.mock.calls.some(([, o]) => o?.method === "PATCH")).toBe(true);
});
it("shows cost estimates and prevents budget-blocked execution", async () => {
  server({ blocked: true });
  render(<App />);
  await userEvent.click(screen.getByRole("button", { name: /Review query/ }));
  expect(
    await screen.findByText("Blocked: estimate exceeds budget"),
  ).toBeInTheDocument();
  expect(
    screen.getByRole("region", { name: "Query cost estimate" }),
  ).toHaveTextContent("20 / 10000");
  expect(screen.getByRole("checkbox")).toBeDisabled();
  expect(screen.getByRole("button", { name: /Run confirmed/ })).toBeDisabled();
});
it("approves the exact digest and explains only stored returned results", async () => {
  const fetch = server();
  const user = userEvent.setup();
  render(<App />);
  await user.click(screen.getByRole("button", { name: /Review query/ }));
  await user.click(await screen.findByRole("checkbox"));
  await user.click(screen.getByRole("button", { name: /Run confirmed/ }));
  await screen.findByRole("heading", { name: "Query results" });
  await user.click(
    screen.getByRole("button", { name: "Explain returned results" }),
  );
  const summary = await screen.findByRole("article", {
    name: "Results summary",
  });
  expect(summary).toHaveTextContent("Revenue = 42");
  expect(summary).toHaveTextContent("[Row 1]");
  expect(fetch.mock.calls.filter(([u]) => u.endsWith("/execute"))).toHaveLength(
    1,
  );
  expect(
    JSON.parse(
      String(fetch.mock.calls.find(([u]) => u.endsWith("/approve"))?.[1]?.body),
    ),
  ).toEqual({ confirmed: true, reviewDigest: "digest-1" });
});
it("displays measured reports and compares only saved matching baselines", async () => {
  server();
  const user = userEvent.setup();
  render(<App />);
  expect(
    await screen.findByText("No completed measurements for this identity yet."),
  ).toBeInTheDocument();
  await user.click(screen.getByText("Evaluation lab"));
  await user.click(screen.getByRole("button", { name: "Run benchmark" }));
  expect(await screen.findByText("75.0%")).toBeInTheDocument();
  expect(screen.getByText(/fixture-model · fixture-only/)).toBeInTheDocument();
  await user.click(
    screen.getByRole("button", { name: "Save this measured run as baseline" }),
  );
  await waitFor(() =>
    expect(screen.queryByText("No matching baseline")).not.toBeInTheDocument(),
  );
  expect(screen.getByText("0.0 pp")).toBeInTheDocument();
});
