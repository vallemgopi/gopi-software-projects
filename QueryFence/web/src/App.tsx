import { useEffect, useState, useRef } from "react";
import {
  ClarificationPanel,
  PlanEditor,
  CostPanel,
  EvaluationPanel,
  type Plan,
  type Catalog,
  type Cost,
  type Clarification,
} from "./workflow";
type Review = {
  planId: string;
  plan: Plan;
  sql: string;
  parameters: unknown[];
  expiresAt: string;
  planner: string;
  model: string;
  status?: string;
  reviewDigest: string;
  role?: string;
  version?: number;
  cost?: Cost;
};
type Activity = {
  planId: string | null;
  plan: Plan | null;
  status: string;
  elapsedMs: number;
  planner: string;
  at: string;
};
type Health = { planner: string; model: string; providerStatus: string };
type Result = {
  rows: { label: string; value: number | string }[];
  elapsedMs: number;
  planId?: string;
  summary?: {
    message: string;
    claims: { row: number; label: string; value: unknown; text: string }[];
    limitation: string;
  };
};
async function requestApi<T>(
  role: string,
  path: string,
  body?: unknown,
  method?: string,
): Promise<T> {
  const response = await fetch("/api/v2" + path, {
    method: method || (body === undefined ? "GET" : "POST"),
    headers: { "Content-Type": "application/json", "X-Demo-Role": role },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(40000),
  });
  const data = await response.json();
  if (!response.ok) throw new Error(data.message || "Request failed.");
  return data;
}
const examples = [
  "Revenue by category last 30 days",
  "Order count by region for electronics",
  "Return rate by month all time",
];
const pretty = (s: string) => s.toLowerCase().replaceAll("_", " ");
export default function App() {
  const [role, setRole] = useState("ANALYST");
  const roleRef = useRef(role);
  roleRef.current = role;
  const api = <T,>(path: string, body?: unknown, method?: string) =>
    requestApi<T>(role, path, body, method);
  const [catalog, setCatalog] = useState<Catalog>();
  const [clarification, setClarification] = useState<Clarification>();
  const [editing, setEditing] = useState(false);
  const [question, setQuestion] = useState(examples[0]);
  const [health, setHealth] = useState<Health>();
  const [history, setHistory] = useState<Activity[]>([]);
  const [review, setReview] = useState<Review>();
  const [result, setResult] = useState<Result>();
  const [confirmed, setConfirmed] = useState(false);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [consumed, setConsumed] = useState(false);
  const refresh = () => {
    const requestedRole = role;
    return api<Record<string, unknown>[]>("/queries/history")
      .then((rows) => {
        if (roleRef.current === requestedRole)
          setHistory(
            rows.map((a) => ({
              planId: String(a.id || a.planId || ""),
              plan: a.plan as Plan | null,
              status: String(a.status),
              elapsedMs: Number(a.elapsed_ms || a.elapsedMs || 0),
              planner: String(a.planner),
              at: String(a.created_at || a.at),
            })),
          );
      })
      .catch(() => {});
  };
  useEffect(() => {
    let active = true;
    api<Health>("/health")
      .then(setHealth)
      .catch(() =>
        setError("API unavailable. Start the Java API and PostgreSQL."),
      );
    void refresh();
    api<Catalog>("/schema")
      .then((data) => {
        if (active && data.metricDefinitions) setCatalog(data);
      })
      .catch(() => {
        if (active)
          setError(
            "Catalog unavailable. Check API authentication and database setup.",
          );
      });
    return () => {
      active = false;
    };
  }, [role]);
  function accept(response: Review | Clarification) {
    if ("clarificationId" in response) {
      setClarification(response);
      setReview(undefined);
    } else {
      setReview(response);
      setClarification(undefined);
    }
    setEditing(false);
    setConfirmed(false);
    setConsumed(false);
    setResult(undefined);
  }
  async function plan() {
    setBusy("Planning");
    setError("");
    setReview(undefined);
    setResult(undefined);
    setConfirmed(false);
    setConsumed(false);
    setClarification(undefined);
    setEditing(false);
    try {
      accept(await api<Review | Clarification>("/queries/plan", { question }));
    } catch (e) {
      setError(e instanceof Error ? e.message : "Planning failed.");
    } finally {
      setBusy("");
      void refresh();
    }
  }
  async function execute() {
    if (!review || !confirmed || editing || review.cost?.allowed === false)
      return;
    setBusy("Executing");
    setError("");
    setConsumed(true);
    try {
      const approval = await api<{ approvalToken: string }>(
        `/queries/${review.planId}/approve`,
        { confirmed: true, reviewDigest: review.reviewDigest },
      );
      setResult(
        await api<Result>(`/queries/${review.planId}/execute`, {
          confirmed: true,
          approvalToken: approval.approvalToken,
        }),
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : "Execution failed.");
    } finally {
      setBusy("");
      void refresh();
    }
  }
  async function clarify(selections: Record<string, string>) {
    if (!clarification) return;
    setBusy("Clarifying");
    setError("");
    try {
      accept(
        await api<Review>(`/queries/${clarification.clarificationId}/clarify`, {
          selections,
        }),
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : "Clarification failed.");
    } finally {
      setBusy("");
      void refresh();
    }
  }
  async function edit(plan: Plan) {
    if (!review) return;
    setBusy("Updating");
    setError("");
    try {
      accept(await api<Review>(`/queries/${review.planId}`, plan, "PATCH"));
    } catch (e) {
      setError(e instanceof Error ? e.message : "Editing failed.");
    } finally {
      setBusy("");
      void refresh();
    }
  }
  async function summarize() {
    if (!review) return;
    setBusy("Summarizing");
    setError("");
    try {
      setResult(
        await api<Result>(`/queries/${review.planId}/results?summary=true`),
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : "Summary unavailable.");
    } finally {
      setBusy("");
    }
  }
  async function loadResult(id: string) {
    setBusy("Loading stored results");
    setError("");
    try {
      const [plan, results] = await Promise.all([
        api<Review>(`/queries/${id}`),
        api<Result>(`/queries/${id}/results`),
      ]);
      setReview(plan);
      setResult(results);
      setConsumed(true);
      setConfirmed(true);
      setClarification(undefined);
      setEditing(false);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Stored results unavailable.");
    } finally {
      setBusy("");
    }
  }
  return (
    <div className="app">
      <a className="skip-link" href="#main">
        Skip to query studio
      </a>
      <aside className="sidebar">
        <a className="brand" href="#main">
          <span className="logo" aria-hidden="true">
            ⌘
          </span>{" "}
          QueryFence
          <span className="brand-tag">Analytics, with a checkpoint.</span>
        </a>

        <a className="nav-active" href="#main" aria-current="page">
          Query studio
        </a>
        <a
          className="nav-link"
          href="#activity"
          onClick={() => {
            (document.getElementById("activity") as HTMLDetailsElement).open =
              true;
          }}
        >
          Recent activity
        </a>
        <a
          className="nav-link"
          href="#how-it-works"
          onClick={() => {
            (document.getElementById("about") as HTMLDetailsElement).open =
              true;
          }}
        >
          How it works
        </a>
      </aside>
      <main id="main" tabIndex={-1}>
        <header>
          <span className="badge">Synthetic data ? local demo</span>
          <span className="muted">
            1,000 retail orders ? Reference date Jan 31, 2026
          </span>
        </header>
        <section className="heading">
          <div>
            <p className="eyebrow">YOUR RETAIL WORKSPACE</p>
            <h1>
              A question. A plan. <span>An answer.</span>
            </h1>
            <p>
              Ask in plain English, check the plan, then choose when to run it.
            </p>
          </div>
          <div className="status-card">
            <strong>
              <span className={health ? "dot" : "dot offline"} />
              {health?.planner === "demo"
                ? "Rules-based demo"
                : health?.planner === "ollama"
                  ? "Local Ollama"
                  : "Connecting…"}
            </strong>
            <small>{health?.model || "Waiting for API"}</small>
          </div>
        </section>
        <div className="role-selector">
          <label htmlFor="demo-role">Demo role</label>
          <select
            id="demo-role"
            value={role}
            disabled={!!busy}
            onChange={(e) => {
              setRole(e.target.value);
              setCatalog(undefined);
              setHistory([]);
              setReview(undefined);
              setResult(undefined);
              setClarification(undefined);
              setConfirmed(false);
              setEditing(false);
              setError("");
            }}
          >
            {["ANALYST", "FINANCE", "OPERATIONS"].map((r) => (
              <option key={r}>{r}</option>
            ))}
          </select>
          <small>Permissions are enforced by the backend.</small>
        </div>
        <nav className="workflow" aria-label="Query workflow">
          <div
            className={
              !review ? "workflow-step current" : "workflow-step complete"
            }
          >
            <span>01</span>
            <div>
              <strong>Ask</strong>
              <small>A question in plain English</small>
            </div>
            <b aria-hidden="true">→</b>
          </div>
          <div
            className={
              review && !result ? "workflow-step current" : "workflow-step"
            }
          >
            <span>02</span>
            <div>
              <strong>Inspect</strong>
              <small>Review the plan & SQL</small>
            </div>
            <b aria-hidden="true">→</b>
          </div>
          <div className={result ? "workflow-step complete" : "workflow-step"}>
            <span>03</span>
            <div>
              <strong>Confirm & explore</strong>
              <small>You decide when it runs</small>
            </div>
          </div>
        </nav>
        <div className="workspace">
          <div className="primary">
            <section className="card question">
              <div className="card-title">
                <h2>
                  <span className="step">01</span> Ask a question
                </h2>
                <span className="muted">No SQL required</span>
              </div>
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  void plan();
                }}
              >
                <label htmlFor="question">
                  What would you like to explore?
                </label>
                <textarea
                  id="question"
                  aria-describedby="question-count"
                  maxLength={500}
                  rows={3}
                  value={question}
                  onChange={(e) => {
                    setQuestion(e.target.value);
                    setReview(undefined);
                    setResult(undefined);
                    setConfirmed(false);
                    setClarification(undefined);
                    setEditing(false);
                  }}
                  disabled={!!busy}
                />
                <div className="input-footer">
                  <span className="muted" id="question-count">
                    {question.length}/500 characters
                  </span>
                  <button disabled={!!busy || !question.trim()} type="submit">
                    {busy === "Planning" ? "Planning…" : "Review query →"}
                  </button>
                </div>
              </form>
              <div className="examples">
                <span>NEED AN IDEA?</span>
                {examples.map((q) => (
                  <button
                    key={q}
                    className="example"
                    disabled={!!busy}
                    onClick={() => {
                      setQuestion(q);
                      setReview(undefined);
                      setResult(undefined);
                      setConfirmed(false);
                      setClarification(undefined);
                      setEditing(false);
                    }}
                  >
                    {q}
                  </button>
                ))}
              </div>
            </section>
            {error && (
              <div className="error" role="alert">
                {error}
              </div>
            )}
            {clarification && (
              <ClarificationPanel
                key={clarification.clarificationId}
                value={clarification}
                busy={!!busy}
                onContinue={(selections) => void clarify(selections)}
              />
            )}
            {busy && (
              <p role="status" className="loading">
                {busy}…{" "}
                {busy === "Planning"
                  ? "The planner is interpreting your question."
                  : busy === "Executing"
                    ? "Running the confirmed plan."
                    : "Please wait."}
              </p>
            )}
            {review && (
              <section className="card review-card">
                <div className="card-title">
                  <h2>
                    <span className="step">02</span> Review & confirm
                  </h2>
                  <span className="badge neutral">
                    {consumed ? "Reviewed" : "Ready to review"}
                  </span>
                </div>
                {review.cost && <CostPanel cost={review.cost} />}
                {catalog && !consumed && (
                  <button
                    className="example edit-toggle"
                    disabled={!!busy}
                    onClick={() => {
                      setEditing(!editing);
                      setConfirmed(false);
                    }}
                  >
                    {editing ? "Close editor" : "Adjust plan"}
                  </button>
                )}
                {editing && catalog && (
                  <PlanEditor
                    key={review.planId}
                    plan={review.plan}
                    catalog={catalog}
                    busy={!!busy}
                    onSave={(p) => void edit(p)}
                    onCancel={() => setEditing(false)}
                  />
                )}
                <dl className="plan-grid">
                  {Object.entries(review.plan)
                    .filter(([k]) => k !== "explanation")
                    .map(([k, v]) => (
                      <div key={k}>
                        <dt>{k.replace("timeWindow", "time window")}</dt>
                        <dd>{v === null ? "All" : pretty(String(v))}</dd>
                      </div>
                    ))}
                </dl>
                <p className="interpretation">{review.plan.explanation}</p>
                <details className="query-details">
                  <summary>SQL & query details</summary>
                  <div className="sql-header">
                    SQL PREVIEW <span>Java compiled · bound parameters</span>
                  </div>
                  <pre>{review.sql}</pre>
                  <p className="parameters">
                    Parameters: {JSON.stringify(review.parameters)}
                  </p>
                  <small className="muted">
                    Expires {new Date(review.expiresAt).toLocaleTimeString()} ·{" "}
                    {review.model}
                  </small>
                </details>
                <div className="confirmation">
                  <label>
                    <input
                      type="checkbox"
                      checked={confirmed}
                      disabled={
                        consumed ||
                        !!busy ||
                        editing ||
                        review.cost?.allowed === false
                      }
                      onChange={(e) => setConfirmed(e.target.checked)}
                    />{" "}
                    I reviewed this plan and confirm execution on the synthetic
                    dataset.
                  </label>
                  <button
                    onClick={() => void execute()}
                    disabled={
                      !confirmed ||
                      consumed ||
                      !!busy ||
                      editing ||
                      review.cost?.allowed === false
                    }
                  >
                    {consumed ? "Plan consumed" : "Run confirmed query →"}
                  </button>
                </div>
              </section>
            )}
            {result && (
              <section className="card">
                <div className="card-title">
                  <h2>Query results</h2>
                  <span className="muted">
                    {result.rows.length} rows · {result.elapsedMs} ms
                  </span>
                </div>
                {result.rows.length === 0 ? (
                  <p className="empty">
                    No rows match these filters. Try a wider window.
                  </p>
                ) : (
                  <div className="table-wrap">
                    <table>
                      <caption className="sr-only">
                        Synthetic retail query results
                      </caption>
                      <thead>
                        <tr>
                          <th scope="col">{review?.plan.dimension}</th>
                          <th scope="col">
                            {review?.plan.metric}
                            {review?.plan.metric === "RETURN_RATE"
                              ? " (%)"
                              : ""}
                          </th>
                          <th scope="col">Relative value</th>
                        </tr>
                      </thead>
                      <tbody>
                        {result.rows.map((row) => (
                          <tr key={row.label}>
                            <td>{row.label}</td>
                            <td>
                              {Number(row.value).toLocaleString(undefined, {
                                maximumFractionDigits: 2,
                              })}
                            </td>
                            <td>
                              <div
                                className="bar"
                                style={{
                                  width: `${(Number(row.value) / Math.max(...result.rows.map((r) => Number(r.value)), 1)) * 100}%`,
                                }}
                              />
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
                <button
                  className="example summary-button"
                  disabled={!!busy}
                  onClick={() => void summarize()}
                >
                  Explain returned results
                </button>
                {result.summary && (
                  <article
                    className="result-summary"
                    aria-label="Results summary"
                  >
                    <h3>Results-grounded summary</h3>
                    <p>{result.summary.message}</p>
                    <ul>
                      {result.summary.claims.map((c) => (
                        <li key={c.row}>
                          {c.text} <strong>[Row {c.row}]</strong>
                        </li>
                      ))}
                    </ul>
                    <small>{result.summary.limitation}</small>
                  </article>
                )}
              </section>
            )}
            <details id="activity" className="card disclosure">
              <summary>
                Recent activity <span>Revisit saved results</span>
              </summary>
              <div className="card-title">
                <h2>Recent activity</h2>
                <span className="muted">
                  Persisted · latest 100 plans for this role
                </span>
              </div>
              {history.length === 0 ? (
                <p className="muted">
                  Your plan and execution events will appear here.
                </p>
              ) : (
                <ul className="activity">
                  {history.slice(0, 12).map((a, i) => (
                    <li key={i}>
                      <span className="activity-icon">◈</span>
                      <div>
                        <strong>
                          {a.plan
                            ? `${pretty(a.plan.metric)} by ${pretty(a.plan.dimension)}`
                            : "Rejected question"}
                        </strong>
                        <small>
                          {a.planner} · {new Date(a.at).toLocaleTimeString()}
                        </small>
                      </div>
                      <span className="badge neutral">{a.status}</span>
                      <small>{a.elapsedMs} ms</small>
                      {a.status === "EXECUTED" && a.planId && (
                        <button
                          className="example"
                          disabled={!!busy}
                          onClick={() => void loadResult(a.planId!)}
                        >
                          View results
                        </button>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </details>
            <details className="card disclosure" id="evaluation-lab">
              <summary>
                Evaluation lab <span>Benchmarks & regressions</span>
              </summary>
              <EvaluationPanel key={role} api={api} identity={role} />
            </details>
          </div>
          <details className="right card disclosure" id="about">
            <summary>
              About the data & guardrails{" "}
              <span>Definitions, scope & safety</span>
            </summary>
            <div className="about-grid">
              {catalog && (
                <section className="card semantic-catalog">
                  <p className="eyebrow">BUSINESS SEMANTICS</p>
                  <h2>One shared definition</h2>
                  {catalog.metricDefinitions.map((m) => (
                    <div key={m.key}>
                      <h3>{m.label}</h3>
                      <p>{m.definition}</p>
                      <small>{m.unit}</small>
                    </div>
                  ))}
                  <small>
                    Catalog {catalog.catalogVersion}. Definitions also guide the
                    configured planner.
                  </small>
                </section>
              )}
              <section className="card guardrails" id="how-it-works">
                <span className="shield" aria-hidden="true">
                  ↳
                </span>
                <p className="eyebrow">THE QUERY BOUNDARY</p>
                <h2>
                  Freedom to ask.
                  <br />
                  Limits by design.
                </h2>
                <p>
                  Three independent steps keep query construction constrained.
                </p>
                <ol>
                  <li>
                    <strong>Planner proposes a plan</strong>
                    <p>
                      Only metrics, dimensions and supported filters. No
                      executable SQL.
                    </p>
                  </li>
                  <li>
                    <strong>Java validates & compiles</strong>
                    <p>
                      Allow-listed enums, fixed SQL fragments and prepared
                      parameters.
                    </p>
                  </li>
                  <li>
                    <strong>PostgreSQL limits access</strong>
                    <p>
                      A dedicated SELECT-only analytics login, read-only
                      transactions and timeouts.
                    </p>
                  </li>
                </ol>
                <div className="callout">
                  A local demonstration, not a production security guarantee.
                </div>
              </section>
              <section className="card dataset">
                <p className="eyebrow">THE DATASET</p>
                <h2>Synthetic retail</h2>
                <div className="stat">
                  1,000 <small>generated orders</small>
                </div>
                <p>5 categories · 5 regions · 3 statuses</p>
                <hr />
                <p>
                  Fixed reference date
                  <br />
                  <strong>January 31, 2026</strong>
                </p>
                <small>
                  Date windows are relative to this date. Metric definitions
                  come from the backend business catalog. All results come from
                  PostgreSQL.
                </small>
              </section>
            </div>
          </details>
        </div>
        <details className="developer-resources">
          <summary>Developer resources</summary>
          <div>
            <h2>Explore the Java backend</h2>
            <p>
              Swagger documents the REST endpoints and lets developers test
              requests directly. Use the query studio above for the guided ask,
              review and confirm experience.
            </p>
            <a href="/swagger-ui/index.html" target="_blank" rel="noreferrer">
              Open API documentation (Swagger) ↗
            </a>
            <p className="developer-note">
              The documentation opens on this website’s address because Nginx
              forwards it to the Spring Boot API. Query execution still requires
              a plan ID and explicit confirmation.
            </p>
          </div>
        </details>
        <footer>
          QueryFence · Safe Natural-Language Analytics{" "}
          <span>Plan first. Run only with your approval.</span>
        </footer>
      </main>
    </div>
  );
}
