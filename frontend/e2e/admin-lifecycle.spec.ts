import { expect, test } from "./fixtures";

import { chooseUser, openApplication } from "./helpers/permissions";
import { createRole, deleteRole } from "./helpers/roles";
import { chips, dangerButton, openDialog, toast } from "./carbon";
import { applicationPicker, TARGET_USER, uniqueSuffix } from "./utils";

/**
 * Appointing and removing administrators, at both tiers.
 *
 * These roles do not live on the application being administered - they sit on
 * FAM's own CSS integration, because a token carries only the roles of the
 * client it was issued to. That indirection is exactly what makes the flow worth
 * an end-to-end test: an appointment can appear to succeed while writing to the
 * wrong integration, and nothing on the screen would say so.
 */
test.describe("administrator lifecycle", () => {
    test.beforeEach(({ sandboxApp }) => {
        // Discovered from /api/css-applications. Absent when the
        // signed-in account administers nothing, which is a skip
        // rather than a failure - see fixtures.ts.
        test.skip(
            !sandboxApp,
            "the signed-in account administers no application"
        );
    });

    test.skip(
        !TARGET_USER,
        "E2E_TARGET_IDIR is not set - no directory user to appoint"
    );

    /** Opens one of the two administrator tabs. */
    const openTab = async (
        page: import("@playwright/test").Page,
        appName: string,
        label: RegExp
    ) => {
        await openApplication(page, appName);
        const tab = page.getByRole("tab").filter({ hasText: label });
        await expect(
            tab,
            `no "${label}" tab - the account may not administer this application`
        ).toBeVisible({ timeout: 30_000 });
        await tab.click();
    };

    const adminRow = (page: import("@playwright/test").Page, extra?: string) => {
        const row = page.locator("tr").filter({ hasText: TARGET_USER.toUpperCase() });
        return extra ? row.filter({ hasText: extra }).first() : row.first();
    };

    test("appoints an application admin, then removes them", async ({ sandboxApp, page }) => {
        await openTab(page, sandboxApp!.description, /application admins/i);
        await page.getByRole("button", { name: /add application admin/i }).click();

        await chooseUser(page, TARGET_USER);
        await page.getByRole("button", { name: /add application admin/i }).click();

        await openTab(page, sandboxApp!.description, /application admins/i);
        await expect(adminRow(page)).toBeVisible({ timeout: 60_000 });

        await adminRow(page).getByRole("button", { name: /^Remove /}).click();
        await openDialog(page);
        await dangerButton(page, "Remove").click();

        await expect(toast(page)).toContainText("Application admin removed", {
            timeout: 30_000,
        });
        await expect(adminRow(page)).toBeHidden({ timeout: 60_000 });
    });

    test("delegates one role, and the row names it", async ({ sandboxApp, page }) => {
        const code = `E2E_DELEG_${uniqueSuffix()}`;
        const roleName = "E2E delegated role";

        try {
            await createRole(page, sandboxApp!.description, { code, name: roleName });

            await openTab(page, sandboxApp!.description, /delegated admins/i);
            await page.getByRole("button", { name: /add delegated admin/i }).click();

            await chooseUser(page, TARGET_USER);
            // The role step is withheld until somebody is chosen.
            await expect(page.locator(".role-multi-select-table")).toBeVisible({
                timeout: 30_000,
            });

            await page
                .locator(".role-multi-select-table")
                .getByLabel(roleName, { exact: true })
                .check();

            await page.getByRole("button", { name: /add delegated admin/i }).click();

            await openTab(page, sandboxApp!.description, /delegated admins/i);

            // The "May grant" column shows the role's name, not its code - the
            // label lives in a sidecar on the application's integration while
            // the delegation itself lives on FAM's, so a row showing the code
            // means that lookup did not happen.
            const row = adminRow(page, roleName);
            await expect(row).toBeVisible({ timeout: 60_000 });
            await expect(chips(row)).not.toHaveCount(0);

            await row.getByRole("button", { name: /^Remove /}).click();
            await openDialog(page);
            await dangerButton(page, "Remove").click();

            await expect(toast(page)).toContainText("Delegated admin removed", {
                timeout: 30_000,
            });
        } finally {
            await deleteRole(page, sandboxApp!.description, code);
        }
    });

    test("shows no administrator tabs for FAM itself", async ({ sandboxApp, page }) => {
        // Every application admin and delegated admin in the province holds a
        // role on FAM's integration. Tabs here would list all of them as FAM's
        // own administrators, which none of them is.
        await page.goto("/manage-permissions");
        await page.locator("#protected-layout-container").waitFor();
        await applicationPicker(page).click();

        const fam = page
            .getByRole("option")
            .filter({ hasText: /forests access management/i })
            .first();

        if (!(await fam.isVisible().catch(() => false))) {
            test.skip(true, "this account cannot administer FAM itself");
            return;
        }
        await fam.click();

        await expect(page.getByRole("tab")).toHaveCount(1);
        await expect(page.getByRole("tab").first()).toContainText(/users/i);
    });
});
