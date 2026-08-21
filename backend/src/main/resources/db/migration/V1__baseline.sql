-- FAM baseline schema.
--
-- Replaces the 95 migrations inherited from the Python service. Those recorded
-- how FAM's own tables grew and were then largely dismantled: applications,
-- roles and role assignments moved to CSS, and V94 dropped the twenty tables
-- that held them. Replaying that history onto an empty database would create
-- sixteen tables in order to drop them again, so this states the end result
-- instead.
--
-- Two tables remain, and both exist for one reason CSS cannot cover:
--
--   fam_privilege_change_audit / fam_privilege_change_type
--     Who granted what to whom. CSS keeps no history of role assignments, so
--     if it is not recorded here it is recorded nowhere.
--
-- Deliberately absent: roles, role assignments, applications, delegated admin
-- and forest client tables. All are CSS or the Forest Client API now, and a
-- column here would be a second, staler copy of them.
--
-- Also absent: fam_user. FAM used to provision a row at login and reject any
-- token without one, which made "has signed in before" a precondition for every
-- API call. It decided nothing - authorisation has always come from the roles on
-- the token - and every field it held is on the token too. The audit records the
-- people it names as snapshots rather than references, so nothing here needs a
-- user table to be readable.
--
-- The entity classes are validated against this schema at start-up
-- (`ddl-auto: validate`), so a mismatch fails the application at boot rather
-- than at the first write. Any change here needs the matching entity change.
--
-- ---------------------------------------------------------------------------
-- Conventions this schema holds to
-- ---------------------------------------------------------------------------
--
-- Audit columns. Every table carries the same four - create_user, create_date,
-- update_user, update_date - including the code tables, so that "who put this
-- row here" has one answer everywhere rather than depending on which table is
-- being read. flyway_schema_history is Flyway's and is left alone.
--
-- All four are NOT NULL. On a create, update_user is filled with create_user
-- rather than left empty: "last touched by" then always has an answer, and a
-- reader never has to know that null means "never updated" - a convention that
-- is invisible in the data and easy to misread as missing. AuditedEntity fills
-- it on insert; rows seeded by this migration carry it explicitly.
--
-- Timestamps are timestamp(6), without a time zone. The containers run with
-- TZ=America/Vancouver (see backend/openshift.deploy.yml), so stored values are
-- BC local time and match what the application logs. The trade-off is real: a
-- local timestamp cannot distinguish the two 01:30s on the November DST
-- fall-back, so an hour of audit history each year is ambiguous to the minute.
--
-- Audit user format. create_user and update_user identify a person as
-- <TYPE>\<GUID>, e.g. IDIR\A1B2C3D4E5F60718293A4B5C6D7E8F90. A bare GUID does
-- not say which directory it came from, and the two directories can be read by
-- different people with different tools; the prefix removes the guesswork. The
-- prefix is the identity provider's name - IDIR or BCEID_BUS. performer_user and
-- target_user use the same form, so this table names people one way throughout,
-- and no separate type column is needed. Rows not written by a person use
-- 'system'.
--
-- Surrogate keys are UUIDs, not sequences. FAM's rows are created from several
-- pods at once and are exported and cross-referenced against systems that have
-- their own numbering; a sequence value invites being read as an ordering or a
-- count, and collides on any future merge of two databases. gen_random_uuid()
-- is a default rather than the only source, so Hibernate can supply the value
-- it already generated.

-- Normally a no-op: dba/01_create_roles.sql creates the schema with
-- AUTHORIZATION fam_owner, and this migration runs afterwards. It is here so a
-- throwaway database works without the DBA scripts.
--
-- If the schema does NOT already exist, it is created owned by whoever runs
-- Flyway. That matters: dba/02 attaches ALTER DEFAULT PRIVILEGES FOR ROLE
-- fam_owner, which only covers tables fam_owner creates. Run the DBA scripts
-- first in any environment that has them.
CREATE SCHEMA IF NOT EXISTS app_fam;

-- ---------------------------------------------------------------------------
-- Code tables
-- ---------------------------------------------------------------------------

-- The five values PrivilegeChangeType can hold. CREATE_ROLE is eleven
-- characters, which is why the code column is 20 rather than the 10 the
-- original table used.
CREATE TABLE app_fam.fam_privilege_change_type (
    privilege_change_type_code  VARCHAR(20)  NOT NULL,
    description                 VARCHAR(100) NOT NULL,
    effective_date              TIMESTAMP(6) NOT NULL DEFAULT NOW(),
    expiry_date                 TIMESTAMP(6),
    create_user                 VARCHAR(100) NOT NULL,
    create_date                 TIMESTAMP(6) NOT NULL DEFAULT NOW(),
    update_user                 VARCHAR(100) NOT NULL,
    update_date                 TIMESTAMP(6) NOT NULL DEFAULT NOW(),
    CONSTRAINT fam_privilege_change_type_pk PRIMARY KEY (privilege_change_type_code)
);

