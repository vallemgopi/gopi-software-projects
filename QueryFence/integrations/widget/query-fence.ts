import { QueryFenceClient } from "../client/queryfence.js";
export type WidgetConfiguration = {
  apiUrl: string;
  theme: "light" | "dark";
  role: string;
  allowedOrigins: string[];
  getAccessToken: () => string | Promise<string>;
};
type Review = {
  planId: string;
  plan: Record<string, unknown>;
  sql: string;
  parameters: unknown[];
  reviewDigest: string;
  status: string;
  cost: {
    allowed: boolean;
    totalCost: number;
    costBudget: number;
    note: string;
  };
};
type Clarification = {
  clarificationId: string;
  questions: { field: string; question: string; options: string[] }[];
};
function element<K extends keyof HTMLElementTagNameMap>(
  name: K,
  text?: string,
) {
  const el = document.createElement(name);
  if (text) el.textContent = text;
  return el;
}
export class QueryFenceWidget extends HTMLElement {
  private root = this.attachShadow({ mode: "open" });
  private client?: QueryFenceClient;
  private content = element("section");
  private message = element("p");
  private generation = 0;
  private working = false;
  private definitions: { key: string; definition: string }[] = [];
  configure(config: WidgetConfiguration) {
    if (!config.allowedOrigins.includes(location.origin))
      throw new Error(
        "This host origin is not allowed by the widget configuration.",
      );
    if (!["light", "dark"].includes(config.theme))
      throw new Error("Unsupported theme.");
    this.setAttribute("data-theme", config.theme);
    this.generation++;
    this.client = new QueryFenceClient({
      apiUrl: config.apiUrl,
      getAccessToken: config.getAccessToken,
      role: config.role,
    });
    this.render();
    const generation = this.generation;
    void this.client
      .schema()
      .then((data) => {
        if (
          generation === this.generation &&
          Array.isArray(data.metricDefinitions)
        )
          this.definitions = data.metricDefinitions;
      })
      .catch(() => {
        if (generation === this.generation)
          this.message.textContent =
            "Catalog unavailable. Check the runtime credential and schema:read scope.";
      });
  }
  connectedCallback() {
    if (!this.client) {
      this.message.textContent =
        "Waiting for the host to provide a runtime access token.";
      this.root.append(this.message);
    }
  }
  disconnectedCallback() {
    this.generation++;
  }
  private render() {
    this.root.replaceChildren();
    const css = element("link");
    css.rel = "stylesheet";
    css.href = new URL("./widget.css", import.meta.url).href;
    this.root.append(
      css,
      element("h2", "QueryFence"),
      element("p", "Synthetic data — local demo"),
    );
    this.message = element("p");
    this.message.setAttribute("role", "status");
    const form = element("form"),
      label = element("label", "Ask about synthetic retail"),
      question = element("textarea");
    question.maxLength = 500;
    question.required = true;
    question.value = "Revenue by category last 30 days";
    label.append(question);
    const submit = element("button", "Review query");
    submit.type = "submit";
    form.append(label, submit);
    form.onsubmit = (e) => {
      e.preventDefault();
      if (this.working) return;
      this.content.replaceChildren();
      void this.perform(async () => {
        const data = await this.client!.plan(question.value);
        this.show(data as unknown as Review | Clarification);
      });
    };
    this.content = element("section");
    this.root.append(form, this.message, this.content);
  }
  private async perform(action: () => Promise<void>) {
    if (this.working) return;
    this.working = true;
    this.message.textContent = "Working…";
    const generation = this.generation;
    this.root
      .querySelectorAll<HTMLButtonElement>("button")
      .forEach((b) => (b.disabled = true));
    try {
      await action();
      if (generation === this.generation) this.message.textContent = "";
    } catch (e) {
      if (generation === this.generation)
        this.message.textContent =
          e instanceof Error ? e.message : "Request failed.";
    } finally {
      this.working = false;
      this.root.querySelectorAll<HTMLButtonElement>("button").forEach((b) => {
        if (b.dataset.requiresApproval !== "true") b.disabled = false;
      });
    }
  }
  private show(data: Review | Clarification) {
    this.content.replaceChildren();
    if ("clarificationId" in data) {
      const selections: Record<string, string> = {};
      this.content.append(element("h3", "Clarification required"));
      for (const q of data.questions) {
        const label = element("label", q.question),
          select = element("select");
        select.append(element("option", "Select an option"));
        select.options[0].value = "";
        for (const value of q.options) {
          const option = element("option", value);
          option.value = value;
          select.append(option);
        }
        select.onchange = () => {
          selections[q.field] = select.value;
        };
        label.append(select);
        this.content.append(label);
      }
      const next = element("button", "Continue with selections");
      next.onclick = () => {
        if (!data.questions.every((q) => selections[q.field])) {
          this.message.textContent = "Select every requested option.";
          return;
        }
        void this.perform(async () => {
          this.show(
            (await this.client!.clarify(
              data.clarificationId,
              selections,
            )) as unknown as Review,
          );
        });
      };
      this.content.append(next);
      return;
    }
    this.content.append(
      element("h3", "Review this plan"),
      element("pre", JSON.stringify(data.plan, null, 2)),
      element(
        "p",
        this.definitions.find((m) => m.key === data.plan.metric)?.definition ||
          "Metric definitions are available through the authenticated schema catalog.",
      ),
      element("pre", data.sql),
      element("p", "Bound parameters: " + JSON.stringify(data.parameters)),
      element(
        "p",
        `Estimated cost ${data.cost.totalCost} / ${data.cost.costBudget}. ${data.cost.note}`,
      ),
    );
    if (!data.cost.allowed || data.status === "BLOCKED") {
      this.content.append(
        element("p", "Blocked by the configured estimate budget."),
      );
      return;
    }
    const label = element("label"),
      check = element("input");
    check.type = "checkbox";
    label.append(
      check,
      document.createTextNode(
        " I reviewed this exact plan and explicitly approve execution.",
      ),
    );
    const run = element("button", "Run approved plan");
    run.disabled = true;
    run.dataset.requiresApproval = "true";
    check.onchange = () => {
      run.disabled = !check.checked;
    };
    run.onclick = () => {
      if (!check.checked || this.working) return;
      check.disabled = true;
      run.disabled = true;
      void this.perform(async () => {
        const approved = await this.client!.approve(
          data.planId,
          data.reviewDigest,
          true,
        );
        const result = await this.client!.execute(
          data.planId,
          approved.approvalToken,
          true,
        );
        this.content.append(
          element("h3", "Returned results"),
          element("pre", JSON.stringify(result, null, 2)),
        );
      });
    };
    this.content.append(label, run);
  }
}
if (!customElements.get("query-fence"))
  customElements.define("query-fence", QueryFenceWidget);
