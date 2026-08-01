-- =============================================================================
-- V5 — Log sheet cancel/reopen
--
-- Activates the previously-reserved LogSheetStatus.CANCELLED value: a supervisor
-- or admin may now cancel a sheet that is still open (PENDING/ASSIGNED/IN_PROGRESS
-- — i.e. before completion or expiry), and later reopen it via the existing
-- extend flow by supplying a new future due date (same pattern already used to
-- reopen an EXPIRED sheet).
--
-- cancelled_at mirrors the existing expired_at column: set when the sheet is
-- cancelled, cleared when it is reopened.
-- =============================================================================
ALTER TABLE log_sheets ADD COLUMN cancelled_at BIGINT;

INSERT INTO permissions (code, name, category, http_method, endpoint_path) VALUES
('POST:/log-sheets/{id}/cancel', 'لغو لاگ‌شیت', 'operational', 'POST', '/log-sheets/{id}/cancel');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code IN ('ADMIN', 'HIGH_USER', 'SUPERVISOR') AND p.code = 'POST:/log-sheets/{id}/cancel';
