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
| `migrations/`               | Flyway migrations V1-V93, plus DBA-owned scripts.              |
| `common/`                   | Shared OpenShift template (secret, ConfigMap, NetworkPolicy).  |
| `monitoring/`               | Sysdig alert definitions.                                      |

## The database is not deployed by this repository

FAM runs against an **on-prem PostgreSQL instance**. There is no StatefulSet, no
Patroni, no Crunchy operator, and no database in any OpenShift template. The
deploy connects to the on-prem host using credentials from a secret.

Role creation and privilege grants are the DBA team's responsibility and live in
`migrations/dba/`, not in the migrations themselves. See `migrations/README.md`.

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
schema: Testcontainers starts a throwaway PostgreSQL, Flyway applies V1-V93, and
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
`oc_token`

**Variables** - `oc_server`, `keycloak_issuer_uri`, `keycloak_client_id`

**Created out of band** - a secret named `oidc-clients` holding the
per-application Keycloak client ids as
`FLYWAY_PLACEHOLDERS_CLIENT_ID_<ENV>_<APP>_OIDC_CLIENT` (see
`migrations/README.md`), and `<name>-<zone>-integrations` holding the Forest
Client API, IDIM proxy and GC Notify credentials.

Only the frontend has a Route. The backend is reached through it at `/api`.

## The external API

`/external/v1/users` is a published contract for downstream applications, and
differs from the rest of the API in two deliberate ways:

- it is **camelCase**, where the internal API is snake_case;
- it is exempt from the FAM-client token check, and authorised instead by
  `call_api_flag` on the caller's roles.

An application can only ever see its own users: the token's client id both
authorises the call and scopes it.

`/external/v1/users/me/role-metadata` lets an application ask FAM what the
signed-in user may do. It is intentionally not gated on `call_api_flag` — a user
asking about their own access should not need a special role to get an answer.

## Before this can run

- The Keycloak realm must map the claims listed in
  `backend/docs/authentication.md` into the **access** token, not just the ID
  token.
- The `oidc-clients` secret needs real per-application Keycloak client ids before
  migrations can run against a deployed database (see `migrations/README.md`).
- `PUT /users/users-information` needs `FAM_UPDATE_USER_INFO_API_KEY` set, and a
  `fam_user` row for the configured requester (`CMENG` by default) — IDIM audits
  every lookup against a real user. It fails closed if either is missing.

## Not carried over

- **Browser tests.** Upstream had none, and the quickstart's demo suites targeted
  other stacks. `vue-tsc` and the unit tests are the current safety net.
- **BC Services Card decryption** (`kms_lookup`, `bcsc_*`, the BCSC proxy route).
  It decrypted payloads with an AWS KMS key; both the IdP and the key management
  are gone.
