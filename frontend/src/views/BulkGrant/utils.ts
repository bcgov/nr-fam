import type { CssBulkGrantRowDto } from "fam-api";

/** Shown on the screen so the expected shape needs no separate documentation. */
export const EXAMPLE_CSV = `user_guid,role
AABBCCDDEEFF00112233445566778899,FSPTS_VIEW_ALL
BBBBCCCCDDDDEEEEFFFF000011112222,FSPTS_VIEW_ALL`;

/**
 * The downloadable template: the header row and nothing else.
 *
 * No example row on purpose. A placeholder GUID would be uploaded as-is often
 * enough to matter, and it can only ever come back as "no user has this GUID" -
 * an error the person did not cause and cannot act on. The shape is on screen
 * beside the download for anyone who wants to see a filled-in row.
 */
export const TEMPLATE_CSV = "user_guid,role\n";

/**
 * Hand the template to the browser as a download.
 *
 * Mirrors `downloadPermissionsCsv`, including the byte-order mark: it is what
 * makes Excel open the file as UTF-8 rather than the local codepage. The
 * uploader strips it again, so a round trip through a spreadsheet still parses.
 */
export const downloadTemplateCsv = (): void => {
    const blob = new Blob(["\ufeff", TEMPLATE_CSV], {
        type: "text/csv;charset=utf-8;",
    });

    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = "fam-bulk-permissions-template.csv";
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
};

/**
 * The person's name, or empty when the GUID resolved to nobody.
 *
 * The caller falls back to showing the raw GUID in that case: a blank cell would
 * make an unresolvable row look like an unremarkable one.
 */
export const fullName = (row: CssBulkGrantRowDto): string =>
    [row.first_name, row.last_name].filter(Boolean).join(" ");

/**
 * Why an upload was refused outright, preferring the backend's message.
 *
 * A whole-file refusal - empty, too many rows - is reported here rather than
 * per row, so the generic fallback should almost never be seen.
 */
export const describeUploadError = (error: unknown): string => {
    const response = (error as any)?.response?.data;
    return (
        response?.description ??
        (error as any)?.message ??
        "The file could not be read."
    );
};
