-- =============================================================================
-- Physical NFC chip serial (UID) on assets, e.g. "00:aa:34:9f:12:cd".
--
-- This is NOT the same thing as asset_entries.nfc_tag_id and the two must never
-- be conflated:
--
--   nfc_tag_id  — the LOGICAL tag identifier used for lookup. It is inherited
--                 from the sub-function's tag (falling back to its code) when the
--                 asset does not carry one of its own, and it is released again
--                 when the asset goes inactive so the replacement equipment can
--                 take it over (see AssetEntryService.applyNfcInheritance).
--   nfc_serial  — the HARDWARE serial burned into the physical chip. It belongs
--                 to that one piece of plastic, is never inherited, never
--                 derived, and never released. Optional everywhere.
--
-- Uniqueness: optional, but unique when supplied. Mirrors
-- ux_asset_entries_nfc_tag_id_lower exactly — a plain (non-partial) unique index
-- on the lowered value. PostgreSQL treats NULLs as distinct in a unique index, so
-- any number of assets may leave the serial empty while two assets can never
-- claim the same physical chip.
-- =============================================================================

ALTER TABLE asset_entries ADD COLUMN nfc_serial VARCHAR(255);

CREATE UNIQUE INDEX ux_asset_entries_nfc_serial_lower ON asset_entries (LOWER(nfc_serial));

-- Snapshot on the log-sheet row, alongside the nfc_tag_id snapshot that has always
-- lived here. Deliberately NOT unique: every generated sheet copies the same asset's
-- serial, and the snapshot must survive the asset row being edited or deleted.
-- Carried to the PWA in LogSheetEntryDto so an offline scan can later match the
-- chip UID without a round trip.
ALTER TABLE log_sheet_entries ADD COLUMN nfc_serial VARCHAR(255);
