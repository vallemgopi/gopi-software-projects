CREATE SEQUENCE phone_reference_seq START WITH 10000001;
CREATE TABLE phone_intake (
 id UUID PRIMARY KEY,
 call_sid VARCHAR(34) NOT NULL UNIQUE,
 reference VARCHAR(12) NOT NULL UNIQUE,
 owner VARCHAR(100) NOT NULL REFERENCES lab_user(username),
 language VARCHAR(10),
 state VARCHAR(30) NOT NULL,
 consented_at TIMESTAMPTZ,
 recording_sid VARCHAR(34) UNIQUE,
 case_id UUID REFERENCES lab_case(id) ON DELETE SET NULL,
 attempts INTEGER NOT NULL DEFAULT 0,
 menu_attempts INTEGER NOT NULL DEFAULT 0,
 next_attempt_at TIMESTAMPTZ NOT NULL,
 lease_until TIMESTAMPTZ,
 error_code VARCHAR(50),
 media_deleted BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMPTZ NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL,
 version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX phone_jobs_due ON phone_intake(state,next_attempt_at);
ALTER TABLE lab_case ADD COLUMN phone_intake_id UUID UNIQUE REFERENCES phone_intake(id);
ALTER TABLE lab_case ADD COLUMN phone_reference VARCHAR(12);
