import { useEffect, useState } from "react";
export type Plan = {
  metric: string;
  dimension: string;
  timeWindow: string;
  category: string | null;
  region: string | null;
  limit: number;
  explanation: string;
};
export type Catalog = {
  catalogVersion: string;
  role: string;
  roles: string[];
  timeWindows: string[];
  metricDefinitions: {
    key: string;
    label: string;
    definition: string;
    unit: string;
  }[];
  permissions: {
    metrics: string[];
    dimensions: string[];
    categories: string[];
    regions: string[];
    categoryRequired: boolean;
    regionRequired: boolean;
  };
};
export type Cost = {
  totalCost: number;
  estimatedRows: number;
  estimatedScanRows: number;
  costBudget: number;
  scanRowBudget: number;
  allowed: boolean;
  note: string;
};
export type Clarification = {
  status: "CLARIFICATION_REQUIRED";
  clarificationId: string;
  questions: { field: string; question: string; options: string[] }[];
  expiresAt: string;
};
export type Api = <T>(
  path: string,
  body?: unknown,
  method?: string,
) => Promise<T>;
export function ClarificationPanel({
  value,
  busy,
  onContinue,
}: {
  value: Clarification;
  busy: boolean;
  onContinue: (selections: Record<string, string>) => void;
}) {
  const [selected, setSelected] = useState<Record<string, string>>({});
  return (
    <section className="card clarification">
      <h2>A little more detail</h2>
      <p>
        Your question has more than one possible interpretation. Choose before a
        plan is compiled.
      </p>
      <div className="edit-grid">
        {value.questions.map((q) => (
          <label key={q.field}>
            {q.question}
            <select
              value={selected[q.field] || ""}
              onChange={(e) =>
                setSelected({ ...selected, [q.field]: e.target.value })
              }
              disabled={busy}
            >
              <option value="">Select an option</option>
              {q.options.map((option) => (
                <option key={option} value={option}>
                  {option.replaceAll("_", " ")}
                </option>
              ))}
            </select>
          </label>
        ))}
      </div>
      <button
        disabled={busy || !value.questions.every((q) => selected[q.field])}
        onClick={() => onContinue(selected)}
      >
        Continue with selections →
      </button>
    </section>
  );
}
export function PlanEditor({
  plan,
  catalog,
  busy,
  onSave,
  onCancel,
}: {
  plan: Plan;
  catalog: Catalog;
  busy: boolean;
  onSave: (plan: Plan) => void;
  onCancel: () => void;
}) {
  const [draft, setDraft] = useState(plan);
  const fields: [keyof Plan, string, string[], boolean][] = [
    ["metric", "Metric", catalog.permissions.metrics, false],
    ["dimension", "Grouping", catalog.permissions.dimensions, false],
    ["timeWindow", "Date window", catalog.timeWindows, false],
    [
      "category",
      "Category filter",
      catalog.permissions.categories,
      !catalog.permissions.categoryRequired,
    ],
    [
      "region",
      "Region filter",
      catalog.permissions.regions,
      !catalog.permissions.regionRequired,
    ],
  ];
  return (
    <form
      className="plan-editor"
      onSubmit={(e) => {
        e.preventDefault();
        onSave(draft);
      }}
    >
      <h3>Adjust the supported fields</h3>
      <p>
        Saving creates a new plan and SQL preview. Any previous approval is
        invalidated.
      </p>
      <div className="edit-grid">
        {fields.map(([field, label, options, all]) => (
          <label key={field}>
            {label}
            <select
              disabled={busy}
              value={String(draft[field] || "")}
              required={!all}
              onChange={(e) =>
                setDraft({ ...draft, [field]: e.target.value || null })
              }
            >
              {all && <option value="">All</option>}
              {options.map((v) => (
                <option key={v} value={v}>
                  {v.replaceAll("_", " ")}
                </option>
              ))}
            </select>
          </label>
        ))}
        <label>
          Result limit
          <input
            type="number"
            min={1}
            max={50}
            required
            value={draft.limit}
            disabled={busy}
            onChange={(e) =>
              setDraft({ ...draft, limit: Number(e.target.value) })
            }
          />
        </label>
      </div>
      <div className="button-row">
        <button disabled={busy} type="submit">
          Save changes & recompile
        </button>
        <button
          disabled={busy}
          type="button"
          className="example"
          onClick={onCancel}
        >
          Cancel editing
        </button>
      </div>
    </form>
  );
}
export function CostPanel({ cost }: { cost: Cost }) {
  return (
    <section
      className={cost.allowed ? "cost-panel" : "cost-panel blocked"}
      aria-label="Query cost estimate"
    >
      <strong>
        {cost.allowed
          ? "Within configured estimate budget"
          : "Blocked: estimate exceeds budget"}
      </strong>
      <dl>
        <div>
          <dt>Planner cost units</dt>
          <dd>
            {cost.totalCost} / {cost.costBudget}
          </dd>
        </div>
        <div>
          <dt>Estimated scan rows</dt>
          <dd>
            {cost.estimatedScanRows} / {cost.scanRowBudget}
          </dd>
        </div>
        <div>
          <dt>Estimated output rows</dt>
          <dd>{cost.estimatedRows}</dd>
        </div>
      </dl>
      <small>{cost.note}</small>
    </section>
  );
}
type Report = {
  datasetVersion: string;
  planner: string;
  model: string;
  modelVersion?: string;
  measuredAt: string;
  questionCount: number;
  metrics: Record<string, number>;
  latencyMs: { mean: number; p50: number; p95: number };
  providerErrors: number;
  outcomes: { id: string; expected: string; actual: string; passed: boolean }[];
};
type Run = {
  id: string;
  status: string;
  dataset_version: string;
  planner: string;
  model: string;
  model_version?: string;
  report?: Report;
  baseline?: boolean;
};
export function EvaluationPanel({
  api,
  identity,
}: {
  api: Api;
  identity: string;
}) {
  const [runs, setRuns] = useState<Run[]>([]),
    [baselines, setBaselines] = useState<Run[]>([]),
    [error, setError] = useState(""),
    [working, setWorking] = useState(false);
  const running = runs.some((r) => r.status === "RUNNING");
  async function load() {
    const data = await api<{ runs: Run[]; baselines: Run[] }>("/evaluations");
    setRuns(Array.isArray(data.runs) ? data.runs : []);
    setBaselines(Array.isArray(data.baselines) ? data.baselines : []);
  }
  useEffect(() => {
    let active = true;
    api<{ runs: Run[]; baselines: Run[] }>("/evaluations")
      .then((data) => {
        if (active) {
          setRuns(Array.isArray(data.runs) ? data.runs : []);
          setBaselines(Array.isArray(data.baselines) ? data.baselines : []);
        }
      })
      .catch(() => {
        if (active) setError("Evaluation history is unavailable.");
      });
    return () => {
      active = false;
    };
  }, [identity]);
  useEffect(() => {
    if (!running) return;
    const timer = setInterval(
      () =>
        void load().catch(() =>
          setError("Could not refresh evaluation status."),
        ),
      2000,
    );
    return () => clearInterval(timer);
  }, [running, identity]);
  async function run() {
    setWorking(true);
    setError("");
    try {
      await api("/evaluations", {});
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Evaluation failed.");
    } finally {
      setWorking(false);
    }
  }
  async function save(id: string) {
    setWorking(true);
    setError("");
    try {
      await api(`/evaluations/${id}/baseline`, {});
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Baseline save failed.");
    } finally {
      setWorking(false);
    }
  }
  const latest = runs.find((r) => r.status === "COMPLETED" && r.report);
  const report = latest?.report;
  const baseline = report
    ? baselines.find(
        (b) =>
          b.dataset_version === report.datasetVersion &&
          b.planner === report.planner &&
          b.model === report.model &&
          b.model_version === report.modelVersion,
      )
    : undefined;
  return (
    <section id="evaluation" className="card evaluation">
      <div className="card-title">
        <h2>Evaluation & regression</h2>
        <button disabled={working || running} onClick={() => void run()}>
          {running ? "Benchmark running…" : "Run benchmark"}
        </button>
      </div>
      <p>
        Run the versioned synthetic fixture against the configured planner.
        Scores appear only after measurement.
      </p>
      {error && <p role="alert">{error}</p>}
      {running && (
        <p role="status">
          Evaluation running in the background. Local model calls can take
          several minutes.
        </p>
      )}
      {report ? (
        <>
          <p className="report-label">
            {report.planner} · {report.model} · {report.datasetVersion}
            <br />
            Model version: {report.modelVersion || "unverified"}
            <br />
            {report.questionCount} labeled questions ·{" "}
            {new Date(report.measuredAt).toLocaleString()}
          </p>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Measured metric</th>
                  <th>Current</th>
                  <th>Saved baseline</th>
                  <th>Difference</th>
                </tr>
              </thead>
              <tbody>
                {Object.entries(report.metrics).map(([metric, value]) => {
                  const old = baseline?.report?.metrics[metric];
                  return (
                    <tr key={metric}>
                      <td>{metric.replace(/([A-Z])/g, " $1")}</td>
                      <td>{(value * 100).toFixed(1)}%</td>
                      <td>
                        {old === undefined ? "—" : (old * 100).toFixed(1) + "%"}
                      </td>
                      <td>
                        {old === undefined
                          ? "No matching baseline"
                          : ((value - old) * 100).toFixed(1) + " pp"}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          <p>
            Latency: mean {report.latencyMs.mean.toFixed(2)} ms · p50{" "}
            {report.latencyMs.p50.toFixed(2)} ms · p95{" "}
            {report.latencyMs.p95.toFixed(2)} ms. Provider errors:{" "}
            {report.providerErrors}.
          </p>
          <button
            className="example"
            disabled={working}
            onClick={() => void save(latest!.id)}
          >
            Save this measured run as baseline
          </button>
          {!baseline && (
            <p>No saved baseline matches this model and dataset version yet.</p>
          )}
          <details>
            <summary>Question outcomes</summary>
            <ul>
              {report.outcomes.map((o) => (
                <li key={o.id}>
                  {o.id}: expected {o.expected}, observed {o.actual} —{" "}
                  {o.passed ? "matched" : "mismatch"}
                </li>
              ))}
            </ul>
          </details>
        </>
      ) : (
        !running && <p>No completed measurements for this identity yet.</p>
      )}
      <small>
        Synthetic, grammar-aligned evaluation. These scores do not establish
        real-world accuracy, runtime guarantees or security certification.
        Comparisons require matching model and dataset versions.
      </small>
    </section>
  );
}
