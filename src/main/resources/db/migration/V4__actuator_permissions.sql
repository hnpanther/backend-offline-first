-- =============================================================================
-- V4 — Actuator (ops monitoring) permission
--
-- spring-boot-starter-actuator exposes /actuator/health and /actuator/metrics.
-- The liveness/readiness probe sub-paths stay public (permitAll in
-- WebSecurityConfig) for load-balancer / watchdog checks and report status only
-- (no component detail). Everything else under /actuator/** is gated behind
-- this permission, granted only to ADMIN — same pattern as V2/V3's session-admin
-- pages.
-- =============================================================================
INSERT INTO permissions (code, name, category, http_method, endpoint_path) VALUES
('GET:/actuator/**', 'مانیتورینگ سیستم (Actuator)', 'admin', 'GET', '/actuator/**');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'ADMIN' AND p.code = 'GET:/actuator/**';
