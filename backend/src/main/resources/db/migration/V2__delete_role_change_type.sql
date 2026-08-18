-- Records the removal of a role from an application.
--
-- A separate migration rather than another row in V1: the baseline has already
-- been applied to the deployed database, and Flyway checksums what it applied.
-- Editing V1 would fail validation on every environment that already ran it.
--
-- Deleting a role takes it away from everyone who held it, so this is the one
-- change type that can revoke several people's access in a single row. The
-- count of affected users lives in the row's JSONB details, since CSS keeps no
-- record of a role once it is gone.
INSERT INTO app_fam.fam_privilege_change_type (privilege_change_type_code, description)
VALUES ('DELETE_ROLE', 'Role deleted')
ON CONFLICT (privilege_change_type_code) DO NOTHING;
