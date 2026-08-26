import path from "node:path";

import { expect, type Locator, type Page } from "@playwright/test";

import { chooseFromComboBox } from "./carbon";

/**
 * E2E_BASE_URL is set by `.github/workflows/reusable-tests.yml` at step level
 * (resolved from the PR slot or the test/prod target). For local hand-runs,
 * export it explicitly:
 *
 *   E2E_BASE_URL=http://localhost:3000 npm run e2e
 *
 * Failing fast here beats silently targeting a stale dev URL and reporting the
 * wrong environment's state as a pass.
 */
if (!process.env.E2E_BASE_URL) {
    throw new Error(
        "E2E_BASE_URL is not set. Export it before running Playwright " +
            "(e.g. `E2E_BASE_URL=http://localhost:3000 npm run e2e`)."
    );
}

export const baseURL = process.env.E2E_BASE_URL;

/** Path to the saved auth state produced by auth.setup.ts. */
export const STORAGE_STATE = path.join(import.meta.dirname, ".auth", "user.json");

/**
 * The user the grant specs act on.
 *
 * Deliberately somebody other than the account the suite signs in as: FAM
 * refuses to alter your own access, so a self-grant would fail with a message
 * about self-administration rather than exercising the flow.
 */
export const TARGET_USER = process.env.E2E_TARGET_IDIR ?? "";

/**
 * Unique-ish identifier suffix for test artefacts so concurrent runs and
 * leftover rows do not collide. Format: E2E<base36 timestamp><rand>.
 *
 * Upper case and alphanumeric only: it is used in CSS role codes, which FAM
 * restricts to letters, digits and underscores.
 */
export const uniqueSuffix = (): string => {
    const t = Date.now().toString(36);
    const r = Math.floor(Math.random() * 36 ** 3)
        .toString(36)
        .padStart(3, "0");
    return `E2E${t}${r}`.toUpperCase();
};

/**
 * Navigate to a route on the protected side of the SPA and wait until the
 * layout shell has rendered - that means AuthProvider has finished
 * bootstrapping and we are past the loading state.
 *
 * On timeout, dumps the current URL and a body excerpt so a failure says
 * whether the SPA stayed loading, bounced to /no-access, or crashed. Without
 * that, every auth problem looks like the same anonymous timeout.
 */
export const gotoProtected = async (page: Page, to: string): Promise<void> => {
    const consoleMessages: string[] = [];
    const pageErrors: string[] = [];
    const onConsole = (msg: { type(): string; text(): string }) => {
        consoleMessages.push(`[${msg.type()}] ${msg.text()}`);
    };
    const onPageError = (err: Error) => pageErrors.push(err.message);

    page.on("console", onConsole);
    page.on("pageerror", onPageError);

    try {
        await page.goto(to);
        await page.locator("#protected-layout-container").waitFor({ timeout: 60_000 });
        await page
            .getByTestId("bc-header__header")
            .waitFor({ timeout: 30_000 });
    } catch (err) {
        const url = page.url();
        const title = await page.title().catch(() => "(unavailable)");
        const bodyHtml = await page
            .evaluate(() => document.body?.innerHTML?.slice(0, 1500) ?? "(no body)")
            .catch(() => "(unavailable)");
        throw new Error(
            `gotoProtected("${to}") never reached the signed-in layout.\n` +
                `  Current URL : ${url}\n` +
                `  Page title  : ${title}\n` +
                `  body excerpt: ${bodyHtml}\n` +
                `  Console (${consoleMessages.length}): ${consoleMessages.slice(-15).join(" | ") || "(none)"}\n` +
                `  Page errors (${pageErrors.length}): ${pageErrors.join(" | ") || "(none)"}\n` +
                `  Original    : ${err instanceof Error ? err.message : String(err)}`
        );
    } finally {
        page.off("console", onConsole);
        page.off("pageerror", onPageError);
    }
};

/** The application picker, which is the only combobox on either screen. */
export const applicationPicker = (page: Page): Locator =>
    page.getByRole("combobox", { name: /application/i });

/**
 * Choose an application in the picker on Manage permissions or Manage roles.
 *
 * Found by role rather than by id: the two screens give their picker different
 * ids, and the old helper only knew Manage permissions' - so every Manage roles
 * spec was selecting nothing and then timing out on a table that never filled.
 */
export const selectApplication = async (
    page: Page,
    nameContains: string
): Promise<void> => {
    await chooseFromComboBox(
        applicationPicker(page),
        nameContains,
        "application - though /api/css-applications offered it, so the two disagree"
    );
};

/**
 * Whether the signed-in account may reach a screen at all.
 *
 * The suite is written to run as an administrator, but the same specs are
 * useful as a read-only smoke check. Rather than fail with a timeout on a
 * missing table, the stateful specs skip when the account cannot administer
 * anything - a skip says "not applicable", a timeout says "broken".
 */
export const hasAdminAccess = async (page: Page): Promise<boolean> => {
    await gotoProtected(page, "/manage-permissions");
    return applicationPicker(page).isVisible();
};
