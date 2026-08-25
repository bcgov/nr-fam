import { expect, test } from "./fixtures";

import { openApplication, revokePermission } from "./helpers/permissions";
import { createRole, deleteRole } from "./helpers/roles";
import { TARGET_USER, uniqueSuffix } from "./utils";

/**
 * Bulk upload: preview, then apply.
 *
 * The preview writes nothing and re-validation happens again on apply, so a
 * previewed payload is never trusted back from the browser. Both halves are
 * asserted, because a preview that looks right while the apply re-derives
 * something different is the failure that would put the wrong access in place.
 */
test.describe("bulk upload", () => {
    test.beforeEach(({ sandboxApp }) => {
        // Discovered from /api/css-applications. Absent when the
        // signed-in account administers nothing, which is a skip
        // rather than a failure - see fixtures.ts.
        test.skip(
            !sandboxApp,
            "the signed-in account administers no application"
        );
    });

    test.skip(!TARGET_USER, "E2E_TARGET_IDIR is not set - nothing to upload");

    /** The target user's real GUID, which is what the CSV keys on. */
    const guidOf = async (
        request: import("@playwright/test").APIRequestContext
    ): Promise<string | null> => {
        const lookup = await request.get(
            `/api/identity-lookup/idir?userId=${encodeURIComponent(TARGET_USER)}`
        );
        if (!lookup.ok()) {
            return null;
        }
        const found = await lookup.json();
        return found?.user_guid ?? found?.userGuid ?? null;
    };

    /** Opens the bulk upload screen for one application. */
    const openBulkUpload = async (
        page: import("@playwright/test").Page,
        appName: string
    ) => {
        await openApplication(page, appName);
        await page.getByRole("button", { name: /bulk|upload/i }).first().click();
    };

    /** Uploads a CSV through the file input, which is what the widget wraps. */
    const upload = async (
        page: import("@playwright/test").Page,
        csv: string
    ) => {
        await page.locator('input[type="file"]').setInputFiles({
            name: "e2e-bulk-grant.csv",
            mimeType: "text/csv",
            buffer: Buffer.from(csv, "utf8"),
        });
    };

    test("previews a file, resolving names before anything is granted", async ({ sandboxApp,
        page,
        request,
    }) => {
        const code = `E2E_BULK_${uniqueSuffix()}`;
        const roleName = "E2E bulk role";

        try {
            await createRole(page, sandboxApp!.description, { code, name: roleName });

            // The CSV keys the user by GUID, so it needs the real one.
            const lookup = await request.get(
                `/api/identity-lookup/idir?userId=${encodeURIComponent(TARGET_USER)}`
            );
            test.skip(!lookup.ok(), "the directory lookup failed");
            const found = await lookup.json();
            const guid = found?.user_guid ?? found?.userGuid;
            test.skip(!guid, "the directory returned no GUID");

            await openApplication(page, sandboxApp!.description);
            await page.getByRole("button", { name: /bulk|upload/i }).first().click();

            await upload(page, `${guid},IDIR,${code}\n`);

            // The preview resolves the person and the role, so the uploader
            // confirms names rather than identifiers - which is the entire
            // reason the step exists.
            await expect(page.getByText(TARGET_USER.toUpperCase()).first()).toBeVisible({
                timeout: 60_000,
            });
            await expect(page.getByText(roleName).first()).toBeVisible();

            // Nothing has been granted yet.
            await openApplication(page, sandboxApp!.description);
            await expect(
                page.locator("tr").filter({ hasText: TARGET_USER.toUpperCase() }).filter({ hasText: roleName })
            ).toHaveCount(0);
        } finally {
            await revokePermission(page, TARGET_USER, roleName).catch(() => {});
            await deleteRole(page, sandboxApp!.description, code);
        }
    });

    test("reports a row it cannot resolve rather than failing the file", async ({ sandboxApp,
        page,
    }) => {
        const code = `E2E_BADROW_${uniqueSuffix()}`;

        try {
            await createRole(page, sandboxApp!.description, { code, name: "E2E bad-row role" });

            await openApplication(page, sandboxApp!.description);
            await page.getByRole("button", { name: /bulk|upload/i }).first().click();

            await upload(page, "NOTAREALGUID,IDIR,NOT_A_REAL_ROLE\n");

            // Rows are unrelated people; one bad line must not discard the rest,
            // so the error belongs on the row rather than on the upload.
            await expect(page.getByText(/error|invalid|not found/i).first()).toBeVisible({
                timeout: 60_000,
            });
        } finally {
            await deleteRole(page, sandboxApp!.description, code);
        }
    });

    test("resolves a district into a place name before granting", async ({
        sandboxApp,
        page,
        request,
    }) => {
        const code = `E2E_BULKD_${uniqueSuffix()}`;

        try {
            await createRole(page, sandboxApp!.description, {
                code,
                name: "E2E bulk district role",
                district: true,
            });

            const guid = await guidOf(request);
            test.skip(!guid, "the directory returned no GUID");

            await openBulkUpload(page, sandboxApp!.description);
            await upload(page, `${guid},IDIR,${code},DCC\n`);

            // A district code is not something anybody can check by eye, which
            // is the whole reason the confirmation step exists.
            await expect(page.getByText("Cariboo-Chilcotin").first()).toBeVisible({
                timeout: 60_000,
            });
        } finally {
            await deleteRole(page, sandboxApp!.description, code);
        }
    });

    test("refuses a district on a role that is not granted per district", async ({
        sandboxApp,
        page,
        request,
    }) => {
        const code = `E2E_BULKU_${uniqueSuffix()}`;

        try {
            await createRole(page, sandboxApp!.description, {
                code,
                name: "E2E bulk unscoped role",
            });

            const guid = await guidOf(request);
            test.skip(!guid, "the directory returned no GUID");

            await openBulkUpload(page, sandboxApp!.description);
            await upload(page, `${guid},IDIR,${code},DCC\n`);

            // The dangerous direction: the value would simply be ignored, and
            // the row would grant wider access than the file asks for.
            await expect(
                page.getByText(/not granted per district/i).first()
            ).toBeVisible({ timeout: 60_000 });
        } finally {
            await deleteRole(page, sandboxApp!.description, code);
        }
    });

    test("refuses a scoped role with its scope column left empty", async ({
        sandboxApp,
        page,
        request,
    }) => {
        const code = `E2E_BULKS_${uniqueSuffix()}`;

        try {
            await createRole(page, sandboxApp!.description, {
                code,
                name: "E2E bulk scoped role",
                district: true,
            });

            const guid = await guidOf(request);
            test.skip(!guid, "the directory returned no GUID");

            await openBulkUpload(page, sandboxApp!.description);
            await upload(page, `${guid},IDIR,${code}\n`);

            // Granting the base role would assign something no application
            // authorises on.
            await expect(
                page.getByText(/granted per district/i).first()
            ).toBeVisible({ timeout: 60_000 });
        } finally {
            await deleteRole(page, sandboxApp!.description, code);
        }
    });

    test("refuses a user type that is neither IDIR nor BCEID", async ({
        sandboxApp,
        page,
        request,
    }) => {
        const code = `E2E_BULKT_${uniqueSuffix()}`;

        try {
            await createRole(page, sandboxApp!.description, {
                code,
                name: "E2E bulk type role",
            });

            const guid = await guidOf(request);
            test.skip(!guid, "the directory returned no GUID");

            await openBulkUpload(page, sandboxApp!.description);
            // BCSC is a real identity provider and deliberately not one FAM
            // admits, so it is the value most likely to be typed in error.
            await upload(page, `${guid},BCSC,${code}\n`);

            await expect(page.getByText(/not a user type/i).first()).toBeVisible({
                timeout: 60_000,
            });
        } finally {
            await deleteRole(page, sandboxApp!.description, code);
        }
    });

    test("offers the template as a link, above the file picker", async ({ sandboxApp, page }) => {
        await openApplication(page, sandboxApp!.description);
        await page.getByRole("button", { name: /bulk|upload/i }).first().click();

        await expect(
            page.getByRole("link", { name: /download the template/i })
        ).toBeVisible({ timeout: 30_000 });
    });
});
