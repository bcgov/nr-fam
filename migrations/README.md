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

## No local seed data

There is none, deliberately. `fam_user` rows are created by signing in - the
first `POST /auth/login` provisions the caller - so a local database needs no
seeding to be usable.

A `db/local` seed used to exist and was applied under the `local` profile. It was
removed because it bought nothing (nothing reads `fam_user` except the caller's
own provisioning and the user-info refresh batch) and cost something real: a
developer whose `local` profile pointed at a deployed environment's database
seeded that database, and the next deployment could not resolve the migration:

```
Detected applied migration not resolved locally: seed local test users
```

`spring.flyway.ignore-migration-patterns: repeatable:missing` is kept for exactly
that case - a database that already has the seed in its history starts cleanly
without anyone editing `flyway_schema_history`. A *versioned* migration going
missing still fails the deployment loudly.

If a seeded row is unwanted, it can go:

```sql
DELETE FROM app_fam.fam_user WHERE user_name LIKE 'LOCAL%';
```

### Keep the `local` profile off a deployed environment's database

The seed is gone, but the hazard behind it is not: the `local` profile still runs
Flyway, so pointing it at a database a deployed environment uses means your
machine migrates that environment's schema. A migration you are still iterating
on lands there, and that environment cannot start until you finish it.

Use the `docker compose` database, or one of your own.
