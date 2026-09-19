import { existsSync, statSync } from "node:fs";

import { expect, test as setup } from "@playwright/test";

import { STORAGE_STATE } from "./utils";

/**
 * Auth setup. Runs once per `playwright test` invocation, as a dependency of
 * every browser project. Three behaviours, in priority order:
 *
 *   1. e2e/.auth/user.json already exists -> do nothing (cached state).
 *   2. E2E_IDIR_USER + E2E_IDIR_PASSWORD are set -> drive the IDIR login
 *      programmatically. Used in CI, where the workflow passes them from
 *      GitHub Actions secrets.
 *   3. Neither -> fall back to the interactive flow: open a headed browser and
 *      wait up to five minutes for a human to sign in. Used locally via
 *      `npm run e2e:login`.
 *
 * Re-run `npm run e2e:login` when the saved tokens expire. The symptom is tests
 * bouncing to the SSO domain, or the layout never appearing.
 */
setup("authenticate via IDIR", async ({ page }) => {
    if (existsSync(STORAGE_STATE)) {
        /*
            Says so, rather than skipping in silence.

            A cached session that has expired looks exactly like a working one
            from here: every test then fails at the sign-in page, and the obvious
            next move - `npm run e2e:login` - appears to do nothing, because this
            returns before doing any of it. Naming the file's age and the command
            that replaces it turns that into one line of output.
        */
        const age = Date.now() - statSync(STORAGE_STATE).mtimeMs;
        const hours = Math.floor(age / 3_600_000);
        console.log(
            `Using the cached session in ${STORAGE_STATE} (saved ${hours}h ago). `
                + "If the tests land on the sign-in page, it has expired: run "
                + "`npm run pree2e:login && npm run e2e:login` to replace it."
        );
        return;
    }

    const idirUser = process.env.E2E_IDIR_USER;
    const idirPassword = process.env.E2E_IDIR_PASSWORD;
    const programmatic = Boolean(idirUser && idirPassword);

    /*
        Long enough for a person to sign in.

        The suite's 90 seconds is for a test driving the app; this one waits for
        somebody to type a password and answer multi-factor on a phone. The wait
        below already allowed five minutes, but the test-level limit cut it off
        at ninety seconds - so an unhurried sign-in was killed mid-MFA, wrote no
        session, and left `npm run e2e:login` looking like it had done nothing.
    */
    setup.setTimeout(programmatic ? 3 * 60_000 : 10 * 60_000);

    await page.goto("/");
    await page.locator("#login-idir-button").click();

    if (programmatic) {
        // The BC Gov SSO login page is on a different origin than the SPA. The
        // selectors match the Logon7 / Keycloak form fields; if the upstream
        // form changes its `name` attributes, this is the place to fix it.
        await page.waitForURL(/logon|loginproxy|keycloak|oidc/i, {
            timeout: 60_000,
        });

        await page.locator('input[name="user"]').fill(idirUser!);
        await page.locator('input[name="password"]').fill(idirPassword!);
        await page
            .locator('input[type="submit"], button[type="submit"]')
            .first()
            .click();
    }

    // Whether interactive or programmatic, wait for the redirect back into the
    // SPA. FAM lands signed-in users on Manage permissions; an account with no
    // administrative access lands on /no-access instead, which is still a
    // successful sign-in and still worth saving state for - the read-only specs
    // assert exactly that.
    //
    /*
        Waited for by what appears, not by the URL.

        A URL predicate that also accepts the landing page is satisfied the
        instant it is asked: the click has not navigated yet, so the browser is
        still on "/" - the wait returns immediately, the attempt is judged a
        failure, and the retry's goto then aborts the sign-in that was in flight.
        The shell is the only unambiguous signal that this worked.
    */
    const shell = page.locator("#protected-layout-container");
    await shell
        .waitFor({
            state: "visible",
            timeout: programmatic ? 2 * 60_000 : 5 * 60_000,
        })
        .catch(() => undefined);

    /*
        Press it again if the exchange failed.

        The authorization code is single-use, and the callback can be loaded
        twice - the dev server reloads on its own, and React's strict mode mounts
        the provider twice - so the second exchange fails, the app reports no
        session, and the browser sits on the landing page. Nothing is wrong with
        the credentials: by this point the identity provider's own session
        cookies exist in this context, so pressing sign in again completes
        without anybody typing anything.

        Without this the whole run ended here, having asked for a password and
        then thrown the session away.
    */
    for (let attempt = 1; attempt <= 3; attempt += 1) {
        if (await shell.isVisible().catch(() => false)) {
            break;
        }
        console.warn(
            `Sign-in came back to the landing page (attempt ${attempt}); `
                + "the code was already spent - retrying against the session just made."
        );
        await page.goto("/");
        await page.locator("#login-idir-button").click();
        await shell
            .waitFor({ state: "visible", timeout: 3 * 60_000 })
            .catch(() => undefined);
    }

    // The layout shell is the simplest "auth landed cleanly" signal: it renders
    // only once AuthProvider has finished bootstrapping.
    await expect(page.locator("#protected-layout-container")).toBeVisible({
        timeout: 30_000,
    });

    await page.context().storageState({ path: STORAGE_STATE });
});
