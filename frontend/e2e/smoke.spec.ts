import { expect, test } from "@playwright/test";

/**
 * The target is up and is FAM.
 *
 * Runs before anything else is worth reading: if this fails, every other
 * failure in the run is downstream of it and says nothing of its own.
 */
test.describe("smoke", () => {
    test("serves the SPA", async ({ page }) => {
        const response = await page.goto("/");

        expect(response?.status(), "the frontend did not serve").toBeLessThan(400);
    });

    test("serves the backend through the /api proxy", async ({ request }) => {
        // The backend has no Route of its own; it is reached through Caddy. A
        // frontend that serves while /api 404s is the failure mode this catches,
        // and it looks like a broken app rather than a broken proxy.
        const response = await request.get("/api/actuator/health");

        expect(response.status()).toBe(200);
    });

    test("reports the database as migrated", async ({ request }) => {
        // FAM's own smoke endpoint: 200 when the database is reachable and
        // migrated, 417 when reachable but the baseline never ran.
        const response = await request.get("/api/smoke_test");

        expect(
            response.status(),
            response.status() === 417
                ? "database reachable but not migrated - the Flyway baseline did not run"
                : "smoke endpoint did not answer 200"
        ).toBe(200);
    });

    test("serves the OpenAPI document the client is generated from", async ({
        request,
    }) => {
        const response = await request.get("/api/v3/api-docs");

        expect(response.status()).toBe(200);
        const spec = await response.json();
        expect(spec.paths?.["/css-applications"]).toBeDefined();
    });
});
