-- ---------------------------------------------------------------------------
-- The application's name, recorded on the audit row itself.
-- ---------------------------------------------------------------------------
--
-- The trail named an application by css_integration_id and css_environment and
-- nothing else, resolving the readable name from CSS when the history was read.
-- That works only while CSS still has the integration. An application removed
-- from CSS - decommissioned, or moved to another team's account - left every row
-- about it labelled by a number, which is not an acceptable answer from a record
-- whose whole purpose is to outlive the thing it describes.
--
-- So it is a snapshot, in the same spirit as change_performer_user_details and
-- change_target_user_details: what the application was called at the time,
-- written once, never resolved again.
--
-- Nullable, and existing rows are left null rather than backfilled. Reading
-- falls back to CSS and then to the id, exactly as it did before, so old rows
-- read no worse than they do today - and an audit table is the last place to
-- rewrite rows in order to tidy them.
-- ---------------------------------------------------------------------------

ALTER TABLE app_fam.fam_privilege_change_audit
    ADD COLUMN css_application_name VARCHAR(100);

COMMENT ON COLUMN app_fam.fam_privilege_change_audit.css_application_name IS
    'What the application was called when the change was made, e.g. FREP (DEV). A snapshot, not a reference: css_integration_id names an integration that may since have been removed from CSS, and the trail has to stay readable when it has. Null on rows written before this column existed, and on a change made while CSS could not be reached - the readable name is best effort and never blocks recording the change itself.';
