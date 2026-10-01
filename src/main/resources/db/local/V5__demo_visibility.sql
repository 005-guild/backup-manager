ALTER TABLE database_catalog ADD COLUMN is_demo BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE backup_record ADD COLUMN is_demo BOOLEAN NOT NULL DEFAULT FALSE;

-- Existing local fixtures predate explicit provenance columns.
UPDATE backup_record
SET is_demo = TRUE
WHERE external_id LIKE 'DEMO:%' AND raw_data LIKE '%"demo":true%';
UPDATE database_catalog d
SET is_demo = TRUE
WHERE d.asset_external_id IS NULL
  AND d.name LIKE 'DEMO!_%' ESCAPE '!'
  AND (d.service_unit LIKE '%@demo' OR EXISTS (
      SELECT 1 FROM backup_record b WHERE b.database_id = d.id AND b.is_demo = TRUE
  ));

CREATE TABLE demo_data_setting (
  id INTEGER PRIMARY KEY,
  visible BOOLEAN NOT NULL,
  CONSTRAINT demo_data_setting_singleton CHECK (id = 1)
);
INSERT INTO demo_data_setting(id, visible) VALUES (1, TRUE);

CREATE VIEW visible_database_catalog AS
SELECT d.* FROM database_catalog d
WHERE d.is_demo = FALSE OR (SELECT s.visible FROM demo_data_setting s WHERE s.id = 1) = TRUE;

CREATE VIEW visible_backup_record AS
SELECT b.* FROM backup_record b
JOIN visible_database_catalog d ON d.id = b.database_id
WHERE b.is_demo = FALSE OR (SELECT s.visible FROM demo_data_setting s WHERE s.id = 1) = TRUE;
