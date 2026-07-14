-- Per-user authentication mode: local BCrypt, Active Directory bind, or hybrid (local first).
ALTER TABLE users
    ADD COLUMN auth_type VARCHAR(32) NOT NULL DEFAULT 'LOCAL';

ALTER TABLE users
    ADD CONSTRAINT ck_users_auth_type
        CHECK (auth_type IN ('LOCAL', 'ACTIVE_DIRECTORY', 'HYBRID'));
