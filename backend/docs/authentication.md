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
| `fam_user.oidc_user_id`         | `preferred_username` (`<guid>@idir`) | `preferred_username` (`<guid>@bceidbusiness`) |
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
2. **A client per downstream application**, per environment — owned by CSS, not
   by FAM. FAM used to seed their ids into `app_fam.fam_application_client`; that
   table and the 51 Flyway placeholders that filled it went with the move to CSS,
   and nothing here records them any more.
3. **The claims above must be mapped into the access token**, not only the ID
   token. `identity_provider` and the provider-specific claims are what FAM keys
   on; a token missing the user GUID is rejected with `missing_key_attribute`.
4. **Roles must reach the access token**, as either `client_roles` (what CSS
   emits) or `resource_access.<client>.roles` (stock Keycloak). Both are read,
   `client_roles` first.

## Administrative roles

FAM has three tiers. They are Keycloak roles read from the caller's access token,
and they live on **FAM's own CSS integration** - not on the integration of the
application being administered.

That placement matters: a token carries `client_roles` for the client it was
issued to, so a role sitting on another application's integration would never
reach FAM. The application therefore has to be named *inside* the role.

| Role | Grants |
| ---- | ------ |
| `FAM_ADMIN` | Everything, in every application and environment. |
| `APP_ADMIN_<integrationId>_<ENV>` | Grant and revoke roles for that one application, **and** appoint delegated administrators for it. |
| `DELEGATED_ADMIN_<integrationId>_<ENV>` | Grant and revoke roles for that one application. **May not** appoint administrators. |

Examples, all created on FAM's integration:

```
FAM_ADMIN
APP_ADMIN_22264_DEV
DELEGATED_ADMIN_22264_DEV
```

### Why the integration id

FAM identifies a CSS application as `(integrationId, environment)` and already
holds both halves, so it can derive the expected role name without anything to
configure. The project name was the alternative and was rejected: renaming a
project in CSS would silently revoke everyone's access, and "Forests Stewardship
Plan" does not slug to anything an administrator would recognise.

**Environment is part of the name on purpose.** Administering DEV does not imply
administering PROD; those are separate grants.

### The line between APP_ADMIN and DELEGATED_ADMIN

Both tiers grant and revoke ordinary user access. The only difference is that an
application administrator may appoint delegated administrators and a delegated
administrator may not.

That single rule is what makes the tier real. Appointing an administrator happens
through the same grant endpoint as any other role - the role being granted just
happens to be one of FAM's own - so the endpoint checks whether the role being
granted is administrative and applies the stricter rule when it is. Without that,
a delegated administrator could grant themselves `APP_ADMIN_<app>_<env>` through
the ordinary path and the distinction would be decorative.

### How the checks are applied

| Operation | Requires |
| --------- | -------- |
| List applications | any tier - the list is filtered to what the caller administers |
| List an application's roles | any tier, for that application |
| List an application's assignments | any tier, for that application |
| Grant or revoke an application role | any tier, for that application |
| Grant or revoke a FAM administrative role | `FAM_ADMIN` or `APP_ADMIN`, for that application |
| **Define a new role on an application** | `FAM_ADMIN`, in any application |

Defining a role is the one operation an application administrator is refused for
their *own* application. Every other row decides who holds a role that already
exists; this one decides what the application's roles mean, which is a change to
its authorisation model rather than to one person's access. See
[css-role-format.md](css-role-format.md) for how a role is represented once
created.

A caller holding no tier for an application cannot see it in the picker and
cannot act on it, even if the CSS API account can see it - that account is team
scoped and returns every integration the team owns, so the filtering is FAM's
job.

### Business BCeID administrators and their organisation

A Business BCeID administrator is external, so they may only deal with users at
their own company. The rule is one comparison - the requester's `business_guid`
against the target's - but *where* it is applied is what matters, and upstream
applied it in three places:

| | upstream | here |
| --- | --- | --- |
| Look up a BCeID user | restricted | yes - `IdentityLookupController` |
| Grant a role | `enforce_bceid_by_same_org_guard` | yes - `TargetOrganizationGuard` |
| List an application's users | filtered in the query | yes - `AssignmentVisibilityService` |

Two properties of the grant check are load-bearing:

- **The target's organisation comes from the directory, never from the request.**
  The caller supplies a GUID and a user type; both are claims about somebody
  else. A caller who could assert their target's organisation could assert their
  way past the rule.
- **It fails closed.** If the directory cannot be reached the grant does not
  happen. An unverifiable organisation is not a matching one.

A BCeID administrator may not grant to an IDIR user at all: an IDIR user belongs
to no business, so no organisation could match.

The listing applies the same rule on the way out: a BCeID administrator sees
only BCeID users from their own organisation. CSS carries no organisation, so
each distinct user is resolved against the directory - IDIR rows are dropped
first and cost nothing, and one lookup serves however many roles a user holds.

Two policies differ from the name enrichment that runs alongside it, and the
difference is deliberate:

- **No cap on lookups.** Enrichment stops at 25 because names are cosmetic. A cap
  on a filter would have to either drop rows it could not check - a listing that
  silently understates who has access - or admit them unchecked, which is the
  hole it exists to close.
- **A directory failure is raised, not swallowed.** Enrichment degrades to
  showing GUIDs. Here the outcome decides which rows exist, and an administrator
  shown a silently shortened list would conclude those users have no access and
  act on it.

### FAM's own integration is FAM_ADMIN only

Administering FAM is deciding who administers every other application, so FAM's
own CSS integration is reserved to `FAM_ADMIN`. An `APP_ADMIN` or
`DELEGATED_ADMIN` role naming FAM's integration grants nothing - the tier is
overridden, not consulted.

FAM has to be told which integration is its own; nothing in a CSS response marks
it:

```
CSS_OWN_INTEGRATION_ID=22261
```

Left unset, the protection cannot be applied and startup logs a warning saying so.

**The filtering is not the control.** The application list omits it for anyone
below `FAM_ADMIN`, but the guard on every other endpoint is what actually stops a
caller who knows the integration id from calling the roles, assignment or grant
endpoints directly. Both use the same predicate so they cannot drift apart.

A refusal on FAM's own integration returns the same message as any other
insufficient-privilege refusal. Saying "this is FAM's own integration" would
confirm which id it is to a caller who was guessing.

### What this costs

Resolving per request meant a revocation took effect on the next call. Reading
from the token means it takes effect at the next refresh - every three minutes,
per the frontend's refresh interval. A user whose access is pulled keeps it for
up to one refresh cycle.

## Column names

`fam_user.oidc_user_id` holds the OIDC subject claim. It was called
`cognito_user_id` until the baseline migration replaced the inherited history:
the reason for keeping the old name - that renaming it meant rewriting 50+
migrations of seed data - went with them. `fam_application_client.cognito_client_id`
is gone entirely, along with the table.

## Dropped

`kms_lookup.py`, `bcsc_decryption.py`, `bcsc_jwk.py` and `router_bcsc_proxy.py`
were not ported. They existed to decrypt BC Services Card payloads using an AWS KMS
key, and both the IdP and the key management are gone.
