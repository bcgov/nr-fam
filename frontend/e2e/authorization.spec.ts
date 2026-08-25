import { expect, test } from "@playwright/test";

import { gotoProtected } from "./utils";

/**
 * The backend refuses what the UI hides.
 *
 * Every rule below is enforced server-side; the screens only decline to offer
 * it. These call the API directly, past the UI, because that is the only way to
 * tell a real guard from a hidden button - and a hidden button is not a control.
 *
 * The session cookie comes from storageState, so these are the signed-in
 * account's own privileges rather than an anonymous request.
 */
test.describe("authorization is enforced by the backend", () => {
    test("an anonymous request is refused", async ({ playwright }) => {
        // A fresh context with no storageState: no token, no cookies.
        const anonymous = await playwright.request.newContext({
            baseURL: process.env.E2E_BASE_URL,
        });

        const response = await anonymous.get("/api/css-applications");

        expect(
            [401, 403],
            `unauthenticated read returned ${response.status()}`
        ).toContain(response.status());

        await anonymous.dispose();
    });

    test("a garbage bearer token is refused", async ({ playwright }) => {
        // Signature verification, not merely "is there a header".
        const forged = await playwright.request.newContext({
            baseURL: process.env.E2E_BASE_URL,
            extraHTTPHeaders: { Authorization: "Bearer not-a-real-token" },
        });

        const response = await forged.get("/api/css-applications");

        expect([401, 403]).toContain(response.status());

        await forged.dispose();
    });

    test("FAM refuses to let somebody alter their own access", async ({
        page,
        request,
    }) => {
        // The guard that stops an administrator dropping their own tier
        // mid-session, leaving the screen disagreeing with their token until
        // they sign in again.
        await gotoProtected(page, "/my-permissions");

        const self = await request.get("/api/auth/self");
        test.skip(!self.ok(), "could not read the signed-in user");

        const me = await self.json();
        const guid = me?.user_guid ?? me?.userGuid;
        test.skip(!guid, "no GUID on /auth/self to attempt a self-grant with");

        const apps = await request.get("/api/css-applications");
        test.skip(!apps.ok(), "could not list applications");
        const [app] = await apps.json();
        test.skip(!app, "this account administers no application");

        const response = await request.post(
            `/api/css-applications/${app.integration_id}/${app.environment}/application-admins`,
            { data: { user_guid: guid, user_type: "IDIR" } }
        );

        expect(
            response.status(),
            "self-appointment should be refused"
        ).toBeGreaterThanOrEqual(400);
    });

    test("role management on FAM's own integration is refused", async ({
        request,
    }) => {
        // The picker hides FAM, but the endpoint takes an integration id from
        // the caller - so hiding it only shapes what is offered. Deleting
        // APP_ADMIN_<id>_PROD here would strip every administrator of that
        // application at once.
        const apps = await request.get("/api/css-applications");
        test.skip(!apps.ok(), "could not list applications");

        const fam = (await apps.json()).find(
            (a: { fam_application?: boolean }) => a.fam_application
        );
        test.skip(!fam, "this account cannot see FAM's own integration");

        const response = await request.post(
            `/api/css-applications/${fam.integration_id}/${fam.environment}/roles`,
            {
                data: {
                    role_code: "E2E_SHOULD_NOT_EXIST",
                    role_name: "E2E should not exist",
                    role_type_district: false,
                    role_type_client: false,
                },
            }
        );

        expect(response.status()).toBeGreaterThanOrEqual(400);
        expect(await response.text()).toMatch(/not managed here|invalid/i);
    });

    test("FAM's own user list excludes other applications' administrators", async ({
        request,
    }) => {
        const apps = await request.get("/api/css-applications");
        test.skip(!apps.ok(), "could not list applications");

        const fam = (await apps.json()).find(
            (a: { fam_application?: boolean }) => a.fam_application
        );
        test.skip(!fam, "this account cannot see FAM's own integration");

        const rows = await request.get(
            `/api/css-applications/${fam.integration_id}/${fam.environment}/user-role-assignments`
        );
        test.skip(!rows.ok(), "could not read FAM's assignments");

        const roleNames: string[] = (await rows.json()).map(
            (r: { role_name: string }) => r.role_name
        );

        // Those people administer something else and have their own tabs for it.
        expect(roleNames.filter((n) => n.startsWith("APP_ADMIN_"))).toEqual([]);
        expect(roleNames.filter((n) => n.startsWith("DELEGATED_ADMIN_"))).toEqual([]);
    });
});
