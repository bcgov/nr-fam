-- ---------------------------------------------------------------------------
-- UPDATE_ROLE: a role's name or description was changed.
-- ---------------------------------------------------------------------------
--
-- Roles can now be renamed and redescribed from the Manage roles screen. The
-- role code - what people hold and applications authorise on - never changes;
-- only the text FAM shows beside it, which lives on sidecar roles in CSS.
--
-- Recorded as its own type rather than as UPDATE, which already means a change
-- to one person's grant. The audit row holds the text before and after, since
-- CSS keeps no history of either.

INSERT INTO app_fam.fam_privilege_change_type
    (privilege_change_type_code, description, create_user, update_user) VALUES
    ('UPDATE_ROLE', 'Role renamed or redescribed', 'system', 'system');

COMMENT ON COLUMN app_fam.fam_privilege_change_type.privilege_change_type_code IS
    'One of GRANT, REVOKE, UPDATE, CREATE_ROLE, DELETE_ROLE, UPDATE_ROLE. Twenty characters rather than the ten the original table used, because CREATE_ROLE is eleven.';
