CREATE ROLE analytics LOGIN PASSWORD 'ephemeral-analytics' NOSUPERUSER NOCREATEDB NOCREATEROLE;
ALTER ROLE analytics SET default_transaction_read_only=on;
ALTER ROLE analytics SET statement_timeout='5s';
CREATE ROLE queryfence_control LOGIN PASSWORD 'ephemeral-control' NOSUPERUSER NOCREATEDB NOCREATEROLE;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
