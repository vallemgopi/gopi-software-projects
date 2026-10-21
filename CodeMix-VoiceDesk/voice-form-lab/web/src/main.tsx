import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { api, credentials, clearCredentials, download } from "./api";
import "./style.css";
import PublicComplaint from "./PublicComplaint";
import PhoneIntake from "./PhoneIntake";
type Field = {
  value: string | null;
  confidence: number;
  evidence: { text: string; start: number; end: number } | null;
  needsReview: boolean;
};
type Case = {
  id: string;
  transcript: string;
  prediction: {
    fields: Record<string, Field>;
    provider: string;
    languageHints: string[];
    confidenceNote: string;
    speech?: {
      warning: string;
      provider: string;
      confidence: number | null;
      requestedLanguage?: string;
      detectedLanguage?: string;
      model?: { repository: string; revision: string };
    };
  };
  status: string;
  source?: string;
  phoneReference?: string | null;
  version: number;
  createdAt: string;
  corrections: {
    transcript: string;
    fields: Record<string, string>;
    createdAt: string;
  }[];
};
const names: Record<string, string> = {
  category: "Issue category",
  summary: "Short summary",
  location: "Location",
  datetime: "Date / time",
  identifier: "Name / identifier",
};
export function Confidence({ field }: { field: Field }) {
  return (
    <span className={"pill " + (field.needsReview ? "warning" : "neutral")}>
      {field.needsReview ? "Needs review" : "Check evidence"} ·{" "}
      {Math.round(field.confidence * 100)}% score
    </span>
  );
}
function App() {
  const [user, setUser] = useState(""),
    [page, setPage] = useState("Dashboard"),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false),
    [notice, setNotice] = useState("");
  const [cases, setCases] = useState<Case[]>([]),
    [metrics, setMetrics] = useState<any>(null),
    [evaluation, setEvaluation] = useState<any>(null),
    [active, setActive] = useState<Case | null>(null),
    [search, setSearch] = useState("");
  const [transcript, setTranscript] = useState(""),
    [fields, setFields] = useState<Record<string, string>>({}),
    [confirmed, setConfirmed] = useState(false),
    [audio, setAudio] = useState<File | null>(null),
    [provider, setProvider] = useState("whisper"),
    [language, setLanguage] = useState("auto");
  async function work(fn: () => Promise<void>) {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      await fn();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Unexpected error");
    } finally {
      setBusy(false);
    }
  }
  async function refresh() {
    const [c, m, e] = await Promise.all([
      api<Case[]>("/cases"),
      api("/metrics"),
      api("/evaluations/latest"),
    ]);
    setCases(c);
    setMetrics(m);
    setEvaluation(e.report || null);
  }
  useEffect(() => {
    if (user) void work(refresh);
  }, [user]);
  function open(c: Case) {
    setActive(c);
    const last = c.corrections.at(-1);
    setTranscript(last?.transcript || c.transcript);
    setFields(
      last?.fields ||
        Object.fromEntries(
          Object.entries(c.prediction.fields).map(([k, v]) => [
            k,
            v.value || "",
          ]),
        ),
    );
    setConfirmed(false);
    setPage("Review");
  }
  async function submit() {
    await work(async () => {
      let c: Case;
      if (audio) {
        const form = new FormData();
        form.append("file", audio);
        form.append("provider", provider);
        form.append("language", language);
        if (provider === "sample") form.append("sampleText", transcript);
        c = await api("/cases/audio", { method: "POST", body: form });
      } else
        c = await api("/cases", {
          method: "POST",
          body: JSON.stringify({ transcript }),
        });
      open(c);
      await refresh();
    });
  }
  async function save() {
    if (!active) return;
    await work(async () => {
      const c = await api<Case>("/cases/" + active.id + "/review", {
        method: "POST",
        body: JSON.stringify({ transcript, fields, version: active.version }),
      });
      open(c);
      setNotice("Review saved. Original prediction preserved.");
      await refresh();
    });
  }
  if (!user)
    return (
      <main className="login">
        <div className="brandmark">
          VF<span>↗</span>
        </div>
        <p className="eyebrow">HUMAN REVIEW · REPRODUCIBLE EVALUATION</p>
        <h1>
          Every voice.
          <br />
          Every detail, checked.
        </h1>
        <p>Code-Mixed Voice Form Reliability Lab</p>
        <p>
          <a className="public-entry" href="/complaint">
            Make a complaint without signing in →
          </a>
        </p>
        <form
          className="card"
          onSubmit={(e) => {
            e.preventDefault();
            const data = new FormData(e.currentTarget);
            void work(async () => {
              credentials(
                String(data.get("username")),
                String(data.get("password")),
              );
              const me = await api("/me");
              setUser(me.username);
            });
          }}
        >
          <h2>Enter the reliability lab</h2>
          <label>
            Username
            <input
              name="username"
              defaultValue="reviewer"
              required
              autoComplete="username"
            />
          </label>
          <label>
            Password
            <input
              name="password"
              type="password"
              required
              autoComplete="current-password"
            />
          </label>
          <button disabled={busy}>{busy ? "Signing in…" : "Sign in →"}</button>
          <p className="muted">
            Use the credentials configured in your local .env.
          </p>
          {error && (
            <p role="alert" className="error">
              {error}
            </p>
          )}
        </form>
        <p className="muted">
          A research and review tool. No institutional submissions.
        </p>
      </main>
    );
  return (
    <div className="shell">
      <aside>
        <a
          className="logo"
          href="#"
          onClick={(e) => {
            e.preventDefault();
            setPage("Dashboard");
          }}
        >
          <span className="brandmark small">VF</span>
          <span>
            Voice Form<span className="sublogo">RELIABILITY LAB</span>
          </span>
        </a>
        <p className="navlabel">WORKSPACE</p>
        <nav aria-label="Main navigation">
          {[
            ["Dashboard", "◫"],
            ["New case", "＋"],
            ["Case history", "▤"],
            ["Phone intake", "☎"],
            ["Evaluation", "◈"],
          ].map(([p, i]) => (
            <button
              key={p}
              className={page === p ? "selected" : ""}
              onClick={() => {
                setPage(p);
                setError("");
                setNotice("");
                if (p === "New case") {
                  setTranscript("");
                  setAudio(null);
                }
              }}
            >
              <span>{i}</span>
              {p}
            </button>
          ))}
        </nav>
        <div className="aside-bottom">
          <span className="pill neutral">● Local-first inference</span>
          <p>
            Tamil · Telugu · Kannada · Malayalam
            <br />
            Synthetic evaluation corpus
          </p>
          <button
            className="ghost"
            onClick={() => {
              clearCredentials();
              setUser("");
              setActive(null);
              setCases([]);
              setMetrics(null);
              setEvaluation(null);
            }}
          >
            Sign out · {user}
          </button>
        </div>
      </aside>
      <main>
        <header>
          <span>
            Workspace / <strong>{page}</strong>
          </span>
          <span className="pill">RESEARCH BUILD · v1.0</span>
        </header>
        <div className="content">
          <div className="titlebar">
            <div>
              <p className="eyebrow">CODE-MIXED VOICE FORM RELIABILITY LAB</p>
              <h1>
                {page === "Dashboard"
                  ? "Reliability, made visible."
                  : page === "Review"
                    ? "Review every detail."
                    : page === "Evaluation"
                      ? "Measure before you trust."
                      : page === "New case"
                        ? "Start with their words."
                        : "The review record."}
              </h1>
              <p className="muted">
                {page === "Dashboard"
                  ? "Follow each prediction from first words to human correction."
                  : page === "Evaluation"
                    ? "Reproducible results from a labeled, synthetic holdout set."
                    : "Capture evidence. Keep predictions and corrections separate."}
              </p>
            </div>
            {page === "Dashboard" && (
              <button
                onClick={() => {
                  setTranscript("");
                  setAudio(null);
                  setPage("New case");
                }}
              >
                ＋ New case
              </button>
            )}
          </div>
          {error && (
            <div className="error" role="alert">
              {error}
              <button className="ghost" onClick={() => void work(refresh)}>
                Retry loading
              </button>
            </div>
          )}
          {notice && (
            <p role="status" className="success">
              {notice}
            </p>
          )}
          {busy && <p role="status">Working…</p>}
          {page === "Phone intake" && (
            <PhoneIntake
              openCase={(id) =>
                void work(async () => {
                  open(await api<Case>("/cases/" + id));
                })
              }
            />
          )}
          {page === "Dashboard" && (
            <>
              <div className="stats">
                {[
                  ["Total cases", metrics?.cases],
                  ["Needs review", metrics?.needsReview],
                  ["Human reviewed", metrics?.reviewed],
                  ["Evaluation examples", evaluation?.examples],
                ].map(([label, value]) => (
                  <div className="stat" key={label}>
                    <span>{label}</span>
                    <strong>{value ?? "—"}</strong>
                    <small>
                      {label === "Evaluation examples"
                        ? evaluation
                          ? "Bundled synthetic holdout"
                          : "Run evaluation to populate"
                        : "From your saved workspace"}
                    </small>
                  </div>
                ))}
              </div>
              <div className="two-col">
                <section className="card">
                  <div className="sectionhead">
                    <h2>Review queue</h2>
                    <span className="pill warning">Human in the loop</span>
                  </div>
                  <CaseList
                    cases={cases
                      .filter((c) => c.status === "NEEDS_REVIEW")
                      .slice(0, 5)}
                    open={open}
                  />
                </section>
                <section className="card dark">
                  <p className="eyebrow">THE RELIABILITY LOOP</p>
                  <h2>
                    A prediction is
                    <br />
                    the beginning.
                  </h2>
                  <ol>
                    <li>Capture text or a short WAV</li>
                    <li>Inspect confidence and evidence</li>
                    <li>Correct, confirm, and save</li>
                    <li>Export labels and measure errors</li>
                  </ol>
                  <p>
                    Missing fields stay missing. A reviewer makes the final
                    call.
                  </p>
                </section>
              </div>
              <div className="two-col">
                <section className="card">
                  <h2>Corrections by field</h2>
                  <p className="muted">
                    Latest reviewer value compared with the original prediction.
                  </p>
                  {metrics && Object.keys(metrics.correctedFields).length ? (
                    Object.entries(metrics.correctedFields).map(([k, v]) => (
                      <div className="barrow" key={k}>
                        <span>{names[k]}</span>
                        <meter
                          min="0"
                          max={Math.max(metrics.reviewed, 1)}
                          value={Number(v)}
                        />
                        <b>{String(v)}</b>
                      </div>
                    ))
                  ) : (
                    <p className="empty">No corrections recorded yet.</p>
                  )}
                </section>
                <section className="card">
                  <h2>Cases over time</h2>
                  {metrics &&
                    Object.entries(metrics.casesByDay).map(([k, v]) => (
                      <div className="row" key={k}>
                        <span>{k}</span>
                        <b>{String(v)} cases</b>
                      </div>
                    ))}
                  {!cases.length && (
                    <p className="empty">Create a case to begin the record.</p>
                  )}
                </section>
              </div>
            </>
          )}
          {page === "New case" && (
            <section className="card formcard">
              <div className="sectionhead">
                <h2>01 / Capture</h2>
                <span className="pill neutral">No paid API required</span>
              </div>
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  void submit();
                }}
              >
                <label>
                  Speech / sample language
                  <select
                    value={language}
                    onChange={(e) => setLanguage(e.target.value)}
                  >
                    <option value="auto">
                      Auto detect — select a language for short clips
                    </option>
                    <option value="ta">Tamil / தமிழ் + English</option>
                    <option value="te">Telugu / తెలుగు + English</option>
                    <option value="kn">Kannada / ಕನ್ನಡ + English</option>
                    <option value="ml">Malayalam / മലയാളം + English</option>
                    <option value="en">English</option>
                    <option value="hi">Hindi</option>
                  </select>
                </label>
                <p className="muted">
                  Speech stays on this computer. Code-mixed recognition can make
                  errors; review every result. Other regional languages are not
                  validated.
                </p>
                <label>
                  Transcript
                  <textarea
                    value={transcript}
                    onChange={(e) => setTranscript(e.target.value)}
                    maxLength={10000}
                    rows={6}
                    required={!audio || provider === "sample"}
                    placeholder="For example: Paani nahi aa raha at Demo Colony since 2026-01-15."
                  />
                </label>
                <button
                  type="button"
                  className="secondary"
                  onClick={() =>
                    setTranscript(
                      (
                        {
                          ta: "தண்ணீர் வரவில்லை at Demo Colony since 2026-01-15. ID: SYN-TA-01.",
                          te: "నీరు రావడం లేదు at Demo Colony since 2026-01-15. ID: SYN-TE-01.",
                          kn: "ನೀರು ಬರುತ್ತಿಲ್ಲ at Demo Colony since 2026-01-15. ID: SYN-KN-01.",
                          ml: "വെള്ളം വരുന്നില്ല at Demo Colony since 2026-01-15. ID: SYN-ML-01.",
                          en: "Water is leaking at Demo Colony since 2026-01-15. ID: SYN-EN-01.",
                        } as Record<string, string>
                      )[language] ||
                        "Paani nahi aa raha at Demo Colony since 2026-01-15. ID: SYN-004.",
                    )
                  }
                >
                  Use synthetic sample transcript
                </button>
                <div className="upload">
                  <label>
                    Optional audio clip · PCM WAV, up to 60 seconds / 5 MiB
                    <input
                      type="file"
                      accept=".wav,audio/wav"
                      onChange={(e) => {
                        const file = e.target.files?.[0] || null;
                        if (file && file.size > 5 * 1024 * 1024) {
                          setError("Audio exceeds 5 MiB");
                          e.target.value = "";
                          return;
                        }
                        setAudio(file);
                      }}
                    />
                  </label>
                  <label>
                    Speech provider
                    <select
                      value={provider}
                      onChange={(e) => setProvider(e.target.value)}
                    >
                      <option value="whisper">
                        Local Whisper CPU — real speech, no paid API
                      </option>
                      <option value="sample">
                        Sample transcript — DEMO, no speech recognition
                      </option>
                      <option value="vosk">
                        Vosk CPU — optional local model required
                      </option>
                    </select>
                  </label>
                  <p className="muted">
                    Sample mode uses the transcript above and ignores audio
                    content. The bundled synthetic-silence.wav is a silent
                    fixture. Audio storage is disabled by default.
                  </p>
                </div>
                <button disabled={busy}>Extract & review →</button>
              </form>
            </section>
          )}
          {page === "Review" && active && (
            <>
              <section className="card">
                <div className="sectionhead">
                  <h2>02 / Transcript review</h2>
                  <span className="pill">
                    {active.status === "REVIEWED" ? "Reviewed" : "Needs review"}
                  </span>
                </div>
                <p className="muted">
                  Provider: {active.prediction.provider} · Language hints:{" "}
                  {active.prediction.languageHints.join(", ")}
                </p>
                {active.prediction.speech?.detectedLanguage && (
                  <p className="muted">
                    Speech language: {active.prediction.speech.detectedLanguage}{" "}
                    · Requested: {active.prediction.speech.requestedLanguage} ·
                    Model: {active.prediction.speech.model?.repository}
                  </p>
                )}
                {active.prediction.speech && (
                  <p className="warning callout">
                    {active.prediction.speech.warning} · Speech confidence:{" "}
                    {active.prediction.speech.confidence === null
                      ? "Unavailable"
                      : active.prediction.speech.confidence}
                  </p>
                )}
                <label>
                  Reviewed transcript
                  <textarea
                    rows={4}
                    maxLength={10000}
                    value={transcript}
                    onChange={(e) => setTranscript(e.target.value)}
                  />
                </label>
                <details>
                  <summary>Original transcript (immutable)</summary>
                  <p>{active.transcript}</p>
                </details>
              </section>
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  void save();
                }}
              >
                <div className="fieldgrid">
                  {Object.entries(active.prediction.fields).map(([key, f]) => (
                    <section className="card fieldcard" key={key}>
                      <div className="sectionhead">
                        <h2>{names[key]}</h2>
                        <Confidence field={f} />
                      </div>
                      <p className="predicted">
                        Model: <strong>{f.value || "Not provided"}</strong>
                      </p>
                      <label>
                        Reviewed {names[key].toLowerCase()}
                        {key === "category" ? (
                          <select
                            value={fields[key] || ""}
                            onChange={(e) =>
                              setFields({ ...fields, [key]: e.target.value })
                            }
                          >
                            <option value="">Not provided</option>
                            {[
                              "water",
                              "electricity",
                              "roads",
                              "sanitation",
                            ].map((x) => (
                              <option key={x}>{x}</option>
                            ))}
                          </select>
                        ) : (
                          <input
                            maxLength={2000}
                            value={fields[key] || ""}
                            onChange={(e) =>
                              setFields({ ...fields, [key]: e.target.value })
                            }
                          />
                        )}
                      </label>
                      <p className="evidence">
                        Evidence:{" "}
                        {f.evidence ? (
                          <>
                            <q>{f.evidence.text}</q>
                            <small>
                              Original transcript characters {f.evidence.start}–
                              {f.evidence.end}
                            </small>
                          </>
                        ) : (
                          "No supporting span — confirm missing or correct it."
                        )}
                      </p>
                    </section>
                  ))}
                </div>
                <section className="card">
                  <p className="muted">
                    {active.prediction.confidenceNote} Blank fields explicitly
                    mark missing information. Scores are not measured accuracy.
                  </p>
                  <label className="check">
                    <input
                      type="checkbox"
                      checked={confirmed}
                      onChange={(e) => setConfirmed(e.target.checked)}
                      required
                    />
                    I reviewed all fields, including those left blank.
                  </label>
                  <button disabled={busy || !confirmed || !transcript.trim()}>
                    Save reviewed case
                  </button>
                </section>
              </form>
              <section className="card">
                <h2>
                  Correction history · {active.corrections.length} revisions
                </h2>
                {active.corrections.map((r, i) => (
                  <details key={i}>
                    <summary>
                      Revision {i + 1} ·{" "}
                      {new Date(r.createdAt).toLocaleString()}
                    </summary>
                    <p>{r.transcript}</p>
                    <pre>{JSON.stringify(r.fields, null, 2)}</pre>
                  </details>
                ))}
              </section>
            </>
          )}
          {page === "Case history" && (
            <section className="card">
              <div className="sectionhead">
                <h2>All cases</h2>
                <button
                  className="secondary"
                  disabled={busy}
                  onClick={() =>
                    void work(async () =>
                      download(
                        "review-labels.json",
                        await api("/exports/corrections"),
                      ),
                    )
                  }
                >
                  Export correction labels
                </button>
              </div>
              <label>
                Search transcripts
                <input
                  type="search"
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  placeholder="Search the record…"
                />
              </label>
              <CaseList
                cases={cases.filter((c) =>
                  [c.transcript, ...c.corrections.map((r) => r.transcript)]
                    .join(" ")
                    .toLowerCase()
                    .includes(search.toLowerCase()),
                )}
                open={open}
                remove={(c) => {
                  if (
                    window.confirm(
                      "Delete this case, retained audio, and all correction revisions? The audit event will remain.",
                    )
                  )
                    void work(async () => {
                      await api("/cases/" + c.id, { method: "DELETE" });
                      await refresh();
                    });
                }}
              />
            </section>
          )}
          {page === "Evaluation" && (
            <>
              <div className="callout">
                <strong>SYNTHETIC CORPUS</strong> · Separate training and
                holdout examples. Results describe this small fixture only. WER
                uses simulated recognition output.
              </div>
              <div className="actions">
                <button
                  disabled={busy}
                  onClick={() =>
                    void work(async () => {
                      const result = await api("/evaluations", {
                        method: "POST",
                      });
                      setEvaluation(result.report);
                    })
                  }
                >
                  Run reproducible evaluation
                </button>
                {evaluation && (
                  <button
                    className="secondary"
                    onClick={() => download("evaluation.json", evaluation)}
                  >
                    Export report JSON
                  </button>
                )}
              </div>
              {evaluation ? (
                <Evaluation report={evaluation} />
              ) : (
                <section className="card empty">
                  No evaluation has run yet. Run the corpus to see computed
                  results.
                </section>
              )}
            </>
          )}
          <footer>
            Voice Form Reliability Lab · Evidence before automation · Synthetic
            data for demonstration
          </footer>
        </div>
      </main>
    </div>
  );
}
function CaseList({
  cases,
  open,
  remove,
}: {
  cases: Case[];
  open: (c: Case) => void;
  remove?: (c: Case) => void;
}) {
  return cases.length ? (
    <div className="case-list">
      {cases.map((c) => (
        <div className="case-row" key={c.id}>
          <button className="case-link" onClick={() => open(c)}>
            <strong>
              {c.corrections.at(-1)?.fields.summary ||
                c.prediction.fields.summary.value ||
                "Untitled case"}
            </strong>
            <span>
              {new Date(c.createdAt).toLocaleDateString()} · {c.id.slice(0, 8)}
              {c.source === "PUBLIC" ? " · Public submission" : ""}
              {c.source === "PHONE"
                ? " · Phone call · " + c.phoneReference
                : ""}
            </span>
          </button>
          <span
            className={
              "pill " + (c.status === "REVIEWED" ? "neutral" : "warning")
            }
          >
            {c.status === "REVIEWED" ? "Reviewed" : "Needs review"}
          </span>
          {remove && (
            <button
              className="danger"
              aria-label={"Delete case " + c.id.slice(0, 8)}
              onClick={() => remove(c)}
            >
              Delete
            </button>
          )}
        </div>
      ))}
    </div>
  ) : (
    <div className="empty">
      <span className="empty-icon">◫</span>
      <p>No cases here yet.</p>
      <small>Captured cases will appear with their review status.</small>
    </div>
  );
}
function Evaluation({ report: r }: { report: any }) {
  const pct = (n: number) => `${(n * 100).toFixed(1)}%`;
  return (
    <>
      <div className="stats">
        <div className="stat">
          <span>Holdout examples</span>
          <strong>{r.examples}</strong>
          <small>{r.datasetVersion}</small>
        </div>
        <div className="stat">
          <span>Simulated WER</span>
          <strong>{pct(r.wer)}</strong>
          <small>
            {r.wordErrors} edits / {r.referenceWords} reference words
          </small>
        </div>
        <div className="stat">
          <span>Model category macro F1</span>
          <strong>{pct(r.reports.model.categoryMacroF1)}</strong>
          <small>Four category labels · missing = abstention</small>
        </div>
        <div className="stat">
          <span>Baseline category macro F1</span>
          <strong>{pct(r.reports.baseline.categoryMacroF1)}</strong>
          <small>English keyword baseline</small>
        </div>
      </div>
      <section className="card">
        <h2>Results by language</h2>
        <p className="muted">
          Synthetic text fixtures only. These results do not measure speech
          recognition or prove code-mixing accuracy. Native-language review is
          still needed.
        </p>
        {r.byLanguage ? (
          <div className="tablewrap">
            <table>
              <thead>
                <tr>
                  <th>Language / script</th>
                  <th>Examples</th>
                  <th>Category exact match</th>
                  <th>Model macro F1</th>
                  <th>Baseline macro F1</th>
                </tr>
              </thead>
              <tbody>
                {Object.entries(r.byLanguage).map(([code, value]) => {
                  const row = value as any;
                  return (
                    <tr key={code}>
                      <th>{code}</th>
                      <td>{row.examples}</td>
                      <td>
                        {pct(row.reports.model.perFieldAccuracy.category)}
                      </td>
                      <td>{pct(row.reports.model.categoryMacroF1)}</td>
                      <td>{pct(row.reports.baseline.categoryMacroF1)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        ) : (
          <p>Run a new evaluation to get language breakdowns.</p>
        )}
      </section>
      <section className="card">
        <h2>Exact match by field</h2>
        <div className="tablewrap">
          <table>
            <thead>
              <tr>
                <th>Field</th>
                <th>Model</th>
                <th>Baseline</th>
                <th>Model, reference present</th>
              </tr>
            </thead>
            <tbody>
              {Object.keys(names).map((k) => (
                <tr key={k}>
                  <th>{names[k]}</th>
                  <td>{pct(r.reports.model.perFieldAccuracy[k])}</td>
                  <td>{pct(r.reports.baseline.perFieldAccuracy[k])}</td>
                  <td>
                    {r.reports.model.nonMissingAccuracy[k] === null
                      ? "N/A"
                      : pct(r.reports.model.nonMissingAccuracy[k])}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className="muted">
          Exact match is case sensitive and includes missing values. Summary
          uses the first sentence as an extractive target.
        </p>
      </section>
      {["model", "baseline"].map((key) => {
        const m = r.reports[key];
        return (
          <section className="card" key={key}>
            <h2>{key === "model" ? "CPU classifier" : "Keyword baseline"}</h2>
            <p>
              Category macro precision {pct(m.categoryMacroPrecision)} · recall{" "}
              {pct(m.categoryMacroRecall)} · F1 {pct(m.categoryMacroF1)}
            </p>
            <h3>Category confusion matrix</h3>
            <p className="muted">Rows = reference; columns = prediction</p>
            <div className="tablewrap">
              <table>
                <thead>
                  <tr>
                    <th>Reference ↓ / Prediction →</th>
                    {m.confusionLabels.map((l: string) => (
                      <th key={l}>{l}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {m.confusionMatrix.map((row: number[], i: number) => (
                    <tr key={i}>
                      <th>{m.confusionLabels[i]}</th>
                      {row.map((v, j) => (
                        <td key={j} className={v ? "matrix-active" : ""}>
                          {v}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <h3>Errors · {m.errors.length}</h3>
            <div className="tablewrap">
              <table>
                <thead>
                  <tr>
                    <th>Example / language</th>
                    <th>Field</th>
                    <th>Reference</th>
                    <th>Prediction</th>
                  </tr>
                </thead>
                <tbody>
                  {m.errors.map((e: any, i: number) => (
                    <tr key={i}>
                      <td>
                        {e.id}
                        <small>{e.language}</small>
                      </td>
                      <td>{e.field}</td>
                      <td>{e.expected ?? "Missing"}</td>
                      <td>{e.predicted ?? "Missing"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        );
      })}
      <details className="card">
        <summary>Reproducibility metadata</summary>
        <pre>
          {JSON.stringify(
            {
              dataset: r.datasetVersion,
              sha256: r.datasetSha256,
              model: r.modelVersion,
              seed: r.seed,
              werSource: r.werSource,
            },
            null,
            2,
          )}
        </pre>
      </details>
    </>
  );
}
createRoot(document.getElementById("root")!).render(
  window.location.pathname.replace(/\/$/, "") === "/complaint" ? (
    <PublicComplaint />
  ) : (
    <App />
  ),
);
