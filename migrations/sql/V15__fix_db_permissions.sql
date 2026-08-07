BEGIN;

-- V12/13/14 didn't upgrade the alembic version so forcing to V15 to get
-- it back in sync
UPDATE alembic_version SET version_num='V15';
-- WHERE alembic_version.version_num = 'V14';

COMMIT;

