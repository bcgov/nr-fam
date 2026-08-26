import type { CssRoleOptionDto } from "fam-api";
import { describe, expect, it } from "vitest";
import type { SelectedUser } from "@/types/SelectUserType";
import { newRoleScopeSelection } from "@/utils/ScopeUtils";
import { hasErrors, validateGrantForm } from "./validation";

/**
 * What a grant form refuses to submit, and why.
 *
 * The rule that matters is the conditional one: a role is only asked for a
 * district when it is district-scoped. Demanding one from every role would block
 * a form nobody could complete.
 */

const role = (over: Partial<CssRoleOptionDto> = {}): CssRoleOptionDto =>
    ({
        name: "FREP_EDITOR",
        display_name: "Editor",
        role_type_district: false,
        role_type_client: false,
        ...over,
    }) as CssRoleOptionDto;

const user = (userId = "JSMITH"): SelectedUser => ({ userId, guid: "AAAA" });

const form = (
    users: SelectedUser[],
    roles: CssRoleOptionDto[] = [],
    fill?: (selection: ReturnType<typeof newRoleScopeSelection>) => void
) => {
    const selections = roles.map((one) => {
        const selection = newRoleScopeSelection(one);
        fill?.(selection);
        return selection;
    });
    return { users, roles: selections };
};

describe("validateGrantForm", () => {
    it("accepts one user and one unscoped role", () => {
        const errors = validateGrantForm(form([user()], [role()]));

        expect(hasErrors(errors)).toBe(false);
    });

    it("requires a user", () => {
        const errors = validateGrantForm(form([], [role()]));

        expect(errors.users).toBe("At least one user is required");
    });

    it("requires a role", () => {
        const errors = validateGrantForm(form([user()]));

        expect(errors.roles).toBe("Please select at least one role");
    });

    it("refuses more users than one grant may carry", () => {
        const many = Array.from({ length: 51 }, (_, index) =>
            user(`USER${index}`)
        );

        const errors = validateGrantForm(form(many, [role()]));

        expect(errors.users).toContain("At most 50 users");
    });

    it("accepts exactly the maximum", () => {
        // The boundary itself is allowed - the message says "at most".
        const many = Array.from({ length: 50 }, (_, index) =>
            user(`USER${index}`)
        );

        expect(validateGrantForm(form(many, [role()])).users).toBeUndefined();
    });

    it("demands a district only from a district-scoped role", () => {
        const errors = validateGrantForm(
            form([user()], [role({ role_type_district: true })])
        );

        expect(errors.perRole.FREP_EDITOR?.districts).toBe(
            "Choose at least one district for this role"
        );
        // Not an organisation: the role is not scoped that way.
        expect(errors.perRole.FREP_EDITOR?.forestClients).toBeUndefined();
    });

    it("demands an organization only from a client-scoped role", () => {
        const errors = validateGrantForm(
            form([user()], [role({ role_type_client: true })])
        );

        expect(errors.perRole.FREP_EDITOR?.forestClients).toBe(
            "Choose at least one organization for this role"
        );
        expect(errors.perRole.FREP_EDITOR?.districts).toBeUndefined();
    });

    it("demands both from a role scoped both ways", () => {
        // A compound role applies per district/organisation pair, so neither
        // half alone means anything.
        const errors = validateGrantForm(
            form(
                [user()],
                [role({ role_type_district: true, role_type_client: true })]
            )
        );

        expect(errors.perRole.FREP_EDITOR?.districts).toBeDefined();
        expect(errors.perRole.FREP_EDITOR?.forestClients).toBeDefined();
    });

    it("is satisfied once the scope is chosen", () => {
        const errors = validateGrantForm(
            form([user()], [role({ role_type_district: true })], (selection) => {
                selection.districts = [
                    { org_unit_code: "DCC", org_unit_name: "Cariboo" },
                ] as never;
            })
        );

        expect(hasErrors(errors)).toBe(false);
    });

    it("keys the complaint by role name, not position", () => {
        // Position breaks the moment a role is removed from the middle of the
        // list, and the card that renders the message is keyed by name.
        const errors = validateGrantForm(
            form(
                [user()],
                [
                    role({ name: "A" }),
                    role({ name: "B", role_type_district: true }),
                ]
            )
        );

        expect(Object.keys(errors.perRole)).toEqual(["B"]);
    });

    it("reports every incomplete role rather than only the first", () => {
        const errors = validateGrantForm(
            form(
                [user()],
                [
                    role({ name: "A", role_type_district: true }),
                    role({ name: "B", role_type_client: true }),
                ]
            )
        );

        expect(Object.keys(errors.perRole).sort()).toEqual(["A", "B"]);
    });
});
