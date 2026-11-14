-- =============================================================================
-- V20 — what one integration API key may read
--
-- Until now every key read every finished round. A plant with one consumer per concern — an ERP
-- importing the equipment rounds, a safety system reading only the patrols, a quality system
-- reading only the approved sheets — had to hand each of them everything.
--
-- A key may now be limited in two independent dimensions, either, both or neither:
--
--   api_key_categories        — the log-sheet categories (V19) its rounds must belong to.
--                               No rows = every category, uncategorised rounds included.
--                               Rows = only rounds frozen into one of those categories; an
--                               uncategorised round is outside the scope.
--   api_keys.scope_statuses   — the finished statuses it may read, as a comma-separated list.
--                               NULL = every status the API exposes.
--
-- WHY THE CATEGORY IS JUDGED ON THE ROUND. The sheet carries the category it was raised under
-- (log_sheets.category_id, frozen by V19), so moving a template to another category later does
-- not move the rounds it already raised from one consumer to another.
--
-- WHY THE STATUSES ARE A COLUMN AND THE CATEGORIES A TABLE. A category is a row that can be
-- renamed, deactivated or deleted, so the scope must reference it — the foreign key is what
-- makes «delete a category a key still reads» refusable. The statuses are a closed list of five
-- words the API publishes; a CHECK pins the column to them, and the row the key's authentication
-- already loads carries them, so reading the scope costs one extra lookup, not two.
--
-- WHY THE FOREIGN KEY TO THE CATEGORY IS RESTRICT. Deleting a category a key is scoped to would
-- leave the key with fewer rows; with its last row gone it would silently widen to EVERY
-- category. A scope must never widen by itself, so the delete is refused
-- (LogSheetCategoryService.delete asks first; the key catches a race).
-- =============================================================================

ALTER TABLE api_keys
    ADD COLUMN scope_statuses VARCHAR(100);

ALTER TABLE api_keys
    ADD CONSTRAINT ck_api_keys_scope_statuses CHECK (
        scope_statuses IS NULL
        OR scope_statuses ~ '^(SUBMITTED|APPROVED|VOIDED|EXPIRED|CANCELLED)(,(SUBMITTED|APPROVED|VOIDED|EXPIRED|CANCELLED))*$');

CREATE TABLE api_key_categories (
    api_key_id   BIGINT NOT NULL,
    category_id  BIGINT NOT NULL,
    CONSTRAINT pk_api_key_categories PRIMARY KEY (api_key_id, category_id),
    CONSTRAINT fk_api_key_categories_key FOREIGN KEY (api_key_id)
        REFERENCES api_keys (id) ON DELETE CASCADE,
    CONSTRAINT fk_api_key_categories_category FOREIGN KEY (category_id)
        REFERENCES log_sheet_categories (id)
);

-- The category delete guard asks «does any key name this category».
CREATE INDEX idx_api_key_categories_category_id ON api_key_categories (category_id);

-- Changing a key's scope is its own decision, apart from issuing one: it changes what an
-- already-deployed consumer receives on its next poll. Granted to whoever may issue keys.
INSERT INTO permissions (code, name, category, http_method, endpoint_path) VALUES
('POST:/integration-keys/{id}/scope', 'تعیین محدوده دسترسی کلید یکپارچه‌سازی', 'admin', 'POST', '/integration-keys/{id}/scope');

INSERT INTO role_permissions (role_id, permission_id)
SELECT rp.role_id, p.id
  FROM role_permissions rp
  JOIN permissions src ON src.id = rp.permission_id
                      AND src.code = 'POST:/integration-keys'
 CROSS JOIN permissions p
 WHERE p.code = 'POST:/integration-keys/{id}/scope'
ON CONFLICT (role_id, permission_id) DO NOTHING;
