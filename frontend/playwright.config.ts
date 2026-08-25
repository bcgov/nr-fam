import { defineConfig, devices } from "@playwright/test";

import { baseURL, STORAGE_STATE } from "./e2e/utils";

/**
 * Playwright E2E config, mirroring nr-fsp-new's structure.
 *
 * Auth flow:
 *   1. `npm run e2e:login` runs the `setup` project headed, parks at the IDIR
 *      login page, and saves cookies + localStorage to e2e/.auth/user.json once
 *      the sign-in completes.
 *   2. Every other project starts from that storageState, so each test boots
 *      already authenticated.
 *
 * Override the target with E2E_BASE_URL (e.g. http://localhost:3000 locally).
 */
export default defineConfig({
    // Generous for a single test against shared, slower TEST/preview infra. The
    // CSS fan-out reads are the slow part: listing assignments costs one
    // upstream request per role. Keeping the ceiling tight still bounds the
    // blast radius of a hang - with retries it fails legibly rather than
    // masquerading as "slow CI".
    timeout: 90_000,
    expect: { timeout: 20_000 },
    testDir: "./e2e",
    // Serial. The write specs create and delete real CSS roles and grants on a
    // shared sandbox integration; parallel workers would see each other's
    // artefacts in the same tables and race on the same role names.
    workers: 1,
    fullyParallel: false,
    forbidOnly: !!process.env.CI,
    retries: process.env.CI ? 2 : 0,
    reporter: [
        ["line"],
        ["list", { printSteps: true }],
        ["html", { open: "never" }],
    ],
    use: {
        baseURL,
        trace: "on-first-retry",
        screenshot: "only-on-failure",
        video: "retain-on-failure",
    },

    projects: [
        {
            name: "setup",
            testMatch: /auth\.setup\.ts/,
            use: { ...devices["Desktop Chrome"] },
        },
        {
            name: "chromium",
            use: {
                ...devices["Desktop Chrome"],
                storageState: STORAGE_STATE,
            },
            dependencies: ["setup"],
        },
        {
            name: "Google Chrome",
            use: {
                ...devices["Desktop Chrome"],
                channel: "chrome",
                storageState: STORAGE_STATE,
            },
            dependencies: ["setup"],
        },
        {
            name: "firefox",
            use: {
                ...devices["Desktop Firefox"],
                storageState: STORAGE_STATE,
            },
            dependencies: ["setup"],
        },
        {
            name: "safari",
            use: {
                ...devices["Desktop Safari"],
                storageState: STORAGE_STATE,
            },
            dependencies: ["setup"],
        },
        {
            name: "Microsoft Edge",
            use: {
                ...devices["Desktop Edge"],
                channel: "msedge",
                storageState: STORAGE_STATE,
            },
            dependencies: ["setup"],
        },
    ],
});
