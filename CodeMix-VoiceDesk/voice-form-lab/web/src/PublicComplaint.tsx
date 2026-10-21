import React, { useEffect, useRef, useState } from "react";
import { recordingToWav } from "./recording";

type Config = {
  enabled: boolean;
  audioRetained: boolean;
  phone?: { enabled: boolean; number: string };
};
type Receipt = {
  reference: string;
  receivedAt: string;
  status: string;
  message: string;
};
async function request<T>(url: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch("/api/public" + url, {
    ...options,
    credentials: "omit",
  });
  if (!response.ok) {
    let message =
      response.status === 429
        ? "Too many submissions. Please wait a minute and try again."
        : "Could not send your complaint. Please try again.";
    try {
      message = (await response.json()).message || message;
    } catch {}
    throw new Error(message);
  }
  return response.json();
}
const languages = [
  ["ta", "Tamil / தமிழ்"],
  ["te", "Telugu / తెలుగు"],
  ["kn", "Kannada / ಕನ್ನಡ"],
  ["ml", "Malayalam / മലയാളം"],
  ["en", "English"],
  ["hi", "Hindi"],
  ["auto", "Auto detect"],
];

export default function PublicComplaint() {
  const [config, setConfig] = useState<Config | null>(null),
    [configError, setConfigError] = useState("");
  const [mode, setMode] = useState("text"),
    [language, setLanguage] = useState("ta"),
    [transcript, setTranscript] = useState("");
  const [audio, setAudio] = useState<File | null>(null),
    [audioUrl, setAudioUrl] = useState("");
  const [consent, setConsent] = useState(false),
    [website, setWebsite] = useState(""),
    [receipt, setReceipt] = useState<Receipt | null>(null);
  const [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [recording, setRecording] = useState(false),
    [requestingMic, setRequestingMic] = useState(false),
    [seconds, setSeconds] = useState(0),
    [converting, setConverting] = useState(false);
  const recorder = useRef<MediaRecorder | null>(null),
    stream = useRef<MediaStream | null>(null),
    timer = useRef<ReturnType<typeof setInterval> | null>(null),
    alive = useRef(true);
  const receiptHeading = useRef<HTMLHeadingElement>(null);
  async function loadConfig() {
    setConfigError("");
    try {
      setConfig(await request<Config>("/config"));
    } catch {
      setConfigError(
        "The complaint service is unavailable. Try loading it again.",
      );
    }
  }
  useEffect(() => {
    alive.current = true;
    void loadConfig();
    return () => {
      alive.current = false;
      if (timer.current) clearInterval(timer.current);
      if (recorder.current?.state === "recording") recorder.current.stop();
      stream.current?.getTracks().forEach((t) => t.stop());
    };
  }, []);
  useEffect(() => {
    if (!audio) {
      setAudioUrl("");
      return;
    }
    const url = URL.createObjectURL(audio);
    setAudioUrl(url);
    return () => URL.revokeObjectURL(url);
  }, [audio]);
  useEffect(() => {
    if (receipt) receiptHeading.current?.focus();
  }, [receipt]);
  function stop() {
    if (timer.current) clearInterval(timer.current);
    timer.current = null;
    if (recorder.current?.state === "recording") recorder.current.stop();
    stream.current?.getTracks().forEach((t) => t.stop());
  }
  async function record() {
    if (requestingMic) return;
    setError("");
    setAudio(null);
    if (
      !window.isSecureContext ||
      !navigator.mediaDevices?.getUserMedia ||
      !window.MediaRecorder
    ) {
      setError(
        "Microphone recording needs a supported browser on HTTPS or localhost. You can upload a WAV file instead.",
      );
      return;
    }
    setRequestingMic(true);
    try {
      const input = await navigator.mediaDevices.getUserMedia({ audio: true });
      if (!alive.current) {
        input.getTracks().forEach((t) => t.stop());
        return;
      }
      stream.current = input;
      const mime = ["audio/webm;codecs=opus", "audio/mp4", "audio/webm"].find(
        (t) => MediaRecorder.isTypeSupported(t),
      );
      const media = new MediaRecorder(
        input,
        mime ? { mimeType: mime } : undefined,
      );
      recorder.current = media;
      const chunks: Blob[] = [];
      let size = 0,
        elapsed = 0;
      media.ondataavailable = (e) => {
        if (e.data.size) {
          chunks.push(e.data);
          size += e.data.size;
          if (size > 16 * 1024 * 1024) stop();
        }
      };
      media.onerror = () => {
        if (alive.current)
          setError("Recording failed. Please upload a WAV clip or try again.");
        stop();
      };
      media.onstop = async () => {
        if (timer.current) clearInterval(timer.current);
        timer.current = null;
        input.getTracks().forEach((t) => t.stop());
        if (!alive.current) return;
        setRecording(false);
        setConverting(true);
        try {
          if (!size || size > 16 * 1024 * 1024)
            throw new Error(
              "Recording was empty or too large. Try a shorter clip.",
            );
          const wav = await recordingToWav(
            new Blob(chunks, { type: media.mimeType }),
          );
          if (alive.current) setAudio(wav);
        } catch (e) {
          if (alive.current)
            setError(
              e instanceof Error
                ? e.message
                : "Could not process the recording.",
            );
        } finally {
          if (alive.current) setConverting(false);
        }
      };
      media.start(250);
      setSeconds(0);
      setRecording(true);
      timer.current = setInterval(() => {
        elapsed++;
        setSeconds(elapsed);
        if (elapsed >= 60) stop();
      }, 1000);
    } catch {
      stream.current?.getTracks().forEach((t) => t.stop());
      if (alive.current)
        setError(
          "Microphone access was denied or unavailable. Allow it in your browser, or upload a WAV file.",
        );
    } finally {
      if (alive.current) setRequestingMic(false);
    }
  }
  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (busy || recording || converting || requestingMic) return;
    setError("");
    if (!consent) {
      setError(
        "Please confirm permission to send this complaint for staff review.",
      );
      return;
    }
    if (mode === "audio" && !audio) {
      setError("Record or upload a spoken WAV clip first.");
      return;
    }
    setBusy(true);
    try {
      let result: Receipt;
      if (mode === "audio" && audio) {
        const form = new FormData();
        form.append("file", audio);
        form.append("language", language);
        form.append("consent", "true");
        form.append("website", website);
        result = await request<Receipt>("/submissions/audio", {
          method: "POST",
          body: form,
        });
      } else {
        result = await request<Receipt>("/submissions", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ transcript, language, consent, website }),
        });
      }
      setReceipt(result);
      setAudio(null);
      setTranscript("");
      setConsent(false);
    } catch (e) {
      setError(
        e instanceof Error ? e.message : "Could not send your complaint.",
      );
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="public-shell">
      <header className="public-header">
        <a href="/complaint" className="logo">
          <span className="brandmark small">VF</span>
          <span>
            Voice Form<span className="sublogo">COMPLAINT DESK</span>
          </span>
        </a>
        <a href="/">Staff sign in →</a>
      </header>
      <main className="public-content">
        <p className="eyebrow">YOUR WORDS. A HUMAN REVIEW.</p>
        <h1>Tell us what happened.</h1>
        <p className="muted">
          Write in your language or send a short voice clip. No account needed.
          A staff member will check the details.
        </p>
        <p className="callout">
          This demonstration receives requests for review. It does not contact
          an authority, dispatch help, or provide an emergency service.
        </p>
        {config?.phone?.enabled && config.phone.number && (
          <p className="callout">
            Prefer a phone call?{" "}
            <a href={"tel:" + config.phone.number}>
              Call {config.phone.number}
            </a>{" "}
            to choose a language and leave a recorded complaint for staff
            review.
          </p>
        )}
        {configError && (
          <div role="alert" className="error">
            {configError}
            <button className="secondary" onClick={() => void loadConfig()}>
              Retry loading
            </button>
          </div>
        )}
        {!config && !configError && (
          <p role="status">Loading complaint form…</p>
        )}
        {receipt ? (
          <section className="card">
            <span className="pill neutral">
              RECEIVED · AWAITING HUMAN REVIEW
            </span>
            <h2 ref={receiptHeading} tabIndex={-1} className="receipt-title">
              Your complaint was received.
            </h2>
            <p>Save this reference for the staff team:</p>
            <p className="receipt-reference">{receipt.reference}</p>
            <p className="muted">
              {receipt.message} Receipt time:{" "}
              {new Date(receipt.receivedAt).toLocaleString()}.
            </p>
            <p>
              There is no public status lookup yet. No other complaints or
              reviewer notes are visible here.
            </p>
            <button
              onClick={() => {
                setReceipt(null);
                setError("");
              }}
            >
              Send another complaint
            </button>
          </section>
        ) : config?.enabled ? (
          <form className="card public-form" onSubmit={(e) => void submit(e)}>
            <label>
              Language
              <select
                value={language}
                onChange={(e) => setLanguage(e.target.value)}
                disabled={busy || recording || requestingMic}
              >
                {languages.map(([code, name]) => (
                  <option key={code} value={code}>
                    {name}
                  </option>
                ))}
              </select>
            </label>
            <div
              className="public-tabs"
              role="group"
              aria-label="Complaint format"
            >
              <button
                type="button"
                className={mode === "text" ? "" : "secondary"}
                aria-pressed={mode === "text"}
                disabled={busy || recording || converting || requestingMic}
                onClick={() => setMode("text")}
              >
                Write complaint
              </button>
              <button
                type="button"
                className={mode === "audio" ? "" : "secondary"}
                aria-pressed={mode === "audio"}
                disabled={busy || recording || converting || requestingMic}
                onClick={() => setMode("audio")}
              >
                Record or upload voice
              </button>
            </div>
            {mode === "text" ? (
              <label>
                Your complaint
                <textarea
                  required
                  maxLength={5000}
                  rows={7}
                  value={transcript}
                  onChange={(e) => setTranscript(e.target.value)}
                  disabled={busy}
                  placeholder="Describe the issue and location. Include a date or name only if needed."
                />
              </label>
            ) : (
              <div className="upload">
                <h2>Speak in your own words.</h2>
                <p className="muted">
                  Choose the main language above. English mixing may be
                  misheard, so a person will review the result.
                </p>
                <div className="actions">
                  <button
                    type="button"
                    disabled={busy || converting || requestingMic}
                    className={recording ? "danger" : ""}
                    onClick={() => (recording ? stop() : void record())}
                  >
                    {requestingMic
                      ? "Opening microphone…"
                      : recording
                        ? "Stop recording"
                        : "Start recording"}
                  </button>
                  {recording && (
                    <span role="status" className="pill warning">
                      Recording · {seconds}s / 60s
                    </span>
                  )}
                </div>
                {converting && <p role="status">Preparing WAV recording…</p>}
                <label>
                  Or upload a WAV clip
                  <input
                    type="file"
                    accept=".wav,audio/wav"
                    disabled={busy || recording || converting || requestingMic}
                    onChange={(e) => {
                      setError("");
                      const file = e.target.files?.[0];
                      if (!file) return;
                      if (
                        file.size > 5 * 1024 * 1024 ||
                        !file.name.toLowerCase().endsWith(".wav")
                      ) {
                        setError("Choose a PCM WAV file under 5 MiB.");
                        e.target.value = "";
                        return;
                      }
                      setAudio(file);
                    }}
                  />
                </label>
                <p className="muted">
                  Up to 60 seconds / 5 MiB. Browser recording converts to WAV
                  automatically.
                </p>
                {audio && (
                  <div>
                    <p role="status">Voice clip ready: {audio.name}</p>
                    <audio
                      controls
                      src={audioUrl}
                      aria-label="Preview your complaint recording"
                    />
                    <button
                      type="button"
                      className="ghost"
                      disabled={busy}
                      onClick={() => setAudio(null)}
                    >
                      Remove clip
                    </button>
                  </div>
                )}
              </div>
            )}
            <div className="honeypot" aria-hidden="true">
              <label>
                Leave website empty
                <input
                  value={website}
                  onChange={(e) => setWebsite(e.target.value)}
                  tabIndex={-1}
                  autoComplete="off"
                />
              </label>
            </div>
            <p className="muted">
              Your words and extracted details will be stored for staff review.{" "}
              {config.audioRetained
                ? "Original audio is also retained by this desk."
                : "Original audio is processed and is not retained."}{" "}
              Avoid sensitive IDs and details about other people.
            </p>
            <label className="check">
              <input
                type="checkbox"
                required
                checked={consent}
                onChange={(e) => setConsent(e.target.checked)}
                disabled={busy}
              />
              I agree to send this complaint for staff review.
            </label>
            {error && (
              <p className="error" role="alert">
                {error}
              </p>
            )}
            <button
              disabled={
                busy ||
                requestingMic ||
                recording ||
                converting ||
                !consent ||
                (mode === "text" ? !transcript.trim() : !audio)
              }
            >
              {busy ? "Sending for review…" : "Send complaint →"}
            </button>
            {busy && (
              <p role="status">
                Processing your request. Please keep this page open.
              </p>
            )}
          </form>
        ) : (
          config && (
            <section className="card">
              <h2>Intake is temporarily closed.</h2>
              <p>Please contact the staff team or try again later.</p>
            </section>
          )
        )}
        <footer>
          Voice Form Reliability Lab · Staff review required · Speech accuracy
          is still being evaluated
        </footer>
      </main>
    </div>
  );
}
