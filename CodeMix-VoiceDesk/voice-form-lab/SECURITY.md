# Security and publishing

This is a local evaluation/demo application, not a production security certification. See [the dated security review](docs/SECURITY_REVIEW.md) for measured checks and remaining dependency advisories.

Never commit `.env`, tokens, private keys, recordings, database files, user corrections, or exports containing real complaints. The bundled corpus and screenshots use synthetic demonstration material. `.env.example` contains deliberately public local demo passwords: change both before any shared deployment. Rotate any credential exposed in chat or Git immediately; ignoring a file does not remove old commits.

Publish the contents of `voice-form-lab` as the repository root so `.github/workflows` runs. Run `python scripts/prepare_github.py --zip` to create `github-release.zip`, a source-only archive excluding local configuration, tools, model downloads, database storage and generated files. Extract the archive before uploading source files to GitHub. Do not upload the entire workspace or a manually created ZIP containing `.env`.

The scanner checks known credential patterns and private environment values copied into source. Its temporary-fixture tests verify key exclusions and credential blocking. It is not a comprehensive secret or personal-data detector, does not inspect Git history, and cannot revoke a leaked token. Inspect the final Git staging list and any existing repository history separately. Enable GitHub secret scanning and push protection and keep Dependabot updates under review.

The default Compose ports bind to loopback; PostgreSQL and ML have no published ports. Publishing source does not publish your local database. Internet deployment is a separate security decision: use HTTPS, private service networking, strong credentials, perimeter login throttling, monitored backups, a data-retention policy, and provider recording cleanup monitoring. HTTP Basic login must only traverse HTTPS outside local testing. Public intake deliberately accepts anonymous writes and has bounded concurrency/rate limits; it does not expose case reads. Staff credentials live only in browser memory, not localStorage. Do not expose the private ML service.

Browser mutations with foreign Origin headers are rejected, while configured same-origin browser requests and signed non-browser provider callbacks remain supported. CORS is not authentication. Requests without Origin still require endpoint authentication or provider signatures. Swagger is intentionally public API documentation, with no application records or credentials.

For a security report, use the repository owner's private contact or GitHub private vulnerability reporting when enabled. Do not post real credentials or complaint data in public issues. No private reporting address has been configured by this template.
