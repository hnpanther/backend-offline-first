-- =============================================================================
-- V3 — Web panel session management permissions
--
-- The web panel now enforces one concurrent session per user and exposes an
-- admin page (/web-sessions) that lists live form-login sessions and can expire
-- them. Sessions themselves live in Spring Security's in-memory SessionRegistry
-- (no table needed — web sessions are intentionally non-persistent across
-- restarts); this migration only seeds the RBAC permissions.
--
-- Category 'admin' keeps them out of the HIGH_USER category-wide grant, so only
-- ADMIN receives them (granted explicitly below).
-- =============================================================================
INSERT INTO permissions (code, name, category, http_method, endpoint_path) VALUES
('GET:/web-sessions', 'لیست نشست‌های وب', 'admin', 'GET', '/web-sessions'),
('POST:/web-sessions/{key}/expire', 'ابطال نشست وب', 'admin', 'POST', '/web-sessions/{key}/expire');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'ADMIN' AND p.code IN (
    'GET:/web-sessions',
    'POST:/web-sessions/{key}/expire'
);
