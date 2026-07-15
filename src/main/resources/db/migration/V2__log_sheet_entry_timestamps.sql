-- Per-asset entry timestamps (epoch millis, client-authoritative on mobile sync).
ALTER TABLE log_sheet_entries
    ADD COLUMN created_at BIGINT,
    ADD COLUMN updated_at BIGINT;
