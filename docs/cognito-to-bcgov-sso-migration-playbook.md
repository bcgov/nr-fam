# Migrating off FAM/Cognito to BC Gov SSO (Keycloak)

A field guide written from the REPT (`bcgov/nr-rept`) migration, August 2026.
Aimed at teams whose app currently authenticates through **FAM legacy → AWS Cognito
+ Amplify** and wants to move to **BC Gov SSO (Keycloak) via CSS**.

Reference stack: React 19 + Vite SPA, Spring Boot resource server, OpenShift Silver,
GitHub Actions. Most of this transfers; the Java specifics won't if you're on Node.

**Scale of the change:** 43 tracked files, **+1,248 / −2,449 lines**, plus 4 new files.
Note the shape — this migration *deletes roughly twice what it adds*. If your diff is
growing, you're probably porting Cognito's machinery instead of dropping it.

---

## 1. The one-paragraph summary

Amplify/Cognito made you drive the OAuth flow by hand: configure token storage before
`configure()`, run your own logout chain, fetch `/oauth2/userInfo` on every request to get
profile claims. Keycloak with `oidc-client-ts` does almost all of that for you — you give it
one issuer URI and it discovers every endpoint, handles PKCE, stores tokens, and renews from
the refresh token. **The work is mostly subtraction.** The risk is not in what you write; it's
in the handful of Cognito assumptions that fail *silently* against Keycloak.

---

## 2. Dependency swap

| Out | In |
|---|---|
| `aws-amplify` | `oidc-client-ts` |

That's the whole frontend dependency change. No `@aws-amplify/*` subpackages, no adapter layer.

---

## 3. Files: delete, add, rewrite

### Delete outright
| File | Why |
|---|---|
| `config/fam/config.ts` | Amplify config object. `oidc-client-ts` needs one issuer URI. |
| `context/auth/logoutChain.ts` | The Siteminder → Keycloak → Cognito chain collapses to one `signoutRedirect()`. |
| `security/CognitoUserInfoService.java` | Profile claims ride the access token now — no per-request `/oauth2/userInfo`. |

### Add
| File | Purpose |
|---|---|
| `services/keycloak.ts` | The `UserManager` singleton, settings, `needsRenewal`, `ensureFreshUser`, `forceRenew`. |
| `pages/AuthCallback/index.tsx` | The code exchange. Amplify did this implicitly inside `configure()`. |
| `util/JwtPrincipalUtilTest.java` | Pins the claim-mapping rules in §5. Write this one; it catches the worst bug. |

### Rewrite
`AuthProvider.tsx`, `authUtils.ts`, `refreshSession.ts`, `types.ts`, `AuthContext.tsx`,
`main.tsx`, `services/APIs.ts`, `services/http/headers.ts`, `SessionTimeout/index.tsx`,
`Oauth2SecurityCustomizer.java`, `JwtPrincipalUtil.java`, `LoggedUserHelper.java`,
`RoleConstants.java`.

**`main.tsx` gets emptier, not fuller.** All the Amplify token-storage bootstrapping — the
`CookieStorage` block that had to run *before* `Amplify.configure()` — simply goes. If you're
tempted to replace it with equivalent `oidc-client-ts` bootstrapping, don't; the `UserManager`
is created lazily on first use instead.

---

## 4. The five silent failures

**This is the section worth reading twice.** Everything here fails without an error message,
a stack trace, or a failing test. Budget your review time here, not on the plumbing.

### 4.1 `token_use` will 401 every request
Cognito JWTs carry `token_use: "id" | "access"`, and most FAM-era resource servers reject
anything that isn't `"access"`. **Keycloak does not emit `token_use` at all.** Leave that
validator in and every single request 401s — including your health checks, so it can look
like a deploy failure rather than an auth failure.

Replace it with an **`azp` check**: the standard realm is shared across many BC Gov apps, and
they're all signed by the same issuer and verifiable against the same JWKS. Signature + issuer
validation alone does **not** prove a token was meant for your app. Take the expected client id
from config, not a constant — it differs per environment.

### 4.2 `azureidir` silently corrupts your audit trail
If you use **IDIR - MFA** (most teams do), the realm reports `identity_provider` as
**`azureidir`**, not `idir`. If you build audit strings like `IDIR\jsmith` for database
`create_user` / `update_user` columns, a naive port starts writing **`AZUREIDIR\jsmith`** for
the same human being. Nothing errors. No test fails. Your audit trail just stops joining up
from the cutover date — including against rows that predate the application.

Normalise **every** IDIR alias (`idir`, `azureidir`, any case) to the single string your
columns already hold.

### 4.3 A wrong `kc_idp_hint` looks like it works
An unrecognised `kc_idp_hint` is **silently ignored** — Keycloak falls through to whatever
provider the client has. On a single-provider integration the wrong value still lands in the
right place, right up until someone adds a second provider. Verify the alias explicitly
(see §10) rather than concluding from a successful login.

### 4.4 Mixed-case GUIDs split one user into two
`idir_user_guid` and the GUID embedded in `preferred_username` (`<guid>@azureidir`) can arrive
in **different cases in the same token** — typically uppercase and lowercase respectively.
If you ever fall back to the GUID for identity (which happens whenever `idir_username` is
absent), `IDIR\0a1b…` and `IDIR\0A1B…` become two rows for one person.

Case-fold the GUID at the same place you normalise the provider. A GUID is a 128-bit number;
its hex spelling carries no meaning. A *username* is a name — leave that one alone.

