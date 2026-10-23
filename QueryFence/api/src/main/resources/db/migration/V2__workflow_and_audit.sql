DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='queryfence_control') THEN CREATE ROLE queryfence_control NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE; END IF; END $$;
CREATE SCHEMA queryfence;
REVOKE ALL ON SCHEMA queryfence FROM PUBLIC;
CREATE TABLE queryfence.plans (
 id uuid PRIMARY KEY, actor text NOT NULL, role text NOT NULL, status text NOT NULL,
 version integer NOT NULL, parent_id uuid, plan jsonb, clarification jsonb,
 planner text NOT NULL, model text NOT NULL, catalog_version text NOT NULL,
 digest text, cost jsonb, approval_hash text, approved_at timestamptz,
 confirmation boolean NOT NULL DEFAULT false, expires_at timestamptz NOT NULL,
 result jsonb, elapsed_ms bigint, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX plans_actor_created ON queryfence.plans(actor,created_at DESC);
CREATE TABLE queryfence.audit_events (
 id bigserial PRIMARY KEY, at timestamptz NOT NULL DEFAULT now(), request_id text,
 actor text NOT NULL, role text NOT NULL, action text NOT NULL, plan_id uuid,
 outcome text NOT NULL, approved boolean NOT NULL DEFAULT false,
 details jsonb NOT NULL DEFAULT '{}'
);
CREATE INDEX audit_actor_at ON queryfence.audit_events(actor,at DESC);
CREATE TABLE queryfence.credentials (
 id uuid PRIMARY KEY, name text NOT NULL, token_hash text NOT NULL UNIQUE,
 role text NOT NULL, scopes text[] NOT NULL, origins text[] NOT NULL,
 expires_at timestamptz NOT NULL, revoked boolean NOT NULL DEFAULT false,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE queryfence.evaluations (
 id uuid PRIMARY KEY, actor text NOT NULL, status text NOT NULL, dataset_version text NOT NULL,
 planner text NOT NULL, model text NOT NULL, report jsonb, baseline boolean NOT NULL DEFAULT false,
 created_at timestamptz NOT NULL DEFAULT now()
);
GRANT USAGE ON SCHEMA queryfence TO queryfence_control;
GRANT SELECT,INSERT,UPDATE ON queryfence.plans,queryfence.credentials,queryfence.evaluations TO queryfence_control;
GRANT SELECT,INSERT ON queryfence.audit_events TO queryfence_control;
GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA queryfence TO queryfence_control;
-- The control role cannot read retail data. The analytics role receives no metadata writes.
REVOKE ALL ON SCHEMA analytics FROM queryfence_control;
