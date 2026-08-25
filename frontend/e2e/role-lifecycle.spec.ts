import { expect, test } from "./fixtures";

import { createRole, deleteRole, roleRow } from "./helpers/roles";
import { selectApplication, uniqueSuffix } from "./utils";

/**
 * Defining a role, then removing it.
 *
 * These write to a real CSS sandbox integration. Every role is created with a
 * unique code so a run cannot collide with a leftover from a previous one, and
 * every spec deletes what it made even when it failed part-way - CSS roles are
 * never garbage-collected, and an abandoned one is offered on the grant screen
 * forever.
 */
test.describe("role lifecycle", () => {
    test.beforeEach(({ sandboxApp }) => {
        // Discovered from /api/css-applications. Absent when the
        // signed-in account administers nothing, which is a skip
        // rather than a failure - see fixtures.ts.
        test.skip(
            !sandboxApp,
            "the signed-in account administers no application"
        );
    });

    test("creates a plain role and deletes it again", async ({ sandboxApp, page }) => {
        const code = `E2E_PLAIN_${uniqueSuffix()}`;

        try {
            await createRole(page, sandboxApp!.description, {
                code,
                name: "E2E plain role",
                description: "Created by the end-to-end suite",
            });

            await expect(roleRow(page, code)).toBeVisible();
            // The name is held in a sidecar role beside the code, so a role
            // showing its code where its name should be means the sidecar was
            // not created.
            await expect(roleRow(page, code)).toContainText("E2E plain role");
        } finally {
            await deleteRole(page, sandboxApp!.description, code);
        }
    });

    test("creates a district-scoped role, which grants must then narrow", async ({ sandboxApp,
        page,
    }) => {
        const code = `E2E_DISTRICT_${uniqueSuffix()}`;

        try {
            await createRole(page, sandboxApp!.description, {
                code,
                name: "E2E district role",
                district: true,
            });

            // The scope requirement is what the grant screen reads to decide
            // whether to demand a district. A role created without its marker
            // looks fine here and silently grants unscoped.
            await expect(roleRow(page, code)).toContainText(/district/i);
        } finally {
            await deleteRole(page, sandboxApp!.description, code);
        }
    });

    test("refuses a duplicate role code", async ({ sandboxApp, page }) => {
        const code = `E2E_DUP_${uniqueSuffix()}`;

        try {
            await createRole(page, sandboxApp!.description, { code, name: "E2E first" });

            // Same code again. CSS would happily find-or-create, so the refusal
            // is FAM's - and without it a second role would silently adopt the
            // first one's members.
            await page.locator("#roleCode").fill(code);
            await page.locator("#roleName").fill("E2E second");
            await page
                .getByRole("button", { name: "Create role", exact: true })
                .click();

            await expect(
                page.getByText(/already|exists|in use/i).first()
            ).toBeVisible({ timeout: 30_000 });
        } finally {
            await deleteRole(page, sandboxApp!.description, code);
        }
    });

    test("does not offer FAM's own application", async ({ sandboxApp, page }) => {
        // FAM's integration holds FAM_ADMIN plus the APP_ADMIN_<id>_<ENV> and
        // DELEGATED_ADMIN_... roles it generates. Deleting one of those from
        // this screen would strip every administrator of that application at
        // once, and nothing here would say so.
        await page.goto("/manage-roles");
        await page.locator("#protected-layout-container").waitFor();
        await page.locator("#application-selector-dropdown-id").click();

        const options = page.locator(".p-select-overlay .p-select-option");
        await expect(options.first()).toBeVisible({ timeout: 30_000 });

        await expect(
            options.filter({ hasText: /forests access management/i })
        ).toHaveCount(0);
    });

    test("deleting a role confirms it with a toast", async ({ sandboxApp, page }) => {
        const code = `E2E_REPORT_${uniqueSuffix()}`;
        await createRole(page, sandboxApp!.description, {
            code,
            name: "E2E reported role",
            district: true,
        });

        await page.goto("/manage-roles");
        await page.locator("#protected-layout-container").waitFor();
        await selectApplication(page, sandboxApp!.description);

        await roleRow(page, code).getByRole("button", { name: "Delete role" }).click();
        await page.getByRole("button", { name: "Delete", exact: true }).click();

        // A toast naming the role and the application, and nothing else. The
        // derived roles that went with it are a consequence of the deletion,
        // not a separate outcome to recite.
        const toast = page.locator(".p-toast-message");
        await expect(toast).toBeVisible({ timeout: 60_000 });
        await expect(toast).toContainText("Role deleted");
        await expect(toast).toContainText(code);
        await expect(roleRow(page, code)).toBeHidden({ timeout: 60_000 });
    });
});
