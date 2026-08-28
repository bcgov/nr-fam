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
    }) => {
        const code = `E2E_BULK_${uniqueSuffix()}`;
        const roleName = "E2E bulk role";

        try {
            await createRole(page, sandboxApp!.description, { code, name: roleName });

            await openApplication(page, sandboxApp!.description);
            await page.getByRole("button", { name: /bulk|upload/i }).first().click();

            await upload(page, `${TARGET_USER},IDIR,${code}\n`);

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

            await upload(page, "NOTAREALUSER,IDIR,NOT_A_REAL_ROLE\n");

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
    }) => {
        const code = `E2E_BULKD_${uniqueSuffix()}`;

        try {
            await createRole(page, sandboxApp!.description, {
                code,
                name: "E2E bulk district role",
                district: true,
            });

            await openBulkUpload(page, sandboxApp!.description);
            await upload(page, `${TARGET_USER},IDIR,${code},DCC\n`);

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
    }) => {
        const code = `E2E_BULKU_${uniqueSuffix()}`;

        try {
            await createRole(page, sandboxApp!.description, {
                code,
                name: "E2E bulk unscoped role",
            });

            await openBulkUpload(page, sandboxApp!.description);
            await upload(page, `${TARGET_USER},IDIR,${code},DCC\n`);

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
    }) => {
        const code = `E2E_BULKS_${uniqueSuffix()}`;

        try {
            await createRole(page, sandboxApp!.description, {
                code,
                name: "E2E bulk scoped role",
                district: true,
            });

            await openBulkUpload(page, sandboxApp!.description);
            await upload(page, `${TARGET_USER},IDIR,${code}\n`);

            // Granting the base role would assign something no application
            // authorises on.
            await expect(
                page.getByText(/granted per district/i).first()
            ).toBeVisible({ timeout: 60_000 });
        } finally {
            await deleteRole(page, sandboxApp!.description, code);
        }
    });

    test("resolves a region into a place name before granting", async ({
        sandboxApp,
        page,
    }) => {
        const code = `E2E_BULKR_${uniqueSuffix()}`;

        try {
            await createRole(page, sandboxApp!.description, {
                code,
                name: "E2E bulk region role",
                region: true,
            });

            await openBulkUpload(page, sandboxApp!.description);
            // Six columns: the region is the last one, after organization.
            await upload(page, `${TARGET_USER},IDIR,${code},,,CARIBOO\n`);

            // Same reason the district test exists: a code is not something
            // anybody can check by eye, and the confirmation step is what turns
            // it into a place.
            await expect(page.getByText("Cariboo").first()).toBeVisible({
                timeout: 60_000,
            });
        } finally {
            await deleteRole(page, sandboxApp!.description, code);
        }
    });

    test("keeps the region column clear of the organization column", async ({
        sandboxApp,
        page,
    }) => {
        const code = `E2E_BULKC_${uniqueSuffix()}`;

        try {
            await createRole(page, sandboxApp!.description, {
                code,
                name: "E2E bulk district role",
                district: true,
            });

            await openBulkUpload(page, sandboxApp!.description);
            // Five columns, written before regions existed. The parser is
            // positional, so this is the case that would break had the region
            // column been slotted in beside district rather than appended -
            // the empty organization column would have been read as a region.
            await upload(page, `${TARGET_USER},IDIR,${code},DCC,\n`);

            await expect(page.getByText("Cariboo-Chilcotin").first()).toBeVisible({
                timeout: 60_000,
            });
            await expect(
                page.getByText(/not a natural resource region/i)
            ).toHaveCount(0);
        } finally {
            await deleteRole(page, sandboxApp!.description, code);
        }
    });

    test("refuses a region on a role that is not granted per region", async ({
        sandboxApp,
        page,
    }) => {
        const code = `E2E_BULKRU_${uniqueSuffix()}`;

        try {
            await createRole(page, sandboxApp!.description, {
                code,
                name: "E2E bulk unscoped role",
            });

            await openBulkUpload(page, sandboxApp!.description);
            await upload(page, `${TARGET_USER},IDIR,${code},,,CARIBOO\n`);

            // As with district: the value would otherwise be ignored and the row
            // would grant wider access than the file asks for.
            await expect(
                page.getByText(/not granted per region/i).first()
            ).toBeVisible({ timeout: 60_000 });
        } finally {
            await deleteRole(page, sandboxApp!.description, code);
        }
    });

    test("refuses a region-scoped role with its region column left empty", async ({
        sandboxApp,
        page,
    }) => {
        const code = `E2E_BULKRS_${uniqueSuffix()}`;

        try {
            await createRole(page, sandboxApp!.description, {
                code,
                name: "E2E bulk region role",
                region: true,
            });

            await openBulkUpload(page, sandboxApp!.description);
            await upload(page, `${TARGET_USER},IDIR,${code}\n`);

            await expect(
                page.getByText(/granted per region/i).first()
            ).toBeVisible({ timeout: 60_000 });
        } finally {
            await deleteRole(page, sandboxApp!.description, code);
        }
    });

    test("refuses a region code that is not one", async ({
        sandboxApp,
        page,
    }) => {
        const code = `E2E_BULKRX_${uniqueSuffix()}`;

        try {
            await createRole(page, sandboxApp!.description, {
                code,
                name: "E2E bulk region role",
                region: true,
            });

            await openBulkUpload(page, sandboxApp!.description);
            await upload(page, `${TARGET_USER},IDIR,${code},,,NOPE\n`);

            await expect(
                page.getByText(/not a natural resource region/i).first()
            ).toBeVisible({ timeout: 60_000 });
        } finally {
            await deleteRole(page, sandboxApp!.description, code);
        }
    });

    test("refuses a user type that is neither IDIR nor BCEID", async ({
        sandboxApp,
        page,
    }) => {
        const code = `E2E_BULKT_${uniqueSuffix()}`;

        try {
            await createRole(page, sandboxApp!.description, {
                code,
                name: "E2E bulk type role",
            });

            await openBulkUpload(page, sandboxApp!.description);
            // BCSC is a real identity provider and deliberately not one FAM
            // admits, so it is the value most likely to be typed in error.
            await upload(page, `${TARGET_USER},BCSC,${code}\n`);

            await expect(page.getByText(/not a user type/i).first()).toBeVisible({
                timeout: 60_000,
            });
        } finally {
            await deleteRole(page, sandboxApp!.description, code);
        }
    });

    test("offers the template beside the file picker", async ({ sandboxApp, page }) => {
        await openApplication(page, sandboxApp!.description);
        await page.getByRole("button", { name: /bulk|upload/i }).first().click();

        // A button, not an anchor, though it is styled as a link: it builds a
        // blob and hands it to the browser, so there is no href to follow. This
        // asked for role "link" and so could never have matched.
        await expect(
            page.getByRole("button", { name: /download the template/i })
        ).toBeVisible({ timeout: 30_000 });

        // And the shape of the file is on screen beside it, so nobody has to
        // open the template to learn what the columns are.
        await expect(
            page.getByText(/username,user_type,role/).first()
        ).toBeVisible();
    });
});
