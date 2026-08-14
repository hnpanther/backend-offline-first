-- =============================================================================
-- V2 — asset status reading time, and an import-job heartbeat
--
-- From this migration onward, schema changes go in their own versioned file rather than
-- being folded back into V1. V1 is now treated as the established baseline: editing it
-- again would change its checksum against every database that has already run it.
--
-- This file carries two unrelated changes because they were consolidated while the schema
-- was still only on the development database. Do NOT keep merging into it now that it has
-- been applied — the next change is V3.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. asset_status_change_requests: when the reading was actually taken
-- -----------------------------------------------------------------------------

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


-- -----------------------------------------------------------------------------
-- 2. import_jobs: a heartbeat, so a wedged import can be detected without a restart
-- -----------------------------------------------------------------------------

-- Until this column existed the only thing that ever cleared a stuck import was
-- ImportJobRecoveryRunner at boot. A job whose worker thread died — killed by an audit-queue
-- rejection thrown out of the failure handler itself, or by an OutOfMemoryError that
-- `catch (Exception)` does not catch — sat at RUNNING forever. Stop could not touch it (the
-- cancel flag is only read from inside the running thread), Delete refused it ("Stop the
-- import job before deleting it"), and assertNoActiveImport() then blocked every future
-- import for every user. The documented remedy was to restart the application.
--
-- A liveness signal is what makes that decidable. Progress alone is not enough: a job that
-- dies on its very first row never advances processed_rows, and a job legitimately parked on
-- a slow row looks identical to a dead one if you only compare row counts.

-- Last time the worker thread proved it was alive (epoch millis).
--
-- Written when the job is marked RUNNING and refreshed on every progress tick (every 25
-- rows, ImportProgressListener). ImportJobWatchdog fails jobs whose heartbeat is older than
-- app.import.stale-timeout-minutes.
--
-- Nullable: jobs that predate this column have none, and the watchdog falls back to
-- started_at for those — an already-finished job is unaffected either way.
ALTER TABLE import_jobs ADD COLUMN heartbeat_at BIGINT;

-- The watchdog scans by status; RUNNING is a handful of rows at most, but this keeps the
-- once-a-minute scan off a sequential scan of the whole job history.
CREATE INDEX ix_import_jobs_status_heartbeat ON import_jobs (status, heartbeat_at);
