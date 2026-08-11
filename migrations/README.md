# Database migrations

Flyway migrations for the FAM `app_fam` schema, ported from
`nr-forests-access-management/server/flyway/sql` (V1–V93, full history preserved).

## Layout

| Path         | Purpose                                                            |
| ------------ | ------------------------------------------------------------------ |
| `sql/`       | Versioned migrations, run by the pipeline and by docker-compose.    |
| `local_sql/` | Local-development seed data only (V1000+). Never runs in a deployed environment. |
| `conf/`      | Local-development Flyway config (OIDC client-id placeholders).      |
| `dba/`       | Scripts the DBA team runs out of band. See `dba/README.md`.         |

## The database is not deployed by this repo

FAM runs against an **on-prem PostgreSQL instance**. There is no database
StatefulSet, no Patroni, no Crunchy operator. The deploy pipeline connects to the
on-prem host with credentials from an OpenShift secret and runs Flyway against it.

Role creation and privilege grants are the DBA team's responsibility and live in
`dba/`, not in `sql/`. The 51 `CREATE USER` / `GRANT` / `REVOKE` statements that
administered the three AWS-era Lambda DB users were removed during the port; the
migrations that contained nothing else are retained as numbered placeholders so
the version sequence stays contiguous.

## Placeholders

The seed-data migrations substitute an OIDC client id for each downstream
application, per environment — 51 placeholders named
`client_id_<env>_<app>_oidc_client`, plus `client_id_fam_console`,
`client_id_fom_ministry` and `client_id_fom_public`.

- **Local dev**: `conf/flyway.local.conf` supplies dummy values.
- **Deployed**: supplied as `FLYWAY_PLACEHOLDERS_<NAME>` environment variables
  from an OpenShift secret. There are no defaults, so a missing value fails the
  migration rather than seeding a bogus client.

These were AWS Cognito client ids upstream and are now Keycloak client ids. The
target column is still named `fam_application_client.cognito_client_id`; renaming
it is tracked with the backend auth work.

## Running locally

```sh
docker compose up migrations
```
