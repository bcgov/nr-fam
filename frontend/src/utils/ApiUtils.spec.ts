import { describe, expect, it } from "vitest";
import { describeApiError } from "./ApiUtils";

/**
 * Reading the backend's own words out of a refusal.
 *
 * The backend answers with `{"detail": {"code", "description"}}` and several
 * callers read `data.description` instead - one level too shallow. Every one of
 * them fell through to Axios's message, so a refusal that said "You cannot
 * change your own permissions" reached the screen as "Request failed with status
 * code 403".
 */

const refusal = (description: string) => ({
    message: "Request failed with status code 403",
    response: { data: { detail: { code: "self_grant_prohibited", description } } },
});

describe("describeApiError", () => {
    it("reads the description the backend actually sent", () => {
        expect(
            describeApiError(
                refusal("You cannot change your own permissions."),
                "fallback"
            )
        ).toBe("You cannot change your own permissions.");
    });

    it("never shows Axios's status line when a response came back", () => {
        // That line is the bug this exists to fix: it tells the reader the
        // number and withholds the sentence.
        const described = describeApiError(
            { message: "Request failed with status code 403", response: { data: {} } },
            "The permission could not be removed."
        );

        expect(described).toBe("The permission could not be removed.");
        expect(described).not.toContain("status code");
    });

    it("joins the messages of a validation error", () => {
        // The other shape the backend produces - an array, not an object.
        expect(
            describeApiError(
                {
                    response: {
                        data: {
                            detail: [
                                { msg: "role is required" },
                                { msg: "scope is required" },
                            ],
                        },
                    },
                },
                "fallback"
            )
        ).toBe("role is required scope is required");
    });

    it("reads the upstream-failure shape, which carries no detail", () => {
        expect(
            describeApiError(
                {
                    response: {
                        data: { failureCode: "UPSTREAM_TIMEOUT", message: "CSS timed out." },
                    },
                },
                "fallback"
            )
        ).toBe("CSS timed out.");
    });

    it("keeps Axios's message when there was no response at all", () => {
        // "Network Error" is genuinely what happened; the fallback would be a
        // guess about which call failed.
        expect(describeApiError({ message: "Network Error" }, "fallback")).toBe(
            "Network Error"
        );
    });

    it("falls back when there is nothing to read", () => {
        expect(describeApiError({}, "The file could not be read.")).toBe(
            "The file could not be read."
        );
        expect(describeApiError(null, "fallback")).toBe("fallback");
    });
});