### 4.5 Clearing tokens before sign-out leaves the realm session alive
Cognito code typically calls `clearStoredTokens()` **first**, because it drove the redirect
chain by hand. **Invert this.** `oidc-client-ts` reads `id_token_hint` off the stored user and
removes it itself. Clear first and you send a logout Keycloak can't attribute to a session:
the realm session survives, and the next sign-in walks straight back in with no prompt — which
reads as "logout is broken" but is actually "logout was un-attributable".

Catch a failed redirect and fall back to `removeUser()` + navigate home.

---

## 5. Claims, roles, and the JWKS path

| Concern | Cognito (was) | Keycloak (now) |
|---|---|---|
| Username | `custom:idp_username` | `idir_username` |
| User GUID | `custom:idp_user_id` | `idir_user_guid` |
| Provider | `custom:idp_name` | `identity_provider` |
| Display name | `custom:idp_display_name` | `display_name` |
| JWKS | `/.well-known/jwks.json` | **`/protocol/openid-connect/certs`** |
| Profile claims | ID token only → `/oauth2/userInfo` per request | **On the access token** |

**Roles arrive in one of two places.** CSS emits `client_roles`; stock Keycloak uses
`resource_access.<azp>.roles`. Which one you get depends on the realm's mappers — **read both**.

**FAM sidecar roles reach the token.** FAM records per-grant expiry as a role assigned to the
person, shaped `FAM:EXPIRES:2026-09-30:YOUR_ROLE`. Harmless to exact-match authorisation, but
they show up anywhere you enumerate granted authorities. Filter the `FAM:` prefix on both sides.

**The claims must be mapped onto the ACCESS token**, not just the ID token. This is a CSS
console setting. Get it wrong and `idir_username` is absent, which silently routes every user
down the GUID path in §4.4.

### How scoped roles appear in the token

**The scope is in the role name, and nowhere else.** A CSS role is a bare name — no attributes,
no description — so when FAM grants a role for a forest client, district or region, it creates a
role named `<CODE>_<SCOPE_TYPE>-<value>` and assigns that. Nothing in the token says "scope"
separately; if you drop the suffix, you've dropped the authorisation.

