-- Run by a DBA, once, before the first Flyway run. Not executed by the pipeline.
--
-- Two roles are involved:
--   fam_owner  - owns the app_fam schema; Flyway connects as this role
--   fam_app    - the runtime role the Spring Boot backend connects as
--
-- Substitute real passwords (or configure the roles for the site's standard
-- authentication method) before running.

CREATE ROLE fam_owner WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'fam_owner_password';
CREATE ROLE fam_app   WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'fam_app_password';

-- Flyway creates "app_fam" itself (V1), but pre-creating it fixes ownership.
CREATE SCHEMA IF NOT EXISTS app_fam AUTHORIZATION fam_owner;

-- Flyway's schema history table lives alongside the application objects.
GRANT ALL ON SCHEMA app_fam TO fam_owner;
