import path from "node:path";
import { expect, test, type Locator, type Page } from "@playwright/test";
import { dangerButton, openDialog, toast } from "./carbon";
import { permissionRow, revokePermission } from "./helpers/permissions";
import { gotoProtected, TARGET_USER, uniqueSuffix } from "./utils";

/**
 * The screenshots in the how-to guides, captured from the running app.
 *
 * <p>Not a test - it asserts almost nothing and proves nothing about FAM. It is
 * here because this is where the machinery already is: a signed-in session, the
 * helpers that drive a grant, and the cleanup that removes what it created.
 *
 * <p><b>Why not by hand.</b> The guides went stale within a year of being
 * written, and re-shooting them meant somebody walking every flow again in a
 * screen recorder. It also meant whatever was in the environment that day
 * appeared in the pictures, which in a directory search is real people's names
 * and addresses. This walks the flows with test data and writes files a build
 * can pick up.
 *
 * <p>Run it on its own project, so an ordinary end-to-end run does not spend
 * minutes taking pictures:
 *
 * <pre>
 *   npm run e2e:login       # once, to store a session
 *   npm run guides:capture
 *   npm run guides:build
 * </pre>
 *
 * <p>Names say what the picture shows, not what order it was taken in, so the
 * guides can be reordered without renaming anything.
 */

/** Where the guides expect to find them. */
const SHOTS = path.resolve(
    import.meta.dirname,
    "..",
    "..",
    "docs",
    "guides",
    "screenshots"
);

/**
 * One size for every picture, so the guides do not mix widths.
 *
 * <p>1440x900 is a common laptop, wide enough that the permissions table shows
 * its columns without a horizontal scrollbar.
 *
 * <p>At 1x. A 1440px-wide image printed across a 6.5in page is about 220 DPI,
 * which is past what a laser printer resolves, and these are committed: at 2x
 * each shot was 3.7MB, so the set would have added forty megabytes to the
 * repository to look no better on paper.
 */
const VIEWPORT = { width: 1440, height: 900 };

test.use({ viewport: VIEWPORT });

/**
 * Chooses an application through the picker, and says which it chose.
 *
 * <p>Through the UI rather than from {@code /api/css-applications}, which the
 * rest of the suite asks: that call is made by Playwright's request context,
 * which carries cookies and no bearer token, so against a locally served SPA it
 * answers 401. The picker is the thing being photographed anyway.
 *
 * <p>FAM's own entry is passed over - it has no users tab to photograph - and
 * E2E_APP_NAME pins a particular application when the account administers more
 * than one.
 */
const chooseApplication = async (page: Page): Promise<string> => {
    const picker = page.getByRole("combobox", { name: /application/i });
    await picker.click();

    const pinned = process.env.E2E_APP_NAME;
    const options = page.getByRole("option");
    await expect(options.first()).toBeVisible({ timeout: 30_000 });

    const labels = await options.allInnerTexts();
    const admissible = labels
        .map((label, at) => ({ label, at }))
        .filter(({ label }) =>
            pinned
                ? label.includes(pinned)
                : !/forests access management/i.test(label)
        );

    /*
        A Test application by preference.

        Production is somebody's live access and does not belong in a
        screenshot. Development is the other way round - it is the environment
        most likely to be half-configured, and on a developer's machine the DEV
        directory lookup is usually not set up at all, which stops the grant
        flow at the search box. Test is real enough to photograph and safe to
        grant in.
    */
    const chosen =
        admissible.find(({ label }) => /\btest\b/i.test(label)) ?? admissible[0];
    const index = chosen?.at ?? -1;
    if (index < 0) {
        throw new Error(
            `no application to photograph. The picker offered: ${labels.join("; ") || "nothing"}`
        );
    }

    // By position rather than by text: an option reads as its name and its
    // environment pill on two lines, and matching that as one string finds
    // nothing.
    await options.nth(index).click();
    /*
        The name and its environment, on one line.

        Not the name alone: the same application appears once per environment,
        and a name-only string handed back to selectApplication matches the
        first of them - so the role created for these pictures was made in
        Development while the pictures were taken of Test, and never appeared
        on the grant form.

        Spaces rather than the newline the pill renders as: Playwright's hasText
        normalises the element's whitespace but not the string it is given.
    */
    const wanted = labels[index].replace(/\s+/g, " ").trim();
    await expect(page.locator(".fam-table").first()).toBeVisible({
        timeout: 60_000,
    });
    await tableSettled(page);
    return wanted;
};

