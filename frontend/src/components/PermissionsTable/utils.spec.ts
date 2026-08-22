import type { CssUserRoleRowDto } from "fam-api";
import { describe, expect, it } from "vitest";
import type { AppPermissionGrantSummary } from "@/views/AddAppPermission/utils";
import { UserType } from "fam-api";
import {
    csvFileName,
    roleLabel,
    toRevokeRequest,
    isNewlyGranted,
    newlyGrantedKeys,
    permissionsTableHeaders,
    toCsv,
} from "./utils";

const row = (overrides: Partial<CssUserRoleRowDto> = {}): CssUserRoleRowDto =>
    ({
        username: "JSMITH",
        domain: "IDIR",
        first_name: "Jane",
        last_name: "Smith",
        email: "jane@gov.bc.ca",
        role_name: "FREP_ADMINISTRATOR",
        scope_type: null,
        scope_value: null,
        ...overrides,
    }) as CssUserRoleRowDto;

describe("toCsv", () => {
    it("leads with the column headings the table shows", () => {
        // The skeleton and the export read the same list, so they cannot drift.
        expect(toCsv([]).split("\r\n")[0]).toBe(
            permissionsTableHeaders.map((h) => `"${h}"`).join(",")
        );
    });

    it("writes one line per row, in column order", () => {
        const lines = toCsv([row()]).split("\r\n");

        expect(lines).toHaveLength(2);
        expect(lines[1]).toBe(
            '"JSMITH","IDIR","Jane Smith","jane@gov.bc.ca","","FREP_ADMINISTRATOR"'
        );
    });

    it("exports the role as the table shows it", () => {
        // The file should match what it was exported from.
        const lines = toCsv([
            row({ role_display_name: "FREP Administrator" }),
        ]).split("\r\n");

        expect(lines[1]).toContain('"FREP Administrator"');
        expect(lines[1]).not.toContain("FREP_ADMINISTRATOR");
    });

    it("keeps a value containing a comma in one field", () => {
        // "Smith, Jane" would otherwise become two columns and shift every
        // field after it.
        const lines = toCsv([row({ last_name: "Smith, Jr" })]).split("\r\n");

        expect(lines[1]).toContain('"Jane Smith, Jr"');
        expect(lines[1].split('","')).toHaveLength(6);
    });

    it("escapes an embedded quote by doubling it", () => {
        const lines = toCsv([row({ first_name: 'Ja"ne' })]).split("\r\n");

        expect(lines[1]).toContain('"Ja""ne Smith"');
    });

    it("writes an empty field rather than the word undefined", () => {
        const lines = toCsv([row({ email: undefined })]).split("\r\n");

        expect(lines[1]).toContain('"JSMITH","IDIR","Jane Smith",""');
        expect(lines[1]).not.toContain("undefined");
    });

    it("exports the scope value when a role has one", () => {
        const lines = toCsv([
            row({ scopes: [{ type: "DISTRICT", value: "DCC", label: undefined }] }),
        ]).split("\r\n");

        expect(lines[1]).toContain('"DCC"');
    });
});

describe("csvFileName", () => {
    it("names the file for the application and the day", () => {
        expect(csvFileName("FREP", new Date("2026-08-12T18:00:00Z"))).toBe(
            "FREP-permissions-2026-08-12.csv"
        );
    });

    it("reduces punctuation in an application name to single hyphens", () => {
        // "Forests Stewardship Plan (DEV)" must not become a filename a browser
        // or operating system will mangle.
        expect(
            csvFileName("Forests Stewardship Plan (DEV)", new Date("2026-01-02T00:00:00Z"))
        ).toBe("Forests-Stewardship-Plan-DEV-permissions-2026-01-02.csv");
    });

    it("still produces a usable name when the application name is unusable", () => {
        expect(csvFileName("///", new Date("2026-01-02T00:00:00Z"))).toBe(
            "permissions-2026-01-02.csv"
        );
    });
});

describe("roleLabel", () => {
    it("prefers the role's short name", () => {
        expect(
            roleLabel(row({ role_display_name: "FREP Administrator" }))
        ).toBe("FREP Administrator");
    });

    it("falls back to the code when there is no description", () => {
        // Roles added directly in the CSS console never have one, and an empty
        // chip would be worse than a technical one.
        expect(roleLabel(row({ role_display_name: undefined }))).toBe(
            "FREP_ADMINISTRATOR"
        );
    });

    it("falls back when the description is blank rather than absent", () => {
        expect(roleLabel(row({ role_display_name: "" }))).toBe(
            "FREP_ADMINISTRATOR"
        );
    });
});

