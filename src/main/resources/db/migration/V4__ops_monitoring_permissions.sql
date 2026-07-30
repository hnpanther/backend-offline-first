-- =============================================================================
-- V4 — Ops monitoring permissions (Actuator + OpenAPI docs)
--
-- spring-boot-starter-actuator exposes /actuator/health and /actuator/metrics.
-- The liveness/readiness probe sub-paths stay public (permitAll in
-- WebSecurityConfig) for load-balancer / watchdog checks and report status only
-- (no component detail). Everything else under /actuator/** is gated behind
-- GET:/actuator/**, granted only to ADMIN — same pattern as V2/V3's session-admin
-- pages.
--
-- springdoc-openapi is enabled in every environment (including production).
-- Access to the spec (/v3/api-docs/**) and the UI (/swagger-ui/**,
-- /swagger-ui.html) is gated behind GET:/v3/api-docs/**, also ADMIN only.
-- =============================================================================
INSERT INTO permissions (code, name, category, http_method, endpoint_path) VALUES
('GET:/actuator/**', 'مانیتورینگ سیستم (Actuator)', 'admin', 'GET', '/actuator/**'),
('GET:/v3/api-docs/**', 'مستندات API (Swagger)', 'admin', 'GET', '/v3/api-docs/**');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'ADMIN' AND p.code IN (
    'GET:/actuator/**',
    'GET:/v3/api-docs/**'
);
