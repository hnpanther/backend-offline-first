package com.hnp.backendofflinefirst.security;

/**
 * Permission codes — one per HTTP endpoint ({@code METHOD:path}).
 * <p>
 * Example: {@code GET:/locations} guards the list page;
 * {@code POST:/locations/{id}/delete} guards delete action.
 * Used in {@code @PreAuthorize("hasAuthority('GET:/locations')")} and seeded in {@code permissions}.
 */
public final class PermissionCodes {

    private PermissionCodes() {}

    /** Builds the authority string stored in DB and checked by Spring Security. */
    public static String code(String method, String path) {
        return method + ":" + path;
    }

    // ── General ───────────────────────────────────────────────────────────────
    public static final String GET_DASHBOARD = code("GET", "/");

    // ── Users (admin) ───────────────────────────────────────────────────────────
    public static final String GET_USERS = code("GET", "/users");
    public static final String POST_USERS = code("POST", "/users");
    public static final String POST_USERS_ID = code("POST", "/users/{id}");
    public static final String POST_USERS_CHANGE_PASSWORD = code("POST", "/users/{id}/change-password");
    public static final String POST_USERS_DELETE = code("POST", "/users/{id}/delete");

    // ── Roles (admin) ───────────────────────────────────────────────────────────
    public static final String GET_ROLES = code("GET", "/roles");
    public static final String POST_ROLES = code("POST", "/roles");
    public static final String POST_ROLES_ID = code("POST", "/roles/{id}");
    public static final String POST_ROLES_DELETE = code("POST", "/roles/{id}/delete");

    // ── Settings (admin) ───────────────────────────────────────────────────────
    public static final String GET_SETTINGS = code("GET", "/settings");

    // ── Mobile API sessions (admin) ────────────────────────────────────────────
    public static final String GET_API_SESSIONS = code("GET", "/api-sessions");
    public static final String POST_API_SESSIONS_REVOKE = code("POST", "/api-sessions/{id}/revoke");
    public static final String POST_API_SESSIONS_REVOKE_USER = code("POST", "/api-sessions/revoke-user/{userId}");

    // ── Web panel sessions (admin) ─────────────────────────────────────────────
    public static final String GET_WEB_SESSIONS = code("GET", "/web-sessions");
    public static final String POST_WEB_SESSIONS_EXPIRE = code("POST", "/web-sessions/{key}/expire");

    // ── Login-attempt throttle (admin) ─────────────────────────────────────────
    public static final String GET_LOGIN_ATTEMPTS = code("GET", "/login-attempts");
    public static final String POST_LOGIN_ATTEMPTS_UNLOCK = code("POST", "/login-attempts/{username}/unlock");

    // ── Operational units ───────────────────────────────────────────────────────
    public static final String GET_OPERATIONAL_UNITS = code("GET", "/operational-units");
    public static final String POST_OPERATIONAL_UNITS = code("POST", "/operational-units");
    public static final String POST_OPERATIONAL_UNITS_ID = code("POST", "/operational-units/{id}");
    public static final String POST_OPERATIONAL_UNITS_DELETE = code("POST", "/operational-units/{id}/delete");
    public static final String POST_OPERATIONAL_UNITS_IMPORT = code("POST", "/operational-units/import");
    public static final String POST_OPERATIONAL_UNITS_IMPORT_STAFF = code("POST", "/operational-units/import-staff");

    // ── Batch import ────────────────────────────────────────────────────────────
    public static final String GET_BATCH_IMPORT = code("GET", "/batch-import");
    public static final String POST_BATCH_IMPORT = code("POST", "/batch-import");
    public static final String GET_BATCH_IMPORT_JOBS = code("GET", "/batch-import/jobs");

