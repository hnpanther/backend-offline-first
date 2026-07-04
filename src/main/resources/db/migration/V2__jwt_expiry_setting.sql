INSERT INTO app_settings (setting_key, value, updated_at)
VALUES ('auth.jwt.expiry_minutes', '480', 0)
ON CONFLICT (setting_key) DO NOTHING;
