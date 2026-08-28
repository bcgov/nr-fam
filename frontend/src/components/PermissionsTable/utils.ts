import {
    UserType,
    type CssUserRoleRevokeRequest,
    type CssUserRoleRowDto,
    type ScopeDto,
} from "fam-api";

/**
 * The assignment a row stands for, as the revoke endpoint names it.
 *
 * The row shows a base role and a scope because that is how it reads; CSS holds
 * one concrete role. Sending both halves lets the backend rebuild the name it
 * actually assigned - revoking the base role would remove nothing and report
 * success.
 */
export const toRevokeRequest = (
    row: CssUserRoleRowDto
): CssUserRoleRevokeRequest => ({
    // The GUID, not the displayed username: that is a user id once the
    // directory has named them, and <guid>@azureidir before.
    user_guid: row.user_guid ?? "",
    user_type: row.domain === "BCEID" ? UserType.BceidBus : UserType.Idir,
    role_name: row.role_name,
    // Every scope, one value each. A compound role revoked with only one of its
    // scopes names a role nobody holds, and the removal quietly does nothing.
    scopes: (row.scopes ?? []).map((scope) => ({
        type: scope.type,
        values: [scope.value],
    })),
});

/**
 * A row's scopes as readable text, e.g. `DCC, 00001012`.
 *
 * Prefers the resolved label - a district's or forest client's name - and falls
 * back to the raw value, which is what a row carries before enrichment or when
 * the upstream lookup is unavailable.
 *
 * Used for sorting, filtering and the CSV, where a list of chips has to collapse
 * to one comparable string.
 */
export const scopeText = (row: CssUserRoleRowDto): string =>
    (row.scopes ?? []).map((scope) => scope.label || scope.value).join(", ");

/**
 * How a role reads on a permission pill: its short name when it has one.
 *
 * The short name, not the long description - a sentence would not fit a pill,
 * and the table is about who holds what rather than what each role means.
 *
 * Falls back to the code, which is what a role added directly in the CSS console
 * will always have. An empty label would be worse than a technical one.
 */
export const roleLabel = (row: CssUserRoleRowDto): string =>
    row.role_display_name || row.role_name;
import type { AppPermissionGrantSummary } from "@/pages/AddAppPermission/grantUtils";
import { wasGranted } from "@/pages/ManagePermissions/utils";
import { PLACE_HOLDER } from "@/constants/constants";

/**
 * {@code <guid>@azureidir -> GUID}, or null for a username that is not in that
 * form - which is every user CSS could name.
 */
const guidFromUsername = (username: string | undefined | null): string | null => {
    const at = username?.indexOf("@") ?? -1;
    return at > 0 ? username!.slice(0, at).toUpperCase() : null;
};

/**
 * What identifies the rows a grant just created.
 *
 * Keyed by user <em>and</em> role. Legacy marked the specific assignment row by
 * its primary key; a CSS row has no such id, so the pair is the closest thing to
 * it - and it is what stops a user's existing, unrelated permissions lighting up
 * as new alongside the one just added.
 *
 * Both the GUID and the user id are recorded because the table shows whichever
 * CSS knew: a user who has never signed in appears as {@code <guid>@azureidir}
 * until the directory fills them in.
 */
export const newlyGrantedKeys = (
    summary: AppPermissionGrantSummary | null
): string[] => {
    if (!summary) {
        return [];
    }
    return summary.outcomes
        // Only the ones that landed. Tagging a refused user "New" would say
        // they have access they were not given.
        .filter(wasGranted)
        .flatMap((outcome) => {
            // Per outcome, because a grant now names several roles: one key for
            // every user/role pair that actually landed. Keyed on the summary's
            // single role, granting two roles marked both rows new even when
            // only one of them succeeded.
            const role = outcome.role.name.toUpperCase();
            return [outcome.user.guid, outcome.user.userId]
                .filter(Boolean)
                .map((id) => `${String(id).toUpperCase()}|${role}`);
        });
};

