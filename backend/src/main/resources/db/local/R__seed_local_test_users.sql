-- Local-development seed users. Only the `local` profile adds classpath:db/local
-- to spring.flyway.locations, so this never runs anywhere else - it ships in the
-- jar unused.
--
-- REPEATABLE (R__), not versioned. It used to be V1000, chosen high so it ran
-- after the baseline - but that number then sat above every real migration, so
-- the next one added (V2) was "out of order" and Flyway refused to start:
--
--   Detected resolved migration not applied to database: 2
--
-- Repeatable migrations carry no version and always run after the versioned
-- ones, so seeding can never block a schema change again.
--
-- Being repeatable, this re-runs whenever its checksum changes - hence
-- ON CONFLICT DO NOTHING. Editing it must stay safe against rows that are
-- already there.
--
-- Only fam_user is worth seeding - applications, roles and role assignments
-- live in CSS, and there is no local way to make someone an admin, because
-- admin rights are roles on FAM's own CSS integration and arrive on the access
-- token. Assign them in CSS, not here.

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
 '000000000000000000000000000000c3@bceidbusiness',CURRENT_USER)
-- No conflict target: fam_user is guarded by two unique indexes, on
-- (type, upper(guid)) and (type, upper(name)), and a re-run collides with
-- whichever it meets first.
ON CONFLICT DO NOTHING;

UPDATE app_fam.fam_user
SET business_guid = '000000000000000000000000000000AA'
WHERE user_name IN ('LOCAL-BCEID-1', 'LOCAL-BCEID-2');

UPDATE app_fam.fam_user
SET business_guid = '000000000000000000000000000000BB'
WHERE user_name = 'LOCAL-BCEID-3';