| Grant | What the token carries |
|---|---|
| Unscoped | `FREP_EDITOR` |
| District | `CHR_FREP_EDITOR_DISTRICT-DCC` |
| Forest client (the bulk upload's `organization` column) | `FOM_SUBMITTER_FOREST_CLIENT-00001018` |
| Region | `FREP_EDITOR_REGION-KOOTENAY_BOUNDARY` |
| District **and** forest client | `FOM_SUBMITTER_DISTRICT-DCC_FOREST_CLIENT-00001012` |
| Expiry on any of the above | `FAM:EXPIRES:2026-09-30:CHR_FREP_EDITOR_DISTRICT-DCC` |

So someone holding the submitter role for three forest clients looks like this:

```jsonc
"client_roles": [
  "FOM_SUBMITTER_FOREST_CLIENT-00001018",
  "FOM_SUBMITTER_FOREST_CLIENT-00001012",
  "FOM_SUBMITTER_FOREST_CLIENT-00147603",
  "FAM:EXPIRES:2026-09-30:FOM_SUBMITTER_FOREST_CLIENT-00001012"
]
```

**One role per scope value, and per combination.** Three clients is three roles. A role scoped by
district *and* forest client, granted for two districts and three clients, is six roles — one for
every pair.

**The bare code is not in the token.** A scoped holder carries only the suffixed name, never
`FOM_SUBMITTER` on its own, so `hasRole("FOM_SUBMITTER")` is false for every scoped user. Neither
do the scope markers (`HAS_DISTRICT_ROLE`, `HAS_FOREST_CLIENT`, `HAS_REGION_ROLE`) appear: they are
composed into the base role's definition, and nobody is assigned the base role of a scoped grant.
The `FAM:LABEL:` and `FAM:DESC:` sidecars are assigned to nobody and never appear either — of the
`FAM:` roles, only `FAM:EXPIRES:` reaches a token.

**Suffixes are written in a fixed order** — district, region, forest client — whatever order the
grant was made in. The same scopes always spell the same role, so exact-matching a full name is
safe.

**Parsing is unambiguous, if you do it the right way.** Role codes are `^[A-Z][A-Z0-9_]{1,58}$`,
so they never contain `-` or `:`. Region values do contain underscores (`KOOTENAY_BOUNDARY`), and
the scope type `FOREST_CLIENT` contains one too, so **never split on the last underscore**.
Instead, strip suffixes from the right: take everything after the last `-` as the value, and
check whether what precedes it ends in `_FOREST_CLIENT`, `_DISTRICT` or `_REGION` (longest
first). If it does, that's one scope; repeat on the remainder. If it doesn't, the hyphen isn't a
scope separator and the name is its own base role.

| Name | Base | Scopes |
|---|---|---|
| `CHR_FREP_EDITOR_DISTRICT-DCC` | `CHR_FREP_EDITOR` | DISTRICT=DCC |
| `FOM_SUBMITTER_FOREST_CLIENT-00001018` | `FOM_SUBMITTER` | FOREST_CLIENT=00001018 |
| `FOM_SUBMITTER_DISTRICT-DCC_FOREST_CLIENT-00001012` | `FOM_SUBMITTER` | DISTRICT=DCC, FOREST_CLIENT=00001012 |
| `SOME-ROLE` | `SOME-ROLE` | none |

FAM's reference implementation is `CssRoleNaming.parse` in `nr-fam`; port its tests along with it.

**Which to use.** If the app checks one known scope ("can this user submit for client
00001018?"), exact-match the full built name. If it needs to list what the user can act on
("which clients?"), parse every role and group by base code. Either way, filter `FAM:` first.

**Roles configured by hand before FAM's role screen existed may be named for their display
text,** so their scoped grants can look like `Submitter (SLR)_DISTRICT-DCC`. The same suffix
rule parses them, but match on what your integration actually holds — check the role listing
rather than assuming every base is an upper-case code.

**Legacy had no district or region scoping** (§12.3). An app that faked district roles as
distinct legacy roles (`EDITOR_DCC`) keeps those names verbatim after the migration unless
someone redefines them as scoped roles — decide that before the access import, because it
changes every name the app authorises on.

---

## 6. Environment variables

### Delete
`AWS_COGNITO_ISSUER_URI`, `COGNITO_USERINFO_URI`, `VITE_USER_POOLS_ID`,
`VITE_USER_POOLS_WEB_CLIENT_ID`, and all three `VITE_LOGOUT_*`.

### Add — only TWO values, whatever your CI calls them
| Value | Consumed by |
|---|---|
| The realm issuer URI (`https://<env>.loginproxy.gov.bc.ca/auth/realms/standard`) | backend resource server **and** the SPA |
| The CSS integration's client id | backend `azp` check **and** the SPA |

Note the count: **eight Cognito variables become two.** The three `VITE_LOGOUT_*` values are
*deleted rather than renamed* — `oidc-client-ts` discovers the end-session endpoint from the
issuer. If you find yourself renaming them, you've misunderstood the model.

**Store each value ONCE, even though the two containers read it under different names.**
This trips people, so it's worth being precise about which layer is which:

- **Container env var names must differ.** The backend wants `KEYCLOAK_ISSUER_URI` /
  `KEYCLOAK_CLIENT_ID`; the SPA needs `VITE_KEYCLOAK_URL` / `VITE_KEYCLOAK_CLIENT_ID`, because
  the `VITE_` prefix is load-bearing — Vite only exposes `VITE_*` to the bundle, and a runtime
  entrypoint that renders config.js typically only copies `VITE_*`. Keep the prefix.
- **CI variables must NOT.** Define the issuer once and the client id once, then feed both
  deploy templates from the same variable. Four CI variables carrying two values is a pair
  nothing keeps in agreement.

We shipped the four-variable version first and collapsed it later. Skip that step.

**Why it matters more than it looks:** both drifts fail *after* a successful sign-in. Issuer
drift means the SPA gets a token from realm A while the API validates against realm B; client-id
drift means the API's `azp` check refuses every token. Either way the user logs in happily and
then the entire app returns 401 — which reads like a roles or permissions problem, sending you
to look in exactly the wrong place. There is no valid configuration in which either pair
differs, so there is nothing to gain by storing them twice.

**Make the issuer a VARIABLE, not a secret.** An issuer URI isn't sensitive, and storing it as
both a secret and a variable means two GitHub entries with nothing checking they agree.

**But confirm they are the same value before you collapse anything.** We got this wrong and it
is the most expensive mistake in this document. Our secret and our variable were both called
`KEYCLOAK_ISSUER_URI`, so they read as an obvious duplicate — they were not. The variable held
the **standard** realm, which the API and SPA authenticate users against. The secret held the
**forests** realm, where the service account for the IDIR user-lookup API lives. Collapsing them
silently repointed service-account creation at the wrong realm.

That failure is quiet in the worst way: the deploy is green, sign-in works, the app works, and
only user *lookup* is broken — a feature nobody exercises in a smoke test.

Two rules that would have caught it:

- **Diff the values, not the names.** Two entries with the same name are evidence of confusion,
  not of duplication. Print both (an issuer URI is safe to print) and compare before merging.
- **Name variables after their realm and role, not just their protocol slot.** We now have
  `KEYCLOAK_ISSUER_URI` (standard; API + SPA) and `KEYCLOAK_SA_ISSUER_URI` (forests; service
  account only). The old naming made two genuinely different things look interchangeable, which
  is what invited the mistake.

If your app calls any BC Gov service-to-service API, expect this shape: **user** auth in one
realm, **service-account** auth in another. They are different issuers, different clients, and
different lifetimes, and only the user-facing one belongs in your resource-server config.

### While you're in there: audit what's left

Migrating auth is the one time everybody re-reads their whole config, so spend the extra hour.
Ask of every remaining variable: **does this genuinely differ between environments?** Inherited
quickstart boilerplate mostly doesn't. Three patterns, in rising order of nastiness:

**Values that are constants wearing a variable's clothes.** A product display name is the
canonical one — it changes when the product is renamed, which is a code change, not a config
change. Ours (`VITE_APP_NAME`) was declared identically in six places and plumbed through a CI
variable in three environments: nine edits to rename one string. Put it in the deploy template's
default and delete the CI entry.

**Values that are structurally fixed but look tunable.** Our SPA's API base (`VITE_BACKEND_URL`)
is `/api` in *every* environment, local included — because the `/api` segment belongs to the
backend's own routes (`@RequestMapping("/api/...")`), and neither the production reverse proxy
nor the dev proxy rewrites the path. It could never hold another value. Worse, our `.env.example`
and README both told newcomers to set it to `http://localhost:8080` for local dev, which drops
the `/api` segment and 404s every request. Nobody noticed, because everyone's working `.env`
already had the right value. **Check that your example config actually works from scratch** —
doc bugs of this shape are invisible to the person who wrote them.

**Variables the migration silently kills.** This one is specific to leaving Cognito, and it is
the reason to do this audit at all rather than defer it.

Cognito needs a per-environment identity-provider name (`TEST-IDIR`, `PROD-IDIR`), so most FAM-era
SPAs have a zone or environment variable whose *real* job is selecting that IdP. Keycloak's
`kc_idp_hint` is the constant `azureidir` everywhere. The moment you migrate, that variable stops
doing anything — but it is still plumbed through your entrypoint, your deploy templates, your CI
and your `.env`, and its description still claims it is "surfaced in the UI".

Ours (`VITE_ZONE`) survived the migration in exactly that state: four layers of plumbing, written
into the browser's runtime config, read by nobody. A variable that looks meaningful but is inert
is worse than one that is merely redundant — eventually somebody changes it to fix something and
loses an afternoon to why nothing happened. Grep your application source for each variable. If
the only hits are the config files that define it, delete it everywhere.

**Two cautions when deleting.** If the variable is the last key in a generated JSON/JS object
(ours was, in the entrypoint's `config.js` heredoc), removing it strips the key but leaves the
preceding comma — a syntax error that ships a blank SPA. Render the template and syntax-check the
*output*, not the heredoc. And in the deploy template, keep the parameter with a sensible default
even after dropping the CI variable, so a manual `oc process` can still override it.

Our count went 8 CI variables → 3, and 25 config entries per environment → 19.

---

## 7. Frontend wiring

**New `/authCallback` route.** Register it in the **public** route table and place it **above
any `*` catch-all**, which would otherwise bounce the callback (carrying `?code=&state=`) to
your landing page before the exchange can happen. It belongs in the public table because the
session doesn't exist until this route creates it.

**Guard it against StrictMode double-mount** with a ref — the authorization code is single-use,
and React 19 dev mode will spend it twice.

**Redirect URIs are derived, not configured.** Sign-in returns to
`<origin><base path>/authCallback`; post-logout is `<origin><base path>`. Computing them from
`window.location.origin` is what lets one built image serve PR previews, TEST and PROD.

**Token storage moves cookies → sessionStorage.** The Amplify cookie constraint came from its
storage-before-configure ordering. Gone. A ~38-line cookie-sweeping `clearStoredTokens()`
collapses to `removeUser()`.

**Tighten your CSP.** Drop `https://*.amazoncognito.com` and
`https://cognito-idp.*.amazonaws.com` from `connect-src`. Grep your WAF config too — comments
and rules often name Cognito as the thing gating `/admin`.

---

## 8. Session timeout — recalculate, don't copy

**Cognito's refresh token lived 60 minutes. Keycloak's standard realm gives you 30.**

If your idle timeout was 30 minutes, it now fires at the exact moment its own credentials die —
the sign-out races them, and "Stay logged in" becomes a button that cannot keep its promise.

REPT moved to **25 min idle / 20 min warning** (5 minutes of headroom) and widened the refresh
margin from 30s to 60s. Access tokens live 5 minutes, and *reading a screen for five minutes is
ordinary*, so an activity-driven keepalive matters more than it did.

Recompute these against your own realm's TTLs. Don't copy REPT's numbers blind — and don't copy
your Cognito numbers at all.

---

## 9. CI/CD and e2e

**Service account for any API-to-API lookups.** If you used FAM's identity-lookup, the
replacement (`nr-user-lookup-api`) authenticates with your *own* client-credentials service
account, not the caller's token. Script its creation idempotently and gate the step on the
admin credential being present, so PR previews skip it and degrade gracefully.

**Secrets containing `$` get silently truncated.** `bcgov/action-deployer-openshift` splices
`parameters` into a bash double-quoted string, so bash expands any `$NAME` inside a secret and
drops it. Route secrets through job-level `env` and reference `"$VAR"`. The symptom is a
password that's wrong by a few characters with nothing in the log.

**Playwright does not capture sessionStorage.** `storageState` covers cookies + localStorage
only — and `oidc-client-ts` keeps tokens in **sessionStorage**. Snapshot it by hand and restore
it via `addInitScript` on an **overridden** `page` fixture (overridden, not auto, so the init
script registers before the page exists).

**Keycloak rotates refresh tokens too.** If you had an `{auto: true}` fixture persisting state
after each test for Cognito, you still need it — every spec shares one refresh token, and a
rotation in test 1 poisons test 2.

**Gate your idle-timeout component off under `navigator.webdriver`** for the same reason.

**MFA may make programmatic CI login impossible** without an exempt service account. Find this
out early — it can change your whole e2e strategy.

---

## 10. Verify against the realm before you debug your code

These take seconds and tell you whether the problem is yours or the integration's.

```bash
ISSUER=https://dev.loginproxy.gov.bc.ca/auth/realms/standard

# 1. Realm reachable, and the issuer string matches your config EXACTLY
#    (oidc-client-ts validates the `iss` claim against your authority).
curl -s "$ISSUER/.well-known/openid-configuration" | jq -r .issuer

# 2. Does the client exist, and is your redirect URI registered?
#    "Client not found." = the client id is wrong.
#    A 302 back to your redirect_uri = client and URI are both good.
curl -s -o /dev/null -w '%{http_code}\n' -G "$ISSUER/protocol/openid-connect/auth" \
  --data-urlencode "client_id=YOUR_CLIENT_ID" \
  --data-urlencode "redirect_uri=http://localhost:3000/authCallback" \
  --data-urlencode "response_type=code" --data-urlencode "scope=openid"

# 3. Full PKCE probe — a 303 to /broker/azureidir/login proves the client,
#    the redirect URI, PKCE, AND that your kc_idp_hint resolves to a real
#    broker rather than being silently ignored (see §4.3).
```

**Also probe with a deliberately wrong redirect URI.** If Keycloak accepts
`https://example.com/anything`, your client's redirect list is a wildcard and it will hand an
authorization code to any origin. Tolerable on a sandbox client; not something to carry into
real environments.

**Worth asking CSS:** do redirect URIs accept wildcards on proper integrations? If yes, any
PR-preview hostname-bucketing scheme you inherited from Cognito (which rejects wildcards in
CallbackURLs) can be deleted outright.

---

## 11. Outside the repo — the CSS console checklist

Nothing in your codebase works until these exist, **per environment**:

- [ ] Integration created; note the client id
- [ ] Redirect URI `<origin><base>/authCallback`
- [ ] Post-logout redirect URI `<origin><base>`
- [ ] Access-token mappers: `idir_username`, `idir_user_guid`, `identity_provider`,
      `display_name`, `given_name`, `family_name`, `email`
- [ ] Roles created and assigned — use the **`roles-new`** endpoint; the older `roles`
      endpoint 404s for anyone who has never signed in
- [ ] Existing Cognito group membership migrated to CSS role assignments
- [ ] Service-account client scopes, if you need API-to-API lookups

---

## 12. Bringing the existing data across

Standing up the integration gets you a working login and an **empty** application. Two things
live in the legacy FAM database that CSS knows nothing about, and both have to be moved
deliberately:

| What | Lives in | Lands as |
|---|---|---|
| **Access** — who currently holds which role | `fam_user_role_xref` | a bulk-upload CSV |
| **Administrators** — who may grant, and what | `fam_application_admin`, `fam_access_control_privilege` | a bulk-upload CSV each |
| **Audit** — who granted what to whom, and when | `fam_privilege_change_audit` | `INSERT` statements |

Do the administrator pulls before the access pull, and both before the audit. Each tier can
appoint the one below it, so loading downwards means there is always somebody in place to finish
by hand if something goes wrong halfway. The two admin files are also small and validate your
role names, which is worth knowing before you commit to the much larger audit import.

### 12.1 Getting a query in at all

The legacy database is Aurora PostgreSQL in a private VPC. Assume you cannot reach it from a
laptop, VPN or not — we couldn't, and `psql` simply hangs rather than refusing. That leaves the
**RDS Query Editor** (needs the Data API enabled) or a **snapshot → S3 → Athena** export.

The Query Editor is not psql, and the differences cost us three rounds of debugging. All three
are silent — they produce a syntax error somewhere other than the mistake:

- **`\set` does not exist.** It's a psql client directive, not SQL. Use a `params` CTE and join
  it in, which also keeps the parameters in one place at the top.
- **Newlines are flattened before execution.** A `--` comment therefore swallows the entire rest
  of the query. Use `/* ... */` block comments, which survive the flattening, or no comments.
- **A `;` inside a string literal splits the statement.** This bites hard when you're generating
  SQL *with* SQL. Build the statement as one literal and append the semicolon as `|| chr(59)`.

The queries below are already written to those rules. If you're on Athena instead, the only
changes are `now()` → `current_timestamp` and flattened table names (`app_fam.fam_user` becomes
`app_fam_fam_user`); the logic is identical.

### 12.2 Discovery — always run this first

Legacy models each environment as its **own application row** (`REPT_DEV`, `REPT_TEST`,
`REPT_PROD`), while the new FAM has one integration with three environments. So you extract per
environment and upload per environment. This query confirms the names and shows what you're
about to pull:

```sql
SELECT a.application_name,
       a.app_environment,
       r.role_name,
       r.role_type_code,
       COUNT(x.user_id) AS users
FROM app_fam.fam_application a
JOIN app_fam.fam_role r                ON r.application_id = a.application_id
LEFT JOIN app_fam.fam_user_role_xref x ON x.role_id = r.role_id
WHERE a.application_name LIKE 'REPT%'
GROUP BY a.application_name, a.app_environment, r.role_name, r.role_type_code
ORDER BY a.application_name, r.role_name;
```

`role_type_code = 'A'` is an abstract role and `'C'` is a concrete one — legacy's way of
modelling a scoped grant. A concrete role points at its abstract parent via `parent_role_id` and
hangs the scope (a forest client) off itself. The extract has to report the **parent** in the
role column and the scope in its own column, which is what the `COALESCE(parent.role_name, …)`
below does. All `'A'` means the application is unscoped and the scope columns stay empty.

### 12.3 The access pull

One row per grant, in the columns the bulk uploader accepts. Somebody holding a role for three
forest clients is three rows — that's what the uploader expects and what reads correctly in a
spreadsheet.

```sql
SELECT
    u.user_name AS username,
    CASE u.user_type_code WHEN 'I' THEN 'IDIR'
                          WHEN 'B' THEN 'BCEID'
                          ELSE u.user_type_code END AS user_type,
    COALESCE(parent.role_name, r.role_name) AS role,
    '' AS district,
    COALESCE(fc.forest_client_number, '') AS organization,
    '' AS region
FROM app_fam.fam_user_role_xref x
JOIN app_fam.fam_user               u      ON u.user_id  = x.user_id
JOIN app_fam.fam_role               r      ON r.role_id  = x.role_id
LEFT JOIN app_fam.fam_role          parent ON parent.role_id = r.parent_role_id
LEFT JOIN app_fam.fam_forest_client fc     ON fc.client_number_id = r.client_number_id
JOIN app_fam.fam_application        a      ON a.application_id = r.application_id
WHERE a.application_name = 'REPT_PROD'
  AND (x.expiry_date IS NULL OR x.expiry_date > now())
ORDER BY username, role, organization;
```

**Four things about that query.**

`user_type_code` is a single letter in legacy (`'I'` / `'B'`) and a word in the new system
(`IDIR` / `BCEID`). This is the first of several places where the same concept has a different
wire form on each side — see §12.6, where it bites much harder.

The `expiry_date` filter drops grants that have already lapsed. Remove it if you're reconciling
against the old system and want to see everything; keep it for a file you're about to upload.

`district` and `region` are always empty. Legacy has no district or region scoping — where an
application appeared to have district roles they were modelled as *distinct roles*, with the
district inside the role name. Check the discovery output before assuming the column is unused.

**Usernames, not GUIDs.** The uploader resolves each username against the directory itself, so
`fam_user.user_name` is the right column — but anyone whose account no longer exists fails that
lookup and is reported per row rather than silently dropped. Read the upload report.

### 12.4 The administrator pulls

Administrators are not in the access pull and never were. Legacy keeps them in
two tables of their own — `fam_application_admin` and `fam_access_control_privilege`
— so a file of users carries nobody who could grant anything, and an application
migrated from the access pull alone arrives with no one able to administer it.

Both load through the same two-step upload as the access pull, from the **Bulk
upload** button on their own tab.

**Application admins.** One column. The tier is the whole appointment — they can
already grant every role the application defines — so there is no role to name
and no scope to carry, and no user type either: the tier is IDIR-only, so the
column could only repeat what the file already means. Usernames are looked up in
the IDIR directory and nowhere else.

```sql
SELECT u.user_name AS username
FROM app_fam.fam_application_admin aa
JOIN app_fam.fam_user        u ON u.user_id = aa.user_id
JOIN app_fam.fam_application a ON a.application_id = aa.application_id
WHERE a.application_name = 'REPT_PROD'
  AND u.user_type_code = 'I'
ORDER BY username;
```

If that returns nothing, the filter changed nothing and the pull is complete.

**Delegated admins.** The same six columns as the access pull, read the same way
— and that is not a coincidence worth glossing over. A delegation authorises
exactly one *concrete* role, so "Editor for district DCC" is a row in both files
and means different things in each: in the access pull it grants that access, and
here it grants the right to grant it. The concrete role resolves to its abstract
parent with the scope in its own column, exactly as in §12.3.

```sql
SELECT
    u.user_name AS username,
    CASE u.user_type_code WHEN 'I' THEN 'IDIR'
                          WHEN 'B' THEN 'BCEID'
                          ELSE u.user_type_code END AS user_type,
    COALESCE(parent.role_name, r.role_name) AS role,
    '' AS district,
    COALESCE(fc.forest_client_number, '') AS organization,
    '' AS region
FROM app_fam.fam_access_control_privilege acp
JOIN app_fam.fam_user               u      ON u.user_id  = acp.user_id
JOIN app_fam.fam_role               r      ON r.role_id  = acp.role_id
LEFT JOIN app_fam.fam_role          parent ON parent.role_id = r.parent_role_id
LEFT JOIN app_fam.fam_forest_client fc     ON fc.client_number_id = r.client_number_id
JOIN app_fam.fam_application        a      ON a.application_id = r.application_id
WHERE a.application_name = 'REPT_PROD'
ORDER BY username, role, organization;
```

Note the application comes from the **role**, not from a column of its own:
`fam_access_control_privilege` names a role, and the role names the application.
Joining the table straight to `fam_application` is not possible and not a
shortcut worth inventing.

**There is no DevOps pull.** That tier has no legacy equivalent — it is new in
the CSS-era FAM — so there is nothing to extract and the bulk upload deliberately
does not offer it. Appoint them one at a time.

**Application admins before delegated admins**, for the reason given at the top
of this section: loading upwards can leave an application with users and nobody
able to administer them.

### 12.5 The audit pull

This one generates `INSERT` statements rather than data: run it, copy the `stmt` column, run
that against the new database. Set the three parameters at the top — the legacy application row,
the CSS integration id, and the application name as you want it to read in the new UI.

```sql
WITH params AS (
  SELECT 'REPT_PROD'::varchar AS legacy_app,
         6589::int AS integration,
         'Sandbox REPT (PROD)'::varchar AS css_app_name
),
src AS (
  SELECT l.change_date, l.create_date, l.privilege_change_type_code,
    l.change_performer_user_details, p.integration, p.css_app_name,
    lower(a.app_environment) AS css_environment,
    CASE
      WHEN l.privilege_details->>'permission_type' = 'Application Admin'
        THEN jsonb_build_object('permission_type', 'End User', 'roles',
               jsonb_build_array(jsonb_build_object(
                 'role', 'APP_ADMIN_' || p.integration || '_' || upper(a.app_environment),
                 'scopes', NULL,
                 'role_assignment_expiry_date', NULL)))
      WHEN jsonb_typeof(l.privilege_details->'roles') <> 'array'
        THEN l.privilege_details
      ELSE jsonb_set(l.privilege_details, '{roles}', COALESCE((
        SELECT jsonb_agg(jsonb_set(elem, '{role}', to_jsonb(COALESCE((
          SELECT COALESCE(pr.role_name, fr.role_name)
          FROM app_fam.fam_role fr
          LEFT JOIN app_fam.fam_role pr ON pr.role_id = fr.parent_role_id
          WHERE fr.application_id = l.application_id AND fr.display_name = elem->>'role'
          LIMIT 1), elem->>'role'))) ORDER BY ord)
        FROM jsonb_array_elements(l.privilege_details->'roles')
             WITH ORDINALITY AS t(elem, ord)
      ), l.privilege_details->'roles'))
    END AS privilege_details,
    CASE WHEN pu.user_guid IS NULL THEN 'system'
         ELSE (CASE pu.user_type_code WHEN 'I' THEN 'IDIR' ELSE 'BCEID_BUS' END)
              || '\' || upper(pu.user_guid) END AS performer_key,
    (CASE tu.user_type_code WHEN 'I' THEN 'IDIR' ELSE 'BCEID_BUS' END)
         || '\' || upper(tu.user_guid) AS target_key,
    jsonb_strip_nulls(jsonb_build_object(
        'user_guid',  upper(tu.user_guid),
        'username',   tu.user_name,
        'first_name', tu.first_name,
        'last_name',  tu.last_name,
        'email',      tu.email)) AS target_details
  FROM params p
  CROSS JOIN app_fam.fam_privilege_change_audit l
  JOIN app_fam.fam_application a ON a.application_id = l.application_id
  LEFT JOIN app_fam.fam_user pu ON pu.user_id = l.change_performer_user_id
  JOIN app_fam.fam_user tu ON tu.user_id = l.change_target_user_id
  WHERE a.application_name = p.legacy_app
)
SELECT format('INSERT INTO app_fam.fam_privilege_change_audit (css_integration_id, css_environment, change_date, change_performer_user_details, change_target_user_details, performer_user, target_user, privilege_change_type_code, privilege_details, create_user, create_date, update_user, update_date, css_application_name) VALUES (%s, %L, %L, %L::jsonb, %L::jsonb, %L, %L, %L, %L::jsonb, %L, %L, %L, %L, %L)',
    integration, css_environment, change_date,
    change_performer_user_details::text, target_details::text,
    performer_key, target_key, privilege_change_type_code,
    privilege_details::text,
    performer_key, create_date, performer_key, create_date,
    css_app_name) || chr(59) AS stmt
FROM src
ORDER BY change_date;
```

---

## 13. Suggested order

1. **Stand up the CSS integration for dev first.** Everything else is unverifiable without it,
   and the lead time is external. Do not leave this to the end.
2. Backend resource server (`token_use` → `azp`, claim mapping, role extraction) **with tests**
   before touching the frontend — it's where the silent failures live.
3. Frontend auth service + `AuthCallback` route.
4. Delete the Cognito surface: config, logout chain, userinfo service, env vars, CSP entries.
5. Recompute session-timeout constants against the real TTLs.
6. CI/CD variables and secrets, service-account script.
7. e2e sessionStorage handling.
8. Local dev config last — it's the easiest to fix and the least informative when broken.
9. **Migrate the data (§12) once a real environment works end to end.** Access first, then
   audit — the access pull is small and it validates your role names before you commit to the
   much larger audit import.

---

## 14. AI prompts

These are written for a coding agent with repo access. The pattern that works: **name the trap
in the prompt.** An agent that doesn't know `token_use` is Cognito-only will port it faithfully.

### Reconnaissance
> Map every place this repo touches authentication. List: the OIDC/Cognito client config, where
> tokens are stored and read, the login and logout flows, the resource server's JWT validation,
> every place a JWT claim is read, and every env var whose name mentions Cognito, Amplify, user
> pools, or logout. For each, tell me whether it's Cognito-specific or provider-agnostic. Don't
> change anything yet.

> Find every place this codebase writes a user identity string to the database — audit columns
> like create_user/update_user, ownership fields, anything similar. For each, show me exactly how
> the string is built and which JWT claims feed it. I need to know what breaks if the identity
> provider name changes.

### Backend
> We're moving from AWS Cognito to BC Gov SSO (Keycloak, standard realm, IDIR-MFA integration).
> Rewrite the resource-server config. Critical: Keycloak emits **no `token_use` claim**, so any
> validator requiring `token_use == "access"` must be removed or it will 401 every request.
> Replace it with an `azp` check against a client id read from configuration — the standard realm
> is shared, so issuer + signature validation alone doesn't prove the token was meant for us.
> JWKS is at `/protocol/openid-connect/certs`, not `/.well-known/jwks.json`.

> Rewrite the JWT claim extraction for Keycloak. Claims: `idir_username`, `idir_user_guid`,
> `identity_provider`, `display_name`. Two rules that must hold:
> (1) the realm reports the provider as `azureidir` (IDIR-MFA federates through Azure AD) — every
> IDIR alias, any case, must normalise to the single string `IDIR` that our audit columns already
> contain, or we silently start writing `AZUREIDIR\user` for the same person;
> (2) `idir_user_guid` and the GUID inside `preferred_username` can arrive in different cases —
> upper-case the GUID wherever it's used as identity, but leave the username exactly as issued.
> Write unit tests pinning both, including a lowercase-GUID case.

> Read roles from `client_roles`, falling back to `resource_access.<azp>.roles` — which one is
> populated depends on the realm's mappers, so read both. Filter out roles prefixed `FAM:`
> (per-grant expiry bookkeeping like `FAM:EXPIRES:2026-09-30:ROLE_NAME`). Unscoped roles arrive
> as the bare code. Scoped roles arrive ONLY with the scope in the name —
> `<CODE>_DISTRICT-<value>`, `<CODE>_REGION-<value>`, `<CODE>_FOREST_CLIENT-<value>`, chained in
> that order when a role has several — and the bare code is absent for those users. For a single
> known scope, exact-match the full name. To enumerate scopes, parse from the right: value after
> the last `-`, then match a known scope type `_FOREST_CLIENT`/`_DISTRICT`/`_REGION` longest first,
> repeat; never split on underscores (region values contain them). Use FAM's grammar, not an
> invented one, and write tests for a multi-scope name and a region like `KOOTENAY_BOUNDARY`.

### Frontend
> Replace aws-amplify with oidc-client-ts. Authorization Code + PKCE, public client, no secret.
> Configure from a single issuer URI — oidc-client-ts discovers authorize/token/end-session, so
> do NOT add per-endpoint variables; the three VITE_LOGOUT_* vars should be deleted, not renamed.
> Tokens in sessionStorage. Set `kc_idp_hint` to `azureidir`. Derive redirect_uri as
> `<origin><base path>/authCallback` from window.location so one image serves every environment.

> Add the /authCallback route that completes the code exchange. It must be in the PUBLIC route
> table and ordered ABOVE any `*` catch-all, which would otherwise bounce the callback before the
> exchange. Guard it with a ref against React StrictMode double-mounting — the code is single-use.

> Rewrite sign-out as a single `signoutRedirect()`. Important: do NOT clear the stored user
> first — oidc-client-ts reads `id_token_hint` off it and removes it itself, and clearing early
> sends a logout Keycloak can't attribute to a session, leaving the realm session alive so the
> next sign-in skips the prompt. Catch a failed redirect and fall back to `removeUser()` + home.

### Session timeout
> Our idle-timeout modal was tuned for Cognito's 60-minute refresh token. Keycloak's is 30
> minutes. Recompute the constants so the idle timeout sits comfortably UNDER the refresh-token
> ceiling — otherwise the logout fires exactly when its own credentials die and "Stay logged in"
> can't work. Show your arithmetic in the comments. Don't change any component logic.

### Cleanup and verification
> Find everything Cognito-related still referenced anywhere in this repo: source, tests, env
> files, Dockerfiles, CSP headers, WAF config, OpenShift templates, GitHub workflows, READMEs.
> Report each with a recommendation to delete, rewrite, or keep, and say why. Don't change
> anything yet.

> Audit our CI configuration for values that must be identical but are stored more than once —
> the same value under two names, or as both a secret and a variable. For each candidate pair,
> FIRST establish whether the two entries actually hold the same value; do not infer it from the
> names matching. Same-named entries of different types are often different values that nobody
> renamed. If you cannot see the values, say so and tell me what to check rather than
> recommending a merge. Then trace every consumer, tell me what breaks and how visibly if they
> drift, and say whether they can safely be collapsed to one entry. Pay attention to any value both the frontend and backend need: those
> often end up duplicated because the two containers read them under different env var names,
> which is a real constraint at the container layer but not at the CI layer.

> For each environment variable this app defines, grep the APPLICATION SOURCE for it — not the
> config files that define it. Report any whose only references are the config plumbing itself
> (entrypoint, deploy templates, CI, .env), because those are dead. Pay particular attention to
> any zone/environment variable: under Cognito its real job was often selecting a per-environment
> IdP name, and the Keycloak hint is a constant, so it may have died in this migration without
> anyone noticing. Also flag any variable whose value is identical in every environment — it
> belongs in a template default or a source constant, not in CI.

> Verify our `.env.example` actually works from a clean checkout. For each value, trace what the
> app does with it and confirm the documented default is correct — don't assume, because whoever
> wrote it had a working local `.env` and wouldn't have noticed a wrong default.

> Before I debug my code: probe the realm directly with curl to establish whether the client
> exists, whether my redirect URI is registered, and whether `kc_idp_hint=azureidir` resolves to
> a real broker instead of being silently ignored. Also probe with a deliberately wrong redirect
> URI to check the client isn't accepting wildcards. Show me the commands and interpret the
> responses.

> Write unit tests for the session-timeout component using fake timers. Cover: warning opens at
> the right remaining time, the countdown format, the danger threshold, "Stay logged in" renewing
> and restarting the FULL idle window, a failed renewal being treated as a real expiry, explicit
> logout NOT setting the session-expired flag, and inertness under `navigator.webdriver`.
> Re-state the timing constants in the test rather than importing them, so retuning fails loudly.
> Then mutate the component to prove each test actually fails when it should.

**That last sentence generalises.** Tests that pass on the first run against code you didn't
just write are worth nothing until you've watched them fail. Ask for the mutation check
explicitly — it's the difference between coverage and confidence.

---

## 15. What we'd do differently

- **Start the CSS integration request on day one.** It's the only thing on the critical path
  you can't do yourself, and every verification is blocked behind it.
- **Write the claim-mapping tests before the claim-mapping code.** The `azureidir` and
  mixed-case-GUID bugs both produce working software with a quietly corrupted audit trail —
  precisely the class of bug that survives code review and manual QA.
- **Resist renaming.** Several Cognito variables have no Keycloak equivalent because
  `oidc-client-ts` derives them. Every rename we avoided was a variable we deleted.
- **Check what the old provider was propping up.** Cognito's rejection of wildcard callback URLs
  is why REPT buckets PR previews into 50 fixed hostnames. That workaround may be pure
  dead weight under CSS — but only if someone thinks to ask.
- **Audit the config while you're already in it.** We collapsed 8 CI variables to 3 only after
  shipping, one question at a time — "why do we have two of these?", "is that value even used?".
  Every one of those answers was available on day one. The migration is the moment everybody has
  the whole config paged into their head; that is when it is cheapest to delete things.
- **Two entries with the same name are not evidence they hold the same value.** We collapsed a
  `KEYCLOAK_ISSUER_URI` secret into the identically-named variable and silently repointed our
  service-account bootstrap from the forests realm to the standard one. Deploy green, login
  fine, user lookup broken. Compare values before merging config, and name things after what
  they point at.
- **A migration can kill a variable without removing it.** `VITE_ZONE` existed to pick the
  Cognito IdP per environment. Keycloak's hint is constant, so the variable went inert the day we
  cut over — still plumbed through four layers, still documented as "surfaced in the UI", read by
  nothing. Grep the application source, not the config, to find these.
