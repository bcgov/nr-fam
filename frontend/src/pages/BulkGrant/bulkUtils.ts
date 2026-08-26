import type { CssBulkGrantRowDto } from "fam-api";

/** Shown on the screen so the expected shape needs no separate documentation. */
export const EXAMPLE_CSV = `user_guid,user_type,role,district,organization,region
AABBCCDDEEFF00112233445566778899,IDIR,FSPTS_VIEW_ALL,,,
BBBBCCCCDDDDEEEEFFFF000011112222,IDIR,CHR_FREP_EDITOR,DCC,,
BBBBCCCCDDDDEEEEFFFF000011112222,IDIR,CHR_FREP_EDITOR,DKA,,
CCCCDDDDEEEEFFFF00001111222233,BCEID,FOM_SUBMITTER,,00001012,
DDDDEEEEFFFF000011112222333344,IDIR,FREP_REGIONAL_LEAD,,,CARIBOO`;

/**
 * The downloadable template: the header row and nothing else.
 *
 * No example row on purpose. A placeholder GUID would be uploaded as-is often
 * enough to matter, and it can only ever come back as "no user has this GUID" -
 * an error the person did not cause and cannot act on. The shape is on screen
 * beside the download for anyone who wants to see a filled-in row.
 *
 * `user_type` is IDIR or BCEID. It may be left empty, in which case both
 * directories are searched - but stating it halves the lookups and stops a GUID
 * resolving to whichever directory happens to answer first.
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
export const TEMPLATE_CSV =
    "user_guid,user_type,role,district,organization,region\n";

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
