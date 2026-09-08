import type { CssBulkGrantRowDto } from "fam-api";
import { describeApiError } from "@/utils/ApiUtils";

/**
 * What a file loads.
 *
 * <p>The three screens are the same screen: same two steps, same preview table,
 * same reading of the result. What differs is the columns a file carries, what
 * the rows are called, and which endpoint applies them - which is little enough
 * to describe in a table rather than to fork the page over.
 *
 * <p>No DevOps kind. They are appointed for an application rather than for
 * access within one, and nothing produces a list of them to load.
 */
export type BulkKind = "users" | "delegatedAdmins" | "applicationAdmins";

export type BulkKindCopy = {
    /** Page title, and the heading of the screen it returns to. */
    title: string;
    /** What the file does, in one line, under the title. */
    description: string;
    /** The verb on the apply button, e.g. "Grant 12 permissions". */
    action: (count: number) => string;
    /** Header row of the template, and of what the parser expects. */
    header: string;
    /** A filled-in example, shown beside the download. */
    example: string;
    /** Downloaded filename. */
    fileName: string;
};

const USER_COLUMNS = "username,user_type,role,district,organization,region";
const APP_ADMIN_COLUMNS = "username";

export const BULK_COPY: Record<BulkKind, BulkKindCopy> = {
    users: {
        title: "Bulk upload permissions",
        description:
            "Grant roles to many people at once, from a spreadsheet.",
        action: (count) =>
            `Grant ${count} permission${count === 1 ? "" : "s"}`,
        header: USER_COLUMNS,
        example: `${USER_COLUMNS}
JSMITH,IDIR,FSPTS_VIEW_ALL,,,
BLEE,IDIR,CHR_FREP_EDITOR,DCC,,
BLEE,IDIR,CHR_FREP_EDITOR,DKA,,
ACMEFORESTRY,BCEID,FOM_SUBMITTER,,00001012,
RSINGH,IDIR,FREP_REGIONAL_LEAD,,,CARIBOO`,
        fileName: "fam-bulk-permissions-template.csv",
    },
    delegatedAdmins: {
        title: "Bulk upload delegated admins",
        description:
            "Appoint many delegated admins at once, each for the role their row names.",
        action: (count) =>
            `Appoint ${count} delegated admin${count === 1 ? "" : "s"}`,
        /*
            The same columns as a permissions file, and read the same way. A
            delegation authorises exactly one concrete role, so "Editor for
            district DCC" is a row in both files - it grants that access in one
            and the right to grant it in the other.
        */
        header: USER_COLUMNS,
        example: `${USER_COLUMNS}
JSMITH,IDIR,FSPTS_VIEW_ALL,,,
BLEE,IDIR,CHR_FREP_EDITOR,DCC,,
ACMEFORESTRY,BCEID,FOM_SUBMITTER,,00001012,`,
        fileName: "fam-bulk-delegated-admins-template.csv",
    },
    applicationAdmins: {
        title: "Bulk upload application admins",
        description:
            "Appoint many application admins at once. IDIR accounts only.",
        action: (count) =>
            `Appoint ${count} application admin${count === 1 ? "" : "s"}`,
        /*
            One column, not six. The tier is the whole appointment - an
            application admin can already grant every role the application
            defines - so there is no role to name and no scope to carry.

            No user type either: the tier is IDIR-only, so the column could only
            repeat what the file already means. Usernames are looked up in the
            IDIR directory and nowhere else.
        */
        header: APP_ADMIN_COLUMNS,
        example: `${APP_ADMIN_COLUMNS}
JSMITH
BLEE`,
        fileName: "fam-bulk-application-admins-template.csv",
    },
};

/** Shown on the screen so the expected shape needs no separate documentation. */
export const EXAMPLE_CSV = BULK_COPY.users.example;

/**
 * The downloadable template: the header row and nothing else.
 *
 * No example row on purpose. A placeholder username would be uploaded as-is
 * often enough to matter, and it can only ever come back as "no user is named
 * that" - an error the person did not cause and cannot act on. The shape is on
 * screen beside the download for anyone who wants to see a filled-in row.
 *
 * `user_type` is IDIR or BCEID. It may be left empty, in which case both
 * directories are searched - but stating it halves the lookups and stops a
 * username resolving to whichever directory happens to answer first.
 *
 * The scope columns are left empty for a role that is not scoped that way, and
 * filled for one that is. One row is one grant, so a person getting a role for
 * three districts is three rows - which is what makes the file readable in a
 * spreadsheet, where these are actually written.
 *
 * `region` is last rather than beside `district`, where it belongs to read. The
 * parser is positional, so inserting a column would reinterpret every file
 * written before it - organisation numbers would arrive as regions.
 */
export const TEMPLATE_CSV = `${BULK_COPY.users.header}\n`;

/**
 * Hand the template to the browser as a download.
 *
 * Mirrors `downloadPermissionsCsv`, including the byte-order mark: it is what
 * makes Excel open the file as UTF-8 rather than the local codepage. The
 * uploader strips it again, so a round trip through a spreadsheet still parses.
 */
export const downloadTemplateCsv = (kind: BulkKind = "users"): void => {
    const copy = BULK_COPY[kind];
    const blob = new Blob(["\ufeff", `${copy.header}\n`], {
        type: "text/csv;charset=utf-8;",
    });

    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = copy.fileName;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
};

/**
 * The person's name, or empty when the username resolved to nobody.
 *
 * The caller falls back to showing the username from the file in that case: a
 * blank cell would make an unresolvable row look like an unremarkable one.
 */
export const fullName = (row: CssBulkGrantRowDto): string =>
    [row.first_name, row.last_name].filter(Boolean).join(" ");

/**
 * Why an upload was refused outright, preferring the backend's message.
 *
 * A whole-file refusal - empty, too many rows - is reported here rather than
 * per row, so the generic fallback should almost never be seen.
 */
export const describeUploadError = (error: unknown): string =>
    describeApiError(error, "The file could not be read.");
