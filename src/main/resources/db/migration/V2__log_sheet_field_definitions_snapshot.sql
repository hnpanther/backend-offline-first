-- Freeze field-definition schema at log-sheet generation time so later class
-- changes do not affect in-flight sheets or their submit-time validation.
ALTER TABLE log_sheets
    ADD COLUMN field_definitions_snapshot JSONB;
