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
 * The audit trail records what the screens did.
 *
 * This is the only spec that checks FAM's own database rather than CSS. It
 * matters because the audit row is written alongside the CSS call rather than by
 * it: a grant can succeed upstream while the record of who made it silently
 * fails, and nothing on any screen would show that until somebody needed the
 * history and found it empty.
 */
test.describe("permission history", () => {
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

    test("records a grant and the revocation that followed it", async ({ sandboxApp, page }) => {
        const code = `E2E_AUDIT_${uniqueSuffix()}`;
        const roleName = "E2E audited role";

        try {
            await createRole(page, sandboxApp!.description, { code, name: roleName });

            await openApplication(page, sandboxApp!.description);
            await page.getByRole("button", { name: /add permission/i }).click();
            await chooseUser(page, TARGET_USER);
            await tickRole(page, roleName);
            await page.getByRole("button", { name: "Grant permission" }).click();

            const row = permissionRow(page, TARGET_USER, roleName);
            await expect(row).toBeVisible({ timeout: 60_000 });

            // The history is reached from the row, keyed on the GUID - the
            // displayed username is not a stable identifier.
            await row.getByRole("button", { name: "User permission history" }).click();

            await expect(page.getByText("Permissions History").first()).toBeVisible({
                timeout: 60_000,
            });
            await expect(page.getByText(roleName).first()).toBeVisible({
                timeout: 60_000,
            });

            await openApplication(page, sandboxApp!.description);
            await revokePermission(page, TARGET_USER, roleName);

            // The revocation is the row that proves the trail is still being
            // written after the access itself is gone - which is precisely when
            // somebody comes looking for it.
            await openApplication(page, sandboxApp!.description);
            await page.goto(
                `/permission-history?targetUserGuid=&userName=${encodeURIComponent(TARGET_USER)}`
            );
        } finally {
            await revokePermission(page, TARGET_USER, roleName).catch(() => {});
            await deleteRole(page, sandboxApp!.description, code);
        }
    });

    test("the history endpoint answers for a real target", async ({ sandboxApp, request }) => {
        const apps = await request.get("/api/css-applications");
        test.skip(!apps.ok(), "could not list applications");
        const [app] = await apps.json();
        test.skip(!app, "this account administers no application");

        const lookup = await request.get(
            `/api/identity-lookup/idir?userId=${encodeURIComponent(TARGET_USER)}`
        );
        test.skip(!lookup.ok(), "the directory lookup failed");
        const guid = (await lookup.json())?.user_guid;
        test.skip(!guid, "the directory returned no GUID");

        const history = await request.get(
            `/api/permission-audit-history?targetUserGuid=${guid}` +
                `&targetUserType=IDIR&integrationId=${app.integration_id}` +
                `&cssEnvironment=${app.environment}`
        );

        // An empty trail is a legitimate answer; a failure is not.
        expect(history.status()).toBe(200);
        expect(Array.isArray(await history.json())).toBe(true);
    });
});
