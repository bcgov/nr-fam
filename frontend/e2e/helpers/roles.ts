import { expect, type Page } from "@playwright/test";

import { dangerButton, openDialog } from "../carbon";
import { selectApplication } from "../utils";

/**
 * Creating and deleting roles on Manage roles.
 *
 * Every write spec that needs a role of its own makes one here rather than
 * relying on a fixture role existing in the sandbox: a role left behind by an
 * earlier run would make "create" fail on a duplicate code, and a role somebody
 * deleted by hand would make every dependent spec fail for an unrelated reason.
 */

export type NewRole = {
    code: string;
    name: string;
    description?: string;
    /** Adds the district scope marker, so grants of it must name a district. */
    district?: boolean;
    /** Adds the region scope marker, so grants of it must name a region. */
    region?: boolean;
    /** Adds the forest client scope marker. */
    forestClient?: boolean;
};

/** Fills the create-role form and submits it for the chosen environment. */
export const createRole = async (
    page: Page,
    appName: string,
    role: NewRole
): Promise<void> => {
    await page.goto("/manage-roles");
    await page.locator("#protected-layout-container").waitFor();
    await selectApplication(page, appName);

    await page.locator("#roleCode").fill(role.code);
    await page.locator("#roleName").fill(role.name);
    if (role.description) {
        await page.locator("#description").fill(role.description);
    }
    if (role.district) {
        await page
            .getByRole("checkbox", { name: "Requires a district selection" })
            .check();
    }
    if (role.region) {
        await page
            .getByRole("checkbox", { name: "Requires a region selection" })
            .check();
    }
    if (role.forestClient) {
        await page
            .getByRole("checkbox", { name: "Requires a forest client selection" })
            .check();
    }

    await page.getByRole("button", { name: "Create role", exact: true }).click();

    // The new role appears in the existing-roles table below the form. Waiting
    // on that rather than on the button settling is what makes the next step
    // safe: CSS creates several roles per scoped role and the table is the only
    // confirmation they all landed.
    await expect(roleRow(page, role.code)).toBeVisible({ timeout: 60_000 });
};

/** The Existing roles row for one role code. */
export const roleRow = (page: Page, code: string) =>
    page.locator("tr").filter({ hasText: code }).first();

/**
 * Deletes a role and waits for it to leave the table.
 *
 * Tolerant of the role already being gone, so it is safe to call from a cleanup
 * block that may run after a failure part-way through a spec.
 */
export const deleteRole = async (
    page: Page,
    appName: string,
    code: string
): Promise<void> => {
    await page.goto("/manage-roles");
    await page.locator("#protected-layout-container").waitFor();
    await selectApplication(page, appName);

    const row = roleRow(page, code);
    if (!(await row.isVisible().catch(() => false))) {
        return;
    }

    // Named per role, so the button is unambiguous even when two codes share a
    // prefix.
    await row.getByRole("button", { name: `Delete ${code}` }).click();

    await openDialog(page);
    // "danger Delete", not "Delete" - see carbon.ts.
    const confirm = dangerButton(page, "Delete");
    await expect(confirm).toBeVisible();
    await confirm.click();

    await expect(row).toBeHidden({ timeout: 60_000 });
};
