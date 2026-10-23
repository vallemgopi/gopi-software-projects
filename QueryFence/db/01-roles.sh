#!/bin/sh
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set=analytics_password="$ANALYTICS_PASSWORD" <<'SQL'
CREATE ROLE analytics LOGIN PASSWORD :'analytics_password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
ALTER ROLE analytics SET default_transaction_read_only = on;
ALTER ROLE analytics SET statement_timeout = '5s';
ALTER ROLE analytics SET search_path = analytics;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE TEMPORARY ON DATABASE queryfence FROM PUBLIC;
SQL
