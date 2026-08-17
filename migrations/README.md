# Database migrations

Flyway migrations for the FAM `app_fam` schema.

`V1__baseline.sql` is the whole schema. It replaces the 95 migrations inherited
from the Python service, which recorded how FAM's own tables grew and were then
largely dismantled — applications, roles and role assignments moved to CSS, and
the last of those migrations dropped the twenty tables that held them. Replaying
that onto an empty database would create sixteen tables in order to drop them
again. The full history remains in git if it is ever needed.

Four tables are left: `fam_user` and `fam_user_type_code` (who has signed in),
and `fam_privilege_change_audit` with `fam_privilege_change_type` (who granted
what to whom, which CSS does not record).

## Layout

| Path         | Purpose                                                            |
| ------------ | ------------------------------------------------------------------ |
| `sql/`       | Versioned migrations, run by the pipeline and by docker-compose.    |
| `local_sql/` | Local-development seed data only (V1000+). Never runs in a deployed environment. |
| `conf/`      | Local-development Flyway config. Near-empty since the baseline.     |
| `dba/`       | Scripts the DBA team runs out of band. See `dba/README.md`.         |

## The database is not deployed by this repo

FAM runs against an **on-prem PostgreSQL instance**. There is no database
StatefulSet, no Patroni, no Crunchy operator. The deploy pipeline connects to the
on-prem host with credentials from an OpenShift secret and runs Flyway against it.

Role creation and privilege grants are the DBA team's responsibility and live in
`dba/`, not in `sql/`. The baseline creates the schema and its tables and nothing
else — it grants no rights and creates no database users.


## Running locally

```sh
docker compose up migrations
```
