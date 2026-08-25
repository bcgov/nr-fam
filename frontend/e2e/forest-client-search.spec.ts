import { expect, test } from "./fixtures";

import { chooseUser, openApplication, tickRole } from "./helpers/permissions";
import { createRole, deleteRole } from "./helpers/roles";
import { TARGET_USER, uniqueSuffix } from "./utils";

/**
 * The organisation autocomplete, against the real Forest Client API.
 *
 * Worth its own spec because it has broken three separate ways that unit tests
 * could not see: the wrong upstream endpoint (a name search that answered 400,
 * which the client read as "no match" and rendered as an empty list); a
 * case-sensitive substring that matched nothing because the stored names are
 * upper case; and option styling that never applied because the overlay is
 * teleported out of the component.
 */
test.describe("forest client autocomplete", () => {
    test.beforeEach(({ sandboxApp }) => {
        // Discovered from /api/css-applications. Absent when the
        // signed-in account administers nothing, which is a skip
        // rather than a failure - see fixtures.ts.
        test.skip(
            !sandboxApp,
            "the signed-in account administers no application"
        );
    });

    test.skip(!TARGET_USER, "E2E_TARGET_IDIR is not set");

    let code: string;
    const roleName = "E2E organisation role";

    test.beforeEach(async ({ sandboxApp, page }) => {
        code = `E2E_FC_${uniqueSuffix()}`;
        await createRole(page, sandboxApp!.description, {
            code,
            name: roleName,
            forestClient: true,
        });

        await openApplication(page, sandboxApp!.description);
        await page.getByRole("button", { name: /add permission/i }).click();
        await chooseUser(page, TARGET_USER);
        await tickRole(page, roleName);
        await expect(page.locator(".role-scope-card")).toBeVisible({
            timeout: 30_000,
        });
    });

    test.afterEach(async ({ sandboxApp, page }) => {
        await deleteRole(page, sandboxApp!.description, code);
    });

    const search = async (page: import("@playwright/test").Page, term: string) => {
        const input = page.locator(".forest-client-autocomplete input");
        await input.fill("");
        await input.fill(term);
        // The picker debounces, and the overlay is teleported to the body.
        return page.locator(".p-autocomplete-overlay .p-autocomplete-option");
    };

    test("finds organisations by name", async ({ sandboxApp, page }) => {
        // The failure this catches returned an empty list rather than an error:
        // the endpoint in use required an id, answered 400 to a name-only
        // query, and the client mapped that to "no match".
        const options = await search(page, "log");

        await expect(options.first()).toBeVisible({ timeout: 30_000 });
    });

    test("finds organisations by part of a client number", async ({ sandboxApp, page }) => {
        // "000" against zero-padded eight-digit numbers. An exact-match upstream
        // answers nothing here, which is exactly what it used to do.
        const options = await search(page, "000");

        await expect(options.first()).toBeVisible({ timeout: 30_000 });
    });

    test("shows the number beside the name, separated", async ({ sandboxApp, page }) => {
        const options = await search(page, "000");
        await expect(options.first()).toBeVisible({ timeout: 30_000 });

        // A number alone is not something anybody recognises, and the styling
        // that separates the two lives outside the component because the
        // overlay is teleported.
        await expect(options.first().locator(".option-number")).toBeVisible();
        await expect(options.first()).toContainText("-");
    });

    test("adds a chosen organisation to the role's card", async ({ sandboxApp, page }) => {
        const options = await search(page, "000");
        await expect(options.first()).toBeVisible({ timeout: 30_000 });
        await options.first().click();

        // It lands in the card's own table, which is what the grant reads.
        await expect(
            page.locator(".role-scope-card .foresnt-client-add-table-container tbody tr").first()
        ).toBeVisible({ timeout: 30_000 });
    });

    test("says so plainly when nothing matches", async ({ sandboxApp, page }) => {
        const nonsense = await search(page, "zzzzqqqqxxxx");

        await expect(nonsense).toHaveCount(0);
        await expect(
            page.getByText("No organization found").first()
        ).toBeVisible({ timeout: 30_000 });
    });
});
