ALTER TABLE queryfence.plans ADD COLUMN planner_version text NOT NULL DEFAULT '2.0.0';
ALTER TABLE queryfence.plans ADD COLUMN model_version text NOT NULL DEFAULT 'unverified';
ALTER TABLE queryfence.evaluations ADD COLUMN planner_version text NOT NULL DEFAULT '2.0.0';
ALTER TABLE queryfence.evaluations ADD COLUMN model_version text NOT NULL DEFAULT 'unverified';
CREATE UNIQUE INDEX one_baseline_per_version ON queryfence.evaluations(actor,dataset_version,planner,model,model_version) WHERE baseline=true;
