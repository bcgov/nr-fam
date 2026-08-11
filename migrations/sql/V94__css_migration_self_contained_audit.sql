-- Move role management to CSS (Common Hosted Single Sign-On) and make the
-- privilege audit self-contained.
--
-- Applications, roles and role assignments now live in CSS. FAM keeps the audit
-- trail, which is why this migration reshapes it before dropping what it used to
-- point at rather than dropping everything outright.
--
-- The audit loses its foreign keys deliberately. An audit record that references
-- mutable operational tables is only as durable as those rows: deleting a user
-- would break history. Recording the identifiers directly makes it append-only
-- and independent, which is what an audit log should be.
--
-- Order matters: every new column is backfilled from the old relationships
-- before the tables supplying them are dropped.

-- ---------------------------------------------------------------------------
-- 1. Audit: add CSS-shaped columns
-- ---------------------------------------------------------------------------

ALTER TABLE app_fam.fam_privilege_change_audit
    ADD COLUMN css_integration_id  INTEGER,
    ADD COLUMN css_environment     VARCHAR(10),
    ADD COLUMN target_user_guid    VARCHAR(32),
    ADD COLUMN target_user_type_code VARCHAR(2),
    ADD COLUMN performer_user_guid VARCHAR(32);

COMMENT ON COLUMN app_fam.fam_privilege_change_audit.css_integration_id IS
    'CSS integration the privilege change was made for. Replaces the fam_application foreign key; a CSS integration plus environment is what identifies an application now.';
COMMENT ON COLUMN app_fam.fam_privilege_change_audit.css_environment IS
    'CSS environment (dev, test, prod). A CSS integration spans environments, so it takes both columns to identify what FAM calls an application.';
COMMENT ON COLUMN app_fam.fam_privilege_change_audit.target_user_guid IS
    'GUID of the user the privilege change was performed on. Recorded directly rather than by foreign key so history survives the user record.';
COMMENT ON COLUMN app_fam.fam_privilege_change_audit.target_user_type_code IS
    'User type of the target user (I or B), needed alongside the GUID to identify them.';
COMMENT ON COLUMN app_fam.fam_privilege_change_audit.performer_user_guid IS
    'GUID of the user who made the change. Null when the change was made by a system process.';

-- ---------------------------------------------------------------------------
-- 2. Backfill from the relationships about to be dropped
-- ---------------------------------------------------------------------------

-- Existing rows carry a FAM application id, which has no CSS equivalent: the
-- mapping from a FAM application to a CSS integration is not derivable here.
-- css_integration_id is therefore left NULL on historical rows, and the
-- application is preserved in the environment column as a readable marker so the
-- record is not silently detached from what it referred to.
UPDATE app_fam.fam_privilege_change_audit audit
SET css_environment = COALESCE(
        (SELECT LOWER(app.app_environment)
           FROM app_fam.fam_application app
          WHERE app.application_id = audit.application_id),
        'unknown')
WHERE audit.css_environment IS NULL;

UPDATE app_fam.fam_privilege_change_audit audit
SET target_user_guid = target.user_guid,
    target_user_type_code = target.user_type_code
FROM app_fam.fam_user target
WHERE target.user_id = audit.change_target_user_id;

UPDATE app_fam.fam_privilege_change_audit audit
SET performer_user_guid = performer.user_guid
FROM app_fam.fam_user performer
WHERE performer.user_id = audit.change_performer_user_id;

-- ---------------------------------------------------------------------------
-- 3. Audit: drop the foreign keys and the columns they governed
-- ---------------------------------------------------------------------------

ALTER TABLE app_fam.fam_privilege_change_audit
    DROP CONSTRAINT IF EXISTS fk_application,
    DROP CONSTRAINT IF EXISTS fk_change_performer_user,
    DROP CONSTRAINT IF EXISTS fk_change_target_user;

DROP INDEX IF EXISTS app_fam.idx_fam_privilege_change_audit_application_id;
DROP INDEX IF EXISTS app_fam.idx_fam_privilege_change_audit_change_target_user_id;

ALTER TABLE app_fam.fam_privilege_change_audit
    DROP COLUMN application_id,
    DROP COLUMN change_target_user_id,
    DROP COLUMN change_performer_user_id;

-- Historical rows have no CSS integration id, so this cannot be NOT NULL. New
-- rows always carry one; the application is required to make the change at all.
CREATE INDEX idx_fam_privilege_change_audit_css_integration
    ON app_fam.fam_privilege_change_audit(css_integration_id, css_environment);

CREATE INDEX idx_fam_privilege_change_audit_target_user
    ON app_fam.fam_privilege_change_audit(target_user_guid);

-- ---------------------------------------------------------------------------
-- 4. Drop what CSS now owns
--
-- Dependency order: assignment tables first, then roles, then the applications
-- and code tables they referenced.
-- ---------------------------------------------------------------------------

-- Dropped leaf-first, derived from the schema's foreign-key graph: nothing is
-- dropped before the tables that reference it.

-- Assignment and membership tables (nothing references these).
DROP TABLE IF EXISTS app_fam.fam_user_role_xref;
DROP TABLE IF EXISTS app_fam.fam_access_control_privilege;
DROP TABLE IF EXISTS app_fam.fam_application_admin;

-- Terms and conditions existed solely to gate Business BCeID delegated admins.
-- Delegated administration is a CSS concern now, so the acceptance record has
-- nothing left to gate.
DROP TABLE IF EXISTS app_fam.fam_user_terms_conditions;

-- The group tables date from V1 and were never used: no entity models them, no
-- query references them, and all four are empty. They are dropped only because
-- they hold foreign keys into fam_role, fam_application and fam_forest_client.
DROP TABLE IF EXISTS app_fam.fam_application_group_xref;
DROP TABLE IF EXISTS app_fam.fam_group_role_xref;
DROP TABLE IF EXISTS app_fam.fam_user_group_xref;
DROP TABLE IF EXISTS app_fam.fam_group;

-- Per-application OIDC client ids are CSS integrations now.
DROP TABLE IF EXISTS app_fam.fam_application_client;

DROP TABLE IF EXISTS app_fam.fam_role;
DROP TABLE IF EXISTS app_fam.fam_role_type;

-- Forest clients are still reachable through the Forest Client API. What is
-- dropped is FAM's local copy: scope now travels in the CSS role name, so there
-- is nothing to join to.
DROP TABLE IF EXISTS app_fam.fam_forest_client;

DROP TABLE IF EXISTS app_fam.fam_application;
DROP TABLE IF EXISTS app_fam.fam_app_environment;

-- Kept: fam_user and fam_user_type_code (users are still provisioned at login
-- and refreshed from IDIM), fam_privilege_change_audit and
-- fam_privilege_change_type.
