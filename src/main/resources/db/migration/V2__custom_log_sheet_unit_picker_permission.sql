-- =============================================================================
-- Adds the permission for the new searchable operational-unit picker used when
-- creating a custom (template-less) log sheet. Mirrors the existing sibling
-- permission GET:/log-sheets/options/assets, which already backs the asset
-- picker in the same modal and is granted to the same roles.
--
-- V1 is frozen (see AGENTS.md §2) — this is the first migration after it.
-- =============================================================================

INSERT INTO permissions (code, name, category, http_method, endpoint_path) VALUES
('GET:/log-sheets/options/units', 'گزینه‌های واحد عملیاتی برای لاگ‌شیت سفارشی', 'operational', 'GET', '/log-sheets/options/units');

-- ADMIN and HIGH_USER get their blanket grants only once, at V1 apply time — a
-- new permission row added afterwards is never picked up retroactively, so it
-- must be granted explicitly here (see AGENTS.md gotcha #22).
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'ADMIN' AND p.code = 'GET:/log-sheets/options/units';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'HIGH_USER' AND p.code = 'GET:/log-sheets/options/units';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'SUPERVISOR' AND p.code = 'GET:/log-sheets/options/units';
