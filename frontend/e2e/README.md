# FAM end-to-end tests

Playwright suite driving the real SPA against a real backend, a real CSS
integration and the real Forest Client and IDIM APIs. Mirrors nr-fsp-new's
setup: one `setup` project authenticates via IDIR and saves
`e2e/.auth/user.json`; every other project boots from that storageState.

## Running

```bash
# 1. Point at a target (no default — failing fast beats a stale URL)
export E2E_BASE_URL=http://localhost:3000       # or a deployed slot

# 2. Name the user the write specs grant to
export E2E_TARGET_IDIR=SOMEUSER                 # NOT you — see below

# 3. Log in once (interactive, headed) — caches e2e/.auth/user.json
npm run e2e:login

# 4. Run
npm run e2e                 # chromium
npm run e2e:ui              # Playwright UI mode
npm run e2e:all-browsers    # chromium, Chrome, Firefox, Safari, Edge
npm run e2e:report          # last HTML report
```

In CI, `auth.setup.ts` signs in programmatically from `E2E_IDIR_USER` /
`E2E_IDIR_PASSWORD` — see `.github/workflows/reusable-tests.yml`. All three of
those, plus `E2E_TARGET_IDIR`, are GitHub **secrets**; only `E2E_APP_NAME` and
`RUN_E2E` are repository variables.

> **A masked target is harder to diagnose.** Because `E2E_TARGET_IDIR` is a
> secret, a failure that would have read `the directory returned no user JSMITH`
> reads `no user ***` instead. If the write specs start failing on user lookup,
> check the secret's value rather than the log.

## Configuration

| Variable | Required | What it is |
|---|---|---|
| `E2E_BASE_URL` | always | Target origin. No default, deliberately. |
| `E2E_APP_NAME` | **no** | Pins one application by substring. The suite otherwise discovers its target from `/api/css-applications` — that endpoint already returns only what the signed-in account may administer, and flags which entry is FAM itself. Only needed when the account can also reach something that is *not* a sandbox. |
| `E2E_TARGET_IDIR` | write specs | The IDIR user grants are made to. **Must not be the signed-in account** — FAM refuses to alter your own access, so a self-grant fails on the self-administration guard rather than exercising anything. Specs needing it `skip` when it is unset. A **secret** in CI, so it is masked in logs and in the uploaded report. |
| `E2E_IDIR_USER` / `E2E_IDIR_PASSWORD` | CI only | Programmatic sign-in. Locally you log in by hand instead. |

## What's covered

| Spec | Writes? | Notes |
|---|---|---|
| `smoke.spec.ts` | no | SPA serves, `/api` proxy is wired, database migrated, OpenAPI served |
| `pages.smoke.spec.ts` | no | every page renders its title; both IdP buttons; catch-all route |
| `my-permissions.spec.ts` | no | the one screen every signed-in user can open; asserts `/auth/self` is actually called |
| `authorization.spec.ts` | no | anonymous and forged tokens refused; self-grant refused; FAM's own roles not manageable; FAM's user list excludes other apps' admins |
| `role-lifecycle.spec.ts` | **yes** | create plain and district-scoped roles, duplicate refused, delete reports what went with it, FAM absent from the picker |
| `grant-lifecycle.spec.ts` | **yes** | grant → verify row → revoke; scoped grant shows chips; scoped role can't be submitted unanswered; several roles at once |
| `admin-lifecycle.spec.ts` | **yes** | appoint and remove an application admin and a delegated admin; no admin tabs for FAM itself |
| `forest-client-search.spec.ts` | **yes** | the organisation autocomplete against the real API |
| `bulk-upload.spec.ts` | **yes** | preview resolves names and grants nothing; a bad row is reported per row; template link |
| `audit-history.spec.ts` | **yes** | a grant and its revocation reach the audit trail |

## Which application the write specs act on

Discovered, not configured. `/api/css-applications` returns exactly what the
signed-in account may administer and marks FAM's own integration; the suite
takes the first entry that is not FAM. In the lower environments every
integration the CSS account can see is a sandbox, so any of them will do — and
each role the suite makes is uniquely named and deleted again, so it does not
matter which.

FAM's own integration is excluded deliberately. Its roles are `FAM_ADMIN` plus
the `APP_ADMIN_<id>_<ENV>` and `DELEGATED_ADMIN_...` roles FAM generates as
administrators are appointed; the backend refuses role management there anyway.

When the account administers nothing, the write specs skip.

## Why the write specs need a sandbox

They create and delete real CSS roles and real grants. Every artefact is named
with a unique suffix (`E2E_<something>_<base36 time><rand>`) so concurrent runs
and leftovers cannot collide, and every spec cleans up in a `finally` block so a
mid-spec failure still removes what it made.

That last part matters more than usual here: **CSS roles are never
garbage-collected**. A role abandoned by a failed run stays in the integration
and keeps being offered on the grant screen forever. If a run is killed
mid-flight, delete stragglers by hand — they all start `E2E_`.

The suite runs `workers: 1` for the same reason. Parallel workers would act on
the same integration and see each other's roles between the create and the
assertion.

## Why so much of this is `skip` rather than `fail`

An account that administers nothing, an environment with no sandbox, an unset
`E2E_TARGET_IDIR` — none of those is a broken build, and reporting them as
failures trains people to ignore the job. A skip says "not applicable"; a
failure should mean the application is wrong.
