import { Tag } from "@carbon/react";
import type { FC } from "react";
import "./application-option.css";

/**
 * What an environment is called in front of a person.
 *
 * The code is what CSS holds and what every role name carries; DEV, TEST and
 * PROD are not words, and the picker is the one place somebody chooses between
 * them rather than reads one back.
 */
const ENVIRONMENT_LABELS: Record<string, string> = {
    dev: "Development",
    test: "Test",
    prod: "Production",
};

/**
 * Carbon tag colours per environment.
 *
 * Carbon has no yellow tag, so all three are repainted in application-option.css
 * and these only decide which class the pill carries. Blue, yellow and green
 * read as the three rungs of a release: something being built, something being
 * checked, and the one that is live.
 */
const ENVIRONMENT_COLORS: Record<string, "blue" | "gray" | "green"> = {
    dev: "blue",
    test: "gray",
    prod: "green",
};

export const environmentLabel = (environment?: string | null): string => {
    const key = environment?.trim().toLowerCase() ?? "";
    // An environment CSS adds later still names itself, rather than vanishing.
    return ENVIRONMENT_LABELS[key] ?? environment?.trim().toUpperCase() ?? "";
};

/**
 * The application's name, without the environment the backend appends.
 *
 * `description` arrives as `FREP (DEV)` - see CssIntegrationService.getApplications -
 * and that suffix is exactly what the pill replaces, so the rest of it is the
 * name. Taken from the description rather than the option's own `name` because
 * the description is the label every other screen shows - see grantTarget - and
 * the picker should not start calling an application something different from
 * the page it leads to. Falls back to `name` for an option with no description,
 * and the role-management option has no `name` at all.
 */
export const applicationName = (option: {
    name?: string;
    description?: string;
    environment?: string;
}): string => {
    const description = option.description?.trim() ?? "";
    if (description) {
        return description.replace(/\s*\([^()]*\)\s*$/, "").trim() || description;
    }
    return option.name ?? "";
};

type Props = {
    /** The option, in either of the two shapes the pickers deal in. */
    option: { name?: string; description?: string; environment?: string };
};

/**
 * One row of an application picker: the application, then its environment as a
 * pill.
 *
 * Only the menu rows. Carbon puts plain text in the closed box - it is an input,
 * so it cannot hold a tag - and that still reads as `FREP (DEV)` from
 * `itemToString`, which is also what the type-ahead filter matches on. So typing
 * "dev" still finds the DEV entries.
 */
export const ApplicationOption: FC<Props> = ({ option }) => {
    const environment = option.environment?.trim().toLowerCase() ?? "";
    return (
        <span className="application-option">
            <span className="application-option__name">
                {applicationName(option)}
            </span>
            {environment ? (
                <Tag
                    className={`application-option__env application-option__env--${environment}`}
                    type={ENVIRONMENT_COLORS[environment] ?? "gray"}
                    size="sm"
                >
                    {environmentLabel(environment)}
                </Tag>
            ) : null}
        </span>
    );
};

export default ApplicationOption;