/** Whether this row is one the grant just created. */
export const isNewlyGranted = (
    row: CssUserRoleRowDto,
    keys: string[]
): boolean => {
    if (keys.length === 0) {
        return false;
    }
    const role = row.role_name?.toUpperCase() ?? "";
    const candidates = [guidFromUsername(row.username), row.username?.toUpperCase()];

    return candidates
        .filter(Boolean)
        .some((id) => keys.includes(`${id}|${role}`));
};

/** Column headings, shared with the loading skeleton so the two cannot drift. */
export const permissionsTableHeaders = [
    "User Name",
    "Domain",
    "Full Name",
    "Email",
    "Scope",
    "Role",
    "Expires",
];

/**
 * One CSV row per table row, in the order the columns are shown.
 *
 * Quoting is not optional: a role name can carry a comma through its scope, and
 * a name can carry one directly ("Smith, Jane"). Embedded quotes are
 * doubled, which is how RFC 4180 escapes them and what a spreadsheet expects.
 */
export const toCsv = (rows: CssUserRoleRowDto[]): string => {
    const cell = (value: unknown): string =>
        `"${String(value ?? "").replace(/"/g, '""')}"`;

    const lines = rows.map((row) =>
        [
            row.username,
            row.domain,
            [row.first_name, row.last_name].filter(Boolean).join(" "),
            row.email,
            scopeText(row),
            // What the table shows, so the file matches what was exported from.
            roleLabel(row),
            // Worded, not raw: a file full of dates would leave the reader
            // working out which of them have already passed.
            formatExpiry(row.expires_on),
        ]
            .map(cell)
            .join(",")
    );

    return [permissionsTableHeaders.map(cell).join(","), ...lines].join("\r\n");
};

/** A filename a person can find again: the application, then today's date. */
export const csvFileName = (appName: string, today: Date): string => {
    const stamp = today.toISOString().slice(0, 10);
    const safeName = appName.replace(/[^A-Za-z0-9]+/g, "-").replace(/^-|-$/g, "");
    // A name that reduces to nothing drops out entirely rather than leaving
    // "permissions-permissions".
    const prefix = safeName ? `${safeName}-permissions` : "permissions";
    return `${prefix}-${stamp}.csv`;
};

/**
 * Hand the CSV to the browser as a download.
 *
 * Kept apart from {@link toCsv} so the content can be tested without a DOM.
 */
export const downloadPermissionsCsv = (
    rows: CssUserRoleRowDto[],
    appName: string
): void => {
    // The BOM is what makes Excel read the file as UTF-8 rather than as the
    // local codepage, which otherwise mangles any accented name.
    const blob = new Blob(["﻿", toCsv(rows)], {
        type: "text/csv;charset=utf-8;",
    });

    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = csvFileName(appName, new Date());
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
};

/**
 * How a grant's end date reads in the table.
 *
 * <p>Three states, and the middle one is the point: "Expired" rather than a past
 * date, because a date alone leaves the reader doing the arithmetic to work out
 * whether the access is still live.
 *
 * <p>No expiry reads as the placeholder every other column uses for an absent
 * value, rather than as a sentence. Most grants have no expiry, so a column of
 * "Never expires" was the loudest thing in the table and said the least - the
 * dates are what somebody is scanning for.
 *
 * <p>Access lasts to the end of the day named, so a grant expiring today is not
 * expired. Comparing the strings works because both are YYYY-MM-DD, which sorts
 * the same way it reads.
 */
export const formatExpiry = (expiresOn: string | null | undefined): string => {
    if (!expiresOn) {
        return PLACE_HOLDER;
    }
    const now = new Date();
    const today = [
        now.getFullYear(),
        String(now.getMonth() + 1).padStart(2, "0"),
        String(now.getDate()).padStart(2, "0"),
    ].join("-");

    return expiresOn < today ? "Expired" : expiresOn;
};

/**
 * One person's hold on one role, however many scopes it took to grant.
 *
 * <p>CSS has no idea of a scoped role: a district-scoped Viewer granted for
 * three regions is three roles and three assignments, and the table showed three
 * rows that differed in one column. Reading "who has what" meant collating them
 * by eye, and the more scopes a role carried the worse it got.
 *
 * <p><b>Grouped by expiry as well as by role.</b> Two grants of the same role
 * ending on different days are genuinely different grants - collapsing them
 * would put one date in a column describing both, and the wrong one for half the
 * scopes.
 */
