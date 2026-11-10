-- =============================================================================
-- V18 — one alarm rule for many parameters, and one default rule for all of them
--
-- WHAT CHANGES. V17 tied a rule to exactly one field definition, so «three dangers in a row on
-- any parameter» meant writing the same rule hundreds of times. Now:
--
--   * a rule has a SCOPE:
--       FIELDS     — the parameters listed in alarm_rule_fields (one or many);
--       ALL_FIELDS — the default: every parameter that no FIELDS rule lists. At most one exists.
--   * a parameter is listed by at most ONE rule (uq_alarm_rule_fields_field), so «which rule
--     governs this parameter» always has exactly one answer:
--       listed by a FIELDS rule  → that rule, ACTIVE OR NOT. A switched-off specific rule
--                                   silences its parameters; it does not hand them back to the
--                                   default. That is the only way to say «never alarm on this»,
--                                   and «off» meaning «stop» is what an administrator expects;
--       listed by none           → the default rule, if it exists and is active;
--   * an alarm's identity becomes (asset, parameter) instead of (rule, asset). With one rule
--     covering many parameters, (rule, asset) would allow only one live alarm per pump for
--     temperature AND pressure together. One live alarm per parameter per asset is also what a
--     supervisor means by «the same alarm», whichever rule happens to govern it now.
--
-- WHY A NEW MIGRATION AND NOT AN EDIT OF V17. V17 has already been applied to a database
-- (flyway_schema_history), and editing an applied migration breaks its checksum and stops the
-- application booting (CLAUDE.md, AGENTS.md #86). It is closed.
--
-- NO DATA IS LOST. Every existing rule keeps its id, parameters and settings: its single field
-- becomes its one listed field, and its name is that field's label. Every existing alarm keeps
-- its row, its history and its rule; field_definition_id was always written when an alarm was
-- raised, and is back-filled from the rule anyway before it becomes NOT NULL. The new identity
-- index cannot conflict with existing rows: under V17 a parameter had one rule, so one live alarm
-- per (rule, asset) was already one per (asset, parameter).
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. A rule's scope and name
-- -----------------------------------------------------------------------------
ALTER TABLE alarm_rules ADD COLUMN scope VARCHAR(20) NOT NULL DEFAULT 'FIELDS';
ALTER TABLE alarm_rules ADD COLUMN name VARCHAR(200);

ALTER TABLE alarm_rules
    ADD CONSTRAINT ck_alarm_rules_scope CHECK (scope IN ('ALL_FIELDS', 'FIELDS'));

-- One default rule. Partial, so it constrains nothing about the FIELDS rules.
CREATE UNIQUE INDEX ux_alarm_rules_one_default ON alarm_rules (scope) WHERE scope = 'ALL_FIELDS';


-- -----------------------------------------------------------------------------
-- 2. The parameters of a FIELDS rule
--
-- UNIQUE on the field: a parameter belongs to at most one rule, so governance is never a tie.
-- CASCADE both ways: a rule deleted takes its list with it (a rule that raised anything cannot
-- be deleted — alarms.alarm_rule_id is RESTRICT), and a parameter hard-deleted leaves the list
-- (a parameter can only be hard-deleted while nothing names its key, roadmap §23).
-- -----------------------------------------------------------------------------
CREATE TABLE alarm_rule_fields (
    alarm_rule_id       BIGINT NOT NULL,
    field_definition_id BIGINT NOT NULL,
    PRIMARY KEY (alarm_rule_id, field_definition_id),
    CONSTRAINT uq_alarm_rule_fields_field UNIQUE (field_definition_id),
    CONSTRAINT fk_alarm_rule_fields_rule FOREIGN KEY (alarm_rule_id)
        REFERENCES alarm_rules (id) ON DELETE CASCADE,
    CONSTRAINT fk_alarm_rule_fields_field FOREIGN KEY (field_definition_id)
        REFERENCES field_definitions (id) ON DELETE CASCADE
);

-- Every V17 rule becomes a FIELDS rule listing its one parameter.
INSERT INTO alarm_rule_fields (alarm_rule_id, field_definition_id)
SELECT id, field_definition_id FROM alarm_rules;

UPDATE alarm_rules r
   SET name = COALESCE(NULLIF(btrim(f.label), ''), f.field_key)
  FROM field_definitions f
 WHERE f.id = r.field_definition_id;

UPDATE alarm_rules SET name = 'قاعده ' || id WHERE name IS NULL OR btrim(name) = '';

ALTER TABLE alarm_rules ALTER COLUMN name SET NOT NULL;
ALTER TABLE alarm_rules
    ADD CONSTRAINT ck_alarm_rules_name CHECK (length(btrim(name)) > 0);


-- -----------------------------------------------------------------------------
-- 3. An alarm is identified by (asset, parameter)
-- -----------------------------------------------------------------------------
UPDATE alarms a
   SET field_definition_id = r.field_definition_id
  FROM alarm_rules r
 WHERE a.alarm_rule_id = r.id
   AND a.field_definition_id IS NULL;

ALTER TABLE alarms ALTER COLUMN field_definition_id SET NOT NULL;

DROP INDEX ux_alarms_one_live_per_rule_and_asset;
CREATE UNIQUE INDEX ux_alarms_one_live_per_asset_and_field
    ON alarms (asset_id, field_definition_id) WHERE state <> 'CLOSED';

-- The watermark — «which readings of this parameter has a closed alarm already used» — is now
-- asked per (asset, parameter), whichever rule raised the alarm.
DROP INDEX idx_alarms_rule_asset_last_reading;
CREATE INDEX idx_alarms_asset_field_last_reading
    ON alarms (asset_id, field_definition_id, last_reading_at);

-- Switching a rule off closes its live alarms, and deleting one asks whether it ever raised any:
-- both look alarms up by rule.
CREATE INDEX idx_alarms_rule_state ON alarms (alarm_rule_id, state);


-- -----------------------------------------------------------------------------
-- 4. The old one-field column goes
-- -----------------------------------------------------------------------------
ALTER TABLE alarm_rules DROP CONSTRAINT uq_alarm_rules_field;
ALTER TABLE alarm_rules DROP CONSTRAINT fk_alarm_rules_field;
ALTER TABLE alarm_rules DROP COLUMN field_definition_id;

-- The scope is stated by whoever writes a rule, never assumed.
ALTER TABLE alarm_rules ALTER COLUMN scope DROP DEFAULT;

COMMENT ON TABLE alarm_rule_fields IS
    'The parameters a FIELDS-scoped alarm rule governs. A parameter is listed by at most one rule; '
    'one listed by none is governed by the single ALL_FIELDS (default) rule, if active (V18).';
