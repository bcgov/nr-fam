import type {
    CssRoleOptionDto,
    CssUserRoleRowDto,
    FamDistrictDto,
    FamForestClientDto,
    FamRegionDto,
} from "fam-api";
import type { PermissionGroup } from "@/components/PermissionsTable/utils";
import type { RoleScopeSelection } from "@/utils/ScopeUtils";

/**
 * Turning a grant that exists back into the form that would have made it.
 *
 * <p>The grant screen writes selections out into CSS role names; this reads them
 * back in. Editing is the only thing that needs the journey in that direction,
 * and it has to be exact - a scope this fails to recover is one the person
 * silently loses when they save.
 */

/**
 * The scopes of one kind that a group was granted for, de-duplicated.
 *
 * <p>De-duplicated because a compound role repeats them: granted for two
 * districts against one organisation, that organisation appears in both
 * combinations and would otherwise be listed twice in a picker that only holds
 * it once.
 */
const scopeValues = (group: PermissionGroup, type: string): string[] => {
    const seen = new Set<string>();
    for (const combination of group.combinations) {
        for (const scope of combination) {
            if (scope.type.toUpperCase() === type) {
                seen.add(scope.value);
            }
        }
    }
    return [...seen];
};

/**
 * The selection a group came from, ready for the role table to render.
 *
 * <p>Districts and regions are matched against the real lists rather than
 * rebuilt from their codes: the pickers show a name, and an entry invented from
 * a code would sit in the table reading {@code DCC} where every other row reads
 * {@code Cariboo-Chilcotin}. A code with no match is still carried - it is a
 * scope the person genuinely holds, and dropping it would quietly revoke it on
 * save.
 *
 * <p>Organisations are the exception. Their names come from the Forest Client
 * API rather than a list FAM holds, so the number is all there is until the
 * picker looks one up.
 */
export const toSelection = (
    group: PermissionGroup,
    role: CssRoleOptionDto,
    districts: FamDistrictDto[],
    regions: FamRegionDto[]
): RoleScopeSelection => ({
    role,
    districts: scopeValues(group, "DISTRICT").map(
        (code) =>
            districts.find((district) => district.org_unit_code === code) ??
            // Retired, or a list that has not arrived. The code is what it is
            // called then - it still has to be carried, or saving would quietly
            // revoke a scope the person holds.
            ({ org_unit_code: code, org_unit_name: code } as FamDistrictDto)
    ),
    regions: scopeValues(group, "REGION").map(
        (code) =>
            regions.find((region) => region.region_code === code) ??
            ({ region_code: code, region_name: code } as FamRegionDto)
    ),
    forestClients: scopeValues(group, "FOREST_CLIENT").map(
        (number) =>
            ({ forest_client_number: number }) as FamForestClientDto
    ),
});

/**
 * One granted combination, as a string that can be compared.
 *
 * <p>Sorted, so the same pairing written in either order is one key: the role
 * name orders its scope suffixes, but nothing guarantees the order they arrive
 * back in.
 */
export const combinationKey = (
    scopes: { type: string; value: string }[]
): string =>
    scopes
        .map((scope) => `${scope.type.toUpperCase()}:${scope.value}`)
        .sort()
        .join("|");

/** What an edit changes, as two lists of whole assignments. */
export type PermissionDiff = {
    /** Combinations the person does not hold yet. */
    added: { type: string; value: string }[][];
    /** Assignments they hold and should not. */
    removed: CssUserRoleRowDto[];
};

/**
 * What has to happen for the grant to look like the selection.
 *
 * <p>A diff rather than a wholesale replace. Revoking everything and granting it
 * back would take away access the edit never touched, and would do so for real
 * in the window between the two - somebody editing one district of six would be
 * briefly locked out of the other five, and permanently if the grant half
 * failed.
 */
export const diffScopes = (
    group: PermissionGroup,
    wanted: { type: string; value: string }[][]
): PermissionDiff => {
    const held = new Map<string, CssUserRoleRowDto>();
    group.assignments.forEach((assignment, index) => {
        held.set(combinationKey(group.combinations[index] ?? []), assignment);
    });

    const wantedKeys = new Set(wanted.map(combinationKey));

    return {
        added: wanted.filter(
            (combination) => !held.has(combinationKey(combination))
        ),
        removed: [...held.entries()]
            .filter(([key]) => !wantedKeys.has(key))
            .map(([, assignment]) => assignment),
    };
};


/**
 * Which combinations have to be sent, given what changed.
 *
 * <p>Normally only the new ones - that is the point of diffing. But an expiry
 * change moves no combination, so the diff alone reports that nothing has
 * happened, and the form would claim success over a date that was never
 * applied.
 *
 * <p>A role carries one expiry marker and granting replaces it, so re-issuing a
 * grant is how the date is changed. When it has moved, every combination the
 * person keeps is re-issued - not only the new ones, or one role would end on
 * two different days and split into two rows the next time the table loaded.
 */
export const plannedGrants = (
    diff: PermissionDiff,
    wanted: { type: string; value: string }[][],
    expiryChanged: boolean
): { type: string; value: string }[][] => (expiryChanged ? wanted : diff.added);

/** Whether the edit changed anything at all worth reporting. */
export const isNoop = (diff: PermissionDiff, expiryChanged: boolean): boolean =>
    !expiryChanged && diff.added.length === 0 && diff.removed.length === 0;
