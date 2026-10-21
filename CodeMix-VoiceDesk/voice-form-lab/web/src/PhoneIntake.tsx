import React, { useEffect, useState } from "react";
import { api } from "./api";
type Intake = {
  id: string;
  reference: string;
  language: string | null;
  state: string;
  attempts: number;
  caseId: string | null;
  errorCode: string | null;
  createdAt: string;
  providerRecordingDeleted: boolean;
  hasRecording: boolean;
};
type Status = {
  enabled: boolean;
  publicNumber: string;
  localizedPromptsConfigured: boolean;
  notice: string;
};
const errors: Record<string, string> = {
  NO_MENU_SELECTION: "No valid menu selection",
  INVALID_DURATION: "Recording was empty or exceeded the limit",
  RECORDING_UNAVAILABLE: "Provider could not supply recording",
  WORKER_INTERRUPTED: "Processing interrupted; recovery scheduled",
  PROCESSING_UNAVAILABLE: "Audio download or model unavailable",
  NO_SPEECH_OR_INVALID_AUDIO: "No speech or unsupported audio",
  INVALID_RECORDING: "Invalid recording format/size",
  ML_REJECTED: "Model rejected input",
  CALL_INCOMPLETE: "Call ended without a usable recording",
};
export default function PhoneIntake({
  openCase,
}: {
  openCase: (id: string) => void;
}) {
  const [status, setStatus] = useState<Status | null>(null),
    [calls, setCalls] = useState<Intake[]>([]),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  async function refresh() {
    setBusy(true);
    setError("");
    try {
      const [s, c] = await Promise.all([
        api<Status>("/phone/status"),
        api<Intake[]>("/phone/intakes"),
      ]);
      setStatus(s);
      setCalls(c);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not load phone intake");
    } finally {
      setBusy(false);
    }
  }
  useEffect(() => {
    void refresh();
  }, []);
  return (
    <>
      <section className="card">
        <div className="sectionhead">
          <h2>Phone complaint desk</h2>
          <button
            className="secondary"
            disabled={busy}
            onClick={() => void refresh()}
          >
            Refresh phone activity
          </button>
        </div>
        {busy && <p role="status">Loading phone activity…</p>}
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
        {status && (
          <>
            <span
              className={"pill " + (status.enabled ? "neutral" : "warning")}
            >
              {status.enabled
                ? "Webhook integration configured"
                : "Phone line not activated"}
            </span>
            <p>
              {status.enabled ? (
                <>
                  Configured number: <strong>{status.publicNumber}</strong>.
                  Make a real test call before sharing it.
                </>
              ) : (
                "No live dial-in number is connected. The integration can be tested locally; callers cannot dial the application yet."
              )}
            </p>
            <p className="muted">{status.notice}</p>
            <p className="muted">
              Callers choose Tamil, Telugu, Kannada, Malayalam, English, Hindi,
              or automatic detection, consent to recording, and leave up to 60
              seconds of speech. Successful processing creates a case awaiting
              human review.
            </p>
            {!status.localizedPromptsConfigured && (
              <p className="callout">
                Prompts currently default to English. Approved prerecorded
                local-language menu, consent, and recording instructions must be
                configured for a fully localized call experience.
              </p>
            )}
          </>
        )}
      </section>
      <section className="card">
        <h2>Recent phone intake · {calls.length}</h2>
        <p className="muted">
          Last 100 calls assigned to your review queue. Caller phone numbers are
          not collected here. A reference acknowledges intake, not resolution.
        </p>
        {calls.length ? (
          <div className="tablewrap">
            <table>
              <thead>
                <tr>
                  <th>Reference</th>
                  <th>Language</th>
                  <th>Stage</th>
                  <th>Attempts</th>
                  <th>Provider audio</th>
                  <th>Review / issue</th>
                </tr>
              </thead>
              <tbody>
                {calls.map((c) => (
                  <tr key={c.id}>
                    <th>
                      {c.reference}
                      <small>{new Date(c.createdAt).toLocaleString()}</small>
                    </th>
                    <td>{c.language || "Not chosen"}</td>
                    <td>{c.state.replaceAll("_", " ")}</td>
                    <td>{c.attempts}</td>
                    <td>
                      {!c.hasRecording ? "No recording" : c.providerRecordingDeleted
                        ? "Deleted"
                        : c.state === "COMPLETED" || c.state === "FAILED"
                          ? "Check cleanup status"
                          : "Pending processing"}
                    </td>
                    <td>
                      {c.caseId ? (
                        <button
                          className="secondary"
                          onClick={() => openCase(c.caseId!)}
                        >
                          Open phone case
                        </button>
                      ) : c.errorCode ? (
                        errors[c.errorCode] || "Processing failed"
                      ) : c.state === "COMPLETED" ? (
                        "Case was removed"
                      ) : (
                        "Awaiting next step"
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          !busy && (
            <p className="empty">
              No phone intake recorded yet. Web complaints remain available.
            </p>
          )
        )}
      </section>
    </>
  );
}
