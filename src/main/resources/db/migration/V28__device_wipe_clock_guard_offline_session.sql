-- =============================================================================
-- V28 — wiping a revoked tablet, the device clock at completion, and a ceiling on how long a
--        session may keep working without reaching the server
--
-- Three changes, one release. All additive: no existing row is rewritten, no column changes
-- type, and every new column is nullable, so a server rolled back to V27's jar still boots
-- against this schema (it simply never reads them).
--
-- 1. WIPE ON REVOKE (roadmap §42, «wipe on revoke»; security.md §3c)
--
--    An administrator who revokes a device may also order the tablet to delete everything the
--    app holds on it. The order is delivered the next time the tablet reaches the server — in the
--    answer to /api/auth/challenge, the one call a tablet makes with its device identity and
--    without a session — and the tablet confirms it, signed with its own key, once its data is
--    gone. Four columns record the order and what became of it:
--
--      wipe_requested_at / wipe_requested_by   who ordered it, when (the order's identity: the
--                                              tablet signs this timestamp back)
--      wipe_signalled_at                       first time the server handed the order to the
--                                              tablet; from then on it cannot be cancelled
--      wipe_confirmed_at                       the tablet's signed confirmation arrived
--
--    The CHECKs state the shape: an order exists only on a REVOKED device, always names who
--    gave it, and nothing can be signalled or confirmed that was never ordered.
--
-- 2. THE DEVICE CLOCK AT COMPLETION (issue.md B-17; the PWA's roadmap §7)
--
--    `completed_at` is the tablet's word, and it decides the deadline. The server now refuses to
--    store a completion time in its own future (beyond a small tolerance): such a time is stored
--    as the server's receipt time instead, and what the tablet claimed is kept beside it in
--    `completed_at_reported`, on the round and on a void submission alike — the evidence a
--    supervisor needs when a round was refused as late because a tablet's clock ran fast.
--    `client_clock_offset_ms` is the correction the app itself applied (server minus device, as
--    it last measured); NULL from an app too old to send it.
--
-- 3. THE OFFLINE SESSION CEILING (security.md, «A session that cannot reach the server»)
--
--    Two app_settings rows, delivered on /api/bootstrap as `offlineSession`. The server never
--    acts on them: they tell the tablet how long a signed-in session may keep working without
--    any answer from the server before the app locks and asks for the password again. Seeded
--    OFF: a lock costs nothing in data, but switching it on is a decision about how long an
--    operator in a dead zone may keep working, and that is the plant's to take.
--
-- The two new permissions are granted derived from POST:/devices/{id}/revoke, not to a list of
-- role codes — the reason V7 gives: a duplicated role gets a NEW code and the same permissions.
-- =============================================================================

-- ---------------------------------------------------------------------------- 1. devices
ALTER TABLE devices
    ADD COLUMN wipe_requested_at BIGINT,
    ADD COLUMN wipe_requested_by BIGINT,
    ADD COLUMN wipe_signalled_at BIGINT,
    ADD COLUMN wipe_confirmed_at BIGINT;

ALTER TABLE devices
    ADD CONSTRAINT fk_devices_wipe_requested_by FOREIGN KEY (wipe_requested_by)
        REFERENCES users (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_devices_wipe_only_when_revoked
        CHECK (wipe_requested_at IS NULL OR status = 'REVOKED'),
    ADD CONSTRAINT ck_devices_wipe_attributed
        CHECK ((wipe_requested_at IS NULL) = (wipe_requested_by IS NULL)),
    ADD CONSTRAINT ck_devices_wipe_signal_needs_order
        CHECK (wipe_signalled_at IS NULL OR wipe_requested_at IS NOT NULL),
    ADD CONSTRAINT ck_devices_wipe_confirm_needs_order
        CHECK (wipe_confirmed_at IS NULL OR wipe_requested_at IS NOT NULL);

INSERT INTO permissions (code, name, category, http_method, endpoint_path) VALUES
('POST:/devices/{id}/wipe',
 'دستور پاک‌سازی داده‌های دستگاه لغوشده',
 'admin',
 'POST',
 '/devices/{id}/wipe'),
('POST:/devices/{id}/wipe/cancel',
 'لغو دستور پاک‌سازی دستگاه (پیش از ابلاغ به دستگاه)',
 'admin',
 'POST',
 '/devices/{id}/wipe/cancel');

INSERT INTO role_permissions (role_id, permission_id)
SELECT rp.role_id, wipe.id
  FROM role_permissions rp
  JOIN permissions revoke ON revoke.id = rp.permission_id
                         AND revoke.code = 'POST:/devices/{id}/revoke'
 CROSS JOIN permissions wipe
 WHERE wipe.code IN ('POST:/devices/{id}/wipe', 'POST:/devices/{id}/wipe/cancel')
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- ---------------------------------------------------------------------------- 2. the clock
ALTER TABLE log_sheets
    ADD COLUMN completed_at_reported  BIGINT,
    ADD COLUMN client_clock_offset_ms BIGINT;

ALTER TABLE log_sheet_void_submissions
    ADD COLUMN completed_at_reported  BIGINT,
    ADD COLUMN client_clock_offset_ms BIGINT;

-- ---------------------------------------------------------------------------- 3. settings
INSERT INTO app_settings (setting_key, value, updated_at) VALUES
('device.offline_session_limit_enabled', 'false', 0),
('device.offline_session_limit_hours', '72', 0)
ON CONFLICT (setting_key) DO NOTHING;
