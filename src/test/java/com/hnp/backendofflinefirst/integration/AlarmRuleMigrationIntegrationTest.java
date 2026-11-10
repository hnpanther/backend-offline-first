package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V18 against a database that has really run V17 and holds alarm data — the case every other
 * test skips, because Flyway builds their schema from empty.
 *
 * <p>V17 had already been applied to a working database (with rules and an alarm in it) when the
 * rule model changed, which is why the change is a new migration and not an edit. This proves the
 * new migration loses nothing: it builds a private schema to V17, writes rules, alarms and events
 * the way V17's code wrote them — including an alarm with no {@code field_definition_id}, which
 * V18 must back-fill before the column becomes NOT NULL — migrates to the latest version, and reads
 * every row back.
 */
class AlarmRuleMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String SCHEMA = "alarm_v18_check";

    @AfterEach
    void dropSchema() throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @Test
    void v18KeepsEveryV17RuleAlarmAndEventAndMovesEachRuleOntoItsParameter() throws SQLException {
        flyway("17").migrate();

        long temp;
        long pressure;
        long asset;
        long tempRule;
        long pressureRule;
        long openAlarm;
        long closedAlarm;
        try (Connection c = connect()) {
            long classId = insert(c, "INSERT INTO asset_classes (name) VALUES ('پمپ') RETURNING id");
            temp = insert(c, "INSERT INTO field_definitions (class_id, field_key, label, data_type, required, "
                    + "deleted, synced) VALUES (" + classId + ", 'temp', 'دما', 'number', false, false, false) RETURNING id");
            pressure = insert(c, "INSERT INTO field_definitions (class_id, field_key, label, data_type, required, "
                    + "deleted, synced) VALUES (" + classId + ", 'pressure', '  ', 'number', false, false, false) RETURNING id");
            long sub = insert(c, "INSERT INTO sub_functions (code, tag) VALUES ('SF-1', 'TAG-1') RETURNING id");
            asset = insert(c, "INSERT INTO asset_entries (asset_code, sub_function_id, class_id) VALUES "
                    + "('P-101', " + sub + ", " + classId + ") RETURNING id");

            tempRule = insert(c, "INSERT INTO alarm_rules (field_definition_id, trigger_severity, "
                    + "consecutive_readings, clear_after_readings, window_days, priority, expected_action, "
                    + "active, created_at, updated_at) VALUES (" + temp + ", 'WARNING', 3, 2, 7, 'HIGH', "
                    + "'اعزام مکانیک', true, 1, 2) RETURNING id");
            pressureRule = insert(c, "INSERT INTO alarm_rules (field_definition_id, trigger_severity, "
                    + "consecutive_readings, clear_after_readings, priority, expected_action, active, "
                    + "created_at, updated_at) VALUES (" + pressure + ", 'DANGER', 1, 1, 'LOW', 'بررسی', false, "
                    + "3, 4) RETURNING id");

            openAlarm = insert(c, "INSERT INTO alarms (alarm_rule_id, asset_id, field_definition_id, field_key, "
                    + "state, severity, priority, trigger_severity, consecutive_readings, clear_after_readings, "
                    + "occurrence_count, condition_met_at, first_reading_at, last_reading_at, created_at, "
                    + "updated_at) VALUES (" + tempRule + ", " + asset + ", " + temp + ", 'temp', 'OPEN', "
                    + "'WARNING', 'HIGH', 'WARNING', 3, 2, 3, 10, 5, 10, 11, 11) RETURNING id");
            // Written without a field id: V18 must recover it from the rule before NOT NULL.
            closedAlarm = insert(c, "INSERT INTO alarms (alarm_rule_id, asset_id, field_definition_id, field_key, "
                    + "state, severity, priority, trigger_severity, consecutive_readings, clear_after_readings, "
                    + "occurrence_count, condition_met_at, first_reading_at, last_reading_at, closed_at, "
                    + "close_reason, created_at, updated_at) VALUES (" + pressureRule + ", " + asset + ", NULL, "
                    + "'pressure', 'CLOSED', 'DANGER', 'LOW', 'DANGER', 1, 1, 1, 20, 20, 20, 21, 'RESOLVED', "
                    + "20, 21) RETURNING id");
            insert(c, "INSERT INTO alarm_events (alarm_id, event_type, occurred_at, comment) VALUES ("
                    + openAlarm + ", 'RAISED', 11, 'سه قرائت') RETURNING id");
            insert(c, "INSERT INTO alarm_events (alarm_id, event_type, occurred_at) VALUES ("
                    + closedAlarm + ", 'CLOSED', 21) RETURNING id");
        }

        flyway(null).migrate();

        try (Connection c = connect()) {
            assertThat(rows(c, "SELECT id, scope, name, trigger_severity, consecutive_readings, "
                    + "clear_after_readings, window_days, priority, expected_action, active FROM alarm_rules ORDER BY id"))
                    .containsExactly(
                            List.of(tempRule, "FIELDS", "دما", "WARNING", 3, 2, 7, "HIGH", "اعزام مکانیک", true),
                            // A blank label falls back to the key.
                            List.of(pressureRule, "FIELDS", "pressure", "DANGER", 1, 1, "null", "LOW", "بررسی", false));

            assertThat(rows(c, "SELECT alarm_rule_id, field_definition_id FROM alarm_rule_fields ORDER BY alarm_rule_id"))
                    .containsExactly(List.of(tempRule, temp), List.of(pressureRule, pressure));

            assertThat(rows(c, "SELECT id, alarm_rule_id, field_definition_id, state, occurrence_count FROM alarms ORDER BY id"))
                    .containsExactly(
                            List.of(openAlarm, tempRule, temp, "OPEN", 3),
                            List.of(closedAlarm, pressureRule, pressure, "CLOSED", 1));

            assertThat(rows(c, "SELECT alarm_id, event_type FROM alarm_events ORDER BY id"))
                    .containsExactly(List.of(openAlarm, "RAISED"), List.of(closedAlarm, "CLOSED"));

            assertThat(rows(c, "SELECT count(*) FROM information_schema.columns WHERE table_schema = '"
                    + SCHEMA + "' AND table_name = 'alarm_rules' AND column_name = 'field_definition_id'"))
                    .as("the one-field column is gone").containsExactly(List.of(0L));

            // The new identity: one live alarm per (asset, parameter), whichever rule.
            final long assetId = asset;
            final long fieldId = temp;
            final long ruleId = pressureRule;
            assertThatThrownBy(() -> {
                try (Statement s = c.createStatement()) {
                    s.executeUpdate("INSERT INTO alarms (alarm_rule_id, asset_id, field_definition_id, field_key, "
                            + "state, severity, priority, trigger_severity, consecutive_readings, "
                            + "clear_after_readings, occurrence_count, condition_met_at, first_reading_at, "
                            + "last_reading_at, created_at, updated_at) VALUES (" + ruleId + ", " + assetId + ", "
                            + fieldId + ", 'temp', 'OPEN', 'DANGER', 'LOW', 'DANGER', 1, 1, 1, 30, 30, 30, 30, 30)");
                }
            }).isInstanceOf(SQLException.class).hasMessageContaining("ux_alarms_one_live_per_asset_and_field");

            // One default rule.
            insert(c, "INSERT INTO alarm_rules (scope, name, trigger_severity, consecutive_readings, "
                    + "clear_after_readings, priority, expected_action, active, created_at, updated_at) VALUES "
                    + "('ALL_FIELDS', 'عمومی', 'DANGER', 3, 1, 'MEDIUM', 'بررسی', true, 1, 1) RETURNING id");
            assertThatThrownBy(() -> insert(c, "INSERT INTO alarm_rules (scope, name, trigger_severity, "
                    + "consecutive_readings, clear_after_readings, priority, expected_action, active, created_at, "
                    + "updated_at) VALUES ('ALL_FIELDS', 'دوم', 'DANGER', 1, 1, 'LOW', 'x', true, 1, 1) RETURNING id"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ux_alarm_rules_one_default");
        }
    }

    private Flyway flyway(String target) {
        var config = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(SCHEMA)
                .createSchemas(true)
                .locations("classpath:db/migration");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private static Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Statement s = c.createStatement()) {
            s.execute("SET search_path TO " + SCHEMA);
        }
        return c;
    }

    private static long insert(Connection c, String sql) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql); ResultSet r = s.executeQuery()) {
            r.next();
            return r.getLong(1);
        }
    }

    /** Every row as a list of values; SQL NULL as the string "null" so rows compare plainly. */
    private static List<List<Object>> rows(Connection c, String sql) throws SQLException {
        List<List<Object>> out = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            int columns = r.getMetaData().getColumnCount();
            while (r.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= columns; i++) {
                    Object value = r.getObject(i);
                    row.add(value == null ? "null" : value);
                }
                out.add(row);
            }
        }
        return out;
    }
}
