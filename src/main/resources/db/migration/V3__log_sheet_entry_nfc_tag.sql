ALTER TABLE log_sheet_entries ADD COLUMN IF NOT EXISTS nfc_tag_id VARCHAR(255);

UPDATE log_sheet_entries e
SET nfc_tag_id = a.nfc_tag_id
FROM asset_entries a
WHERE e.asset_id = a.id
  AND e.nfc_tag_id IS NULL
  AND a.nfc_tag_id IS NOT NULL;
