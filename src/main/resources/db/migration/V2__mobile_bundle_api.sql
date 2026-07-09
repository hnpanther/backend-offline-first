-- Mobile bundle API: lightweight bootstrap + per-log-sheet context bundles.

INSERT INTO permissions (code, name, category, http_method, endpoint_path) VALUES
('GET:/api/bootstrap', 'API — bootstrap اپ موبایل', 'api', 'GET', '/api/bootstrap'),
('GET:/api/log-sheets/{id}/bundle', 'API — بسته لاگ‌شیت', 'api', 'GET', '/api/log-sheets/{id}/bundle');

-- ADMIN — all permissions (including new ones via cross join is already done at seed;
-- for existing DBs, grant explicitly)
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'ADMIN'
  AND p.code IN ('GET:/api/bootstrap', 'GET:/api/log-sheets/{id}/bundle')
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'HIGH_USER'
  AND p.code IN ('GET:/api/bootstrap', 'GET:/api/log-sheets/{id}/bundle')
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'SUPERVISOR'
  AND p.code IN ('GET:/api/bootstrap', 'GET:/api/log-sheets/{id}/bundle')
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'OPERATOR'
  AND p.code IN ('GET:/api/bootstrap', 'GET:/api/log-sheets/{id}/bundle')
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'SENIOR_OPERATOR'
  AND p.code IN ('GET:/api/bootstrap', 'GET:/api/log-sheets/{id}/bundle')
ON CONFLICT (role_id, permission_id) DO NOTHING;
