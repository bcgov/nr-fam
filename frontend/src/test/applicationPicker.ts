import { screen } from "@testing-library/react";

/**
 * Finds an application in an open application picker.
 *
 * <p>The menu rows render the application's name beside an environment pill -
 * see components/ApplicationOption - so `FREP (DEV)` is on screen as "FREP" and
 * a "Development" tag, and no single text node says the whole thing. The tests
 * still name applications the way the rest of the app does, by the description
 * the backend composes, so this translates one into the other.
 */
const ENVIRONMENT_LABELS: Record<string, string> = {
    dev: "Development",
    test: "Test",
    prod: "Production",
};

/**
 * @param description the option as the backend composes it, e.g. `FREP (DEV)`
 */
export const findApplicationOption = (description: string) => {
    const match = /^(.*)\s*\(([^()]*)\)\s*$/.exec(description.trim());
    if (!match) {
        // No environment in the name: nothing to translate.
        return screen.findByText(description);
    }
    const [, name, environment] = match;
    const label =
        ENVIRONMENT_LABELS[environment.trim().toLowerCase()] ??
        environment.trim().toUpperCase();
    // The accessible name of a list-box option is its whole content, which is
    // the name and the pill's text with a space between them.
    return screen.findByRole("option", {
        name: `${name.trim()} ${label}`,
    });
};
