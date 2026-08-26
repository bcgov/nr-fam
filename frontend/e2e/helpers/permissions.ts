import { expect, type Page } from "@playwright/test";

import { dangerButton, openDialog, tickCheckbox } from "../carbon";
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

    // The results arrive in a modal. Confirming from it is what populates the
    // selected-users table the rest of the form reads.
    const dialog = await openDialog(page);

    await expect(
        dialog.getByText(userId.toUpperCase()).first(),
        `the directory returned no user "${userId}" - set E2E_TARGET_IDIR to an ` +
            `account that exists in this environment`
    ).toBeVisible({ timeout: 60_000 });

    // A lone result arrives already selected, so ticking it would turn it off
    // and leave Confirm disabled. Only tick when there is a choice to make.
    const confirm = dialog.getByRole("button", { name: "Confirm" });
    if (await confirm.isDisabled()) {
        await tickCheckbox(dialog, `Select ${userId.toUpperCase()}`);
    }
    await confirm.click();

    // The selected-users table below the search, as distinct from the same name
    // inside the modal that has just closed.
    await expect(
        page.locator(".user-id-card-table").getByText(userId.toUpperCase())
    ).toBeVisible({ timeout: 30_000 });
};

/**
 * Ticks one role by its visible name in the multi-select list.
 *
 * The checkbox's label is the role's own name, hidden because the row beside it
 * already says so - which makes the accessible name the only handle on it.
 */
export const tickRole = async (page: Page, roleName: string): Promise<void> => {
    const table = page.locator(".role-multi-select-table");
    await expect(
        table,
        "the role list is not showing - a user has to be chosen first"
    ).toBeVisible({ timeout: 30_000 });

    await expect(
        table.getByLabel(roleName, { exact: true }),
        `no role named "${roleName}" is offered - it may not exist in this ` +
            `application, or the account may not be allowed to grant it`
    ).toBeAttached({ timeout: 30_000 });

    await tickCheckbox(table, roleName);
};

/**
 * The scope panel for a role, which opens inside that role's own row.
 *
 * Ticking a scoped role expands its row; the pickers used to sit in a separate
 * step further down the form.
 */
export const scopePanel = (page: Page) => page.locator(".role-scope-fields");

/**
 * Chooses the first value a fixed-list scope picker offers.
 *
 * Districts and regions are a select box plus a table of what was chosen, the
 * same shape as the organisation picker - they were checkbox lists before.
 * Whichever value comes first is fine: these specs are about the scope
 * surviving the round trip, not about any particular district.
 */
export const pickFirstScope = async (
    page: Page,
    noun: "District" | "Region"
): Promise<void> => {
    const picker = page.getByRole("combobox", { name: noun });
    await expect(picker).toBeVisible({ timeout: 30_000 });
    await picker.click();

    const option = page.getByRole("option").first();
    await expect(option).toBeVisible({ timeout: 30_000 });
    await option.click();
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

    await openDialog(page);
    // "danger Remove", not "Remove" - see carbon.ts.
    const confirm = dangerButton(page, "Remove");
    await expect(confirm).toBeVisible();
    await confirm.click();

    await expect(row).toBeHidden({ timeout: 60_000 });
};
