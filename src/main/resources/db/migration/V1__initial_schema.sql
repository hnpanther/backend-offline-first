-- =============================================================================
-- backend-offline-first — consolidated schema (V1)
-- Includes: master data, operational data, users/org structure, RBAC permissions.
-- Permission model: {resource}.{action} where action is view|create|update|delete|import
-- =============================================================================

-- =============================================================================
-- users
-- Application users for admin panel login and field operations.
-- Fields: id (PK), username (unique), password_hash, full_name, active,
--         created_at, updated_at.
-- =============================================================================
CREATE TABLE users (
    id            VARCHAR(255) NOT NULL PRIMARY KEY,
    username      VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name     VARCHAR(255),
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    BIGINT,
    updated_at    BIGINT,
    CONSTRAINT uk_users_username UNIQUE (username)
);

-- =============================================================================
-- operational_units
-- Hierarchical operational units (org structure). Each unit may have sub-units.
-- Used to scope log-sheet visibility for USER role.
-- Fields: id (PK), code, name, parent_id (self-reference), created_at, updated_at.
-- =============================================================================
CREATE TABLE operational_units (
    id         VARCHAR(255) NOT NULL PRIMARY KEY,
    code       VARCHAR(255),
    name       VARCHAR(255),
    parent_id  VARCHAR(255),
    created_at BIGINT,
    updated_at BIGINT
);

-- =============================================================================
-- unit_supervisors / unit_operators
-- Many-to-many links between operational units and supervising/operator users.
-- =============================================================================
CREATE TABLE unit_supervisors (
    unit_id VARCHAR(255) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    PRIMARY KEY (unit_id, user_id)
);

CREATE TABLE unit_operators (
    unit_id VARCHAR(255) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    PRIMARY KEY (unit_id, user_id)
);

-- =============================================================================
-- permissions
-- Atomic access rights: one row per action (view/create/update/delete/import).
-- Assigned to roles via role_permissions; checked by Spring Security @PreAuthorize.
-- Fields: id (PK), code (unique, e.g. locations.view), name (Persian label), category.
-- =============================================================================
CREATE TABLE permissions (
    id            VARCHAR(255) NOT NULL PRIMARY KEY,
    code          VARCHAR(255) NOT NULL,
    name          VARCHAR(255),
    category      VARCHAR(255),
    http_method   VARCHAR(10),
    endpoint_path VARCHAR(512),
    CONSTRAINT uk_permissions_code UNIQUE (code)
);

-- =============================================================================
-- roles
-- Named permission groups. system_role=true roles (ADMIN/HIGH_USER/USER) are protected.
-- Fields: id (PK), code (unique), name, description, system_role, created_at, updated_at.
-- =============================================================================
CREATE TABLE roles (
    id          VARCHAR(255) NOT NULL PRIMARY KEY,
    code        VARCHAR(255) NOT NULL,
    name        VARCHAR(255),
    description VARCHAR(255),
    system_role BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  BIGINT,
    updated_at  BIGINT,
    CONSTRAINT uk_roles_code UNIQUE (code)
);

