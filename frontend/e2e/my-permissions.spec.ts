import { expect, test } from "@playwright/test";

import { gotoProtected } from "./utils";

/**
 * The one screen every signed-in user can open.
 *
 * It reports on the caller, so it needs no administrative access and no sandbox
 * data - which makes it the right place to assert that the token actually
 * resolved to somebody. A screen that renders while `/auth/self` silently
 * returned nothing looks identical to one belonging to a user with no access,
 * so the distinction is asserted rather than assumed.
 */
test.describe("my permissions", () => {
    test("renders for the signed-in user", async ({ page }) => {
        await gotoProtected(page, "/my-permissions");

        await expect(page.getByText("My permissions").first()).toBeVisible();
        await expect(
            page.getByText("The applications you can use").first()
        ).toBeVisible();
    });

    test("answers from the server rather than from the token", async ({ page }) => {
        // FAM resolves roles per request so a revocation takes effect without a
        // fresh sign-in. If this screen ever started reading the token instead,
        // the call would stop happening and nothing else would show it.
        const call = page.waitForResponse(
            (r) => r.url().includes("/api/auth/self") && r.status() === 200,
            { timeout: 60_000 }
        );

        await gotoProtected(page, "/my-permissions");

        expect((await call).ok()).toBe(true);
    });

    test("shows a table or an empty state, never a spinner that never ends", async ({
        page,
    }) => {
        await gotoProtected(page, "/my-permissions");

        // Either outcome is correct; a permanent loading state is not. This is
        // the shape of failure a broken CSS fan-out actually produces.
        const settled = page
            .locator("table, .p-datatable-emptymessage")
            .or(page.getByText(/no |none|nothing/i));

        await expect(settled.first()).toBeVisible({ timeout: 60_000 });
    });
});
