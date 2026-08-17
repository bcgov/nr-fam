-- Local-development seed users. Never runs in a deployed environment: only
-- docker-compose adds local_sql to FLYWAY_LOCATIONS, and migrations/Dockerfile
-- copies ./sql alone.
--
-- Runs after the V1 baseline. Only fam_user is worth seeding - applications,
-- roles and role assignments live in CSS, and there is no local way to make
-- someone an admin, because admin rights are roles on FAM's own CSS
-- integration and arrive on the access token. Assign them in CSS, not here.

INSERT INTO app_fam.fam_user (
    user_name,
    user_type_code,
    user_guid,
    oidc_user_id,
    create_user
)
VALUES
-- Signs in to the FAM console locally. Give this user the FAM_ADMIN role in
-- CSS to make them an administrator.
('LOCALDEV','I','00000000000000000000000000000001',
 '00000000000000000000000000000001@idir',CURRENT_USER),
('LOCALTEST','I','00000000000000000000000000000002',
 '00000000000000000000000000000002@idir',CURRENT_USER),
-- Two Business BCeID users in one organisation, one in another - enough to
-- exercise the same-organisation rules.
('LOCAL-BCEID-1','B','000000000000000000000000000000C1',
 '000000000000000000000000000000c1@bceidbusiness',CURRENT_USER),
('LOCAL-BCEID-2','B','000000000000000000000000000000C2',
 '000000000000000000000000000000c2@bceidbusiness',CURRENT_USER),
('LOCAL-BCEID-3','B','000000000000000000000000000000C3',
 '000000000000000000000000000000c3@bceidbusiness',CURRENT_USER);

UPDATE app_fam.fam_user
SET business_guid = '000000000000000000000000000000AA'
WHERE user_name IN ('LOCAL-BCEID-1', 'LOCAL-BCEID-2');

UPDATE app_fam.fam_user
SET business_guid = '000000000000000000000000000000BB'
WHERE user_name = 'LOCAL-BCEID-3';
