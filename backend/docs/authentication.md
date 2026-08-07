# Authentication: Cognito → BC Gov SSO (Keycloak)

FAM previously authenticated against an AWS Cognito user pool. It now authenticates
against BC Gov SSO (Keycloak), and this backend is a **resource server only** — it
validates access tokens, it never performs the login redirect.

## What moved, and why

Cognito ran a **pre-token-generation Lambda** (`server/auth_function`) on every
login. That trigger did two things:

1. created or refreshed the user's `app_fam.fam_user` row from the IdP attributes;
2. ran a database query and injected the user's FAM roles into the token's
   `cognito:groups` claim.

A BC Gov SSO realm cannot run application code at token-generation time without a
custom Keycloak SPI, which is not something a tenant can deploy. Both halves
therefore moved into this service:

| Cognito                                   | Now                                                    |
| ----------------------------------------- | ------------------------------------------------------ |
| `populate_user_if_necessary`              | `UserProvisioningService`, via `POST /auth/login`      |
| `access_token_groups_override`            | `AccessRoleResolver`, per request                      |
| `cognito:groups` claim                    | resolved from the database                             |
| `jwt_validation.validate_token`           | Spring Security OAuth2 resource server                 |
| `enforce_fam_client_token`                | `FamClientTokenFilter`                                 |

### One behavioural change

Roles are no longer fixed at login. They are resolved from the database on every
request, so **revoking access takes effect immediately** rather than at the next
token refresh. This is stricter than the Cognito behaviour, not looser.

### The login bootstrap is required

The frontend must call `POST /auth/login` once, immediately after a successful
Keycloak sign-in and before any other API call. Until it does, a first-time user
holds a valid token but has no `fam_user` row, and every other endpoint answers
`403 requester_not_exists`.

`POST /auth/login` is idempotent and also refreshes the stored name and email, so
calling it on every sign-in and token refresh is correct.

## Claims this service reads

Names follow the [BC Gov SSO Identity Mappers reference](https://github.com/bcgov/sso-docs).
All reading is confined to `TokenClaimsReader`.

| FAM field                       | IDIR              | Business BCeID        |
| ------------------------------- | ----------------- | --------------------- |
| user name                       | `idir_username`   | `bceid_username`      |
| user GUID                       | `idir_user_guid`  | `bceid_user_guid`     |
| business GUID                   | —                 | `bceid_business_guid` |
| `fam_user.cognito_user_id`      | `preferred_username` (`<guid>@idir`) | `preferred_username` (`<guid>@bceidbusiness`) |
| calling application             | `azp`             | `azp`                 |
| identity provider               | `identity_provider` | `identity_provider` |

Also read where present: `given_name`, `family_name`, `email`.

**Supported providers are an allowlist**: `idir`, `azureidir`, `bceidbusiness`.
Everything else — including `bceidbasic`, `bceidboth` and BC Services Card — is
rejected. FAM only ever supported IDIR and Business BCeID, and a Basic BCeID user
would have no organisation, which the same-organisation rules cannot reason about.

GUIDs are normalised to bare uppercase hex, because that is how they are stored and
how the `fam_usr_uk` constraint matches them.

## Realm configuration required

1. **A FAM client**, whose id is configured as `FAM_OIDC_CLIENT_ID`. Tokens on the
   internal API must carry it as `azp`; `/external/**` is exempt, since that
   surface exists for other applications' clients and is authorised separately by
   `call_api_flag`.
2. **A client per downstream application**, per environment. Their ids are seeded
   into `app_fam.fam_application_client` by the migrations — see
   `migrations/README.md` for the 51 `FLYWAY_PLACEHOLDERS_CLIENT_ID_*` values.
3. **The claims above must be mapped into the access token**, not only the ID
   token. `identity_provider` and the provider-specific claims are what FAM keys
   on; a token missing the user GUID is rejected with `missing_key_attribute`.

No Keycloak role or group mapping is needed. FAM does not read roles from the
token.

## Column names

`fam_user.cognito_user_id` and `fam_application_client.cognito_client_id` keep
their names despite no longer having anything to do with Cognito. They are seeded
by 50+ migrations, and renaming them would mean rewriting that history. In Java
they are mapped as `oidcUserId` and `oidcClientId`.

## Dropped

`kms_lookup.py`, `bcsc_decryption.py`, `bcsc_jwk.py` and `router_bcsc_proxy.py`
were not ported. They existed to decrypt BC Services Card payloads using an AWS KMS
key, and both the IdP and the key management are gone.