/**
 * Makes sure the page is signed in, re-entering through Keycloak if it is not.
 *
 * <p>The saved storage state is not enough on its own. FAM keeps its tokens in
 * {@code sessionStorage} so they do not outlive a closed tab - see
 * services/keycloak - and Playwright's {@code storageState} carries cookies and
 * {@code localStorage} only. A fresh context therefore starts signed out however
 * recently somebody logged in.
 *
 * <p>What the state does carry is the Keycloak and Azure session cookies, so
 * pressing the sign-in button walks straight back through the identity provider
 * without a password prompt. That is all this does.
 */
const ensureSignedIn = async (page: Page) => {
    await page.goto("/");
    const signIn = page.locator("#login-idir-button");
    if (!(await signIn.isVisible().catch(() => false))) {
        return;
    }
    await signIn.click();
    await page.waitForURL(
        (url) =>
            url.pathname.startsWith("/manage-permissions") ||
            url.pathname.startsWith("/my-permissions") ||
            url.pathname.startsWith("/no-access"),
        { timeout: 120_000 }
    );
    await expect(page.locator("#protected-layout-container")).toBeVisible({
        timeout: 30_000,
    });
};

/**
 * Waits for a table to hold data rather than a skeleton.
 *
 * <p>Carbon's DataTableSkeleton renders the table's shape while the query is in
 * flight, and it carries the same wrapper the real table does - so "the table is
 * visible" is true a second before there is anything in it. The first capture
 * run photographed five rows of grey placeholder bars.
 */
const tableSettled = async (page: Page) => {
    // Counted to zero, not asserted hidden: `toBeHidden` is satisfied by an
    // element that does not exist, so it passes in the moment before the
    // skeleton is rendered and photographs the placeholder that follows.
    await expect(page.locator(".cds--skeleton")).toHaveCount(0, {
        timeout: 60_000,
    });
};

/**
 * Replaces the people in any table on screen with invented ones.
 *
 * <p><b>The reason this exists.</b> The sandbox is full of real directory
 * accounts - the first capture run produced a picture of two colleagues' names
 * and work email addresses, destined for a PDF that gets emailed around and
 * posted on a wiki. No screenshot in these guides should carry somebody's
 * identity, and the least fragile way to guarantee that is to overwrite it in
 * the page before the shutter opens rather than to rely on the environment
 * holding only test data.
 *
 * <p>Columns are found by their headings, so this follows a table that gains or
 * reorders columns. Everything else - roles, scopes, dates - is left exactly as
 * it is: those are what the guide is about.
 *
 * <p>The same row always becomes the same invented person, so a reader following
 * a flow across several pictures sees one consistent story.
 */
/**
 * The invented people the pictures show.
 *
 * <p>Row order decides who is who, so the same row reads as the same person
 * across a flow.
 */
const PEOPLE = [
    { user: "JSMITH", first: "Jane", last: "Smith" },
    { user: "BJONES", first: "Bob", last: "Jones" },
    { user: "APATEL", first: "Asha", last: "Patel" },
    { user: "MTREMBLAY", first: "Marc", last: "Tremblay" },
    { user: "LCHEN", first: "Lin", last: "Chen" },
    { user: "DOKAFOR", first: "Dayo", last: "Okafor" },
    { user: "RSINGH", first: "Ravi", last: "Singh" },
    { user: "KMORRIS", first: "Kim", last: "Morris" },
];

type Substitution = { find: string; replace: string };

/**
 * Real strings that must never reach a picture, and what to show instead.
 *
 * <p>Filled in at run time from the directory - see rememberRealIdentity. A
 * table can be rewritten column by column, but a toast says "granted to
 * <full name> (<username>)" and a confirmation dialog names the person in
 * prose; those need the literal strings.
 */
const substitutions: Substitution[] = [];

const remember = (find: string | null | undefined, replace: string) => {
    const value = find?.trim();
    if (!value || substitutions.some((s) => s.find === value)) {
        return;
    }
    substitutions.push({ find: value, replace });
};

/** Everything the directory knows about the person being granted to. */
const rememberRealIdentity = (identity: {
    userId?: string;
    firstName?: string;
    lastName?: string;
    email?: string;
}) => {
    const alias = PEOPLE[0];
    // Longest first, so a full name is replaced before its first name is.
    remember(
        [identity.firstName, identity.lastName].filter(Boolean).join(" "),
        `${alias.first} ${alias.last}`
    );
    remember(
        [identity.lastName, identity.firstName].filter(Boolean).join(", "),
        `${alias.last}, ${alias.first}`
    );
    remember(identity.email, `${alias.first.toLowerCase()}.${alias.last.toLowerCase()}@gov.bc.ca`);
    remember(identity.userId, alias.user);
    remember(identity.firstName, alias.first);
    remember(identity.lastName, alias.last);
    substitutions.sort((a, b) => b.find.length - a.find.length);
};

