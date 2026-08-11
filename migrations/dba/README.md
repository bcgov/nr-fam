# DBA-owned database setup

The FAM database lives on an **on-prem PostgreSQL instance that this repository does
not deploy**. Role creation and privilege administration are owned by the DBA team,
so they are deliberately absent from the Flyway migrations in `../sql`.

Everything in this directory is run **by a DBA**, out of band, not by the deploy
pipeline.

## Order of operations

1. DBA creates the database, the `app_fam` schema owner, and the application role
   (`01_create_roles.sql`).
2. The deploy pipeline runs Flyway (`../sql`, `V1`..`V93`) as the schema owner.
   This creates and evolves all `app_fam` objects.
3. DBA grants the application role its runtime privileges (`02_app_user_grants.sql`).
   Re-run this after any migration that adds a table.

## Why the grants are not in Flyway

Upstream FAM ran on AWS with **three** separate database users, each administered
through Flyway with placeholder substitution:

| Former AWS DB user              | Consumer                          |
| ------------------------------- | --------------------------------- |
| `${api_db_username}`            | app-access-control API Lambda     |
| `${admin_management_api_db_user}`| admin-management API Lambda      |
| `${auth_lambda_db_user}`        | Cognito pre-token-generation Lambda |

Those migrations did not simply grant — `V30` and `V31` *revoked* broad privileges
and re-granted narrow ones per user. Collapsing three users into the single merged
backend and replaying those statements in order would have left the application
under-privileged. The 51 `CREATE USER` / `GRANT` / `REVOKE` /
`ALTER DEFAULT PRIVILEGES` statements were therefore removed from the migrations
(the affected migrations are retained as numbered placeholders so the version
sequence stays contiguous) and replaced by `02_app_user_grants.sql`, which grants
the **union** of what the three users previously held.

`02_app_user_grants.sql` is intentionally schema-wide rather than per-table. The
merged backend performs every operation the three former users performed between
them, which in practice was full DML on every table in `app_fam`. Tighten it if
your security review calls for it.
