#!/bin/sh
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set=control_password="$CONTROL_PASSWORD" <<'SQL'
DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='queryfence_control') THEN CREATE ROLE queryfence_control NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE; END IF; END $$;
SELECT format('ALTER ROLE queryfence_control LOGIN PASSWORD %L', :'control_password') \gexec
ALTER ROLE queryfence_control SET statement_timeout='5s';
SQL
