-- Run by a DBA before the application first starts. Not executed by the
-- pipeline.
--
-- Grants the runtime role (fam_app) the union of the privileges formerly split
-- across the three AWS Lambda DB users. See README.md in this directory.
--
-- CREATE is new. The application runs Flyway on start-up now, so the role it
-- connects as creates tables rather than only reading and writing rows. There
-- is no longer a separate migrations container that could run as fam_owner
-- while the app ran with less.

GRANT USAGE, CREATE ON SCHEMA app_fam TO fam_app;

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
