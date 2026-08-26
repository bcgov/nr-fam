import { EmailSendingStatus } from "fam-api/model";
import { describe, expect, it } from "vitest";
import type {
    AppPermissionGrantSummary,
    UserGrantOutcome,
} from "@/pages/AddAppPermission/grantUtils";
import {
    describeUser,
    emailFailed,
    failureReason,
    toGrantBanners,
    toGrantRequestErrorBanner,
    toGrantToast,
    wasGranted,
} from "./utils";

/**
 * What a grant reports once it has happened.
 *
 * The distinction under test throughout is toast versus banner: a toast expires
 * and is for an outcome nobody has to act on, a banner waits to be dismissed and
 * is for one somebody does.
 */

const outcome = (over: Partial<UserGrantOutcome> = {}): UserGrantOutcome =>
    ({
        user: { userId: "JSMITH", guid: "AAAA1111", firstName: "Jane", lastName: "Smith" },
        role: { name: "FREP_EDITOR", display_name: "Editor" },
        results: [{ assigned: true }],
        error: undefined,
        ...over,
    }) as UserGrantOutcome;

const summary = (outcomes: UserGrantOutcome[]): AppPermissionGrantSummary =>
    ({ applicationName: "FREP (DEV)", outcomes }) as AppPermissionGrantSummary;

describe("describeUser", () => {
    it("names the person and their user id", () => {
        expect(describeUser(outcome())).toBe("Jane Smith (JSMITH)");
    });

    it("falls back to the user id when the directory knows no name", () => {
        expect(
            describeUser(outcome({ user: { userId: "JSMITH" } as never }))
        ).toBe("JSMITH");
    });
});

describe("wasGranted", () => {
    it("is true when at least one role landed", () => {
        // A grant can partly succeed - one district assigned while another
        // fails - and the part that worked is real access.
        expect(
            wasGranted(
                outcome({ results: [{ assigned: true }, { assigned: false }] as never })
            )
        ).toBe(true);
    });

    it("is false when the user failed outright", () => {
        expect(
            wasGranted(outcome({ error: "That user is at another organisation." }))
        ).toBe(false);
    });

    it("is false when nothing was assigned", () => {
        expect(wasGranted(outcome({ results: [{ assigned: false }] as never }))).toBe(
            false
        );
    });
});

describe("emailFailed", () => {
    it("is true when an assigned role's notification never sent", () => {
        // The access is real but the person does not know, so somebody has to
        // contact them.
        expect(
            emailFailed(
                outcome({
                    results: [
                        {
                            assigned: true,
                            email_sending_status:
                                EmailSendingStatus.SentToEmailServiceFailure,
                        },
                    ] as never,
                })
            )
        ).toBe(true);
    });

    it("ignores an email failure on a role that was not assigned", () => {
        expect(
            emailFailed(
                outcome({
                    results: [
                        {
                            assigned: false,
                            email_sending_status:
                                EmailSendingStatus.SentToEmailServiceFailure,
                        },
                    ] as never,
                })
            )
        ).toBe(false);
    });
});

describe("failureReason", () => {
    it("prefers the outcome's own error", () => {
        expect(
            failureReason(outcome({ error: "That user is at another organisation." }))
        ).toBe("That user is at another organisation.");
    });

    it("joins the per-role messages when there is no outright error", () => {
        expect(
            failureReason(
                outcome({
                    results: [
                        { assigned: false, error_message: "no such role" },
                        { assigned: false, error_message: "CSS refused" },
                    ] as never,
                })
            )
        ).toBe("no such role; CSS refused");
    });

    it("says something rather than nothing when the backend explained nothing", () => {
        expect(failureReason(outcome({ results: [{ assigned: false }] as never }))).toBe(
            "the role could not be assigned"
        );
    });
});

describe("toGrantToast", () => {
    it("names the role and the person when there is one of each", () => {
        const toast = toGrantToast(summary([outcome()]));

        expect(toast).toMatchObject({ kind: "success", title: "Permission granted" });
        expect(toast?.subtitle).toBe(
            "Editor was granted to Jane Smith (JSMITH) in FREP (DEV)."
        );
    });

    it("counts distinct people and roles rather than outcomes", () => {
        // Two roles for two people is four outcomes, and "4 users" would be
        // wrong twice over.
        const outcomes = [
            outcome({ user: { userId: "A" } as never, role: { name: "R1" } as never }),
            outcome({ user: { userId: "A" } as never, role: { name: "R2" } as never }),
            outcome({ user: { userId: "B" } as never, role: { name: "R1" } as never }),
            outcome({ user: { userId: "B" } as never, role: { name: "R2" } as never }),
        ];

        expect(toGrantToast(summary(outcomes))?.subtitle).toBe(
            "2 roles granted to 2 users in FREP (DEV)."
        );
    });

    it("warns and points at the banner when some failed", () => {
        const toast = toGrantToast(
            summary([outcome(), outcome({ user: { userId: "B" } as never, error: "no" })])
        );

        expect(toast?.kind).toBe("warning");
        expect(toast?.subtitle).toContain("1 could not be granted");
    });

    it("says nothing at all when nobody was granted anything", () => {
        // Entirely a failure, and the banner reports it. "0 users" would be
        // noise on top.
        expect(toGrantToast(summary([outcome({ error: "no" })]))).toBeNull();
    });

    it("says nothing for an empty or missing summary", () => {
        expect(toGrantToast(null)).toBeNull();
        expect(toGrantToast(summary([]))).toBeNull();
    });
});

describe("toGrantBanners", () => {
    it("raises nothing when everything worked", () => {
        // The plain success case is a toast; the granted rows are already in the
        // table, marked "New".
        expect(toGrantBanners(summary([outcome()]))).toEqual([]);
    });

    it("raises an error banner listing who was refused", () => {
        const banners = toGrantBanners(
            summary([outcome({ error: "That user is at another organisation." })])
        );

        expect(banners).toHaveLength(1);
        expect(banners[0].kind).toBe("error");
        expect(banners[0].outcomes).toHaveLength(1);
        expect(banners[0].title).toContain("FREP (DEV)");
    });

    it("reports an unsent email separately from a refusal", () => {
        // These people do have access, so listing them as failures would be
        // wrong - but somebody still has to tell them.
        const banners = toGrantBanners(
            summary([
                outcome({
                    results: [
                        {
                            assigned: true,
                            email_sending_status:
                                EmailSendingStatus.SentToEmailServiceFailure,
                        },
                    ] as never,
                }),
                outcome({ user: { userId: "B" } as never, error: "refused" }),
            ])
        );

        expect(banners.map((banner) => banner.kind)).toEqual(["warning", "error"]);
        expect(banners[0].subtitle).toContain("Jane Smith (JSMITH)");
        // The person whose email failed is not in the failure list.
        expect(banners[1].outcomes).toHaveLength(1);
        expect(banners[1].outcomes?.[0].user.userId).toBe("B");
    });

    it("raises nothing for an empty or missing summary", () => {
        expect(toGrantBanners(null)).toEqual([]);
        expect(toGrantBanners(summary([]))).toEqual([]);
    });
});

describe("toGrantRequestErrorBanner", () => {
    it("carries the message through", () => {
        const banners = toGrantRequestErrorBanner("The application was not found.");

        expect(banners).toHaveLength(1);
        expect(banners[0].subtitle).toBe("The application was not found.");
    });

    it("raises nothing when there is no message", () => {
        expect(toGrantRequestErrorBanner(null)).toEqual([]);
    });
});