describe("newly granted highlighting", () => {
    const summary = (outcomes: unknown[]): AppPermissionGrantSummary =>
        ({ applicationName: "FREP (DEV)", outcomes }) as any;

    // The role travels on the outcome, not on the summary: one grant can name
    // several, and they do not share a fate.
    const grantedUser = (
        userId: string,
        guid: string,
        roleName = "FREP_ADMINISTRATOR"
    ) => ({
        user: { userId, guid },
        role: { name: roleName },
        results: [{ assigned: true }],
    });

    it("marks the row of a user just granted that role", () => {
        const keys = newlyGrantedKeys(summary([grantedUser("JSMITH", "AAA1")]));

        expect(isNewlyGranted(row({ username: "JSMITH" }), keys)).toBe(true);
    });

    it("matches a user CSS has not named yet, by their GUID", () => {
        // Straight after a grant that is exactly what the table shows: the user
        // has never signed in, so CSS holds only <guid>@azureidir.
        const keys = newlyGrantedKeys(summary([grantedUser("JSMITH", "AAA1")]));

        expect(
            isNewlyGranted(row({ username: "aaa1@azureidir" }), keys)
        ).toBe(true);
    });

    it("does not mark that user's other roles", () => {
        // Legacy marked one assignment row. Marking every row a user has would
        // claim permissions they held already are new.
        const keys = newlyGrantedKeys(summary([grantedUser("JSMITH", "AAA1")]));

        expect(
            isNewlyGranted(
                row({ username: "JSMITH", role_name: "SOME_OTHER_ROLE" }),
                keys
            )
        ).toBe(false);
    });

    it("does not mark a different user", () => {
        const keys = newlyGrantedKeys(summary([grantedUser("JSMITH", "AAA1")]));

        expect(isNewlyGranted(row({ username: "BJONES" }), keys)).toBe(false);
    });

    it("does not mark a user whose grant was refused", () => {
        // Tagging them "New" would say they have access they were not given.
        const keys = newlyGrantedKeys(
            summary([
                { user: { userId: "JSMITH", guid: "AAA1" }, results: [], error: "refused" },
            ])
        );

        expect(keys).toEqual([]);
        expect(isNewlyGranted(row({ username: "JSMITH" }), keys)).toBe(false);
    });

    it("marks every scope row of one scoped grant", () => {
        // A district-scoped grant creates a row per district, all of them new.
        const keys = newlyGrantedKeys(
            summary([grantedUser("JSMITH", "AAA1", "CHR_FREP_EDITOR")])
        );

        expect(
            isNewlyGranted(
                row({ username: "JSMITH", role_name: "CHR_FREP_EDITOR", scopes: [{ type: "DISTRICT", value: "DCC", label: undefined }] }),
                keys
            )
        ).toBe(true);
        expect(
            isNewlyGranted(
                row({ username: "JSMITH", role_name: "CHR_FREP_EDITOR", scopes: [{ type: "DISTRICT", value: "DQU", label: undefined }] }),
                keys
            )
        ).toBe(true);
    });

    it("marks every role that landed, not just the first", () => {
        // A grant now names several roles. Keyed on one of them, the rows for
        // the others would go unmarked while claiming to be handled.
        const keys = newlyGrantedKeys(
            summary([
                grantedUser("JSMITH", "AAA1", "ROLE_A"),
                grantedUser("JSMITH", "AAA1", "ROLE_B"),
            ])
        );

        expect(
            isNewlyGranted(row({ username: "JSMITH", role_name: "ROLE_A" }), keys)
        ).toBe(true);
        expect(
            isNewlyGranted(row({ username: "JSMITH", role_name: "ROLE_B" }), keys)
        ).toBe(true);
    });

    it("marks only the roles that succeeded for that user", () => {
        // Two roles for one person, one refused. Marking the refused row would
        // say they have access they were not given.
        const keys = newlyGrantedKeys(
            summary([
                grantedUser("JSMITH", "AAA1", "ROLE_A"),
                {
                    user: { userId: "JSMITH", guid: "AAA1" },
                    role: { name: "ROLE_B" },
                    results: [],
                    error: "different organization",
                },
            ])
        );

        expect(
            isNewlyGranted(row({ username: "JSMITH", role_name: "ROLE_A" }), keys)
        ).toBe(true);
        expect(
            isNewlyGranted(row({ username: "JSMITH", role_name: "ROLE_B" }), keys)
        ).toBe(false);
    });

    it("marks nothing when there was no grant", () => {
        expect(newlyGrantedKeys(null)).toEqual([]);
        expect(isNewlyGranted(row(), [])).toBe(false);
    });
});

describe("toRevokeRequest", () => {
    it("names the user by GUID, not by the displayed username", () => {
        // The username is a user id once the directory has named them, so it
        // cannot identify anyone.
        const request = toRevokeRequest(
            row({ username: "JSMITH", user_guid: "AAAA1111" })
        );

        expect(request.user_guid).toBe("AAAA1111");
    });

    it("sends both halves of a scoped assignment", () => {
        // CSS holds one concrete role, CHR_FREP_EDITOR_DISTRICT-DCC. Revoking
        // the base role would remove nothing and report success.
        const request = toRevokeRequest(
            row({
                role_name: "CHR_FREP_EDITOR",
                scopes: [{ type: "DISTRICT", value: "DCC", label: undefined }],
            })
        );

        expect(request.role_name).toBe("CHR_FREP_EDITOR");
        expect(request.scopes?.[0]?.type).toBe("DISTRICT");
        expect(request.scopes?.[0]?.values).toEqual(["DCC"]);
    });

    it("omits the scope for an unscoped assignment", () => {
        const request = toRevokeRequest(row({ scopes: [] }));

        expect(request.scopes).toEqual([]);
    });

    it("maps the domain onto the user type the backend expects", () => {
        // The user type decides which identity provider the CSS username is
        // built against; the wrong one names a user who does not exist.
        expect(toRevokeRequest(row({ domain: "BCEID" })).user_type).toBe(UserType.BceidBus);
        expect(toRevokeRequest(row({ domain: "IDIR" })).user_type).toBe(UserType.Idir);
    });
});