COMMENT ON TABLE app_fam.fam_privilege_change_type IS
    'Kinds of change recorded in fam_privilege_change_audit: granting, revoking or updating a user''s access, and defining or removing a role an application offers.';
COMMENT ON COLUMN app_fam.fam_privilege_change_type.privilege_change_type_code IS
    'One of GRANT, REVOKE, UPDATE, CREATE_ROLE, DELETE_ROLE. Twenty characters rather than the ten the original table used, because CREATE_ROLE is eleven.';
COMMENT ON COLUMN app_fam.fam_privilege_change_type.description IS
    'What to show a person instead of the code, e.g. "Role revoked".';
COMMENT ON COLUMN app_fam.fam_privilege_change_type.effective_date IS
    'When this code became usable. Defaults to the moment the row is inserted, which for these rows is when the baseline ran.';
COMMENT ON COLUMN app_fam.fam_privilege_change_type.expiry_date IS
    'When this code was retired; null while it is current. Audit rows written while it was live still reference it, so a retired code must still resolve.';

-- DELETE_ROLE takes a role away from everyone who held it, so it is the one
-- change type that can revoke several people's access in a single row. The
-- count of affected users lives in the row's JSONB details, since CSS keeps no
-- record of a role once it is gone.
INSERT INTO app_fam.fam_privilege_change_type
    (privilege_change_type_code, description, create_user, update_user) VALUES
    ('GRANT',       'Role added',   'system', 'system'),
    ('REVOKE',      'Role revoked', 'system', 'system'),
    ('UPDATE',      'Role updated', 'system', 'system'),
    ('CREATE_ROLE', 'Role created', 'system', 'system'),
    ('DELETE_ROLE', 'Role deleted', 'system', 'system');

-- ---------------------------------------------------------------------------
-- Privilege change audit
-- ---------------------------------------------------------------------------

-- Append-only, and free of foreign keys to anything but its own code table.
-- The people involved are recorded as GUIDs plus a JSON snapshot of who they
-- were at the time, never as references: FAM stores no user rows at all, and a
-- grant routinely names somebody who has never signed in. Snapshots also keep
-- the trail truthful after users are renamed or roles redefined.
--
-- update_user and update_date are carried for consistency with every other
-- table and are expected to stay null: nothing updates a row here. A non-null
-- update_user on this table is a finding, not a normal state.
CREATE TABLE app_fam.fam_privilege_change_audit (
    privilege_change_audit_id     UUID         NOT NULL DEFAULT gen_random_uuid(),
    css_integration_id            INTEGER,
    css_environment               VARCHAR(10),
    change_date                   TIMESTAMP(6) NOT NULL,
    change_performer_user_details JSONB        NOT NULL,
    change_target_user_details    JSONB,
    performer_user                VARCHAR(100) NOT NULL,
    target_user                   VARCHAR(100),
    privilege_change_type_code    VARCHAR(20)  NOT NULL,
    privilege_details             JSONB        NOT NULL,
    create_user                   VARCHAR(100) NOT NULL,
    create_date                   TIMESTAMP(6) NOT NULL DEFAULT NOW(),
    update_user                   VARCHAR(100) NOT NULL,
    update_date                   TIMESTAMP(6) NOT NULL DEFAULT NOW(),
    CONSTRAINT fam_privilege_change_audit_pk PRIMARY KEY (privilege_change_audit_id),
    CONSTRAINT fam_privilege_change_type_fk FOREIGN KEY (privilege_change_type_code)
        REFERENCES app_fam.fam_privilege_change_type (privilege_change_type_code)
);

COMMENT ON TABLE app_fam.fam_privilege_change_audit IS
    'Append-only record of privilege changes. CSS keeps no history of its own, so this is the only account of who granted or revoked what.';
COMMENT ON COLUMN app_fam.fam_privilege_change_audit.privilege_change_audit_id IS
    'Surrogate key. A UUID rather than a sequence: rows are written from several pods at once, and an audit identifier that looks like a counter invites being read as "how many changes have there been".';
COMMENT ON COLUMN app_fam.fam_privilege_change_audit.css_integration_id IS
    'The CSS integration the change was made against. With css_environment this identifies what FAM calls an application.';
COMMENT ON COLUMN app_fam.fam_privilege_change_audit.css_environment IS
    'The CSS environment the change was made against, e.g. dev. A CSS integration spans environments, so it takes this and css_integration_id together to identify what FAM calls an application.';
COMMENT ON COLUMN app_fam.fam_privilege_change_audit.change_date IS
    'When the change happened. Distinct from create_date, which is when this row was written.';
