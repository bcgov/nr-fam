import { existsSync } from "node:fs";

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
        return;
    }

    const idirUser = process.env.E2E_IDIR_USER;
    const idirPassword = process.env.E2E_IDIR_PASSWORD;
    const programmatic = Boolean(idirUser && idirPassword);

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
    await page.waitForURL(
        (url) =>
            url.pathname.startsWith("/manage-permissions") ||
            url.pathname.startsWith("/my-permissions") ||
            url.pathname.startsWith("/no-access"),
        { timeout: programmatic ? 2 * 60_000 : 5 * 60_000 }
    );

    // The layout shell is the simplest "auth landed cleanly" signal: it renders
    // only once AuthProvider has finished bootstrapping.
    await expect(page.locator("#protected-layout-container")).toBeVisible({
        timeout: 30_000,
    });

    await page.context().storageState({ path: STORAGE_STATE });
});
