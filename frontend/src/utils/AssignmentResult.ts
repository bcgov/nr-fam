import type { CssUserRoleAssignmentResult } from "fam-api/model";

/**
 * Reading an appointment outcome that arrived with a 200.
 *
 * <b>A 200 is not an appointment.</b> The three appointment endpoints do not
 * fail the request when CSS refuses the assignment: the delegation role may have
 * been created before the refusal, and a scoped delegation can land for one
 * district and be refused for the next, so the outcome is reported per role in
 * the body rather than as a status. A screen that only catches thrown errors
 * reads a refusal as a success - which is how somebody CSS never assigned came
 * to be announced as added and then not appear in the table.
 *
 * The grant screen has always read these fields; the three admin screens did
 * not. This is that reading, in one place, so the next screen to appoint
 * somebody does not have to rediscover it.
 */

type Results = CssUserRoleAssignmentResult | CssUserRoleAssignmentResult[];

const asList = (results: Results): CssUserRoleAssignmentResult[] =>
    Array.isArray(results) ? results : [results];

/**
 * Whether the person now holds anything at all.
 *
 * Compared against `true` rather than read as truthy: `assigned` is required by
 * the schema, and a body missing it is a response we do not understand - which
 * should read as "not assigned", not as success by omission.
 */
export const anyAssigned = (results: Results): boolean =>
    asList(results).some((result) => result.assigned === true);

/** Whether anything in the response was refused, successes beside it or not. */
export const anyRefused = (results: Results): boolean =>
    asList(results).some((result) => result.assigned !== true);

/**
 * Why the refused ones were refused, in CSS's own words where it gave any.
 *
 * De-duplicated: appointing somebody for six districts that all fail for the
 * same reason produces six copies of one sentence, and a toast repeating itself
 * six times reads as a fault in FAM rather than as the one refusal it is.
 */
export const refusalReason = (results: Results, fallback: string): string => {
    const messages = asList(results)
        .filter((result) => result.assigned !== true)
        .map((result) => result.error_message?.trim())
        .filter((message): message is string => Boolean(message));

    return messages.length > 0 ? [...new Set(messages)].join(" ") : fallback;
};
