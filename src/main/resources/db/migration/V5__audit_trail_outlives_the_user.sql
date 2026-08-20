-- =============================================================================
-- V5 — the audit trail must outlive the user it describes
--
-- THE PROBLEM
--
-- `audit_log.actor_user_id` was ON DELETE RESTRICT, and audit rows are written ASYNCHRONOUSLY
-- (AuditWriteService, @Async on auditExecutor, REQUIRES_NEW). Those two facts together lose
-- audit history:
--
--   1. a user performs an action; the audit INSERT is queued
--   2. an administrator deletes that user
--   3. `UserService.hasAppActivity` asks `auditLogRepository.existsByActorUserId` — which sees
--      only rows already WRITTEN, not the one still sitting in the queue — and answers "no
--      activity", so the delete is allowed
--   4. the queued INSERT then fails on the foreign key, and the row is gone for good
--
-- Observed for real: the FK violation appears in the log of an integration test that deletes a
-- fixture user, while the test itself stays green — which is exactly how this would behave in
-- production, silently.
--
-- WHY ON DELETE SET NULL RATHER THAN A SYNCHRONOUS FLUSH
--
-- Draining the queue before every delete would fix step 3 and leave the shape of the problem
-- intact: the trail would still depend on a race being won. And it would couple deleting a user
-- to the health of the audit executor, which is the coupling that already cost this project an
-- import job stuck at RUNNING (see AsyncConfig's CallerRunsPolicy note).
--
-- The deeper point is that an audit row is a record of something that HAPPENED. It should not
-- be deletable — or fail to be writable — because the actor's account was later removed. The
-- row already carries `actor_username`, denormalised for precisely this reason: so the trail
-- stays readable when the user is gone or renamed. SET NULL makes the id follow the same rule
-- the username already followed.
--
-- log_sheet_action_log is left alone: its `actor_user_id` has no FK at all, so it never had
-- this failure mode.
--
-- WHAT THIS DOES NOT CHANGE
--
-- `UserService.delete` still refuses to remove a user with recorded activity — that guard is
-- about not orphaning meaningful history, and it stays. This migration is about the rows that
-- were in flight when the guard ran, which the guard could never have seen.
-- =============================================================================

ALTER TABLE audit_log
    DROP CONSTRAINT fk_audit_log_actor_user;

ALTER TABLE audit_log
    ADD CONSTRAINT fk_audit_log_actor_user
        FOREIGN KEY (actor_user_id) REFERENCES users (id) ON DELETE SET NULL;

COMMENT ON COLUMN audit_log.actor_user_id IS
    'Who made the change. ON DELETE SET NULL: an audit row records something that happened and '
    'must survive the removal of the account that did it. actor_username is denormalised '
    'alongside and stays readable after the id is cleared.';
