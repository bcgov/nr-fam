import { test as base, type APIRequestContext } from "@playwright/test";

import { baseURL, STORAGE_STATE } from "./utils";

/** One row of `/api/css-applications`. */
export type ApplicationOption = {
    integration_id: number;
    environment: string;
    name: string;
    description: string;
    fam_application: boolean;
};

/**
 * The application the write specs create roles in and grant against.
 *
 * <b>Discovered, not configured.</b> `/api/css-applications` already returns
 * only what the signed-in account may administer, and flags which entry is FAM
 * itself - so the suite can work out what it is allowed to act on rather than
 * being told. In the lower environments every integration the CSS account can
 * see is a sandbox, so any of them will do.
 *
 * FAM's own integration is excluded because its roles are not application
 * roles: they are `FAM_ADMIN` plus the `APP_ADMIN_<id>_<ENV>` and
 * `DELEGATED_ADMIN_...` roles FAM generates as administrators are appointed.
 * Creating one there would put a role in FAM's namespace that FAM never reads,
 * and the backend refuses it anyway.
 *
 * E2E_APP_NAME pins a specific one. Only needed when the account can reach
 * something that is *not* a sandbox - a production account, or a shared
 * environment where one integration is real. Unset is the normal case.
 */
export const resolveSandboxApp = async (
    request: APIRequestContext
): Promise<ApplicationOption | null> => {
    const response = await request.get("/api/css-applications");

    // A refusal is not "administers nothing" - it means the saved session is
    // not being sent, and every write spec would skip on it. Skipping the whole
    // suite silently is the worst outcome available here: the job goes green
    // having tested nothing. So this fails loudly instead.
    if (response.status() === 401 || response.status() === 403) {
        throw new Error(
            `/api/css-applications answered ${response.status()} while resolving the ` +
                `target application. The saved session is not reaching the API - ` +
                `re-run \`npm run e2e:login\`, or check E2E_IDIR_USER / ` +
                `E2E_IDIR_PASSWORD in CI.`
        );
    }
    if (!response.ok()) {
        return null;
    }

    const apps: ApplicationOption[] = await response.json();
    const administrable = apps.filter((app) => !app.fam_application);

    const pinned = process.env.E2E_APP_NAME;
    if (pinned) {
        return (
            administrable.find(
                (app) =>
                    app.description?.includes(pinned) || app.name?.includes(pinned)
            ) ?? null
        );
    }

    // Order is whatever CSS returned. It does not matter which one: every role
    // the suite makes is uniquely named and deleted again, so any application
    // the account administers exercises the same screens.
    return administrable[0] ?? null;
};

/**
 * `sandboxApp` resolved once per worker.
 *
 * Worker-scoped so the whole run agrees on one application - specs create a
 * role in one place and then look for it in another, and re-resolving per test
 * could land them on different integrations if CSS ever reordered its answer.
 */
export const test = base.extend<{}, { sandboxApp: ApplicationOption | null }>({
    sandboxApp: [
        async ({ browser }, use) => {
            // storageState and baseURL are applied by the `context` fixture, so
            // a context created by hand inherits neither. Without passing them
            // the request would go out unauthenticated to a relative URL, the
            // API would refuse it, and every write spec would skip - green, and
            // having tested nothing.
            const context = await browser.newContext({
                storageState: STORAGE_STATE,
                baseURL,
            });
            const app = await resolveSandboxApp(context.request);
            await context.close();
            await use(app);
        },
        { scope: "worker" },
    ],
});

export { expect } from "@playwright/test";
