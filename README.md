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
| `migrations/`               | Flyway migrations V1-V94, plus DBA-owned scripts.              |
| `common/`                   | Shared OpenShift template (secret, ConfigMap, NetworkPolicy).  |
| `monitoring/`               | Sysdig alert definitions.                                      |

## The database is not deployed by this repository

FAM runs against an **on-prem PostgreSQL instance**. There is no StatefulSet, no
Patroni, no Crunchy operator, and no database in any OpenShift template. The
deploy connects to the on-prem host using credentials from a secret.

Role creation and privilege grants are the DBA team's responsibility and live in
`migrations/dba/`, not in the migrations themselves. See `migrations/README.md`.

Since V94 the database holds very little: applications, roles and role
assignments moved to CSS, and what remains is `fam_user` and the privilege change
audit. The audit deliberately keeps no foreign keys - an audit row that
references mutable operational tables is only as durable as those rows.

## Running locally

```sh
docker compose up
```

That starts PostgreSQL, applies the migrations (with dummy OIDC client ids from
`migrations/conf/flyway.local.conf`), runs the backend on `:8080` and the
frontend on `:3000`.

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
schema: Testcontainers starts a throwaway PostgreSQL, Flyway applies V1-V94, and
Hibernate validates every mapping against the result. It needs a Docker daemon,
so it fails on machines without one - CI always runs it.

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
merge. Each environment needs:

**Secrets** - `db_host`, `db_name`, `db_user`, `db_password`, `oc_namespace`,
`oc_token`, `css_client_id`, `css_client_secret`, `user_lookup_base_url`,
`keycloak_sa_client_id`, `keycloak_sa_client_secret`

**Variables** - `oc_server`, `keycloak_issuer_uri`, `keycloak_client_id`

**Variables you should set** - `css_own_integration_id`: FAM's own CSS
integration id, e.g. `22261`. Administering FAM means deciding who administers
every other application, so that integration is reserved to `FAM_ADMIN`. Nothing
in a CSS response marks it, so FAM has to be told. Left unset the protection
cannot be applied and startup logs a warning; it is not required, so a PR preview
without it still deploys.

Optional variables, all defaulted - set only to override: `db_port`,
`css_api_url`, `css_token_url`, `css_idp_alias_idir`, `css_idp_alias_bceid`.

`css_idp_alias_idir` is the one worth knowing about. It is Keycloak's federated
username suffix for IDIR, and the standard realm carries two IDIR integrations:
`azureidir` (Entra-backed, what BC Gov staff actually sign in through) and the
legacy `idir`. CSS has no user search, so FAM constructs the username exactly -
and assigning a role to a username that does not exist is accepted silently, so
the wrong value makes every IDIR grant appear to succeed while doing nothing.

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

The scopes (`user-lookup:idir:search`, `user-lookup:idir:read`,
`user-lookup:business-bceid:read`) are owned by nr-user-lookup-api and must
already exist in the realm. The script errors rather than creating them.

**Created out of band** - a secret named `oidc-clients` holding the
per-application Keycloak client ids as
`FLYWAY_PLACEHOLDERS_CLIENT_ID_<ENV>_<APP>_OIDC_CLIENT` (see
`migrations/README.md`), and `<name>-<zone>-integrations` holding the Forest
Client API credentials and the SMTP relay settings
(`SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_FROM`).

Only the frontend has a Route. The backend is reached through it at `/api`.


## Before this can run

- The Keycloak realm must map the claims listed in
  `backend/docs/authentication.md` into the **access** token, not just the ID
  token.
- The `oidc-clients` secret needs real per-application Keycloak client ids before
  migrations can run against a deployed database (see `migrations/README.md`).
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
