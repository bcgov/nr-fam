import { describe, expect, it } from "vitest";
import { anyAssigned, anyRefused, refusalReason } from "./AssignmentResult";

/**
 * Reading an appointment outcome that arrived with a 200.
 *
 * The point of these is the asymmetry: a body that does not say `assigned: true`
 * is not an appointment. Everything here is about not reading silence as
 * success.
 */

const result = (assigned: boolean, errorMessage?: string) =>
    ({
        role_name: "DELEGATED_ADMIN_6538_DEV__FREP_VIEWER",
        role_created: false,
        assigned,
        error_message: errorMessage,
        email_sending_status: "NOT_REQUIRED",
    }) as never;

describe("AssignmentResult", () => {
    it("reads a single result as well as a list", () => {
        expect(anyAssigned(result(true))).toBe(true);
        expect(anyAssigned([result(true)])).toBe(true);
    });

    it("treats a body that does not say assigned as not assigned", () => {
        // The field is required by the schema, so a body without it is one we
        // do not understand - which must not read as success by omission.
        expect(anyAssigned({} as never)).toBe(false);
        expect(anyRefused({} as never)).toBe(true);
    });

    it("reports a partial delegation as both landed and refused", () => {
        // Three districts, one refused: the appointment happened AND something
        // went wrong, and reporting only one of those loses half of it.
        const results = [result(true), result(true), result(false, "Refused")];

        expect(anyAssigned(results)).toBe(true);
        expect(anyRefused(results)).toBe(true);
    });

    it("gives CSS's own reason rather than a generic one", () => {
        expect(
            refusalReason([result(false, "invalid idp bceidbusiness")], "fallback")
        ).toBe("invalid idp bceidbusiness");
    });

    it("says the same thing once when every scope failed the same way", () => {
        // Six districts refused for one reason is one refusal, not six - and a
        // toast repeating itself reads as a fault in FAM rather than in CSS.
        const results = Array.from({ length: 6 }, () => result(false, "Refused"));

        expect(refusalReason(results, "fallback")).toBe("Refused");
    });

    it("falls back when the refusal came with no message", () => {
        expect(refusalReason([result(false)], "fallback")).toBe("fallback");
        expect(refusalReason([result(false, "   ")], "fallback")).toBe("fallback");
    });

    it("ignores the reasons of results that did land", () => {
        expect(
            refusalReason([result(true, "stale"), result(false, "Refused")], "fallback")
        ).toBe("Refused");
    });
});