    // ── Locations ─────────────────────────────────────────────────────────────
    public static final String GET_LOCATIONS = code("GET", "/locations");
    public static final String POST_LOCATIONS = code("POST", "/locations");
    public static final String POST_LOCATIONS_ID = code("POST", "/locations/{id}");
    public static final String POST_LOCATIONS_DELETE = code("POST", "/locations/{id}/delete");
    public static final String POST_LOCATIONS_IMPORT = code("POST", "/locations/import");
    public static final String GET_LOCATIONS_IMPORT_TEMPLATE = code("GET", "/locations/import-template");

    // ── Plant systems ───────────────────────────────────────────────────────────
    public static final String GET_PLANT_SYSTEMS = code("GET", "/plant-systems");
    public static final String POST_PLANT_SYSTEMS = code("POST", "/plant-systems");
    public static final String POST_PLANT_SYSTEMS_ID = code("POST", "/plant-systems/{id}");
    public static final String POST_PLANT_SYSTEMS_DELETE = code("POST", "/plant-systems/{id}/delete");
    public static final String POST_PLANT_SYSTEMS_IMPORT = code("POST", "/plant-systems/import");
    public static final String GET_PLANT_SYSTEMS_IMPORT_TEMPLATE = code("GET", "/plant-systems/import-template");

    // ── Main functions ──────────────────────────────────────────────────────────
    public static final String GET_MAIN_FUNCTIONS = code("GET", "/main-functions");
    public static final String POST_MAIN_FUNCTIONS = code("POST", "/main-functions");
    public static final String POST_MAIN_FUNCTIONS_ID = code("POST", "/main-functions/{id}");
    public static final String POST_MAIN_FUNCTIONS_DELETE = code("POST", "/main-functions/{id}/delete");
    public static final String POST_MAIN_FUNCTIONS_IMPORT = code("POST", "/main-functions/import");
    public static final String GET_MAIN_FUNCTIONS_IMPORT_TEMPLATE = code("GET", "/main-functions/import-template");

    // ── Sub functions ───────────────────────────────────────────────────────────
    public static final String GET_SUB_FUNCTIONS = code("GET", "/sub-functions");
    public static final String POST_SUB_FUNCTIONS = code("POST", "/sub-functions");
    public static final String POST_SUB_FUNCTIONS_ID = code("POST", "/sub-functions/{id}");
    public static final String POST_SUB_FUNCTIONS_DELETE = code("POST", "/sub-functions/{id}/delete");
    public static final String POST_SUB_FUNCTIONS_IMPORT = code("POST", "/sub-functions/import");
    public static final String GET_SUB_FUNCTIONS_IMPORT_TEMPLATE = code("GET", "/sub-functions/import-template");

    // ── Asset classes ───────────────────────────────────────────────────────────
    public static final String GET_ASSET_CLASSES = code("GET", "/asset-classes");
    public static final String POST_ASSET_CLASSES = code("POST", "/asset-classes");
    public static final String POST_ASSET_CLASSES_ID = code("POST", "/asset-classes/{id}");
    public static final String POST_ASSET_CLASSES_DELETE = code("POST", "/asset-classes/{id}/delete");
    public static final String GET_ASSET_CLASS_FIELDS = code("GET", "/asset-classes/{classId}/fields");
    public static final String POST_ASSET_CLASS_FIELDS = code("POST", "/asset-classes/{classId}/fields");
    public static final String POST_ASSET_CLASS_FIELD_ID = code("POST", "/asset-classes/{classId}/fields/{fieldId}");
    public static final String POST_ASSET_CLASS_FIELD_DELETE = code("POST", "/asset-classes/{classId}/fields/{fieldId}/delete");

    // ── Asset entries ───────────────────────────────────────────────────────────
    public static final String GET_ASSET_ENTRIES = code("GET", "/asset-entries");
    public static final String POST_ASSET_ENTRIES = code("POST", "/asset-entries");
    public static final String POST_ASSET_ENTRIES_ID = code("POST", "/asset-entries/{id}");
    public static final String POST_ASSET_ENTRIES_DELETE = code("POST", "/asset-entries/{id}/delete");
    public static final String POST_ASSET_ENTRIES_IMPORT = code("POST", "/asset-entries/import");
    public static final String GET_ASSET_ENTRIES_IMPORT_TEMPLATE = code("GET", "/asset-entries/import-template");

