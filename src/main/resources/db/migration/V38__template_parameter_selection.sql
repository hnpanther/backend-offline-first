-- The source identity survives relabelling, reordering and soft disable/enable.
ALTER TABLE field_definitions ADD COLUMN binding_id uuid NOT NULL DEFAULT gen_random_uuid();
CREATE UNIQUE INDEX ux_field_definition_binding ON field_definitions(binding_id);
CREATE FUNCTION rotate_field_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (OLD.class_id, lower(OLD.field_key), OLD.data_type, OLD.unit)
        IS DISTINCT FROM (NEW.class_id, lower(NEW.field_key), NEW.data_type, NEW.unit) THEN
        NEW.binding_id := gen_random_uuid();
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER field_binding_change BEFORE UPDATE ON field_definitions
FOR EACH ROW EXECUTE FUNCTION rotate_field_binding();

-- Track each asset-owned key even while it is disabled. Dropping the key retires its UUID.
ALTER TABLE asset_entries ADD COLUMN field_bindings jsonb NOT NULL DEFAULT '{}';
CREATE FUNCTION maintain_asset_field_bindings() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    item record;
    previous jsonb;
    old_ids jsonb := '{}';
    type_name text;
    unit_name text;
    binding text;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        old_ids := OLD.field_bindings;
    END IF;
    NEW.field_bindings := '{}';
    FOR item IN SELECT lower(trim(key)) AS key, value FROM jsonb_each(coalesce(NEW.field_overrides, '{}')) LOOP
        previous := old_ids -> item.key;
        binding := previous ->> 'id';
        IF coalesce((item.value->>'removed')::boolean, false) THEN
            -- A disabled override can contain just the removal marker.
            type_name := previous ->> 'dataType';
            unit_name := previous ->> 'unit';
        ELSE
            type_name := item.value ->> 'dataType';
            unit_name := item.value ->> 'unit';
        END IF;
        IF binding IS NULL OR (previous->>'dataType', previous->>'unit')
                IS DISTINCT FROM (type_name, unit_name) THEN
            binding := gen_random_uuid()::text;
        END IF;
        NEW.field_bindings := NEW.field_bindings || jsonb_build_object(item.key,
            jsonb_build_object('id', binding, 'dataType', type_name, 'unit', unit_name));
    END LOOP;
    RETURN NEW;
END $$;
CREATE TRIGGER asset_field_binding_change BEFORE INSERT OR UPDATE OF field_overrides ON asset_entries
FOR EACH ROW EXECUTE FUNCTION maintain_asset_field_bindings();
UPDATE asset_entries SET field_overrides = field_overrides WHERE field_overrides IS NOT NULL;

ALTER TABLE log_sheet_templates ADD COLUMN parameter_rules jsonb,
    ADD COLUMN parameter_revision bigint NOT NULL DEFAULT 0;
ALTER TABLE log_sheets ADD COLUMN entry_schema jsonb;