CREATE TABLE role_permissions (
    role_id       VARCHAR(255) NOT NULL,
    permission_id VARCHAR(255) NOT NULL,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE user_roles (
    user_id VARCHAR(255) NOT NULL,
    role_id VARCHAR(255) NOT NULL,
    PRIMARY KEY (user_id, role_id)
);

-- =============================================================================
-- locations
-- Hierarchical site/area master data. Each location belongs to one operational unit.
-- Fields: id (PK), code, name, parent_id, unit_id, created_at, updated_at.
-- =============================================================================
CREATE TABLE locations (
    id         VARCHAR(255) NOT NULL PRIMARY KEY,
    code       VARCHAR(255),
    name       VARCHAR(255),
    parent_id  VARCHAR(255),
    unit_id    VARCHAR(255),
    created_at BIGINT,
    updated_at BIGINT
);

-- =============================================================================
-- plant_systems
-- Physical or logical plant systems within a location.
-- Fields: id (PK), code, name, location_id, created_at, updated_at.
-- =============================================================================
CREATE TABLE plant_systems (
    id          VARCHAR(255) NOT NULL PRIMARY KEY,
    code        VARCHAR(255),
    name        VARCHAR(255),
    location_id VARCHAR(255),
    created_at  BIGINT,
    updated_at  BIGINT
);

-- =============================================================================
-- main_functions
-- Top-level functional groupings under a plant system.
-- Fields: id (PK), code, name, system_id, location_id, created_at, updated_at.
-- =============================================================================
CREATE TABLE main_functions (
    id          VARCHAR(255) NOT NULL PRIMARY KEY,
    code        VARCHAR(255),
    name        VARCHAR(255),
    system_id   VARCHAR(255),
    location_id VARCHAR(255),
    created_at  BIGINT,
    updated_at  BIGINT
);

-- =============================================================================
-- sub_functions
-- Granular functions/equipment groups under a main function.
-- Fields: id (PK), code, name, tag, main_function_id, system_id, location_id,
--         created_at, updated_at.
-- =============================================================================
CREATE TABLE sub_functions (
    id               VARCHAR(255) NOT NULL PRIMARY KEY,
    code             VARCHAR(255),
    name             VARCHAR(255),
    tag              VARCHAR(255),
    main_function_id VARCHAR(255),
    system_id        VARCHAR(255),
    location_id      VARCHAR(255),
    created_at       BIGINT,
    updated_at       BIGINT
);

-- =============================================================================
-- asset_classes / field_definitions
-- Asset type definitions and dynamic form field schema per class.
-- =============================================================================
CREATE TABLE asset_classes (
    id         VARCHAR(255) NOT NULL PRIMARY KEY,
    name       VARCHAR(255),
    fields     JSONB,
    created_at BIGINT,
    updated_at BIGINT
);

CREATE TABLE field_definitions (
    id         VARCHAR(255) NOT NULL PRIMARY KEY,
    class_id   VARCHAR(255),
    field_key  VARCHAR(255),
    label      VARCHAR(255),
    data_type  VARCHAR(255),
    unit       VARCHAR(255),
    required   BOOLEAN      NOT NULL,
    validation JSONB,
    sort_order INTEGER,
    version    INTEGER,
    deleted    BOOLEAN      NOT NULL,
    synced     BOOLEAN      NOT NULL,
    created_at BIGINT,
    updated_at BIGINT
);

-- =============================================================================
-- asset_entries
-- Physical assets tagged with NFC; resolved via GET /api/asset-entries/nfc/{nfcTagId}.
-- Fields: id (PK), nfc_tag_id (unique), class_id, asset_name, sub_function_id,
--         location, created_at, updated_at.
-- =============================================================================
CREATE TABLE asset_entries (
    id              VARCHAR(255) NOT NULL PRIMARY KEY,
    nfc_tag_id      VARCHAR(255),
    class_id        VARCHAR(255),
    asset_name      VARCHAR(255),
    sub_function_id VARCHAR(255),
    location        VARCHAR(255),
    created_at      BIGINT,
    updated_at      BIGINT,
    CONSTRAINT uk_asset_entries_nfc_tag_id UNIQUE (nfc_tag_id)
);

-- =============================================================================
-- data_records
-- Operator inspection records submitted from the offline mobile app.
-- Upserted in batch via POST /api/records/batch using local_id.
-- =============================================================================
CREATE TABLE data_records (
    id             VARCHAR(255) NOT NULL PRIMARY KEY,
    local_id       VARCHAR(255),
    nfc_tag_id     VARCHAR(255),
    asset_entry_id VARCHAR(255),
    asset_name     VARCHAR(255),
    asset_type_id  VARCHAR(255),
    record_status  VARCHAR(255),
    sync_status    VARCHAR(255),
    form_data      JSONB,
    notes          VARCHAR(255),
    operator_name  VARCHAR(255),
    location       VARCHAR(255),
    synced_at      BIGINT,
    sync_error     VARCHAR(255),
    created_at     BIGINT,
    updated_at     BIGINT,
    CONSTRAINT uk_data_records_local_id UNIQUE (local_id)
);

-- =============================================================================
-- log_sheet_templates
-- Reusable templates for batch/round log-sheet inspections.
-- =============================================================================
CREATE TABLE log_sheet_templates (
    id          VARCHAR(255) NOT NULL PRIMARY KEY,
    name        VARCHAR(255),
    description VARCHAR(255),
    scope_type  VARCHAR(255),
    scope_id    VARCHAR(255),
    created_at  BIGINT,
    updated_at  BIGINT
);

-- =============================================================================
-- log_sheets / log_sheet_entries
-- Submitted log-sheet headers and per-asset line items from mobile.
-- operational_unit_id scopes visibility for USER role.
-- =============================================================================
CREATE TABLE log_sheets (
    id                  VARCHAR(255) NOT NULL PRIMARY KEY,
    local_id            VARCHAR(255),
    template_id         VARCHAR(255),
    template_name       VARCHAR(255),
    scope_summary       VARCHAR(255),
    operator_name       VARCHAR(255),
    status              VARCHAR(255),
    sync_status         VARCHAR(255),
    submitted_at        BIGINT,
    synced_at           BIGINT,
    sync_error          VARCHAR(255),
    operational_unit_id VARCHAR(255),
    created_at          BIGINT,
    updated_at          BIGINT,
    CONSTRAINT uk_log_sheets_local_id UNIQUE (local_id)
);

CREATE TABLE log_sheet_entries (
    id                 VARCHAR(255) NOT NULL PRIMARY KEY,
    log_sheet_id       VARCHAR(255),
    asset_id           VARCHAR(255),
    asset_name         VARCHAR(255),
    sub_function_code  VARCHAR(255),
    sub_function_tag   VARCHAR(255),
    class_id           VARCHAR(255),
    form_data          JSONB
);

-- =============================================================================
-- Indexes — sync deltas, lookups, RBAC joins
-- =============================================================================
CREATE INDEX idx_locations_updated_at ON locations (updated_at);
CREATE INDEX idx_plant_systems_updated_at ON plant_systems (updated_at);
CREATE INDEX idx_main_functions_updated_at ON main_functions (updated_at);
CREATE INDEX idx_sub_functions_updated_at ON sub_functions (updated_at);
CREATE INDEX idx_asset_classes_updated_at ON asset_classes (updated_at);
CREATE INDEX idx_field_definitions_updated_at ON field_definitions (updated_at);
CREATE INDEX idx_asset_entries_updated_at ON asset_entries (updated_at);
CREATE INDEX idx_log_sheet_templates_updated_at ON log_sheet_templates (updated_at);
CREATE INDEX idx_field_definitions_class_id ON field_definitions (class_id);
CREATE INDEX idx_log_sheet_entries_log_sheet_id ON log_sheet_entries (log_sheet_id);
CREATE INDEX idx_operational_units_updated_at ON operational_units (updated_at);
CREATE INDEX idx_operational_units_parent_id ON operational_units (parent_id);
CREATE INDEX idx_locations_unit_id ON locations (unit_id);
CREATE INDEX idx_unit_supervisors_user_id ON unit_supervisors (user_id);
CREATE INDEX idx_unit_operators_user_id ON unit_operators (user_id);
CREATE INDEX idx_log_sheets_operational_unit_id ON log_sheets (operational_unit_id);
CREATE INDEX idx_user_roles_user_id ON user_roles (user_id);
CREATE INDEX idx_role_permissions_permission_id ON role_permissions (permission_id);

-- =============================================================================
-- Permissions seed — one row per HTTP endpoint (METHOD:path)
-- =============================================================================
INSERT INTO permissions (id, code, name, category, http_method, endpoint_path) VALUES
-- General
('perm-get-dashboard', 'GET:/', 'داشبورد', 'general', 'GET', '/'),
-- Users
('perm-get-users', 'GET:/users', 'لیست کاربران', 'admin', 'GET', '/users'),
('perm-post-users', 'POST:/users', 'ایجاد کاربر', 'admin', 'POST', '/users'),
('perm-post-users-id', 'POST:/users/{id}', 'ویرایش کاربر', 'admin', 'POST', '/users/{id}'),
('perm-post-users-pwd', 'POST:/users/{id}/change-password', 'تغییر رمز کاربر', 'admin', 'POST', '/users/{id}/change-password'),
('perm-post-users-del', 'POST:/users/{id}/delete', 'حذف کاربر', 'admin', 'POST', '/users/{id}/delete'),
-- Roles
('perm-get-roles', 'GET:/roles', 'لیست نقش‌ها', 'admin', 'GET', '/roles'),
('perm-post-roles', 'POST:/roles', 'ایجاد نقش', 'admin', 'POST', '/roles'),
('perm-post-roles-id', 'POST:/roles/{id}', 'ویرایش نقش', 'admin', 'POST', '/roles/{id}'),
('perm-post-roles-del', 'POST:/roles/{id}/delete', 'حذف نقش', 'admin', 'POST', '/roles/{id}/delete'),
-- Settings
('perm-get-settings', 'GET:/settings', 'تنظیمات برنامه', 'admin', 'GET', '/settings'),
-- Operational units
('perm-get-op-units', 'GET:/operational-units', 'لیست واحدهای عملیاتی', 'organization', 'GET', '/operational-units'),
('perm-post-op-units', 'POST:/operational-units', 'ایجاد واحد عملیاتی', 'organization', 'POST', '/operational-units'),
('perm-post-op-units-id', 'POST:/operational-units/{id}', 'ویرایش واحد عملیاتی', 'organization', 'POST', '/operational-units/{id}'),
('perm-post-op-units-del', 'POST:/operational-units/{id}/delete', 'حذف واحد عملیاتی', 'organization', 'POST', '/operational-units/{id}/delete'),
-- Locations
('perm-get-locations', 'GET:/locations', 'لیست مکان‌ها', 'master-data', 'GET', '/locations'),
('perm-post-locations', 'POST:/locations', 'ایجاد مکان', 'master-data', 'POST', '/locations'),
('perm-post-locations-id', 'POST:/locations/{id}', 'ویرایش مکان', 'master-data', 'POST', '/locations/{id}'),
('perm-post-locations-del', 'POST:/locations/{id}/delete', 'حذف مکان', 'master-data', 'POST', '/locations/{id}/delete'),
('perm-post-locations-imp', 'POST:/locations/import', 'ورود مکان از اکسل', 'master-data', 'POST', '/locations/import'),
('perm-get-locations-tpl', 'GET:/locations/import-template', 'دانلود قالب اکسل مکان', 'master-data', 'GET', '/locations/import-template'),
-- Plant systems
('perm-get-plant-sys', 'GET:/plant-systems', 'لیست سیستم‌های واحد', 'master-data', 'GET', '/plant-systems'),
('perm-post-plant-sys', 'POST:/plant-systems', 'ایجاد سیستم واحد', 'master-data', 'POST', '/plant-systems'),
('perm-post-plant-sys-id', 'POST:/plant-systems/{id}', 'ویرایش سیستم واحد', 'master-data', 'POST', '/plant-systems/{id}'),
('perm-post-plant-sys-del', 'POST:/plant-systems/{id}/delete', 'حذف سیستم واحد', 'master-data', 'POST', '/plant-systems/{id}/delete'),
('perm-post-plant-sys-imp', 'POST:/plant-systems/import', 'ورود سیستم واحد از اکسل', 'master-data', 'POST', '/plant-systems/import'),
('perm-get-plant-sys-tpl', 'GET:/plant-systems/import-template', 'دانلود قالب اکسل سیستم واحد', 'master-data', 'GET', '/plant-systems/import-template'),
-- Main functions
('perm-get-main-fn', 'GET:/main-functions', 'لیست توابع اصلی', 'master-data', 'GET', '/main-functions'),
('perm-post-main-fn', 'POST:/main-functions', 'ایجاد تابع اصلی', 'master-data', 'POST', '/main-functions'),
('perm-post-main-fn-id', 'POST:/main-functions/{id}', 'ویرایش تابع اصلی', 'master-data', 'POST', '/main-functions/{id}'),
('perm-post-main-fn-del', 'POST:/main-functions/{id}/delete', 'حذف تابع اصلی', 'master-data', 'POST', '/main-functions/{id}/delete'),
('perm-post-main-fn-imp', 'POST:/main-functions/import', 'ورود تابع اصلی از اکسل', 'master-data', 'POST', '/main-functions/import'),
('perm-get-main-fn-tpl', 'GET:/main-functions/import-template', 'دانلود قالب اکسل تابع اصلی', 'master-data', 'GET', '/main-functions/import-template'),
-- Sub functions
('perm-get-sub-fn', 'GET:/sub-functions', 'لیست توابع فرعی', 'master-data', 'GET', '/sub-functions'),
('perm-post-sub-fn', 'POST:/sub-functions', 'ایجاد تابع فرعی', 'master-data', 'POST', '/sub-functions'),
('perm-post-sub-fn-id', 'POST:/sub-functions/{id}', 'ویرایش تابع فرعی', 'master-data', 'POST', '/sub-functions/{id}'),
('perm-post-sub-fn-del', 'POST:/sub-functions/{id}/delete', 'حذف تابع فرعی', 'master-data', 'POST', '/sub-functions/{id}/delete'),
('perm-post-sub-fn-imp', 'POST:/sub-functions/import', 'ورود تابع فرعی از اکسل', 'master-data', 'POST', '/sub-functions/import'),
('perm-get-sub-fn-tpl', 'GET:/sub-functions/import-template', 'دانلود قالب اکسل تابع فرعی', 'master-data', 'GET', '/sub-functions/import-template'),
-- Asset classes
('perm-get-asset-cls', 'GET:/asset-classes', 'لیست کلاس‌های دارایی', 'master-data', 'GET', '/asset-classes'),
('perm-post-asset-cls', 'POST:/asset-classes', 'ایجاد کلاس دارایی', 'master-data', 'POST', '/asset-classes'),
('perm-post-asset-cls-id', 'POST:/asset-classes/{id}', 'ویرایش کلاس دارایی', 'master-data', 'POST', '/asset-classes/{id}'),
('perm-post-asset-cls-del', 'POST:/asset-classes/{id}/delete', 'حذف کلاس دارایی', 'master-data', 'POST', '/asset-classes/{id}/delete'),
('perm-get-asset-fields', 'GET:/asset-classes/{classId}/fields', 'لیست فیلدهای کلاس', 'master-data', 'GET', '/asset-classes/{classId}/fields'),
('perm-post-asset-fields', 'POST:/asset-classes/{classId}/fields', 'افزودن فیلد به کلاس', 'master-data', 'POST', '/asset-classes/{classId}/fields'),
('perm-post-asset-field-id', 'POST:/asset-classes/{classId}/fields/{fieldId}', 'ویرایش فیلد کلاس', 'master-data', 'POST', '/asset-classes/{classId}/fields/{fieldId}'),
('perm-post-asset-field-del', 'POST:/asset-classes/{classId}/fields/{fieldId}/delete', 'حذف فیلد کلاس', 'master-data', 'POST', '/asset-classes/{classId}/fields/{fieldId}/delete'),
-- Asset entries
('perm-get-asset-ent', 'GET:/asset-entries', 'لیست دارایی‌ها', 'master-data', 'GET', '/asset-entries'),
('perm-post-asset-ent', 'POST:/asset-entries', 'ایجاد دارایی', 'master-data', 'POST', '/asset-entries'),
('perm-post-asset-ent-id', 'POST:/asset-entries/{id}', 'ویرایش دارایی', 'master-data', 'POST', '/asset-entries/{id}'),
('perm-post-asset-ent-del', 'POST:/asset-entries/{id}/delete', 'حذف دارایی', 'master-data', 'POST', '/asset-entries/{id}/delete'),
('perm-post-asset-ent-imp', 'POST:/asset-entries/import', 'ورود دارایی از اکسل', 'master-data', 'POST', '/asset-entries/import'),
('perm-get-asset-ent-tpl', 'GET:/asset-entries/import-template', 'دانلود قالب اکسل دارایی', 'master-data', 'GET', '/asset-entries/import-template'),
-- Log sheet templates
('perm-get-lst', 'GET:/log-sheet-templates', 'لیست قالب‌های لاگ', 'master-data', 'GET', '/log-sheet-templates'),
('perm-post-lst', 'POST:/log-sheet-templates', 'ایجاد قالب لاگ', 'master-data', 'POST', '/log-sheet-templates'),
('perm-post-lst-id', 'POST:/log-sheet-templates/{id}', 'ویرایش قالب لاگ', 'master-data', 'POST', '/log-sheet-templates/{id}'),
('perm-post-lst-del', 'POST:/log-sheet-templates/{id}/delete', 'حذف قالب لاگ', 'master-data', 'POST', '/log-sheet-templates/{id}/delete'),
-- Operational data
('perm-get-records', 'GET:/records', 'لیست رکوردها', 'operational', 'GET', '/records'),
('perm-get-records-id', 'GET:/records/{id}', 'جزئیات رکورد', 'operational', 'GET', '/records/{id}'),
('perm-get-log-sheets', 'GET:/log-sheets', 'لیست لاگ‌شیت‌ها', 'operational', 'GET', '/log-sheets'),
('perm-get-log-sheets-id', 'GET:/log-sheets/{id}', 'جزئیات لاگ‌شیت', 'operational', 'GET', '/log-sheets/{id}'),
('perm-get-reports', 'GET:/reports', 'گزارش‌گیری', 'reports', 'GET', '/reports'),
-- Mobile API
('perm-get-api-md', 'GET:/api/master-data', 'API — دریافت داده پایه', 'api', 'GET', '/api/master-data'),
('perm-post-api-rec', 'POST:/api/records/batch', 'API — ارسال رکورد', 'api', 'POST', '/api/records/batch'),
('perm-post-api-ls', 'POST:/api/log-sheets/batch', 'API — ارسال لاگ‌شیت', 'api', 'POST', '/api/log-sheets/batch'),
('perm-get-api-nfc', 'GET:/api/asset-entries/nfc/{nfcTagId}', 'API — جستجوی NFC', 'api', 'GET', '/api/asset-entries/nfc/{nfcTagId}');

-- =============================================================================
-- Default system roles
-- ADMIN     — all permissions
-- HIGH_USER — all except admin category (users, roles, settings)
-- USER      — log sheet view/create + required API; scoped by operational unit
-- =============================================================================
INSERT INTO roles (id, code, name, description, system_role, created_at, updated_at) VALUES
('role-admin',     'ADMIN',     'مدیر سیستم',   'دسترسی کامل به همه بخش‌ها',                    TRUE, 0, 0),
('role-high-user', 'HIGH_USER', 'کاربر ارشد',   'همه دسترسی‌ها به‌جز مدیریت کاربران و تنظیمات', TRUE, 0, 0),
('role-user',      'USER',      'اپراتور',      'مشاهده و ثبت لاگ‌شیت در محدوده واحد عملیاتی', TRUE, 0, 0);

INSERT INTO role_permissions (role_id, permission_id)
SELECT 'role-admin', id FROM permissions;

INSERT INTO role_permissions (role_id, permission_id)
SELECT 'role-high-user', id FROM permissions
WHERE category <> 'admin';

INSERT INTO role_permissions (role_id, permission_id)
SELECT 'role-user', id FROM permissions
WHERE code IN ('GET:/log-sheets', 'GET:/log-sheets/{id}', 'GET:/api/master-data', 'POST:/api/log-sheets/batch');