/**
 * Replaces the people on screen with invented ones.
 *
 * <p><b>The reason this exists.</b> The sandbox is full of real directory
 * accounts - the first capture run produced a picture of two colleagues' names
 * and work email addresses, destined for a PDF that gets emailed around. No
 * screenshot in these guides should carry somebody's identity, and the least
 * fragile way to guarantee that is to overwrite it in the page before the
 * shutter opens rather than to rely on the environment holding only test data.
 *
 * <p>Two passes. Tables are rewritten by column, found by their headings, so a
 * directory listing of strangers becomes a listing of invented people. Then the
 * literal strings belonging to the one person being granted to are replaced
 * wherever else they appear - in a toast, in a dialog's prose.
 *
 * <p>Roles, scopes and dates are left exactly as they are: those are what the
 * guide is about.
 */
const anonymise = async (page: Page) => {
    await page.evaluate(
        ({ people, literals }) => {
            const personFor = (index: number) => people[index % people.length];
            const email = (p: { first: string; last: string }) =>
                `${p.first.toLowerCase()}.${p.last.toLowerCase()}@gov.bc.ca`;

            const COLUMNS: Record<string, (p: (typeof people)[number]) => string> = {
                "user name": (p) => p.user,
                username: (p) => p.user,
                "full name": (p) => `${p.first} ${p.last}`,
                "first name": (p) => p.first,
                "last name": (p) => p.last,
                email: (p) => email(p),
                business: () => "Timber Co",
            };

            /**
             * Which column a heading is, if it is one worth replacing.
             *
             * <p>Matched on containment. A sortable Carbon header reads "Click
             * to sort rows by User Name header in ascending order" - the
             * instruction comes first and the heading is buried in the middle of
             * it, so neither equality nor a prefix matches. Longest key first,
             * so "user name" is not beaten to it by "name".
             */
            const columnKey = (heading: string) => {
                const text = heading.replace(/\s+/g, " ").trim().toLowerCase();
                return Object.keys(COLUMNS)
                    .sort((a, b) => b.length - a.length)
                    .find((key) => text.includes(key));
            };

            /**
             * The text node in a cell that holds its value.
             *
             * <p>The longest one that is not inside a pill. A username cell may
             * also carry the "New" tag, and replacing the cell wholesale - or
             * its first text node - deleted that tag from the very picture the
             * guide uses to point it out.
             */
            const valueNode = (cell: Element) => {
                const walker = document.createTreeWalker(cell, NodeFilter.SHOW_TEXT);
                let best: Text | null = null;
                let node = walker.nextNode() as Text | null;
                while (node) {
                    const inTag = (node.parentElement as HTMLElement | null)?.closest(
                        ".cds--tag"
                    );
                    const text = node.textContent?.trim() ?? "";
                    if (!inTag && text.length > (best?.textContent?.trim().length ?? 0)) {
                        best = node;
                    }
                    node = walker.nextNode() as Text | null;
                }
                return best;
            };

            for (const table of Array.from(document.querySelectorAll("table"))) {
                const headings = Array.from(table.querySelectorAll("thead th")).map(
                    (cell) => columnKey(cell.textContent ?? "")
                );
                Array.from(table.querySelectorAll("tbody tr")).forEach((row, rowIndex) => {
                    const person = personFor(rowIndex);
                    Array.from(row.querySelectorAll("td")).forEach((cell, index) => {
                        const heading = headings[index];
                        const replace = heading ? COLUMNS[heading] : undefined;
                        if (!replace) {
                            return;
                        }
                        const node = valueNode(cell);
                        if (node) {
                            node.textContent = replace(person);
                        }
                    });
                });
            }

            if (literals.length > 0) {
                /*
                    Form fields first. A value is an attribute, not a text node,
                    so the walk below never sees it - and the grant form keeps
                    the username that was searched for sitting in its box, in
                    full view of the picture being taken of the rest of the form.
                */
                for (const field of Array.from(
                    document.querySelectorAll("input, textarea")
                )) {
                    const element = field as HTMLInputElement | HTMLTextAreaElement;
                    for (const { find, replace } of literals) {
                        if (element.value?.toLowerCase().includes(find.toLowerCase())) {
                            element.value = element.value.replace(
                                new RegExp(
                                    find.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"),
                                    "gi"
                                ),
                                replace
                            );
                        }
                    }
                }

                const walker = document.createTreeWalker(
                    document.body,
                    NodeFilter.SHOW_TEXT
                );
                let node = walker.nextNode();
                while (node) {
                    let text = node.textContent ?? "";
                    for (const { find, replace } of literals) {
                        if (text.toLowerCase().includes(find.toLowerCase())) {
                            text = text.replace(
                                new RegExp(
                                    find.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"),
                                    "gi"
                                ),
                                replace
                            );
                        }
                    }
                    if (text !== node.textContent) {
                        node.textContent = text;
                    }
                    node = walker.nextNode();
                }
            }
        },
        { people: PEOPLE, literals: substitutions }
    );
};

