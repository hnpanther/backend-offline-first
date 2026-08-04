package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks in the schema and permission-seed decisions made on this branch, so a later edit to
 * {@code V1__initial_schema.sql} cannot silently undo them. Each assertion reads the live
 * catalog rather than the migration text, so it fails on the real effect, not on wording.
 */
class BranchSchemaAndSeedRegressionTest extends AbstractPostgresIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    private boolean columnExists(String table, String column) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                Integer.class, table, column);
        return n != null && n > 0;
    }

    // ---- reserved, schema-only columns ----

    @Test
    void everyHierarchyTableCarriesTheReservedStatusColumn() {
        for (String table : List.of("locations", "plant_systems", "main_functions", "sub_functions", "asset_entries")) {
            assertThat(columnExists(table, "status"))
                    .as("%s.status", table).isTrue();
        }
    }

    @Test
    void everyHierarchyTableCarriesTheReservedSecondaryTitleColumn() {
        for (String table : List.of("locations", "plant_systems", "main_functions", "sub_functions")) {
            assertThat(columnExists(table, "name_fa")).as("%s.name_fa", table).isTrue();
        }
        assertThat(columnExists("asset_entries", "asset_name_fa")).isTrue();
    }

    @Test
    void reservedColumnsStayNullableSoNothingHasToPopulateThem() {
        String nullable = jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_name = 'locations' AND column_name = 'status'", String.class);
        assertThat(nullable).isEqualTo("YES");
    }

    // ---- one active asset per sub-function ----

    @Test
    void theSubFunctionUniquenessIndexIsPartialOnActive() {
        // A plain unique index here would forbid keeping replaced equipment on the same
        // sub-function, which is the whole point of the change.
        List<String> defs = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'asset_entries' "
                        + "AND indexname = 'ux_asset_entries_active_sub_function'", String.class);
        assertThat(defs).hasSize(1);
        assertThat(defs.getFirst()).contains("UNIQUE").contains("sub_function_id").contains("WHERE active");

        assertThat(jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'asset_entries' "
                        + "AND indexname = 'ux_asset_entries_sub_function_id'", String.class))
                .as("the old unconditional index must be gone").isEmpty();
    }

    // ---- EXPLICIT template mode ----

    @Test
    void templateClassIdIsNullableSoExplicitTemplatesCanOmitIt() {
        String nullable = jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_name = 'log_sheet_templates' AND column_name = 'class_id'", String.class);
        assertThat(nullable).isEqualTo("YES");
    }

    @Test
    void frozenTemplateAssetsCascadeOnTemplateDeleteButRestrictOnAssetDelete() {
        assertThat(deleteRuleOf("fk_lsta_template")).isEqualTo("CASCADE");
        assertThat(deleteRuleOf("fk_lsta_asset")).isEqualTo("RESTRICT");
    }

    private String deleteRuleOf(String constraintName) {
        return jdbc.queryForObject(
                "SELECT rc.delete_rule FROM information_schema.referential_constraints rc "
                        + "WHERE rc.constraint_name = ?", String.class, constraintName);
    }

    // ---- legacy removals ----

    @Test
    void logSheetsNoLongerCarriesTheUnusedClientSyncColumns() {
        assertThat(columnExists("log_sheets", "local_id")).isFalse();
        assertThat(columnExists("log_sheets", "sync_error")).isFalse();
        assertThat(jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE conname = 'uk_log_sheets_local_id'", String.class))
                .isEmpty();
    }

    @Test
    void theDeprecatedMasterDataPermissionIsGone() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM permissions WHERE code = 'GET:/api/master-data'", Integer.class);
        assertThat(n).isZero();
    }

    // ---- template writes are ADMIN/HIGH_USER only ----

    @Test
    void supervisorKeepsTemplateReadAccessButLosesTheCreateGrant() {
        assertThat(permissionsOf("SUPERVISOR")).contains("GET:/log-sheet-templates");
        assertThat(permissionsOf("SUPERVISOR"))
                .as("templates are read-only for supervisors")
                .doesNotContain("POST:/log-sheet-templates",
                        "POST:/log-sheet-templates/{id}",
                        "POST:/log-sheet-templates/{id}/delete");
    }

    @Test
    void adminStillHoldsEveryPermissionAfterTheSeedEdits() {
        // Guards the blanket-grant hazard: editing the permission list must not leave ADMIN short.
        Integer total = jdbc.queryForObject("SELECT count(*) FROM permissions", Integer.class);
        assertThat(permissionsOf("ADMIN")).hasSize(total);
    }

    private List<String> permissionsOf(String roleCode) {
        return jdbc.queryForList(
                "SELECT p.code FROM role_permissions rp "
                        + "JOIN roles r ON r.id = rp.role_id "
                        + "JOIN permissions p ON p.id = rp.permission_id "
                        + "WHERE r.code = ?", String.class, roleCode);
    }
}
