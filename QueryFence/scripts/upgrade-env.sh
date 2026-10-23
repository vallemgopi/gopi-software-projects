#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
test -f .env || { echo 'Run setup-env first'; exit 1; }
umask 077
for key in CONTROL_PASSWORD LOCAL_DEMO_SECRET; do
 if ! grep -q "^${key}=" .env; then printf '%s=%s\n' "$key" "$(openssl rand -hex 32)" >> .env; fi
done
echo 'Added missing v2 local secrets; existing values preserved.'
