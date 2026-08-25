import { expect, test } from "./fixtures";

import {
    chooseUser,
    openApplication,
    permissionRow,
    revokePermission,
    tickRole,
} from "./helpers/permissions";
import { createRole, deleteRole } from "./helpers/roles";
import { TARGET_USER, uniqueSuffix } from "./utils";

/**
 * Granting a permission and revoking it, through the screens.
 *
 * The whole point of the suite: a grant crosses the directory lookup, the role
 * picker, the CSS assignment and the audit write, and every one of those has
 * failed independently at some point. Asserting the row appears in the table is
 * the only check that covers the join.
 *
 * Needs a target user, because FAM refuses to alter your own access - a
 * self-grant would fail on the self-administration guard rather than exercising
 * anything.
 */
test.describe("grant lifecycle", () => {
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
        "E2E_TARGET_IDIR is not set - no directory user to grant to"
    );

    test("grants a role to a user, then revokes it", async ({ sandboxApp, page }) => {
        const code = `E2E_GRANT_${uniqueSuffix()}`;
        const roleName = "E2E grant role";

        try {
            await createRole(page, sandboxApp!.description, { code, name: roleName });

            await openApplication(page, sandboxApp!.description);
            await page.getByRole("button", { name: /add permission/i }).click();

            await chooseUser(page, TARGET_USER);
            await tickRole(page, roleName);
            await page.getByRole("button", { name: "Grant permission" }).click();

            // Back on Manage permissions, with a toast and the new row marked.
            await expect(page.getByText("Permission granted").first()).toBeVisible({
                timeout: 60_000,
            });
            await expect(
                permissionRow(page, TARGET_USER, roleName)
            ).toBeVisible({ timeout: 60_000 });

            await revokePermission(page, TARGET_USER, roleName);

            await expect(page.getByText("Permission removed").first()).toBeVisible({
                timeout: 30_000,
            });
        } finally {
            await revokePermission(page, TARGET_USER, roleName).catch(() => {});
            await deleteRole(page, sandboxApp!.description, code);
        }
    });

    test("grants a district-scoped role and shows its scope as a chip", async ({ sandboxApp,
        page,
    }) => {
        const code = `E2E_SCOPED_${uniqueSuffix()}`;
        const roleName = "E2E scoped role";

        try {
            await createRole(page, sandboxApp!.description, {
                code,
                name: roleName,
                district: true,
            });

            await openApplication(page, sandboxApp!.description);
            await page.getByRole("button", { name: /add permission/i }).click();

            await chooseUser(page, TARGET_USER);
            await tickRole(page, roleName);

            // The scope card appears only for a role that needs narrowing, and
            // the form cannot be submitted until it is answered.
            const card = page.locator(".role-scope-card");
            await expect(card).toBeVisible({ timeout: 30_000 });

            const firstDistrict = card
                .locator(".district-select-table-container .p-checkbox input")
                .first();
            await expect(firstDistrict).toBeAttached({ timeout: 30_000 });
            await firstDistrict.dispatchEvent("change");

            await page.getByRole("button", { name: "Grant permission" }).click();

            const row = permissionRow(page, TARGET_USER, roleName);
            await expect(row).toBeVisible({ timeout: 60_000 });
            // The scope is recorded in the CSS role name and parsed back out.
            // A row with no chip means the suffix was lost on the way through.
            await expect(row.locator(".p-chip")).not.toHaveCount(0);
        } finally {
            await revokePermission(page, TARGET_USER, roleName).catch(() => {});
            await deleteRole(page, sandboxApp!.description, code);
        }
    });

    test("will not submit a scoped role with nothing chosen for it", async ({ sandboxApp,
        page,
    }) => {
        const code = `E2E_UNSCOPED_${uniqueSuffix()}`;
        const roleName = "E2E unanswered role";

        try {
            await createRole(page, sandboxApp!.description, {
                code,
                name: roleName,
                district: true,
            });

            await openApplication(page, sandboxApp!.description);
            await page.getByRole("button", { name: /add permission/i }).click();
            await chooseUser(page, TARGET_USER);
            await tickRole(page, roleName);

            await page.getByRole("button", { name: "Grant permission" }).click();

            // The grant would name a role that does not exist, so it is refused
            // here rather than creating a permission nobody holds.
            await expect(
                page.getByText(/check the highlighted fields|at least one district/i).first()
            ).toBeVisible({ timeout: 30_000 });
            await expect(
                permissionRow(page, TARGET_USER, roleName)
            ).toHaveCount(0);
        } finally {
            await deleteRole(page, sandboxApp!.description, code);
        }
    });

    test("withholds the role step until a user is chosen", async ({ sandboxApp, page }) => {
        await openApplication(page, sandboxApp!.description);
        await page.getByRole("button", { name: /add permission/i }).click();

        // "Which roles are they getting" is not an answerable question yet.
        await expect(page.locator(".role-multi-select-table")).toHaveCount(0);

        await chooseUser(page, TARGET_USER);

        await expect(page.locator(".role-multi-select-table")).toBeVisible({
            timeout: 30_000,
        });
    });

    test("grants several roles at once", async ({ sandboxApp, page }) => {
        const first = `E2E_MULTI_A_${uniqueSuffix()}`;
        const second = `E2E_MULTI_B_${uniqueSuffix()}`;
        const firstName = "E2E multi role A";
        const secondName = "E2E multi role B";

        try {
            await createRole(page, sandboxApp!.description, { code: first, name: firstName });
            await createRole(page, sandboxApp!.description, { code: second, name: secondName });

            await openApplication(page, sandboxApp!.description);
            await page.getByRole("button", { name: /add permission/i }).click();
            await chooseUser(page, TARGET_USER);
            await tickRole(page, firstName);
            await tickRole(page, secondName);

            await page.getByRole("button", { name: "Grant permission" }).click();

            // CSS assigns one role per call, so this is two calls that do not
            // share a fate - both rows appearing is the only proof both landed.
            await expect(
                permissionRow(page, TARGET_USER, firstName)
            ).toBeVisible({ timeout: 60_000 });
            await expect(
                permissionRow(page, TARGET_USER, secondName)
            ).toBeVisible({ timeout: 60_000 });
        } finally {
            await revokePermission(page, TARGET_USER, firstName).catch(() => {});
            await revokePermission(page, TARGET_USER, secondName).catch(() => {});
            await deleteRole(page, sandboxApp!.description, first);
            await deleteRole(page, sandboxApp!.description, second);
        }
    });
});
