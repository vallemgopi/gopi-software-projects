# Direct complaint intake

The public form is `/complaint`, currently http://localhost:5173/complaint on the running local Docker deployment. It does not require a staff account. The staff portal remains http://localhost:5173 and requires authentication.

## Submit and review

1. Choose Tamil, Telugu, Kannada, Malayalam, English, Hindi, or auto detection.
2. Type the complaint, upload a PCM WAV, or use **Start recording** / **Stop recording**. Recording stops after 60 seconds and converts the browser's audio into mono 16-bit 16 kHz WAV. Preview the clip before sending.
3. Agree to send the words/details for staff review. The form discloses whether original audio is retained from the actual server configuration. Default: audio processed in memory and not retained.
4. Send. The receipt contains a random reference, receipt timestamp, and `AWAITING_REVIEW`. It does not say a case was approved, dispatched, or submitted to an authority.
5. The configured `DEMO_USER` sees the case in the normal review queue/history, marked **Public submission** and `NEEDS_REVIEW`. The reviewer inspects and corrects it using the existing immutable-prediction / correction-revision workflow.

There is no anonymous case listing, status lookup, correction access, or deletion endpoint. Save the reference for contacting the staff desk separately. No SMS/email/external authority notification is sent. This is not an emergency service.

## API

Only these routes allow anonymous requests:

* `GET /api/public/config`: intake enabled, audio retention flag, upload limits, supported language codes. No credentials or personal data.
* `POST /api/public/submissions`: JSON `{ "transcript": "Synthetic water leak at Demo Lane.", "language": "en", "consent": true, "website": "" }`. Transcript limit: 5,000 characters. Returns a receipt only.
* `POST /api/public/submissions/audio`: multipart `file`, `language`, `consent=true`, and optional empty `website`. Uses real local Whisper; client-supplied provider/sample text cannot select demo transcription. WAV extension/magic, 5 MiB, duration, and decoder checks apply.

The `website` field is a honeypot and must remain empty. Unknown JSON properties are rejected. No anonymous request can mark a case reviewed or choose its reviewer. Source, declared language, and consent timestamp are persisted with Flyway migration V2; the audit actor is `anonymous-intake`, without IP address or transcript in logs. Existing cases remain `STAFF` and keep their data/corrections.

nginx limits public submission requests per client IP to 10/minute with a burst of 10; the Java API adds an instance-wide 30/minute and two-concurrent-request backstop without storing client addresses. These are basic single-instance controls, not a distributed anti-abuse system. Configure the trusted reverse proxy/IP handling and suitable additional abuse protection for a public installation. Set `PUBLIC_INTAKE_ENABLED=false` and recreate the API to close anonymous intake. Existing staff access is retained.

## Other phones and internet hosting

**The current localhost URL only works on this computer.** Anonymous access is implemented, but an internet deployment has not been created. People on other phones need the form served from a reachable HTTPS origin. Microphone APIs require HTTPS, except on localhost; on an insecure LAN URL recording is unavailable and the form explains the upload alternative.

No hosting account, domain, or public server has been supplied. The project can run from its Docker Compose setup on a suitable server once those are selected. At that point:

* Set the real HTTPS origin in `CORS_ORIGIN`, configure TLS at the ingress, and expose only the web entry point.
* Keep PostgreSQL/ML private, retain staff authentication, and replace public demo credentials before making the staff API reachable from the internet.
* Size CPU/RAM for the local speech model; protect stored complaint data and configure a retention policy appropriate for the actual desk.

No paid account, tunnel, public URL, or external submission destination was created during this change. Local testing establishes the workflow, not South Indian spoken-recognition accuracy. Generated fixtures and browser fake-microphone tests are not consented speech benchmarks.
