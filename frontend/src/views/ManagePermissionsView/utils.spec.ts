import { Severity } from "@/types/NotificationTypes";
import type {
    AppPermissionGrantSummary,
    UserGrantOutcome,
} from "@/views/AddAppPermission/utils";
import { EmailSendingStatus } from "fam-api/model";
import { describe, expect, it } from "vitest";
import {
    describeUser,
    emailFailed,
    failedRoles,
    toGrantNotifications,
    wasGranted,
} from "./utils";

const user = (userId: string, firstName?: string, lastName?: string) =>
    ({ userId, firstName, lastName, guid: `GUID-${userId}` }) as any;

const assigned = (
    roleName: string,
    email: EmailSendingStatus = EmailSendingStatus.NotRequired
) => ({
    role_name: roleName,
    role_created: false,
    assigned: true,
    error_message: null,
    email_sending_status: email,
});

const notAssigned = (roleName: string, message: string | null = "boom") => ({
    role_name: roleName,
    role_created: false,
    assigned: false,
    error_message: message,
    email_sending_status: EmailSendingStatus.NotRequired,
});

const summary = (outcomes: UserGrantOutcome[]): AppPermissionGrantSummary => ({
    applicationName: "FREP (DEV)",
    roleName: "FREP_ADMINISTRATOR",
    outcomes,
});

const severities = (s: AppPermissionGrantSummary | null) =>
    toGrantNotifications(s).map((n) => n.severity);

describe("describeUser", () => {
    it("gives the name with the user id after it", () => {
        expect(describeUser({ user: user("JSMITH", "Jane", "Smith") } as any)).toBe(
            "Jane Smith (JSMITH)"
        );
    });

    it("falls back to the user id when there is no name", () => {
        expect(describeUser({ user: user("JSMITH") } as any)).toBe("JSMITH");
    });
});

describe("wasGranted", () => {
    it("is true when any role landed", () => {
        // A scoped grant is one role per district and they do not share a fate.
        expect(
            wasGranted({
                user: user("A"),
                results: [assigned("R_DISTRICT-DCC"), notAssigned("R_DISTRICT-DQU")],
            } as any)
        ).toBe(true);
    });

    it("is false when the call for that user failed outright", () => {
        expect(
            wasGranted({ user: user("A"), results: [], error: "refused" } as any)
        ).toBe(false);
    });

    it("is false when every role failed", () => {
        expect(
            wasGranted({ user: user("A"), results: [notAssigned("R")] } as any)
        ).toBe(false);
    });
});

describe("toGrantNotifications", () => {
    it("shows nothing when there was no grant", () => {
        expect(toGrantNotifications(null)).toEqual([]);
        expect(toGrantNotifications(summary([]))).toEqual([]);
    });

    it("shows one success banner when everybody was granted", () => {
        expect(
            severities(
                summary([
                    { user: user("A"), results: [assigned("R")] },
                    { user: user("B"), results: [assigned("R")] },
                ] as any)
            )
        ).toEqual([Severity.Success]);
    });

    it("shows one failure banner when nobody was granted", () => {
        expect(
            severities(
                summary([{ user: user("A"), results: [], error: "refused" }] as any)
            )
        ).toEqual([Severity.Error]);
    });

    it("shows both when some succeeded and some did not", () => {
        // A grant to several users is several calls; reporting only one outcome
        // would hide the other.
        expect(
            severities(
                summary([
                    { user: user("A"), results: [assigned("R")] },
                    { user: user("B"), results: [], error: "different organization" },
                ] as any)
            )
        ).toEqual([Severity.Success, Severity.Error]);
    });

    it("warns separately when access was granted but no email went out", () => {
        // These people do have access, so listing them as failures would be
        // wrong - but somebody still has to tell them.
        const notifications = toGrantNotifications(
            summary([
                {
                    user: user("A", "Jane", "Smith"),
                    results: [
                        assigned("R", EmailSendingStatus.SentToEmailServiceFailure),
                    ],
                },
            ] as any)
        );

        expect(notifications.map((n) => n.severity)).toEqual([
            Severity.Success,
            Severity.Warn,
        ]);
        expect(String(notifications[1].message)).toContain("Jane Smith (A)");
    });

    it("does not warn about email when the grant itself failed", () => {
        expect(
            severities(
                summary([{ user: user("A"), results: [notAssigned("R")] }] as any)
            )
        ).toEqual([Severity.Error]);
    });

    it("counts a partly successful user as granted, not failed", () => {
        // They hold the role for one district; saying the grant failed would be
        // false, and the shortfall is named on the success banner instead.
        const outcome = {
            user: user("A"),
            results: [assigned("R_DISTRICT-DCC"), notAssigned("R_DISTRICT-DQU")],
        } as any;

        expect(severities(summary([outcome]))).toEqual([Severity.Success]);
        expect(failedRoles(outcome)).toHaveLength(1);
    });
});

describe("emailFailed", () => {
    it("ignores the email status of a role that was not assigned", () => {
        // Nothing was sent because nothing was granted; that is not a failure to
        // report to the administrator.
        expect(
            emailFailed({
                user: user("A"),
                results: [
                    {
                        ...notAssigned("R"),
                        email_sending_status:
                            EmailSendingStatus.SentToEmailServiceFailure,
                    },
                ],
            } as any)
        ).toBe(false);
    });
});