COMMENT ON COLUMN app_fam.fam_privilege_change_audit.change_performer_user_details IS
    'Snapshot of who made the change, as JSON. Stored as a copy rather than only an identifier so the trail stays truthful after the performing user is renamed or removed.';
COMMENT ON COLUMN app_fam.fam_privilege_change_audit.change_target_user_details IS
    'Snapshot of who the change was made to, as JSON: username, name and email at the time of the change. Null for a change with no target user, such as CREATE_ROLE, and null when the directory could not be reached - resolving it is best effort and never blocks the change it records. A snapshot rather than a join because FAM stores no row for the target: a grant routinely names somebody who has never signed in.';
COMMENT ON COLUMN app_fam.fam_privilege_change_audit.performer_user IS
    'Who made the change, in the same <TYPE>\<GUID> form as create_user - e.g. IDIR\A1B2C3D4E5F60718293A4B5C6D7E8F90 - or ''system'' when a system process did. The type prefix replaces a separate code column: one value identifies the person and the directory they came from, and it reads the same everywhere in this table.';
COMMENT ON COLUMN app_fam.fam_privilege_change_audit.target_user IS
    'Who the change was made to, in the same <TYPE>\<GUID> form as performer_user. Null for a change with no target user, such as CREATE_ROLE. Not a reference: FAM stores no user rows, and the readable name is in change_target_user_details.';
COMMENT ON COLUMN app_fam.fam_privilege_change_audit.privilege_change_type_code IS
    'What kind of change this row records. The only foreign key this table has - everything else is recorded directly, so history cannot be broken by deleting an operational row.';
COMMENT ON COLUMN app_fam.fam_privilege_change_audit.privilege_details IS
    'What changed, as JSON. The shape follows the change type: a grant or revocation describes the role and scopes; CREATE_ROLE describes the role that was defined.';

-- History is read for one user within one application, most recent first. The
-- identifier is compared case-insensitively, so the index has to be too.
CREATE INDEX fam_privilege_change_audit_history_idx
    ON app_fam.fam_privilege_change_audit
       (css_integration_id, css_environment, UPPER(target_user), change_date DESC);

-- ---------------------------------------------------------------------------
-- Audit column comments
-- ---------------------------------------------------------------------------
--
-- The same four columns, with the same meaning, on every table. Written out
-- per table rather than applied in a loop: a baseline is read far more often
-- than it is run, and plain statements can be checked by a parser without a
-- live server, which a PL/pgSQL body cannot.


COMMENT ON COLUMN app_fam.fam_privilege_change_type.create_user IS
    'Who created this row, as <TYPE>\<GUID> - e.g. IDIR\A1B2C3D4E5F60718293A4B5C6D7E8F90 - or ''system'' when FAM wrote it rather than a person. The prefix is the identity provider''s name, the same form performer_user and target_user use. Deliberately not a reference to anything: FAM stores no user rows, so this must be readable on its own.';
COMMENT ON COLUMN app_fam.fam_privilege_change_type.create_date IS
    'When this row was created. timestamp(6) without a time zone - the containers run TZ=America/Vancouver, so this is BC local time and lines up with the application logs. The cost is that the two 01:30s on the November DST fall-back are indistinguishable.';
COMMENT ON COLUMN app_fam.fam_privilege_change_type.update_user IS
    'Who last changed this row, in the same format as create_user. Filled with create_user on insert rather than left null, so "last touched by" always has an answer instead of encoding "never updated" as an absence a reader has to know about.';
COMMENT ON COLUMN app_fam.fam_privilege_change_type.update_date IS
    'When this row was last changed. Equal to create_date until something updates the row.';


COMMENT ON COLUMN app_fam.fam_privilege_change_audit.create_user IS
    'Who created this row, as <TYPE>\<GUID> - e.g. IDIR\A1B2C3D4E5F60718293A4B5C6D7E8F90 - or ''system'' when FAM wrote it rather than a person. The prefix is the identity provider''s name, the same form performer_user and target_user use. Deliberately not a reference to anything: FAM stores no user rows, so this must be readable on its own.';
COMMENT ON COLUMN app_fam.fam_privilege_change_audit.create_date IS
    'When this row was created. timestamp(6) without a time zone - the containers run TZ=America/Vancouver, so this is BC local time and lines up with the application logs. The cost is that the two 01:30s on the November DST fall-back are indistinguishable.';
COMMENT ON COLUMN app_fam.fam_privilege_change_audit.update_user IS
    'Who last changed this row, in the same format as create_user. Filled with create_user on insert rather than left null, so "last touched by" always has an answer instead of encoding "never updated" as an absence a reader has to know about.';
COMMENT ON COLUMN app_fam.fam_privilege_change_audit.update_date IS
    'When this row was last changed. Equal to create_date until something updates the row. Nothing ever updates this table - it is append-only - so a value here that differs from create_date is a finding, not a normal state.';
