import type { ForestClientInput } from "@/components/AddPermissions/ForestClientAddTable.vue";
import type {
    CssScopeSelection,
    CssRoleOptionDto,
    FamDistrictDto,
    FamForestClientDto,
} from "fam-api";

/**
 * The scope selections a request carries, one entry per dimension the role needs.
 *
 * A role scoped by district AND forest client contributes both, and the backend
 * grants every district/client pair. Building this in one place matters: a grant
 * and a delegation of the same role must name the same concrete roles, or the
 * delegation authorises nothing.
 *
 * A dimension the role does not require contributes nothing, even if the form
 * happens to hold values for it - switching roles mid-form should not smuggle a
 * stale selection into the request.
 */
export const toScopeSelections = (
    role: { role_type_district?: boolean; role_type_client?: boolean } | null,
    districts: { org_unit_code: string }[],
    forestClients: { forest_client_number: string }[]
): CssScopeSelection[] => {
    if (!role) {
        return [];
    }
    const selections: CssScopeSelection[] = [];
    if (role.role_type_district) {
        selections.push({
            type: "DISTRICT",
            values: districts.map((district) => district.org_unit_code),
        });
    }
    if (role.role_type_client) {
        selections.push({
            type: "FOREST_CLIENT",
            values: forestClients.map((client) => client.forest_client_number),
        });
    }
    return selections;
};

/**
 * One chosen role, with what it is scoped to.
 *
 * Shared by the grant screen and the delegated-admin screen, which ask different
 * questions of the same shape: on one the districts are what a person is being
 * given, on the other they are what that person may hand out. The wording
 * differs; the data does not.
 *
 * The scope belongs to the role rather than to the form. Two roles chosen at
 * once can be scoped differently - one by district, one by forest client, one
 * not at all - and even two district-scoped roles need not cover the same
 * districts. A single pair of shared arrays could express none of that.
 */
export type RoleScopeSelection = {
    role: CssRoleOptionDto;
    districts: FamDistrictDto[];
    forestClients: FamForestClientDto[];
    /**
     * The organisation search box's own state, per role.
     *
     * Shared, two client-scoped roles on the same screen would echo each other's
     * typing, and the `<label for>` would point at whichever rendered last.
     */
    forestClientInput: ForestClientInput;
};

/** Whether a role has to be narrowed before it means anything. */
export const requiresScope = (role: CssRoleOptionDto): boolean =>
    Boolean(role.role_type_district || role.role_type_client);

/** What a role is called, falling back to its code. */
export const roleLabel = (role: CssRoleOptionDto): string =>
    role.display_name ?? role.name;

/**
 * A role freshly chosen, with nothing selected for it yet.
 *
 * The input id carries the role name so every search box on the screen has its
 * own; two elements sharing an id would break the label association for both.
 */
export const newRoleScopeSelection = (
    role: CssRoleOptionDto
): RoleScopeSelection => ({
    role,
    districts: [],
    forestClients: [],
    forestClientInput: {
        id: `forest-client-number-input-${role.name}`,
        value: "",
        isValid: true,
        errorMsg: "",
        isVerifying: false,
    },
});

/**
 * The backend's ceiling on one request's scope combinations.
 *
 * It creates a CSS role per combination, so a compound role multiplies fast.
 * Mirrored here so a form can say so before the request is refused.
 */
export const MAX_SCOPE_COMBINATIONS = 50;

/**
 * How many concrete roles one selection will actually produce.
 *
 * A role scoped by district *and* forest client applies per pair, so three
 * districts and two organisations is six, not five. That multiplication is the
 * thing worth showing: it is not obvious from the form, and it is what runs into
 * the ceiling.
 */
export const scopeCombinationCount = (selection: RoleScopeSelection): number => {
    const dimensions: number[] = [];
    if (selection.role.role_type_district) {
        dimensions.push(selection.districts.length);
    }
    if (selection.role.role_type_client) {
        dimensions.push(selection.forestClients.length);
    }
    // An unscoped role is one, not zero.
    return dimensions.reduce((total, size) => total * size, 1);
};

/** Selections whose scope has already outgrown what the backend accepts. */
export const selectionsOverTheLimit = (
    selections: RoleScopeSelection[]
): RoleScopeSelection[] =>
    selections.filter(
        (selection) => scopeCombinationCount(selection) > MAX_SCOPE_COMBINATIONS
    );