    // ── Log sheet templates ─────────────────────────────────────────────────────
    public static final String GET_LOG_SHEET_TEMPLATES = code("GET", "/log-sheet-templates");
    public static final String POST_LOG_SHEET_TEMPLATES = code("POST", "/log-sheet-templates");
    public static final String POST_LOG_SHEET_TEMPLATES_ID = code("POST", "/log-sheet-templates/{id}");
    public static final String POST_LOG_SHEET_TEMPLATES_DELETE = code("POST", "/log-sheet-templates/{id}/delete");

    // ── Operational data ──────────────────────────────────────────────────────────
    public static final String GET_RECORDS = code("GET", "/records");
    public static final String GET_RECORDS_ID = code("GET", "/records/{id}");
    public static final String GET_LOG_SHEETS = code("GET", "/log-sheets");
    public static final String GET_LOG_SHEETS_ID = code("GET", "/log-sheets/{id}");
    public static final String POST_LOG_SHEETS_CUSTOM = code("POST", "/log-sheets/custom");
    public static final String GET_LOG_SHEETS_OPTIONS_ASSETS = code("GET", "/log-sheets/options/assets");
    public static final String POST_LOG_SHEETS_VOID = code("POST", "/log-sheets/{id}/void");
    public static final String POST_LOG_SHEETS_UNVOID = code("POST", "/log-sheets/{id}/unvoid");
    public static final String POST_LOG_SHEETS_REOPEN = code("POST", "/log-sheets/{id}/reopen");
    public static final String POST_LOG_SHEETS_CANCEL = code("POST", "/log-sheets/{id}/cancel");
    public static final String GET_REPORTS = code("GET", "/reports");

    // ── NFC fault reports ──────────────────────────────────────────────────────
    public static final String GET_NFC_FAULT_REPORTS = code("GET", "/nfc-fault-reports");
    public static final String POST_NFC_FAULT_REPORTS = code("POST", "/nfc-fault-reports");
    public static final String POST_NFC_FAULT_REPORTS_DELETE = code("POST", "/nfc-fault-reports/{id}/delete");

    // ── Ops / monitoring (admin) ───────────────────────────────────────────────
    /** Guards everything under /actuator/** except the public liveness/readiness probes. */
    public static final String GET_ACTUATOR = code("GET", "/actuator/**");
    /** Guards the OpenAPI spec and Swagger UI (/v3/api-docs/**, /swagger-ui/**, /swagger-ui.html). */
    public static final String GET_API_DOCS = code("GET", "/v3/api-docs/**");

    // ── Mobile API ────────────────────────────────────────────────────────────────
    public static final String GET_API_BOOTSTRAP = code("GET", "/api/bootstrap");
    public static final String POST_API_RECORDS_BATCH = code("POST", "/api/records/batch");
    public static final String POST_API_LOG_SHEETS_BATCH = code("POST", "/api/log-sheets/batch");
    public static final String GET_API_LOG_SHEETS_BUNDLE = code("GET", "/api/log-sheets/{id}/bundle");
    public static final String GET_API_ASSET_ENTRIES_NFC = code("GET", "/api/asset-entries/nfc/{nfcTagId}");
    public static final String POST_API_NFC_FAULT_REPORTS_BATCH = code("POST", "/api/nfc-fault-reports/batch");

    /** Default endpoint permissions for the USER system role. */
    public static final String[] USER_DEFAULT = {
            GET_LOG_SHEETS, GET_LOG_SHEETS_ID,
            GET_API_BOOTSTRAP, POST_API_LOG_SHEETS_BATCH
    };
}