/*
    Creating and deleting the role the grant pictures are taken against.

    Written here rather than reusing helpers/roles, which identifies an
    application by a string handed to the picker's text filter. That cannot name
    one environment of an application: the option renders the name and the
    environment pill on separate lines, and Playwright's hasText does not match
    across the break - so the string picked whichever environment came first and
    the role was created somewhere other than where the pictures were taken.
    These go through the same index-based chooser the captures use.
*/
const createGuideRole = async (page: Page, code: string, name: string) => {
    await gotoProtected(page, "/manage-roles");
    await chooseApplication(page);
    await page.locator("#roleCode").fill(code);
    await page.locator("#roleName").fill(name);
    await page
        .locator("#description")
        .fill("Used to illustrate the how-to guides.");
    await page.getByRole("button", { name: "Create role", exact: true }).click();
    await expect(
        page.locator("tr").filter({ hasText: code }).first()
    ).toBeVisible({ timeout: 60_000 });
};

/**
 * Ticks one role in the grant form's list.
 *
 * <p>By its label rather than the input. Carbon hides the checkbox itself and
 * draws the box as part of the label, so Playwright's actionability check waits
 * for an element that will never receive a pointer event - the capture run hung
 * here for five minutes and was killed mid-flow, leaving a role behind.
 */
const tickGuideRole = async (page: Page, roleName: string) => {
    const table = page.locator(".role-multi-select-table");
    await expect(table, "the role list needs a user chosen first").toBeVisible({
        timeout: 30_000,
    });
    const checkbox = table.getByLabel(roleName, { exact: true });
    await expect(
        checkbox,
        `no role named "${roleName}" is offered in this application`
    ).toBeAttached({ timeout: 30_000 });

    const id = await checkbox.getAttribute("id");
    await table.locator(`label[for="${id}"]`).click();
    await expect(checkbox).toBeChecked();
};

/** Idempotent, so a half-finished capture run still tidies up after itself. */
const deleteGuideRole = async (page: Page, code: string) => {
    await gotoProtected(page, "/manage-roles");
    await chooseApplication(page);
    // Real rows, not merely the absence of skeletons: they are absent before
    // they are rendered too, and a cleanup that looks too early finds nothing
    // and leaves the role behind.
    await expect(page.locator("tbody tr").first()).toBeVisible({
        timeout: 60_000,
    });
    const row = page.locator("tr").filter({ hasText: code }).first();
    if (!(await row.isVisible().catch(() => false))) {
        return;
    }
    // "Remove <code>", not "Delete <code>": the row's control is a RemoveButton
    // whose accessible name says what it removes. The dialog it opens is the one
    // that says Delete.
    await row.getByRole("button", { name: `Remove ${code}` }).click();
    await openDialog(page);
    await dangerButton(page, "Delete").click();
    await expect(row).toBeHidden({ timeout: 60_000 });
};

const shoot = async (
    target: Page | Locator,
    name: string,
    options: { fullPage?: boolean } = {}
) => {
    // Every picture, without exception: forgetting it on one is how a name gets
    // published.
    const page = "page" in target ? (target.page() as Page) : (target as Page);
    await anonymise(page);
    await target.screenshot({
        path: path.join(SHOTS, `${name}.png`),
        // A form is explained step by step, and the steps below the fold are
        // the ones people get stuck on - the notify checkbox and the button
        // that submits. A table is cropped to the viewport as usual: a hundred
        // rows of it prove nothing the first ten do not.
        ...(options.fullPage ? { fullPage: true } : {}),
    });
};

/** The whole dialog, cropped to itself rather than the dimmed page behind it. */
const shootDialog = async (page: Page, name: string) => {
    const dialog = await openDialog(page);
    await shoot(dialog, name);
    return dialog;
};

