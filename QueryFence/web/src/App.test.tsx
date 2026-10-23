import { render, screen, waitFor, cleanup } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import App from "./App";
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});
it("keeps developer documentation separate from the main analytics navigation", async () => {
  mockApi();
  render(<App />);
  expect(screen.getByRole("link", { name: "How it works" })).toHaveAttribute(
    "href",
    "#how-it-works",
  );
  expect(
    screen.queryByRole("link", { name: /API explorer/ }),
  ).not.toBeInTheDocument();
  await userEvent.click(screen.getByText("Developer resources"));
  expect(
    screen.getByRole("link", { name: /Open API documentation/ }),
  ).toHaveAttribute("href", "/swagger-ui/index.html");
  expect(
    screen.getByText(/Nginx forwards it to the Spring Boot API/),
  ).toBeVisible();
});
it("scales result bars to the largest result instead of rendering every bar full width", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) => ({
      ok: true,
      json: async () =>
        url.endsWith("/health")
          ? { planner: "demo", model: "Rules-based" }
          : url.endsWith("/history")
            ? []
            : url.endsWith("/plan")
              ? review
              : {
                  rows: [
                    { label: "LARGE", value: 100 },
                    { label: "SMALL", value: 25 },
                    { label: "ZERO", value: 0 },
                  ],
                  elapsedMs: 1,
                },
    })),
  );
  const user = userEvent.setup();
  render(<App />);
  await user.click(screen.getByRole("button", { name: /Review query/ }));
  await user.click(await screen.findByRole("checkbox"));
  await user.click(screen.getByRole("button", { name: /Run confirmed/ }));
  const small = await screen.findByRole("row", { name: "SMALL 25" });
  expect(small.querySelector(".bar")).toHaveStyle({ width: "25%" });
  expect(
    screen.getByRole("row", { name: "LARGE 100" }).querySelector(".bar"),
  ).toHaveStyle({ width: "100%" });
  expect(
    screen.getByRole("row", { name: "ZERO 0" }).querySelector(".bar"),
  ).toHaveStyle({ width: "0%" });
});
it("offers keyboard navigation and describes the question length", async () => {
  mockApi();
  const user = userEvent.setup();
  render(<App />);
  await user.tab();
  expect(
    screen.getByRole("link", { name: "Skip to query studio" }),
  ).toHaveFocus();
  expect(
    screen.getByRole("navigation", { name: "Query workflow" }),
  ).toHaveTextContent("Confirm & explore");
  expect(screen.getByRole("textbox")).toHaveAccessibleDescription(
    /500 characters/,
  );
});
it("clears approval when the question changes and never executes the stale plan", async () => {
  const fetch = mockApi();
  const user = userEvent.setup();
  render(<App />);
  await user.click(screen.getByRole("button", { name: /Review query/ }));
  await user.click(await screen.findByRole("checkbox"));
  expect(screen.getByRole("button", { name: /Run confirmed/ })).toBeEnabled();
  await user.click(
    screen.getByRole("button", { name: "Return rate by month all time" }),
  );
  expect(screen.getByRole("textbox")).toHaveValue(
    "Return rate by month all time",
  );
  expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
  expect(screen.queryByText(review.sql)).not.toBeInTheDocument();
  expect(fetch.mock.calls.some(([url]) => url.endsWith("/execute"))).toBe(
    false,
  );
});
const review = {
  planId: "abc",
  plan: {
    metric: "REVENUE",
    dimension: "CATEGORY",
    timeWindow: "LAST_30_DAYS",
    category: null,
    region: null,
    limit: 20,
    explanation: "Test interpretation",
  },
  sql: "SELECT category AS label FROM analytics.orders LIMIT ?",
  parameters: [20],
  expiresAt: "2026-01-31T12:00:00Z",
  planner: "demo",
  model: "Rules-based",
};
function mockApi(planResponse: unknown = review, fail = false) {
  const fn = vi.fn(async (url: string, options?: RequestInit) => ({
    ok: !fail || !url.endsWith("/plan"),
    json: async () =>
      url.endsWith("/health")
        ? { planner: "demo", model: "Rules-based", providerStatus: "ready" }
        : url.endsWith("/history")
          ? []
          : url.endsWith("/plan")
            ? planResponse
            : { rows: [{ label: "HOME", value: 1234 }], elapsedMs: 8 },
  }));
  vi.stubGlobal("fetch", fn);
  return fn;
}
it("renders the API response and requires explicit confirmation before execution", async () => {
  const fetch = mockApi();
  const user = userEvent.setup();
  render(<App />);
  await user.click(screen.getByRole("button", { name: /Review query/ }));
  expect(await screen.findByText("Test interpretation")).toBeInTheDocument();
  expect(screen.getByText(review.sql)).toBeInTheDocument();
  const run = screen.getByRole("button", { name: /Run confirmed/ });
  expect(run).toBeDisabled();
  expect(fetch.mock.calls.some(([url]) => url.endsWith("/execute"))).toBe(
    false,
  );
  await user.click(screen.getByRole("checkbox"));
  await user.click(run);
  expect(await screen.findByText("HOME")).toBeInTheDocument();
  expect(screen.getByText("1,234")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Plan consumed" })).toBeDisabled();
  expect(
    fetch.mock.calls.find(([url]) => url.endsWith("/execute"))?.[1]?.body,
  ).toBe('{"confirmed":true}');
});
it("shows planning failure without an execution action", async () => {
  mockApi({ message: "Unsupported question domain." }, true);
  render(<App />);
  await userEvent.click(screen.getByRole("button", { name: /Review query/ }));
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "Unsupported question domain.",
  );
  expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
});
it("shows loading while planning and prevents repeat submission", async () => {
  let resolve: (v: unknown) => void = () => {};
  const pending = new Promise((r) => {
    resolve = r;
  });
  vi.stubGlobal(
    "fetch",
    vi.fn((url: string) =>
      url.endsWith("/plan")
        ? pending
        : Promise.resolve({
            ok: true,
            json: async () =>
              url.endsWith("/history")
                ? []
                : { planner: "demo", model: "Rules-based" },
          }),
    ),
  );
  render(<App />);
  await userEvent.click(screen.getByRole("button", { name: /Review query/ }));
  expect(screen.getByRole("status")).toHaveTextContent("Planning");
  expect(screen.getByRole("button", { name: "Planning…" })).toBeDisabled();
  resolve({ ok: true, json: async () => review });
  await waitFor(() => expect(screen.getByRole("checkbox")).toBeInTheDocument());
});
it.each([false, true])(
  "handles empty results and execution failures (failure=%s)",
  async (failure) => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string) => ({
        ok: !(failure && url.endsWith("/execute")),
        json: async () =>
          url.endsWith("/health")
            ? { planner: "demo", model: "Rules-based" }
            : url.endsWith("/history")
              ? []
              : url.endsWith("/plan")
                ? review
                : failure
                  ? { message: "Analytics database unavailable." }
                  : { rows: [], elapsedMs: 1 },
      })),
    );
    const user = userEvent.setup();
    render(<App />);
    await user.click(screen.getByRole("button", { name: /Review query/ }));
    await screen.findByRole("checkbox");
    await user.click(screen.getByRole("checkbox"));
    await user.click(screen.getByRole("button", { name: /Run confirmed/ }));
    if (failure)
      expect(await screen.findByRole("alert")).toHaveTextContent(
        "Analytics database unavailable.",
      );
    else
      expect(
        await screen.findByText(/No rows match these filters/),
      ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Plan consumed" }),
    ).toBeDisabled();
  },
);

it("starts with a focused question and opens secondary information on demand", async () => {
  mockApi();
  render(<App />);
  expect(
    screen.queryByRole("heading", { name: /Review & confirm/ }),
  ).not.toBeInTheDocument();
  const about = document.getElementById("about") as HTMLDetailsElement;
  const activity = document.getElementById("activity") as HTMLDetailsElement;
  expect(about.open).toBe(false);
  expect(activity.open).toBe(false);
  await userEvent.click(screen.getByRole("link", { name: "How it works" }));
  expect(about.open).toBe(true);
  await userEvent.click(screen.getByRole("link", { name: "Recent activity" }));
  expect(activity.open).toBe(true);
});
it("lets a user inspect SQL without losing the explicit confirmation requirement", async () => {
  mockApi();
  render(<App />);
  await userEvent.click(screen.getByRole("button", { name: /Review query/ }));
  const preview = await screen.findByText(review.sql);
  expect(preview).not.toBeVisible();
  await userEvent.click(screen.getByText("SQL & query details"));
  expect(preview).toBeVisible();
  expect(screen.getByRole("button", { name: /Run confirmed/ })).toBeDisabled();
  expect(screen.getByRole("checkbox")).not.toBeChecked();
});
