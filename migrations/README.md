# Database administration

The **migrations themselves are no longer here.** They ship inside the backend
jar at `backend/src/main/resources/db/migration` and Flyway runs them on
application start-up, before Hibernate validates the schema. That keeps the
schema a build expects and the code that expects it in one artefact, which
cannot be deployed out of step — there is no migrations image, no init
container, and nothing to sequence.

What is left here is the work a DBA does out of band.

| Path    | Purpose                                                        |
| ------- | -------------------------------------------------------------- |
| `dba/`  | Role creation and grants. Run by a DBA, never by the pipeline.  |

## The database is not deployed by this repo

FAM runs against an on-prem PostgreSQL instance. There is no database
StatefulSet, no Patroni, no Crunchy operator. The application connects with
credentials from an OpenShift secret.

## The runtime user now needs DDL rights

This is the one consequence of moving migrations into the application. The role
the app connects as creates tables, not just rows, so it needs `CREATE` on the
`app_fam` schema — see `dba/02_app_user_grants.sql`. Previously the migrations
container could run as `fam_owner` while the app ran as the less privileged
`fam_app`; with one process doing both, that split is gone.

If your security posture requires keeping it, the alternative is to run Flyway
as a separate job again — set `spring.flyway.enabled=false` on the app and apply
`backend/src/main/resources/db/migration` with the Flyway CLI as `fam_owner`.

## Local seed data

`backend/src/main/resources/db/local/V1000__seed_local_test_users.sql` is applied
only under the `local` profile, which adds `classpath:db/local` to
`spring.flyway.locations`. It ships in the jar and is never applied anywhere
else.
