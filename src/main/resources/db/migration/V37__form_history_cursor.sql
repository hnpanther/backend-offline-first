-- A wall clock or an identity alone can advance past an earlier uncommitted edit.
-- The counter row serializes publication for one reporter and rolls back with the edit.
CREATE TABLE form_history_counters (
    reporter_id bigint PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    position bigint NOT NULL
);
ALTER TABLE form_submissions ADD COLUMN history_position bigint NOT NULL DEFAULT 0;
UPDATE form_submissions SET history_position = id WHERE reported_by_user_id IS NOT NULL;
INSERT INTO form_history_counters (reporter_id, position)
SELECT reported_by_user_id, max(history_position) FROM form_submissions
WHERE reported_by_user_id IS NOT NULL GROUP BY reported_by_user_id;
CREATE UNIQUE INDEX ux_form_history_position ON form_submissions (reported_by_user_id, history_position);

CREATE FUNCTION stamp_form_history_position() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.reported_by_user_id IS NOT NULL THEN
        INSERT INTO form_history_counters (reporter_id, position) VALUES (NEW.reported_by_user_id, 1)
        ON CONFLICT (reporter_id) DO UPDATE SET position = form_history_counters.position + 1
        RETURNING position INTO NEW.history_position;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_form_history_position BEFORE INSERT OR UPDATE ON form_submissions
    FOR EACH ROW EXECUTE FUNCTION stamp_form_history_position();
