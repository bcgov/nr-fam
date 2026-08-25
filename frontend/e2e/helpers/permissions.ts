import { expect, type Page } from "@playwright/test";

import { selectApplication } from "../utils";

/**
 * Driving the Manage permissions screen: finding a user, granting a role, and
 * revoking it again.
 *
 * Everything here goes through the UI rather than the API. The point of the
 * suite is that the screens work end to end - a helper that called the backend
 * directly would pass while the form that feeds it was broken.
 */

/** Opens Manage permissions with one application chosen. */
export const openApplication = async (
    page: Page,
    appName: string
): Promise<void> => {
    await page.goto("/manage-permissions");
    await page.locator("#protected-layout-container").waitFor();
    await selectApplication(page, appName);
    // The users table is the tab that opens by default.
    await expect(page.locator(".fam-table").first()).toBeVisible({
        timeout: 60_000,
    });
};

/**
 * Searches the directory and adds the first match to the selection.
 *
 * The search is a real IDIM lookup, so it needs a user id that exists. It is
 * supplied per environment rather than hard-coded - see TARGET_USER.
 */
export const chooseUser = async (page: Page, userId: string): Promise<void> => {
    await page.locator("#user-search-input").fill(userId);
    await page.getByRole("button", { name: "Search users" }).click();

    // The result arrives in a dialog. Adding from it is what populates the
    // selected-users table the rest of the form reads.
    const row = page.locator("tr").filter({ hasText: userId.toUpperCase() });
    await expect(
        row.first(),
        `the directory returned no user "${userId}" - set E2E_TARGET_IDIR to an ` +
            `account that exists in this environment`
    ).toBeVisible({ timeout: 60_000 });

    const add = page.getByRole("button", { name: /add|select/i }).first();
    if (await add.isVisible().catch(() => false)) {
        await add.click();
    }

    // The selected-users table carries fam-table now, and the chosen person is
    // in it.
    await expect(
        page.locator(".user-table").filter({ hasText: userId.toUpperCase() })
    ).toBeVisible({ timeout: 30_000 });
};

/** Ticks one role by its visible name in the multi-select list. */
export const tickRole = async (page: Page, roleName: string): Promise<void> => {
    const checkbox = page
        .locator(".role-multi-select-table .p-checkbox input")
        .and(page.locator(`[aria-label="${roleName}"]`));

    await expect(
        checkbox,
        `no role named "${roleName}" is offered - it may not exist in this ` +
            `application, or the account may not be allowed to grant it`
    ).toBeAttached({ timeout: 30_000 });

    await checkbox.dispatchEvent("change");
};

/** One row of the users table, matched on user and role together. */
export const permissionRow = (page: Page, userId: string, roleName: string) =>
    page
        .locator("tr")
        .filter({ hasText: userId.toUpperCase() })
        .filter({ hasText: roleName })
        .first();

/**
 * Revokes one permission and waits for the row to go.
 *
 * Tolerant of the row already being absent, so cleanup after a mid-spec failure
 * does not itself fail.
 */
export const revokePermission = async (
    page: Page,
    userId: string,
    roleName: string
): Promise<void> => {
    const row = permissionRow(page, userId, roleName);
    if (!(await row.isVisible().catch(() => false))) {
        return;
    }

    await row.getByRole("button", { name: "Delete user permission" }).click();

    const confirm = page.getByRole("button", { name: "Remove", exact: true });
    await expect(confirm).toBeVisible();
    await confirm.click();

    await expect(row).toBeHidden({ timeout: 60_000 });
};
