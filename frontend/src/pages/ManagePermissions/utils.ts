import { EmailSendingStatus } from "fam-api/model";
import { roleLabel } from "@/utils/ScopeUtils";
import type {
    AppPermissionGrantSummary,
    UserGrantOutcome,
} from "@/pages/AddAppPermission/grantUtils";

/**
 * What a grant made on another screen has to say for itself here.
 *
 * Everything in this file is pure - it decides <em>what</em> is reported, not
 * how it looks. The page renders it. That split is what lets the wording be
 * tested without mounting anything, and it is why the Vue version's `h()` calls
 * are gone: they made the same functions untestable outside a component.
 */

/** The user as a person reads them: "Jane Smith (JSMITH)". */
export const describeUser = (outcome: UserGrantOutcome): string => {
    const name = [outcome.user.firstName, outcome.user.lastName]
        .filter(Boolean)
        .join(" ");
    return name ? `${name} (${outcome.user.userId})` : outcome.user.userId;
};

/**
 * Whether this user ended up holding the role.
 *
 * A grant can partly succeed - one district's role assigned while another fails
 * - so "granted" means at least one role landed, and the failures are reported
 * separately rather than cancelling it out.
 */
export const wasGranted = (outcome: UserGrantOutcome): boolean =>
    !outcome.error && outcome.results.some((result) => result.assigned);

/** Roles that did not land, whether the user got others or not. */
export const failedRoles = (outcome: UserGrantOutcome) =>
    outcome.results.filter((result) => !result.assigned);

/**
 * Whether the "you have been granted access" email failed to reach the relay.
 *
 * Worth telling the administrator: the access is real, but the person does not
 * know about it yet, so somebody has to contact them.
 */
export const emailFailed = (outcome: UserGrantOutcome): boolean =>
    outcome.results.some(
        (result) =>
            result.assigned &&
            result.email_sending_status ===
                EmailSendingStatus.SentToEmailServiceFailure
    );

/** Why one user did not get what was granted, in the backend's own words. */
export const failureReason = (outcome: UserGrantOutcome): string => {
    if (outcome.error) {
        return outcome.error;
    }
    const messages = failedRoles(outcome)
        .map((result) => result.error_message)
        .filter(Boolean);
    return messages.length > 0
        ? messages.join("; ")
        : "the role could not be assigned";
};

const plural = (count: number, noun: string) =>
    count === 1 ? `1 ${noun}` : `${count} ${noun}s`;

/**
 * What the toast says about one grant, or null when there is nothing to
 * celebrate.
 *
 * Null when nobody was granted anything: that case is entirely a failure, and
 * the banner reports it. A toast saying "0 users" would be noise on top of it.
 */
export const toGrantToast = (
    summary: AppPermissionGrantSummary | null
): { kind: "success" | "warning"; title: string; subtitle: string } | null => {
    if (!summary || summary.outcomes.length === 0) {
        return null;
    }

    const granted = summary.outcomes.filter(wasGranted);
    if (granted.length === 0) {
        return null;
    }

    const failedCount = summary.outcomes.length - granted.length;

    // Counted over distinct people and distinct roles, not over outcomes: three
    // roles granted to two people is six outcomes, and "6 users" would be wrong
    // twice over.
    const users = new Set(granted.map((outcome) => outcome.user.userId));
    const roles = new Set(granted.map((outcome) => outcome.role.name));

    // One of each is named; more are counted. Naming five roles and five people
    // would push the toast taller than the table it sits over, and the banner
    // names them all when any of them failed.
    const subtitle =
        users.size === 1 && roles.size === 1
            ? `${roleLabel(granted[0].role)} was granted to ${describeUser(granted[0])} ` +
              `in ${summary.applicationName}.`
            : `${plural(roles.size, "role")} granted to ${plural(users.size, "user")} ` +
              `in ${summary.applicationName}.`;

    return failedCount > 0
        ? {
              kind: "warning",
              title: "Some permissions were not granted",
              subtitle: `${subtitle} ${failedCount} could not be granted - see the message below.`,
          }
        : { kind: "success", title: "Permission granted", subtitle };
};

/**
 * A banner on the page, as opposed to a toast that expires.
 *
 * `outcomes` is present only on the failure banner, which lists them.
 */
export type PermissionBanner = {
    id: string;
    kind: "error" | "warning";
    title: string;
    subtitle?: string;
    outcomes?: UserGrantOutcome[];
    applicationName?: string;
};

/**
 * The banners for one grant.
 *
 * <b>Only what somebody has to act on.</b> The plain success case is a toast -
 * see {@link toGrantToast} - because the granted rows are already visible in the
 * table, marked "New". What stays here is a grant that was refused, and a grant
 * that landed but whose email did not: both leave something outstanding, and a
 * banner waits to be dismissed rather than expiring on its own.
 */
export const toGrantBanners = (
    summary: AppPermissionGrantSummary | null
): PermissionBanner[] => {
    if (!summary || summary.outcomes.length === 0) {
        return [];
    }

    const granted = summary.outcomes.filter(wasGranted);
    const failed = summary.outcomes.filter((outcome) => !wasGranted(outcome));
    const emailFailures = granted.filter(emailFailed);

    const banners: PermissionBanner[] = [];

    // Separate from the failure banner: these people do have access, so listing
    // them as failures would be wrong, but somebody still has to tell them.
    if (emailFailures.length > 0) {
        banners.push({
            id: "email-failures",
            kind: "warning",
            title: "Access was granted, but no email could be sent",
            subtitle:
                `No email reached ${emailFailures.map(describeUser).join(", ")}. ` +
                "Contact them to say their permission is ready.",
        });
    }

    if (failed.length > 0) {
        banners.push({
            id: "grant-failures",
            kind: "error",
            title: `Failed: some permissions were not added in ${summary.applicationName}`,
            outcomes: failed,
            applicationName: summary.applicationName,
        });
    }

    return banners;
};

/** A grant that failed before any user was attempted. */
export const toGrantRequestErrorBanner = (
    message: string | null
): PermissionBanner[] =>
    message
        ? [
              {
                  id: "grant-request-error",
                  kind: "error",
                  title: "The permission could not be granted",
                  subtitle: message,
              },
          ]
        : [];
