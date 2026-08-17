import {
    UserType,
    type CssUserRoleRevokeRequest,
    type CssUserRoleRowDto,
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
    user_type: row.domain === "BCEID" ? UserType.B : UserType.I,
    role_name: row.role_name,
    scope_type: row.scope_type ?? undefined,
    scope_value: row.scope_value ?? undefined,
});

/**
 * How a role reads on screen: its description when it has one.
 *
 * Falls back to the code, which is what a role defined before FAM held
 * descriptions has - and what one added directly in the CSS console will always
 * have. An empty label would be worse than a technical one.
 */
export const roleLabel = (row: CssUserRoleRowDto): string =>
    row.role_description || row.role_name;
import type { AppPermissionGrantSummary } from "@/views/AddAppPermission/utils";
import { wasGranted } from "@/views/ManagePermissionsView/utils";

/** The row shading legacy used to mark a just-added permission. */
export const NEW_ACCESS_STYLE_IN_TABLE = {
    "background-color": "#C2E0FF",
    "box-shadow": "inset 0 0 0 0.063rem #85C2FF",
};

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
    const role = summary.roleName.toUpperCase();
    return summary.outcomes
        // Only the ones that landed. Tagging a refused user "New" would say
        // they have access they were not given.
        .filter(wasGranted)
        .flatMap((outcome) =>
            [outcome.user.guid, outcome.user.userId]
                .filter(Boolean)
                .map((id) => `${String(id).toUpperCase()}|${role}`)
        );
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
            row.scope_value,
            // What the table shows, so the file matches what was exported from.
            roleLabel(row),
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
