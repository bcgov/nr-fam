import { describe, expect, it } from "vitest";
import { domainLabel, formatFullName } from "./UserUtils";

/**
 * Composing a name from what CSS reports.
 *
 * The case that prompted this: Keycloak's BCeID federation does not put a
 * forename and a surname in first_name/last_name, so joining the pair repeated
 * the username inside the name - and the table already had a Username column
 * next to it.
 */
describe("formatFullName", () => {
    it("drops the username repeated at the end of a BCeID name", () => {
        // The row as it appeared: "Marco Villeneuve MVilleneuve3", beside a
        // Username column already reading MVilleneuve3.
        expect(
            formatFullName("Marco Villeneuve", "MVilleneuve3", "MVilleneuve3")
        ).toBe("Marco Villeneuve");
    });

    it("drops it whichever of the two fields the username was packed into", () => {
        // Which field Keycloak uses is not something FAM controls, and the
        // joined string is the same either way.
        expect(
            formatFullName("Marco", "Villeneuve MVilleneuve3", "MVilleneuve3")
        ).toBe("Marco Villeneuve");
    });

    it("ignores case, since CSS and the username column need not agree on it", () => {
        expect(formatFullName("Marco", "Villeneuve mvilleneuve3", "MVilleneuve3"))
            .toBe("Marco Villeneuve");
    });

    it("leaves an ordinary IDIR name alone", () => {
        expect(formatFullName("Jane", "Smith", "JSMITH")).toBe("Jane Smith");
    });

    it("keeps a surname that merely resembles the username", () => {
        // Only an exact match is dropped. "Villeneuve" is this person's real
        // surname and survives a username of "MVilleneuve3".
        expect(formatFullName("Marco", "Villeneuve", "MVilleneuve3"))
            .toBe("Marco Villeneuve");
    });

    it("keeps a two-word surname", () => {
        // The reason only an exact username match is dropped rather than a
        // trailing token: this is a real name, and the last token is part of it.
        expect(formatFullName("Ada", "van der Berg", "AVANDERB"))
            .toBe("Ada van der Berg");
    });

    it("keeps the username when it is the whole of what is known", () => {
        // Emptying the cell would hide a duplication that is no longer there.
        expect(formatFullName(null, "MVilleneuve3", "MVilleneuve3"))
            .toBe("MVilleneuve3");
    });

    it("handles a missing half, and reports nothing as empty", () => {
        expect(formatFullName("Jane", null, "JSMITH")).toBe("Jane");
        expect(formatFullName(null, "Smith", "JSMITH")).toBe("Smith");
        expect(formatFullName(null, null, "JSMITH")).toBe("");
        expect(formatFullName(null, null, null)).toBe("");
    });

    it("does not depend on a username being supplied", () => {
        expect(formatFullName("Jane", "Smith")).toBe("Jane Smith");
    });
});

describe("domainLabel", () => {
    it("reads the API's enum value as a name, not a code", () => {
        // The reported one: BCEID_BUS in a Domain column people read.
        expect(domainLabel("BCEID_BUS")).toBe("Business BCeID");
    });

    it("reads the domain the backend derives from a CSS username", () => {
        // A second spelling of the same thing, shown in the permissions tables.
        expect(domainLabel("BCEID")).toBe("Business BCeID");
    });

    it("leaves IDIR alone, which is already the product's name", () => {
        expect(domainLabel("IDIR")).toBe("IDIR");
    });

    it("ignores case and surrounding space", () => {
        expect(domainLabel(" bceid_bus ")).toBe("Business BCeID");
    });

    it("returns an unrecognised value untouched rather than relabelling it", () => {
        // A friendly name invented for a value we do not know would hide that
        // something upstream changed.
        expect(domainLabel("BCSC")).toBe("BCSC");
    });

    it("handles nothing at all", () => {
        expect(domainLabel(null)).toBe("");
        expect(domainLabel(undefined)).toBe("");
    });
});
