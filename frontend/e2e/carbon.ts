import { expect, type Locator, type Page } from "@playwright/test";

/**
 * How to drive Carbon's components, in one place.
 *
 * The suite used to reach for PrimeVue's class names - `.p-select-overlay`,
 * `.p-chip`, `.p-toast-message`. Those are gone with the framework, and the
 * replacements are not a rename: Carbon renders its listbox inside the field
 * rather than teleporting it to the body, keeps modals mounted when closed, and
 * prefixes a danger button's accessible name. Each of those caught the suite out
 * once, so the workaround lives here rather than being rediscovered per spec.
 *
 * Roles and labels are preferred to class names throughout. A class is an
 * implementation detail of whichever component library is in fashion; a
 * `combobox` that a screen reader can find is the thing the screen actually
 * promises.
 */

/**
 * A Carbon danger button announces as "danger <label>".
 *
 * Carbon prepends visually-hidden text so the destructive action is obvious to
 * a screen reader. An exact-name match therefore never finds it, and the
 * failure reads as "the button is missing" rather than "the name has a prefix".
 */
export const dangerButton = (page: Page, label: string): Locator =>
    page.getByRole("button", { name: new RegExp(`${label}$`) });

/**
 * Chooses an item in a Carbon ComboBox.
 *
 * Unlike PrimeVue's Select the listbox is rendered inside the field's own
 * wrapper, not teleported to the body - so the option is found through the
 * combobox rather than at the document root.
 */
export const chooseFromComboBox = async (
    combobox: Locator,
    text: string,
    what = "option"
): Promise<void> => {
    await combobox.click();

    const option = combobox
        .page()
        .getByRole("option")
        .filter({ hasText: text })
        .first();

    await expect(
        option,
        `no ${what} matching "${text}" was offered`
    ).toBeVisible({ timeout: 30_000 });

    await option.click();
};

/**
 * Ticks a Carbon Checkbox by its label.
 *
 * The scope and role pickers hide their labels - the row already names what is
 * being ticked - so the accessible name is the only handle on them, and
 * `check()` needs the input rather than the visible box.
 */
export const tickCheckbox = async (
    scope: Locator | Page,
    label: string
): Promise<void> => {
    const checkbox = scope.getByLabel(label, { exact: true });
    await expect(checkbox).toBeAttached({ timeout: 30_000 });
    await checkbox.check();
};

/**
 * Whatever the app is currently saying, as one element.
 *
 * The stack rather than the toasts in it. More than one can be up at once now
 * that failures are toasts rather than banners, and `toContainText` against a
 * locator matching several of them fails Playwright's strict-mode check - so
 * asserting on the container is what lets "the app said X" stay a single
 * assertion however many other things it is also saying.
 *
 * Each toast inside still carries `role="status"`, which is what makes it
 * announced rather than merely present. Reach for {@link toastSaying} to act on
 * a particular one.
 */
export const toast = (page: Page): Locator => page.locator(".toast-stack");

/** One named toast, for closing it or asserting on it alone. */
export const toastSaying = (page: Page, text: string | RegExp): Locator =>
    page.getByRole("status").filter({ hasText: text });

/**
 * A Carbon Modal that is genuinely open.
 *
 * Carbon keeps the modal mounted and toggles `is-visible`, so waiting on
 * `role="dialog"` alone resolves against a closed one - the unit suite lost an
 * afternoon to exactly that, asserting against a dialog that had not opened yet.
 */
export const openDialog = async (page: Page): Promise<Locator> => {
    await expect(page.locator(".cds--modal.is-visible")).toBeVisible({
        timeout: 30_000,
    });
    return page.getByRole("dialog");
};

/** Carbon's Tag, which replaced the PrimeVue Chip on roles and scopes. */
export const chips = (scope: Locator): Locator => scope.locator(".cds--tag");
