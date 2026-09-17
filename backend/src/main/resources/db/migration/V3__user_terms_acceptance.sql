-- ---------------------------------------------------------------------------
-- Delegated administrator acceptance of the FAM Terms of Use.
-- ---------------------------------------------------------------------------
--
-- A Business BCeID delegated administrator must accept the Terms of Use before
-- they can administer anything, and must accept again whenever the terms change.
-- The legacy application recorded that in fam_user_terms_conditions, which went
-- with fam_user in the baseline.
--
-- This is the one piece of delegated administration CSS cannot hold. The roles
-- say who is a delegated administrator; nothing in CSS can say that the person
-- agreed to the terms, when, and which version. That is a legal record, so it
-- belongs here next to the audit trail rather than on a role attribute someone
-- could edit.
--
-- No foreign key to a user, for the same reason the audit has none: FAM keeps no
-- user table. The person is named as <TYPE>\<GUID>, the same form as
-- create_user, and the username and organisation are snapshots as at acceptance.
--
-- One row per person per version, append-only. Bumping
-- FamConstants.CURRENT_TERMS_AND_CONDITIONS_VERSION asks everyone again and
-- keeps the record of what they agreed to before.
-- ---------------------------------------------------------------------------

CREATE TABLE app_fam.fam_user_terms_acceptance (
    user_terms_acceptance_id UUID         NOT NULL DEFAULT gen_random_uuid(),
    accepted_user            VARCHAR(100) NOT NULL,
    user_name                VARCHAR(100),
    business_guid            VARCHAR(32),
    terms_version            VARCHAR(30)  NOT NULL,
    create_user              VARCHAR(100) NOT NULL,
    create_date              TIMESTAMP(6) NOT NULL DEFAULT NOW(),
    update_user              VARCHAR(100) NOT NULL,
    update_date              TIMESTAMP(6) NOT NULL DEFAULT NOW(),
    CONSTRAINT fam_user_terms_acceptance_pk PRIMARY KEY (user_terms_acceptance_id),
    CONSTRAINT fam_user_terms_acceptance_version_uk UNIQUE (accepted_user, terms_version)
);

COMMENT ON TABLE app_fam.fam_user_terms_acceptance IS
    'Which version of the FAM Terms of Use each Business BCeID delegated administrator has accepted, and when. Append-only: one row per person per version, so the record of an earlier acceptance survives a change to the terms.';
COMMENT ON COLUMN app_fam.fam_user_terms_acceptance.user_terms_acceptance_id IS
    'Surrogate key.';
COMMENT ON COLUMN app_fam.fam_user_terms_acceptance.accepted_user IS
    'Who accepted, as <TYPE>\<GUID> - e.g. BCEID_BUS\A1B2C3D4E5F60718293A4B5C6D7E8F90. Not a foreign key: FAM keeps no user table, and the token is where the identity comes from.';
COMMENT ON COLUMN app_fam.fam_user_terms_acceptance.user_name IS
    'The username at the time of acceptance, e.g. JSMITH. A snapshot for whoever reads this table, never used to match a person - the GUID in accepted_user does that.';
COMMENT ON COLUMN app_fam.fam_user_terms_acceptance.business_guid IS
    'The Business BCeID organisation the person accepted on behalf of, as at acceptance. The terms bind the organisation (the Subscriber), not only the individual.';
COMMENT ON COLUMN app_fam.fam_user_terms_acceptance.terms_version IS
    'The version of the Terms of Use accepted. Matches FamConstants.CURRENT_TERMS_AND_CONDITIONS_VERSION at the time, which the frontend text must be kept in step with.';
COMMENT ON COLUMN app_fam.fam_user_terms_acceptance.create_user IS
    'Who created this row, as <TYPE>\<GUID>. Always the accepting person - acceptance is never recorded on somebody''s behalf.';
COMMENT ON COLUMN app_fam.fam_user_terms_acceptance.create_date IS
    'When this row was created, which is when the terms were accepted. timestamp(6) without a time zone, BC local time.';
COMMENT ON COLUMN app_fam.fam_user_terms_acceptance.update_user IS
    'Who last changed this row, in the same format as create_user. Filled with create_user on insert; nothing updates these rows.';
COMMENT ON COLUMN app_fam.fam_user_terms_acceptance.update_date IS
    'When this row was last changed. Equal to create_date, since nothing updates these rows.';
