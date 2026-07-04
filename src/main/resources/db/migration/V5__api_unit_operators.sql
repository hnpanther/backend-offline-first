-- Mobile API: list unit operators for supervisor assign UI
INSERT INTO permissions (code, name, category, http_method, endpoint_path) VALUES
('GET:/api/operational-units/{unitId}/operators', 'API — اپراتورهای واحد', 'api', 'GET', '/api/operational-units/{unitId}/operators')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r, permissions p
WHERE r.code IN ('SUPERVISOR', 'ADMIN', 'HIGH_USER')
  AND p.code = 'GET:/api/operational-units/{unitId}/operators'
ON CONFLICT DO NOTHING;
