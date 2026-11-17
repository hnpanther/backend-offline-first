-- =============================================================================
-- V24 — how long a round stays on the tablet (roadmap §41)
--
-- Three settings the server owns and every tablet follows. They reach the device on
-- /api/bootstrap (`deviceRetention`), and the PWA's local purge (`cleanupLogSheets.ts`) reads
-- them instead of the constants it used to have compiled in. Nothing on the server is purged with
-- them: they govern the device's own copy only.
--
--   device.synced_retention_hours    24     1 – 720    a round the server accepted, kept as history
--   device.unsynced_purge_enabled    false  —          whether a round that never reached the
--                                                      server is ever purged automatically
--   device.unsynced_retention_hours  168    72 – 8760  used only when the switch above is on
--
-- No table changes. `app_settings` is key/value and `AppSettingsService` already falls back to the
-- same defaults when a row is missing, so these rows are not needed for the feature to work. They
-- are seeded for the reason V3 gives: `SELECT * FROM app_settings` should answer "what are the
-- tablets running under" without reading Java, and a pg_dump should carry an answer rather than an
-- absence.
--
-- THE SWITCH IS SEEDED OFF, and that is a behaviour change for the tablets: before this, a round
-- the server refused was deleted from the device after 7 days and an expired draft 24 hours after
-- its deadline — both of them rounds whose readings had never reached the server (PWA issue P-01).
-- Off keeps them until they are delivered or the device is cleared.
--
-- ON CONFLICT DO NOTHING, as every seed here: an installation that has already saved these from
-- the Settings page keeps what the administrator chose.
-- =============================================================================

INSERT INTO app_settings (setting_key, value, updated_at) VALUES
('device.synced_retention_hours', '24', 0),
('device.unsynced_purge_enabled', 'false', 0),
('device.unsynced_retention_hours', '168', 0)
ON CONFLICT (setting_key) DO NOTHING;
