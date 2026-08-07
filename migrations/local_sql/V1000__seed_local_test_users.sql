-- Local-development seed users. Never runs in a deployed environment: only
-- docker-compose adds local_sql to FLYWAY_LOCATIONS, and migrations/Dockerfile
-- copies ./sql alone.
--
-- V17 used to create the users this file relied on, seeding five real BC Gov
-- IDIR accounts and making them FAM admins. That seed was removed, so the local
-- identities are created here instead - synthetic, and obviously so.
--
-- Runs after V93, so it targets the final schema: FAM roles no longer exist
-- (V38 deleted them) and application admin rights live in fam_application_admin.

INSERT INTO app_fam.fam_user (
    user_name,
    user_type_code,
    user_guid,
    cognito_user_id,
    create_user
)
VALUES
-- Application admin: signs in to the FAM console locally with full rights.
('LOCALDEV','I','00000000000000000000000000000001',
 '00000000000000000000000000000001@idir',CURRENT_USER),
-- Delegated admin subject, used by V1001.
('LOCALTEST','I','00000000000000000000000000000002',
 '00000000000000000000000000000002@idir',CURRENT_USER);

-- Make LOCALDEV an admin of FAM itself and of FOM_DEV. This is the post-V38
-- model: admin rights are rows in fam_application_admin, not roles.
INSERT INTO app_fam.fam_application_admin (
    user_id,
    application_id,
    create_user
)
SELECT
    (SELECT user_id FROM app_fam.fam_user
      WHERE user_name = 'LOCALDEV' AND user_type_code = 'I'),
    application_id,
    CURRENT_USER
FROM app_fam.fam_application
WHERE application_name IN ('FAM', 'FOM_DEV');
