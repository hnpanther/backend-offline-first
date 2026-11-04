-- =============================================================================
-- V11 — NFC, QR, either, or neither: how a round identifies its assets
--
-- Until now every round was an NFC round, because that was the only thing the tablet could
-- read. A plant has positions a chip cannot survive and positions nobody will ever label, so the
-- requirement becomes a property of the round rather than of the system.
--
-- Three additive changes, no stored row touched:
--
--   1. `log_sheet_templates.scan_mode` — the choice.
--   2. `log_sheets.scan_mode` — that choice FROZEN onto the round, like its field snapshot.
--   3. `PWA_QR` admitted by the entry-revision CHECK, which would otherwise have refused the
--      first CORRECTION of a QR reading while accepting the reading itself.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1 & 2. The mode, on the template and on the round
--
-- WHY IT IS COPIED ONTO THE SHEET RATHER THAN READ THROUGH THE TEMPLATE. The same reason
-- `field_definitions_snapshot` is copied: a round in an operator's hands must not change its
-- rules underneath them. A supervisor who switches a template to QR on Tuesday is describing
-- Wednesday's rounds, not the one somebody is walking right now with a tablet that has been
-- offline since Monday — and that tablet, holding a bundle it downloaded before the change,
-- would otherwise be told by the server that it had scanned the wrong kind of thing.
--
-- It also makes a CUSTOM sheet work with no extra machinery: it has no template to read from, so
-- the column on the sheet is its only home either way.
--
-- NOT NULL DEFAULT 'NFC' is the whole of the migration for existing rows. Every template and
-- every sheet that exists today was an NFC round and is now explicitly one — which is what the
-- default has to say, because this column is read by the device to decide what to offer.
-- No CHECK, matching `asset_selection_mode` beside it: a fifth mode must not need DDL, and the
-- application validates the value on write (`AssetScanMode`, checked in the service, not the
-- dropdown — a dropdown is not a boundary).
-- -----------------------------------------------------------------------------
ALTER TABLE log_sheet_templates
    ADD COLUMN scan_mode VARCHAR(20) NOT NULL DEFAULT 'NFC';

COMMENT ON COLUMN log_sheet_templates.scan_mode IS
    'How a round raised from this template identifies its assets: NFC, QR, NFC_OR_QR or NONE. '
    'Copied onto each sheet at generation (V11).';

ALTER TABLE log_sheets
    ADD COLUMN scan_mode VARCHAR(20) NOT NULL DEFAULT 'NFC';

COMMENT ON COLUMN log_sheets.scan_mode IS
    'How THIS round identifies its assets, frozen when it was raised so a later change to the '
    'template cannot change the rules under an operator already walking it (V11).';


-- -----------------------------------------------------------------------------
-- 3. A QR reading must survive being corrected
--
-- `log_sheet_entries.entry_source` is a plain VARCHAR with no CHECK, so a QR reading stores
-- without complaint. `log_sheet_entry_revisions.entry_source` is NOT: V4 pinned it to the three
-- values that existed then. The failure that produces is the nastiest shape there is — the
-- reading is accepted, the round is submitted, everything looks right, and the FIRST correction
-- of that reading fails on a constraint, days later, in front of a supervisor who did nothing
-- unusual.
--
-- Rebuilt rather than relaxed: the CHECK is what keeps a typo in this column from becoming a
-- value nothing can render, and dropping it would be paying for a new value with the rule.
-- -----------------------------------------------------------------------------
ALTER TABLE log_sheet_entry_revisions
    DROP CONSTRAINT IF EXISTS ck_lser_entry_source;

ALTER TABLE log_sheet_entry_revisions
    ADD CONSTRAINT ck_lser_entry_source
        CHECK (entry_source IS NULL
               OR entry_source IN ('WEB', 'PWA_NFC', 'PWA_QR', 'PWA_MANUAL'));
