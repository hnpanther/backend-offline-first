-- Indexes for asset parameter reports (log_sheet_entries joined by asset_id).
CREATE INDEX idx_log_sheet_entries_asset_id ON log_sheet_entries (asset_id);
