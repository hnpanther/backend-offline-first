-- =============================================================================
-- V2 — asset status change requests: when the reading was actually taken
--
-- From this migration onward, schema changes go in their own versioned file rather than
-- being folded back into V1. V1 is now treated as the established baseline: editing it
-- again would change its checksum against every database that has already run it.
-- =============================================================================

-- When the operator actually recorded the reading on the log sheet (device time), or the
-- filing time for a request a supervisor raised by hand.
--
-- On approval this becomes asset_status_history.changed_at, so the asset timeline says when
-- the equipment was OBSERVED rather than when a supervisor got round to signing it off. A
-- status noted at 08:15 and approved at 16:40 belongs at 08:15 — otherwise every asset's
-- history bunches up around review times and stops lining up with the rounds that produced it.
--
-- Nullable: requests raised before this column existed have no reading time to recover, and
-- approval falls back to the request's own timestamp for those.
ALTER TABLE asset_status_change_requests ADD COLUMN reading_recorded_at BIGINT;
