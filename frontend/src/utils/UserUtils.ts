import { PLACE_HOLDER } from "@/constants/constants";

/**
 * Formats a user's full name and ID (userName) into a readable string.
 * If both first and last names are missing, it returns the ID only if present.
 * If either the first or last name is missing, it formats the remaining name with the ID if available.
 * If all parameters are missing, it returns "Unknown User".
 *
 * @param {string | null} [userName] - The optional unique identifier for the user. Can be null.
 * @param {string | null} [firstName] - The optional first name of the user. Can be null.
 * @param {string | null} [lastName] - The optional last name of the user. Can be null.
 * @returns {string} - The formatted string in the format: "FirstName LastName (ID)",
 *                     or "LastName (ID)" if the first name is missing,
 *                     or "FirstName (ID)" if the last name is missing,
 *                     or just the "ID" if both names are missing but the ID is available,
 *                     or "Unknown User" if all parameters are missing.
 */
export const formatUserNameAndId = (
    userName?: string | null,
    firstName?: string | null,
    lastName?: string | null
): string => {
    if (!firstName && !lastName) {
        return userName ?? "";
    }

    if (!firstName) {
        return lastName + (userName ? ` (${userName})` : "");
    }

    if (!lastName) {
        return firstName + (userName ? ` (${userName})` : "");
    }

    return `${firstName} ${lastName}${userName ? ` (${userName})` : ""}`;
};

/**
 * A person's name, without their username repeated inside it.
 *
 * <b>Why the username is a parameter.</b> CSS reports a Business BCeID user as
 * first and last name, but Keycloak's BCeID federation does not fill those two
 * fields with a forename and a surname - the username ends up inside them. So
 * joining the pair produced "Marco Villeneuve MVilleneuve3" in every table with
 * a Full Name column, with the username shown twice on the same row.
 *
 * Only a *trailing* token is dropped, and only when it matches this row's own
 * username exactly. That is narrow on purpose: stripping anything that merely
 * looks like a username would eat real surnames - "van der Berg", anything
 * hyphenated or two-word - and a name that is wrong in a new way is worse than
 * one that is wrong in the way somebody already reported.
 *
 * The username is never removed when it is all that is left: a row CSS knows
 * only by username still shows it, rather than emptying the cell to hide a
 * duplication that is no longer there.
 *
 * Returns "" rather than a placeholder, so the caller decides how an unnamed row
 * reads - both tables already write `|| PLACE_HOLDER`.
 */
export const formatFullName = (
    firstName?: string | null,
    lastName?: string | null,
    userName?: string | null
): string => {
    const parts = [firstName, lastName]
        .filter(Boolean)
        .join(" ")
        .split(/\s+/)
        .filter(Boolean);

    const trailing = parts[parts.length - 1]?.toLowerCase();
    const own = userName?.trim().toLowerCase();

    if (own && parts.length > 1 && trailing === own) {
        parts.pop();
    }

    return parts.join(" ");
};

/**
 * How a directory reads on screen.
 *
 * <p>FAM holds three spellings of the same two things: the API's enum values
 * ({@code BCEID_BUS}), the domain the backend derives from a CSS username
 * ({@code BCEID}), and the label the profile menu shows ("Business BCeID").
 * The first two are codes and were being rendered raw in tables people read.
 *
 * <p>Anything unrecognised is returned untouched rather than relabelled. A value
 * this does not know is worth seeing as it is - inventing a friendly name for it
 * would hide that something upstream changed.
 */
export const domainLabel = (domain?: string | null): string => {
    switch (domain?.trim().toUpperCase()) {
        case "IDIR":
            return "IDIR";
        case "BCEID":
        case "BCEID_BUS":
            return "Business BCeID";
        default:
            return domain ?? "";
    }
};
