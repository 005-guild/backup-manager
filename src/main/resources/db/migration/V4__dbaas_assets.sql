ALTER TABLE database_catalog ADD COLUMN asset_external_id VARCHAR(255);

CREATE TABLE dbaas_asset (
  external_id VARCHAR(255) PRIMARY KEY,
  db_name VARCHAR(255) NOT NULL,
  logicdb_code VARCHAR(255),
  ldbid BIGINT,
  valid VARCHAR(10),
  raw_data TEXT NOT NULL,
  last_seen_run_id BIGINT NOT NULL DEFAULT 0,
  first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_dbaas_asset_db_name ON dbaas_asset(db_name);
CREATE INDEX idx_dbaas_asset_run_id ON dbaas_asset(last_seen_run_id);
