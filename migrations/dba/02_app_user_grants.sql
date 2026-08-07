-- Run by a DBA after Flyway has migrated the schema, and again after any
-- migration that introduces a new table or sequence. Not executed by the pipeline.
--
-- Grants the runtime role (fam_app) the union of the privileges formerly split
-- across the three AWS Lambda DB users. See README.md in this directory.

GRANT USAGE ON SCHEMA app_fam TO fam_app;

GRANT SELECT, INSERT, UPDATE, DELETE
    ON ALL TABLES IN SCHEMA app_fam TO fam_app;

GRANT USAGE, SELECT
    ON ALL SEQUENCES IN SCHEMA app_fam TO fam_app;

-- Cover objects created by future migrations without needing a re-run.
-- ALTER DEFAULT PRIVILEGES only applies to objects created by the role that
-- runs it, so this must be executed as (or FOR ROLE) the schema owner.
ALTER DEFAULT PRIVILEGES FOR ROLE fam_owner IN SCHEMA app_fam
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO fam_app;

ALTER DEFAULT PRIVILEGES FOR ROLE fam_owner IN SCHEMA app_fam
    GRANT USAGE, SELECT ON SEQUENCES TO fam_app;
