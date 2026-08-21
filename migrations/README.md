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

## The baseline was rewritten

`V1__baseline.sql` was restated rather than amended - audit columns on every
table, `IDIR`/`BCEID_BUS` user type codes, UUID keys - and `V2` was folded into
it. Flyway checksums what it applied, so **any database that already ran the old
V1 will refuse to start**:

```
Migration checksum mismatch for migration version 1
Detected applied migration not resolved locally: delete role change type
```

The fix is to drop the schema and let the application rebuild it. This is only
safe because FAM is not yet in use; it destroys every row.

```sql
DROP SCHEMA app_fam CASCADE;
```

Then re-run `dba/01_create_roles.sql` and `dba/02_app_user_grants.sql` (they
create the schema with the right ownership and default privileges) and restart
the backend, which applies the baseline.

## No local seed data

There is none, and there is nothing left to seed: the only tables are the audit
trail and its code table, and the code table is populated by the baseline itself.

A `db/local` seed used to exist and was applied under the `local` profile. It
seeded `fam_user`, a table that no longer exists. It was removed before that
because it bought nothing and cost something real: a
developer whose `local` profile pointed at a deployed environment's database
seeded that database, and the next deployment could not resolve the migration:

```
Detected applied migration not resolved locally: seed local test users
```

No `ignore-migration-patterns` setting is carried for it. That was a transitional
allowance while a deployed database still had the seed in its history, and it is
gone along with the schema it applied to. Every migration is now resolved
locally, and one going missing fails the deployment loudly - which is the point.

### Keep the `local` profile off a deployed environment's database

The seed is gone, but the hazard behind it is not: the `local` profile still runs
Flyway, so pointing it at a database a deployed environment uses means your
machine migrates that environment's schema. A migration you are still iterating
on lands there, and that environment cannot start until you finish it.

Use the `docker compose` database, or one of your own.
