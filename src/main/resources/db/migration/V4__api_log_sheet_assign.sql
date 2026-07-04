-- Mobile API: supervisor assign / reassign (online-only from app)
INSERT INTO permissions (code, name, category, http_method, endpoint_path) VALUES
('POST:/api/log-sheets/{id}/assign', 'API — انتساب لاگ‌شیت', 'api', 'POST', '/api/log-sheets/{id}/assign'),
('POST:/api/log-sheets/{id}/reassign', 'API — بازانتساب لاگ‌شیت', 'api', 'POST', '/api/log-sheets/{id}/reassign')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r, permissions p
WHERE r.code IN ('SUPERVISOR', 'ADMIN', 'HIGH_USER')
  AND p.code IN (
    'POST:/api/log-sheets/{id}/assign',
    'POST:/api/log-sheets/{id}/reassign'
  )
ON CONFLICT DO NOTHING;
