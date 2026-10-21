ALTER TABLE lab_case ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'STAFF';
ALTER TABLE lab_case ADD COLUMN consented_at TIMESTAMPTZ;
ALTER TABLE lab_case ADD COLUMN submitted_language VARCHAR(20);
CREATE INDEX case_source_created ON lab_case(source, created_at DESC);
