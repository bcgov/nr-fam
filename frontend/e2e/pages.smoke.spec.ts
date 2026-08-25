import { expect, test } from "@playwright/test";

import { gotoProtected } from "./utils";

/**
 * Every page renders its own title for a signed-in user.
 *
 * Deliberately shallow and deliberately broad. It is the spec that catches a
 * route that stopped resolving, a view that throws on mount, or an auth
 * regression that turns the whole app into a redirect loop - the class of
 * breakage that makes every other spec fail for reasons of its own.
 */

const PAGES: Array<{ path: string; title: string | RegExp }> = [
    { path: "/manage-permissions", title: "Manage permissions" },
    { path: "/my-permissions", title: "My permissions" },
];

test.describe("every page renders", () => {
    for (const { path, title } of PAGES) {
        test(`${path} shows "${title}"`, async ({ page }) => {
            await gotoProtected(page, path);

            await expect(page.getByText(title, { exact: false }).first()).toBeVisible(
                { timeout: 30_000 }
            );
        });
    }

    test("the landing page offers both identity providers", async ({ page }) => {
        // Signed in already, so this asserts the page itself rather than the
        // redirect: FAM admits IDIR and Business BCeID and nothing else.
        await page.goto("/");

        await expect(page.locator("#login-idir-button")).toBeVisible();
        await expect(page.locator("#login-business-bceid-button")).toBeVisible();
    });

    test("an unknown route lands somewhere deliberate", async ({ page }) => {
        // Not a blank screen and not a hard 404 from the static host: the SPA
        // catch-all route has to handle it, or a mistyped link looks like an
        // outage.
        await page.goto("/definitely-not-a-real-page");

        await expect(page.locator("body")).not.toBeEmpty();
        await expect(page.locator("#root, #app, body > div").first()).toBeVisible();
    });
});
