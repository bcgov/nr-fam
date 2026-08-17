[![Merge](https://github.com/bcgov/nr-fam/actions/workflows/merge.yml/badge.svg)](https://github.com/bcgov/nr-fam/actions/workflows/merge.yml)
[![Analysis](https://github.com/bcgov/nr-fam/actions/workflows/analysis.yml/badge.svg)](https://github.com/bcgov/nr-fam/actions/workflows/analysis.yml)

# Forest Access Management (FAM)

FAM defines who has access to which BC natural-resource applications, and the
roles they operate under once access is granted.

This repository is a port of
[nr-forests-access-management](https://github.com/bcgov/nr-forests-access-management)
from AWS serverless to OpenShift. What changed:

| Area           | Was                                         | Now                                       |
| -------------- | ------------------------------------------- | ----------------------------------------- |
| Backend        | Two Python FastAPI Lambdas                  | One Spring Boot service                   |
| Auth           | AWS Cognito + a pre-token-generation Lambda | BC Gov SSO (Keycloak)                     |
| Database       | AWS RDS, provisioned by Terraform           | On-prem PostgreSQL, **not** deployed here |
| Frontend       | Vue 3 on S3/CloudFront                      | Same Vue 3 app, behind Caddy on OpenShift |
| API client     | Two generated TypeScript clients            | One, generated from the running backend   |
| Infrastructure | Terraform                                   | OpenShift templates + GitHub Actions      |

## Layout

| Path                        | What it is                                                    |
| --------------------------- | ------------------------------------------------------------- |
| `backend/`                  | Spring Boot API (Java 21, Maven). See `backend/docs/`.         |
| `frontend/`                 | Vue 3 SPA, served by Caddy.                                    |
| `frontend/client-code-gen/` | The generated API client and its spec. See its README.         |
| `migrations/`               | DBA-owned role and grant scripts. The migrations themselves ship in the backend jar. |
| `common/`                   | Shared OpenShift template (secret, ConfigMap, NetworkPolicy).  |
| `monitoring/`               | Sysdig alert definitions.                                      |

## The database is not deployed by this repository

FAM runs against an **on-prem PostgreSQL instance**. There is no StatefulSet, no
Patroni, no Crunchy operator, and no database in any OpenShift template. The
deploy connects to the on-prem host using credentials from a secret.

Role creation and privilege grants are the DBA team's responsibility and live in
`migrations/dba/`, not in the migrations themselves. See `migrations/README.md`.

The database holds four tables: `fam_user` and `fam_user_type_code` (who has
signed in) and `fam_privilege_change_audit` with `fam_privilege_change_type` (who
granted what to whom, which CSS does not record). Applications, roles and role
assignments are CSS's. The audit deliberately keeps no foreign keys - an audit
row that references mutable operational tables is only as durable as those rows.

**Migrations run inside the application.** `backend/src/main/resources/db/migration`
ships in the jar and Flyway applies it at start-up, before Hibernate validates
the schema, so the schema a build expects and the code that expects it are one
artefact. There is no migrations image and no init container. The consequence is
that the runtime database role needs `CREATE` on `app_fam` - it creates tables,
not just rows. See `migrations/README.md`.

## Running locally

```sh
docker compose up
```

That starts PostgreSQL, then the backend on `:8080` and the frontend on `:3000`.
The backend migrates the database itself on start-up; under the `local` profile
it also applies the seed users in `db/local`.

The frontend is the one you open. It proxies `/api` to the backend and strips
the prefix, mirroring the Caddy config the deployed frontend runs behind, so the
API is same-origin in development too.

To sign in you need a real BC Gov SSO client; set `KEYCLOAK_ISSUER_URI` and
`FAM_OIDC_CLIENT_ID` in your environment first. Without them the stack still
starts, but every authenticated call is rejected.

Running the pieces individually:

```sh
cd backend  && ./mvnw spring-boot:run     # needs a database on :5432
cd frontend && npm run install-frontend && npm run dev
```

The frontend dev server proxies `/api` to the backend and strips the prefix,
matching the Caddy configuration it runs behind in production. The API is
therefore same-origin in both, so CORS is not involved.

## Tests

```sh
cd backend  && ./mvnw verify   # unit tests + SchemaValidationIT (needs Docker)
cd frontend && npm run type-check && npm run test:cov
```

`SchemaValidationIT` is the one check that the JPA entities match the real
schema: Testcontainers starts a throwaway PostgreSQL, the application's own
Flyway configuration applies `db/migration`, and Hibernate validates every
mapping against the result - the same path a deployment takes. H2 cannot stand
in for it, because the schema uses `JSONB` and identity columns. It needs a
Docker daemon, so it fails on machines without one - CI always runs it.

## Changing the API

The OpenAPI document is generated from the **running backend**, and the
frontend's TypeScript client is generated from that document. After changing a
controller or DTO:

```sh
cd backend && ./mvnw test -Dtest=OpenApiSpecGeneratorTest
cd ../frontend/client-code-gen && npm ci && npm run gen-api-client
cd .. && npm run type-check
```

CI fails if the committed `fam-openapi.json` is stale, so this cannot be
forgotten. `frontend/client-code-gen/README.md` documents the contract details
that are easy to break - property naming, enum schema names, and parameter order.

## Deployment

GitHub Actions deploys to OpenShift per pull request, then to test and prod on
merge. Set these on the repository, or per **environment** (`test`, `prod`) where
they differ - the CSS credentials, `css_own_integration_id` and `smtp_from`
usually do.

### Secrets

| Secret | Required | What it is |
| ------ | -------- | ---------- |
| `oc_namespace` | yes | OpenShift namespace |
| `oc_token` | yes | OpenShift token |
| `db_host` | yes | On-prem PostgreSQL host |
| `db_port` | if not 5432 | Port. Defaults to `5432`. |
| `db_name` | yes | Database name |
| `db_user` | yes | Runtime role. **Needs `CREATE` on `app_fam`** - the application runs Flyway at start-up. |
| `db_password` | yes | Password for that role |
| `css_api_client_id` | in practice | CSS API account (CSS app -> My Teams -> CSS API Account) |
| `css_api_client_secret` | in practice | As above |
| `user_lookup_base_url` | in practice | Base URL of nr-user-lookup-api |
| `fc_api_token` | in practice | Forest Client API key. One key for all environments - see below. |
| `keycloak_sa_client_id` | optional | Keycloak admin client that provisions FAM's user-lookup service account. See below. |
| `keycloak_sa_client_secret` | optional | Secret for that admin client |

Not used by the deploy: `sonar_token_backend` and `sonar_token_frontend` (code
analysis, one project each) and `SYSDIG_API_TOKEN` (`analysis.yml` and
`scheduled.yml`). Absent, those jobs fail but no deployment is affected.
`GITHUB_TOKEN` is supplied automatically.

"In practice" means the deploy succeeds without them and the application starts,
but every CSS-backed screen then fails at call time - the credentials are checked
when they are used, not at boot.

### Variables

| Variable | Required | What it is |
| -------- | -------- | ---------- |
| `oc_server` | yes | OpenShift API URL. No default. |
| `keycloak_issuer_uri` | yes | Realm users **sign in** to, e.g. `https://test.loginproxy.gov.bc.ca/auth/realms/standard`. |
| `user_lookup_issuer_uri` | with the SA secrets | Realm the **user-lookup service account** lives in - a different one, e.g. `https://test.loginproxy.gov.bc.ca/auth/realms/forests`. See below. |
| `keycloak_client_id` | yes | FAM's browser client. Also becomes `FAM_OIDC_CLIENT_ID`. |
| `css_own_integration_id` | should | FAM's own CSS integration id, e.g. `22261`. See below. |
| `smtp_host` | for email | Mail relay. **Blank disables sending entirely.** |
| `smtp_from` | for email | Envelope sender. Required, or nothing is sent. |
| `css_api_url` | optional | Defaults to the production CSS API. |
| `css_token_url` | optional | Defaults to the production token endpoint. |

### Two worth understanding

**`css_own_integration_id`.** Administering FAM means deciding who administers
every other application, so FAM's own integration is reserved to `FAM_ADMIN`.
Nothing in a CSS response marks which integration is FAM's, so it has to be told.
Left unset the protection cannot be applied and start-up logs a warning; the
deploy still succeeds, which is what lets a PR preview run without it.

**Email needs both `smtp_host` and `smtp_from`.** Host blank and the application
logs "No SMTP host configured" once at start-up, and grants complete without
notifying anyone. Host set but `from` blank and nothing sends either, because a
relay rejects a message with no envelope sender - so the application refuses up
front rather than failing per message. No credentials are configured: the BC Gov
relay accepts unauthenticated mail from inside the network, so the mail client
keeps its defaults of port 25, no auth and no STARTTLS.

### The user-lookup service account

FAM resolves IDIR and Business BCeID identities from **nr-user-lookup-api**,
which replaced the IDIM proxy. Its client id and secret are **not** configured by
hand: `.github/scripts/ensure-keycloak-service-account.sh` runs on deploy and
idempotently creates a confidential client (`nr-fam-backend`), assigns the three
scopes that API enforces, reads the secret back, and emits both as masked step
outputs. The deploy then stores them in a Secret.

That needs an admin service-account client with the realm-management
`manage-clients` role - `keycloak_sa_client_id` / `keycloak_sa_client_secret`.
Without them the step is skipped, which is what lets a PR preview deploy; the
backend then fails identity lookups loudly rather than returning empty results.

**It is not the login realm.** Users sign in through `standard`, but
nr-user-lookup-api validates its callers against its own realm - `forests` - and
that is the only realm where the user-lookup client scopes are defined. So the
service account is created there, against `user_lookup_issuer_uri`, and the
backend requests its token from that realm too. The admin client has to live in
the same realm, because `manage-clients` is realm-scoped. Point this at the
login realm and the client lands where the scopes do not exist; if it got a
token at all, the API would reject it as issued by the wrong issuer.

The scopes (`user-lookup:idir:search`, `user-lookup:idir:read`,
`user-lookup:business-bceid:read`) are owned by nr-user-lookup-api and must
already exist in the realm. The script errors rather than creating them.

### The Forest Client API

Forest-client-scoped roles need client numbers resolved to names, which comes
from the Forest Client API.

**Every environment uses the PROD instance**, so there is one key,
`fc_api_token`, and one endpoint. The API publishes a TEST instance too, and the
AWS deployment used it for dev and test, but this one does not. The consequence
is that non-prod FAM reads production forest-client data. That data is read-only
reference material - client numbers and names - so a dev deployment cannot change
anything upstream, but it is real data.

The application still has two instance slots, TEST and PROD, picked per request
by `ApiInstanceEnvResolver` (**both** the deployment and the target application
must be prod before PROD is chosen). That machinery stays, because it governs
other integrations; `backend/openshift.deploy.yml` simply points both slots at
the same endpoint and the same key, which makes the choice a no-op here.

The base URL is public and is a template default rather than a deployment
variable. Only the key is secret; it is sent as `X-API-KEY`.

The two failure modes differ, which is worth knowing when reading logs. A blank
**base URL** disables an instance at start-up and logs `Forest Client API PROD
instance not configured`; a blank **key** does not, so the instance initialises
and the first search fails with a 401. A missing key is therefore silent until
someone uses the screen.

**Created out of band** - `<name>-<zone>-integrations`, now holding only the CMENG
shared secret (`FAM_UPDATE_USER_INFO_API_KEY`); it is mounted `optional: true`, so
a deployment without it still starts. Everything else the deploy creates itself:
the database, CSS, user-lookup and Forest Client secrets all come from
`common/openshift.init.yml`, and SMTP is two plain template parameters.

Only the frontend has a Route. The backend is reached through it at `/api`.


## Before this can run

- The Keycloak realm must map the claims listed in
  `backend/docs/authentication.md` into the **access** token, not just the ID
  token.
- **The runtime database role needs `CREATE` on `app_fam`.** The application
  runs Flyway on start-up, so it creates tables rather than only reading and
  writing rows. `migrations/dba/02_app_user_grants.sql` grants it; a DBA runs
  that before the first deploy.
- `PUT /users/users-information` needs `FAM_UPDATE_USER_INFO_API_KEY` set, and a
  `fam_user` row for the configured requester (`CMENG` by default) — IDIM audits
  every lookup against a real user. It fails closed if either is missing.
- **FAM's own CSS integration needs its admin roles.** Admin rights are read from
  the caller's token, so `FAM_ADMIN` and `<APPLICATION_NAME>_ADMIN` have to exist
  in CSS and be assigned. Without them `authorize()` rejects every request. See
  `backend/docs/authentication.md`.

## Not carried over

- **Browser tests.** Upstream had none, and the quickstart's demo suites targeted
  other stacks. `vue-tsc` and the unit tests are the current safety net.
- **BC Services Card decryption** (`kms_lookup`, `bcsc_*`, the BCSC proxy route).
  It decrypted payloads with an AWS KMS key; both the IdP and the key management
  are gone.