test.describe("how-to guide screenshots", () => {
    // What the grant screenshots are taken against: one role, created here and
    // removed at the end, so the pictures never show a real person's access.
    const suffix = uniqueSuffix();
    const roleCode = `E2E_GUIDE_${suffix}`;
    const roleName = `Guide role ${suffix}`;

    /*
        Not serial. Each picture stands on its own, and the one flow that has
        steps - the grant - is a single test. Running them in series meant a
        directory outage during the grant flow skipped the pictures after it,
        which have nothing to do with the directory.
    */

    test.beforeEach(async ({ page }, testInfo) => {
        // The signed-out picture is the one test that must not be signed in.
        if (testInfo.title === "the sign-in screen") {
            return;
        }
        await ensureSignedIn(page);
    });

    test("the sign-in screen", async ({ browser }) => {
        // Its own context: the point of the picture is the signed-out page, and
        // every other test here arrives already signed in.
        const context = await browser.newContext({
            storageState: undefined,
            viewport: VIEWPORT,
        });
        const page = await context.newPage();
        await page.goto("/");
        await expect(page.locator("#login-idir-button")).toBeVisible();
        await shoot(page, "01-sign-in");
        await context.close();
    });

    test("the application picker, open", async ({ page }) => {
        await gotoProtected(page, "/manage-permissions");
        await page.getByRole("combobox", { name: /application/i }).click();
        await expect(page.getByRole("option").first()).toBeVisible();
        await shoot(page, "03-application-picker");
    });

    test("the tabs, and the users tab", async ({ page }) => {
        await gotoProtected(page, "/manage-permissions");
        await chooseApplication(page);
        await shoot(page, "12-tabs");

        const users = page.getByRole("tab").filter({ hasText: /users/i });
        if (await users.isVisible().catch(() => false)) {
            await users.click();
            await tableSettled(page);
        }
        await shoot(page, "04-users-tab");
    });

    test("the grant flow", async ({ page }) => {
        /*
            Longer than the suite's 90 seconds. This one test creates a role,
            searches the directory, grants, photographs five screens and then
            puts everything back; the ordinary limit expired mid-flow and the
            cleanup never ran, which leaves a role behind that CSS will never
            collect.
        */
        test.setTimeout(300_000);
        test.skip(
            !TARGET_USER,
            "E2E_TARGET_IDIR is not set - the grant pictures need somebody to grant to"
        );

        await createGuideRole(page, roleCode, roleName);

        /*
            Who the target actually is, so every picture can stop saying so.

            Asked of the same directory the screen asks, rather than guessed
            from the username: the grant toast and the removal dialog name the
            person in prose - "granted to <full name> (<username>)" - and a
            substitution that only knew the username left the name in place.
        */
        const identity = await page.evaluate(async (userId: string) => {
            const key = Object.keys(sessionStorage).find((k) =>
                k.startsWith("oidc.")
            );
            const token = key
                ? JSON.parse(sessionStorage.getItem(key) as string)?.access_token
                : null;
            if (!token) {
                return null;
            }
            const res = await fetch(
                `/api/identity-lookup/idir?userId=${encodeURIComponent(userId)}&environment=test`,
                { headers: { Authorization: `Bearer ${token}` } }
            );
            return res.ok ? await res.json() : null;
        }, TARGET_USER);

        if (identity?.found) {
            rememberRealIdentity(identity);
        } else {
            // Better to stop than to photograph somebody's name.
            throw new Error(
                `could not resolve ${TARGET_USER} in the directory, so the grant ` +
                    `pictures cannot be anonymised`
            );
        }

        try {
            await gotoProtected(page, "/manage-permissions");
            await chooseApplication(page);
            await page.getByRole("button", { name: /add permission/i }).click();

            /*
                The search field, before it has been used.

                Typed with an invented username rather than the one actually
                being granted to: the picture is of the field, and a real IDIR
                username printed in a guide is somebody's identity as surely as
                their name is. The real one goes in straight afterwards, once the
                shutter has closed.
            */
            const search = page.locator("#user-search-input");
            await search.fill("JSMITH");
            await shoot(page, "05-user-search");

            await search.fill(TARGET_USER);
            await page.getByRole("button", { name: "Search users" }).click();
            await shootDialog(page, "06-user-search-results");

            // Finishes the selection the picture above was taken mid-way
            // through, then fills the form the next picture is of.
            const dialog = page.getByRole("dialog");
            const confirm = dialog.getByRole("button", { name: "Confirm" });
            if (await confirm.isDisabled()) {
                await dialog
                    .getByLabel(`Select ${TARGET_USER.toUpperCase()}`)
                    .check();
            }
            await confirm.click();

            await tickGuideRole(page, roleName);
            await shoot(page, "07-choose-roles", { fullPage: true });

            await page.getByRole("button", { name: "Grant permission" }).click();
            await expect(toast(page)).toContainText("Permission granted");
            await shoot(page, "08-grant-confirmation");

            /*
                The table with the grant still marked New, which is what the
                guides describe. Not reloaded first: the New marks come from the
                grant summary held in memory, and a reload forgets them.

                Found by the role rather than by the person: the picture above
                was taken through anonymise, so the row no longer says who it
                was granted to - only the role name survives untouched.
            */
            const grantedRow = page.locator("tr").filter({ hasText: roleName });
            await expect(grantedRow.first()).toBeVisible({ timeout: 60_000 });
            await tableSettled(page);
            await shoot(page, "09-permissions-table");

            /*
                The removal confirmation, taken on the way to cleaning up.

                Reloaded first: the picture above was taken through anonymise,
                which rewrote the table's usernames in the DOM - so the row that
                has to be found again by the name it was granted to no longer
                says it, and looking for it hung until the test timed out.
            */
            await page.reload();
            // The picker is React state, not the URL, so a reload lands on
            // Manage permissions with nothing chosen and an empty table.
            await chooseApplication(page);
            await tableSettled(page);
            await page
                .locator("tr")
                .filter({ hasText: roleName })
                .first()
                .getByRole("button", { name: /^Remove / })
                .click();
            await shootDialog(page, "10-remove-permission");
            await page.keyboard.press("Escape");
        } finally {
            // Same reason as above: cleanup needs the real names back, and the
            // application chosen again after the reload.
            await page.reload().catch(() => {});
            await chooseApplication(page).catch(() => {});
            await revokePermission(page, TARGET_USER, roleName).catch(() => {});
            await deleteGuideRole(page, roleCode);
        }
    });

    test("the delegated and application admin forms", async ({ page }) => {
        // The empty forms, which say what they ask for without anybody being
        // appointed. Nothing is submitted, so there is nothing to clean up.
        //
        // Reached the way somebody reaches them - through the tabs - because the
        // screens need the application on the query string and the tab is what
        // puts it there.
        const openTabAndForm = async (
            tab: RegExp,
            button: RegExp,
            route: RegExp,
            name: string
        ) => {
            await gotoProtected(page, "/manage-permissions");
            await chooseApplication(page);
            const target = page.getByRole("tab").filter({ hasText: tab });
            if (!(await target.isVisible().catch(() => false))) {
                test.skip(true, `this account has no ${tab.source} tab`);
            }
            await target.click();
            await tableSettled(page);
            await page.getByRole("button", { name: button }).first().click();
            // The button navigates, and a screenshot taken in the meantime is a
            // picture of the screen being left. The first capture run produced
            // three of those.
            await page.waitForURL(route, { timeout: 60_000 });
            // The form's own lists - roles, and the user search - load too.
            await tableSettled(page);
            await shoot(page, name);
        };

        await openTabAndForm(
            /delegated admins/i,
            /add delegated admin/i,
            /add-delegated-admin/,
            "13-add-delegated-admin"
        );
        await openTabAndForm(
            /application admins/i,
            /add application admin/i,
            /add-application-admin/,
            "14-add-application-admin"
        );

        await gotoProtected(page, "/manage-permissions");
        await chooseApplication(page);
        await page.getByRole("button", { name: /bulk|upload/i }).first().click();
        await page.waitForURL(/bulk-upload/, { timeout: 60_000 });
        await tableSettled(page);
        await shoot(page, "15-bulk-grant");
    });

    test("my permissions", async ({ page }) => {
        await gotoProtected(page, "/my-permissions");
        // The screen fills in two passes and the second is a fan-out across
        // every application, so this waits for the slower half.
        await tableSettled(page);
        await shoot(page, "11-my-permissions");
    });

    test("the terms of use", async ({ page }) => {
        test.skip(
            true,
            "Shown only to a Business BCeID delegated administrator who has not " +
                "accepted the current version. The stored session is IDIR, so this " +
                "one is captured by hand until there is a BCeID test account."
        );
        await gotoProtected(page, "/manage-permissions");
        await shoot(page, "02-terms-of-use");
    });
});
