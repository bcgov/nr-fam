-- ---------------------------------------------------------------------------
-- change_comment: why the person making a change made it, in their own words.
-- ---------------------------------------------------------------------------
--
-- Granting, revoking and editing a user's permission now take an optional
-- comment - a ticket number, "moved to the Kamloops office", "left the
-- contractor". The trail already said who did what to whom and when; this is
-- the only place it can say why.
--
-- Written once with the row it explains and never edited, like everything else
-- on this table. An edit is recorded as the grants and revocations it was made
-- of, so each of those rows carries the same comment.
--
-- Nullable, and existing rows are left null: the comment is optional, and the
-- rows written before it existed simply have none.
-- ---------------------------------------------------------------------------

ALTER TABLE app_fam.fam_privilege_change_audit
    ADD COLUMN change_comment VARCHAR(500);

COMMENT ON COLUMN app_fam.fam_privilege_change_audit.change_comment IS
    'Free-text reason the performer gave for the change, up to 500 characters. Optional, so null when none was given and on every row written before this column existed.';