export type PermissionGroup = {
    /** Every assignment behind this row. Never empty. */
    assignments: CssUserRoleRowDto[];
    /** One entry per assignment: the scopes that assignment was granted for. */
    combinations: ScopeDto[][];
};

/** What decides whether two assignments are the same grant. */
const groupKey = (row: CssUserRoleRowDto): string =>
    [
        row.user_guid ?? row.username,
        row.role_name,
        // Undefined and "never expires" are the same thing; both differ from a
        // date, and two dates differ from each other.
        row.expires_on ?? "",
    ].join("|");

/**
 * Collapses assignments into one row per person, role and expiry.
 *
 * <p>Order is preserved: the first assignment of a group fixes where the group
 * sits, so a table sorted by role stays sorted after grouping.
 */
export const groupByRole = (rows: CssUserRoleRowDto[]): PermissionGroup[] => {
    const groups = new Map<string, PermissionGroup>();

    for (const row of rows) {
        const key = groupKey(row);
        const existing = groups.get(key);
        const scopes = row.scopes ?? [];

        if (existing) {
            existing.assignments.push(row);
            // An unscoped role has nothing to add; it would show as an empty
            // combination and push the others onto their own lines for nothing.
            if (scopes.length > 0) {
                existing.combinations.push(scopes);
            }
        } else {
            groups.set(key, {
                assignments: [row],
                combinations: scopes.length > 0 ? [scopes] : [],
            });
        }
    }

    return [...groups.values()];
};

/**
 * Whether this group's scopes need spelling out rather than simply listing.
 *
 * <p>Two things force it. A role scoped more than one way is granted per
 * <em>combination</em> - being a submitter for a district and an organisation is
 * not the same as being one for either - so those pairs have to stay visibly
 * paired. And a group holding more than one kind of scope needs each value
 * labelled, or a column of bare codes says nothing about what they are.
 */
export const needsScopeDetail = (group: PermissionGroup): boolean => {
    const types = new Set(
        group.combinations.flat().map((scope) => scope.type)
    );
    return types.size > 1 || group.combinations.some((one) => one.length > 1);
};

/** The prefix the history page uses too, so one scope reads the same in both. */
export const scopeTypeLabel = (type: string): string => {
    switch (type.toUpperCase()) {
        case "DISTRICT":
            return "District";
        case "REGION":
            return "Region";
        default:
            return "Organization";
    }
};

/**
 * One scope as it reads on a pill: {@code District: DCC}, {@code Region:
 * Cariboo}, {@code Organization: 00001012}.
 *
 * <p>Always prefixed. The three are not distinguishable from their values alone,
 * and a pill that says what kind of thing it is stays readable when it is lifted
 * out of its column - into a revoke confirmation, or read aloud.
 *
 * <p>Only regions read as a name. A district's full name runs to
 * {@code Cariboo-Chilcotin Natural Resource District}, which is too long for a
 * pill that already says "District" - and the code is short, familiar, and what
 * people quote. An organisation reads as its number for the same kind of reason:
 * it is what the grant was made against and what the picker searches by.
 */
export const scopeChipLabel = (scope: ScopeDto): string =>
    `${scopeTypeLabel(scope.type)}: ${
        scope.type.toUpperCase() === "REGION"
            ? scope.label || scope.value
            : scope.value
    }`;

/**
 * Everything in a group as one searchable, sortable string.
 *
 * <p>Carries the codes as well as the names: a chip reads "Kootenay-Boundary"
 * but the code is what the grant was made against and what somebody arrives with
 * from a ticket.
 */
export const groupScopeText = (group: PermissionGroup): string =>
    group.combinations
        .map((combination) =>
            combination
                .map((scope) =>
                    scope.label && scope.label !== scope.value
                        ? `${scope.label} ${scope.value}`
                        : scope.value
                )
                .join(" + ")
        )
        .join(", ");
