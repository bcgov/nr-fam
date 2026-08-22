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
    toGrantToast,
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

const ROLE = {
    name: "FREP_ADMINISTRATOR",
    display_name: "FREP Administrator",
    composite: false,
    composites: [],
    role_type_district: false,
    role_type_client: false,
} as any;

/** Each outcome carries its own role now, so a default is supplied here. */
const summary = (
    outcomes: Array<Partial<UserGrantOutcome>>
): AppPermissionGrantSummary => ({
    applicationName: "FREP (DEV)",
    outcomes: outcomes.map((outcome) => ({
        role: ROLE,
        ...outcome,
    })) as UserGrantOutcome[],
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

    it("shows no banner at all when everybody was granted", () => {
        // Success is a toast now. A banner would sit there needing dismissal to
        // report something the table below already shows, marked "New".
        expect(
            severities(
                summary([
                    { user: user("A"), results: [assigned("R")] },
                    { user: user("B"), results: [assigned("R")] },
                ] as any)
            )
        ).toEqual([]);
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
        ).toEqual([Severity.Error]);
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

        // Still a banner rather than a toast: somebody has to contact them, and
        // a message that expires after six seconds is no place for a task.
        expect(notifications.map((n) => n.severity)).toEqual([Severity.Warn]);
        expect(String(notifications[0].message)).toContain("Jane Smith (A)");
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
        // false, so no failure banner is raised for them.
        const outcome = {
            user: user("A"),
            results: [assigned("R_DISTRICT-DCC"), notAssigned("R_DISTRICT-DQU")],
        } as any;

        expect(severities(summary([outcome]))).toEqual([]);
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

describe("toGrantToast", () => {
    it("names the one person granted", () => {
        const toast = toGrantToast(
            summary([
                { user: user("A", "Jane", "Smith"), results: [assigned("R")] },
            ] as any)
        );

        expect(toast?.severity).toBe("success");
        expect(toast?.detail).toContain("Jane Smith (A)");
    });

    it("counts several rather than naming them all", () => {
        // Five names would make the toast taller than the table it sits over.
        const toast = toGrantToast(
            summary([
                { user: user("A"), results: [assigned("R")] },
                { user: user("B"), results: [assigned("R")] },
                { user: user("C"), results: [assigned("R")] },
            ] as any)
        );

        expect(toast?.detail).toContain("3 users");
    });

    it("warns rather than celebrates when some were refused", () => {
        const toast = toGrantToast(
            summary([
                { user: user("A"), results: [assigned("R")] },
                { user: user("B"), results: [], error: "different organization" },
            ] as any)
        );

        // The toast says how many; the banner underneath names them.
        expect(toast?.severity).toBe("warn");
        expect(toast?.detail).toContain("1 could not be granted");
    });

    it("says nothing when nobody was granted anything", () => {
        // Entirely a failure, and the banner reports it. "0 users were granted"
        // would be noise stacked on top of the real message.
        expect(
            toGrantToast(
                summary([
                    { user: user("A"), results: [], error: "refused" },
                ] as any)
            )
        ).toBeNull();
    });

    it("says nothing when there was no grant at all", () => {
        expect(toGrantToast(null)).toBeNull();
        expect(toGrantToast(summary([] as any))).toBeNull();
    });

    it("names the role and the application", () => {
        const toast = toGrantToast(
            summary([{ user: user("A"), results: [assigned("R")] }] as any)
        );

        // Several applications can be administered from this screen, so a
        // toast that named neither would not say what had just happened.
        expect(toast?.detail).toContain("FREP Administrator");
        expect(toast?.detail).toContain("FREP (DEV)");
    });
});
