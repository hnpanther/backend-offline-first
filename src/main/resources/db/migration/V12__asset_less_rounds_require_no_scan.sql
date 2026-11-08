-- =============================================================================
-- V12 — a round with no equipment cannot require a scan
--
-- V11 gave every existing template and every existing round `scan_mode = 'NFC'`, which was the
-- right default for the rounds that had assets: that is what they already were. It was wrong
-- for the ones that have none.
--
-- An asset-less round (V10) is a questionnaire, a handover, an area check. There is no tag and
-- no sticker, because there is no equipment. Telling the tablet to require an NFC scan on one
-- puts a «شروع اسکن NFC» card in front of an operator with nothing to point it at, and no way
-- to open the form — which is exactly what it did.
--
-- `CustomLogSheetService` and `LogSheetTemplateService` already force NONE for anything created
-- from now on. This is the backfill for everything created before them.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. Templates that declare they carry no equipment
--
-- `asset_selection_mode = 'NONE'` is a recorded intent (V10), so this is unambiguous: the
-- template says it has no assets, therefore it has nothing to scan.
-- -----------------------------------------------------------------------------
UPDATE log_sheet_templates
   SET scan_mode = 'NONE'
 WHERE asset_selection_mode = 'NONE'
   AND scan_mode <> 'NONE';


-- -----------------------------------------------------------------------------
-- 2. Rounds that have no entries
--
-- Keyed on the entries themselves rather than on the template, because a CUSTOM sheet has no
-- template to ask and is exactly the case this feature was built for. A log sheet's entries are
-- written once, when it is raised, and nothing deletes them — so "no entries" means "was raised
-- with no equipment", not "lost its equipment somewhere".
--
-- FINISHED ROUNDS ARE INCLUDED, and that is deliberate. `scan_mode` on a sheet is normally
-- frozen history and must not be rewritten — but a finished asset-less round saying it required
-- an NFC scan is not history, it is a default that was never true of it. Correcting it makes
-- the record honest; leaving it would have the detail page state a requirement the round could
-- never have met.
-- -----------------------------------------------------------------------------
UPDATE log_sheets s
   SET scan_mode = 'NONE'
 WHERE s.scan_mode <> 'NONE'
   AND NOT EXISTS (SELECT 1 FROM log_sheet_entries e WHERE e.log_sheet_id = s.id);
