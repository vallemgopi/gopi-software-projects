# Telephone complaint intake

The application includes an optional inbound Twilio recording integration. It is disabled by default.

A Twilio free-trial account was used during development. **Live telephone calls have not been verified**, including calls from users outside the trial account’s permitted destinations. Production number availability, regional requirements, and caller access have not been checked. No paid Twilio subscription, production number, or public hosting is configured by this project. Local automated tests use synthetic calls and mocked media and ML services; they do not prove live telephone connectivity or speech accuracy.

## Caller workflow

Callers choose 1 for Tamil, 2 for Telugu, 3 for Kannada, 4 for Malayalam, 5 for English, 6 for Hindi, or 7 for automatic detection. They hear a recording disclosure, press 1 to consent (or 9 to decline), and can leave up to 60 seconds of speech, ending with #.

A numeric reference acknowledges receipt; it does not mean the complaint was processed or resolved. After a recording callback, the application queues transcription and extraction and creates a private case for staff review. Staff can inspect progress under **Phone intake**, then review the case normally.

Default spoken prompts are in English. To localize the experience, set `PHONE_PROMPTS_BASE_URL` to an HTTPS directory containing operator-approved `menu.wav`, `consent-{language}.wav`, and `record-{language}.wav` files for `ta`, `te`, `kn`, `ml`, `en`, `hi`, and `auto`. Consent recordings must disclose provider recording, processing, text retention, and the configured audio retention policy. Language menu options do not demonstrate recognition quality. Test with consented speakers and telephone audio before deployment.

## Activation

1. Confirm directly with Twilio that your account, inbound voice number, intended regions, and caller destinations are supported. The free-trial setup used during development has not been verified for external callers or production use.
2. Deploy the API behind a publicly reachable HTTPS origin. Set `PHONE_PUBLIC_BASE_URL` to that exact origin, without a path or trailing slash. The proxy must preserve the `/api/phone/twilio/` paths. A local Compose address cannot receive provider callbacks.
3. In your private `.env`, configure:
   - `PHONE_ENABLED=true`
   - `PHONE_PUBLIC_NUMBER` in E.164 format
   - `PHONE_PUBLIC_BASE_URL`
   - `TWILIO_ACCOUNT_SID`
   - `TWILIO_AUTH_TOKEN` for signature verification
   - `TWILIO_API_KEY_SID` and `TWILIO_API_KEY_SECRET` with recording read and delete permissions

   Never commit these values or paste them into chat. Leave phone intake disabled until the required settings exist. Demo login settings are separate.
4. Configure the number’s incoming voice webhook to POST to `https://YOUR-ORIGIN/api/phone/twilio/voice`. The application generates the other action and status callback URLs. Recreate the API with `docker compose up -d --build --wait`.
5. Before publishing the number, make a real, consented test call for each supported prompt and language path. Check the reference, transcription, staff review, duplicate callbacks, consent rejection, and provider recording deletion. Configuration alone does not verify a live phone line.

### Webhook routes

The following POST routes are under `/api/phone/twilio/`: `voice`, `language`, `consent`, `recorded`, and `recording-status`. They require a valid provider signature, even though they do not use staff login.

The staff-only GET routes `/api/phone/status` and `/api/phone/intakes` require application authentication. OpenAPI describes these endpoints.

## Reliability and privacy

The SDK verifies signatures against the configured public origin and all form fields. Duplicate parameters and query strings are rejected. Caller `From` numbers are not persisted. Account, call, and recording identifiers are private operational metadata; the staff list omits provider identifiers.

Recording URLs supplied in callbacks are ignored. Downloads use a fixed authenticated provider host, reject redirects, enforce a 5 MiB limit, and require WAV headers.

The webhook acknowledges and queues work quickly. Durable PostgreSQL jobs retry processing up to five times with increasing delays, recover expired processing leases, and expire incomplete calls after one hour. Repeated callbacks do not create duplicate cases, including after staff delete a case. Predictions and human corrections remain separate.

After successful processing or terminal failure, the application requests provider audio deletion. Failed cleanup remains visible and is retried. This is separate from application `RETAIN_AUDIO`, which is off by default. Temporary provider recording is required for this integration even when application audio retention is disabled. Monitor provider storage and cleanup; network or credential failures can delay deletion. Do not put raw audio or secrets in application logs.

## Reproducible checks

Run `mvn -f api/pom.xml test` with Docker available. Phone tests use Testcontainers PostgreSQL, synthetic signed callbacks, mocked recording media, and mocked ML. They cover seven language selections, consent rejection, forged signatures, retry and recovery, callback replay, private endpoints, case deletion, and cleanup.

Run `npm run e2e` from `web` to check the disabled phone desk against the default running stack.

These automated checks do not make live provider requests. Live telephone calls, external caller access, and localized recordings remain deployment acceptance checks.

Provider references: [recording callbacks](https://www.twilio.com/docs/voice/twiml/record), [webhook signatures](https://www.twilio.com/docs/usage/security), and [recording media and deletion](https://www.twilio.com/docs/voice/api/recording).
