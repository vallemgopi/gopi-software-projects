#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if [ -e .env ]; then echo '.env already exists; preserve or edit it manually.'; exit 1; fi
umask 077
printf 'DB_PASSWORD=%s\nANALYTICS_PASSWORD=%s\nPLANNER_MODE=demo\nOLLAMA_MODEL=qwen2.5:3b\nOLLAMA_URL=http://host.docker.internal:11434\n' "$(openssl rand -hex 24)" "$(openssl rand -hex 24)" > .env
echo 'Created .env with random local demo credentials.'
sh scripts/upgrade-env.sh
