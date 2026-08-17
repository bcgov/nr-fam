import GrantFailureNtfnTemplate from "@/components/NotificationContent/GrantFailureNtfnTemplate.vue";
import GrantSuccessNtfnTemplate from "@/components/NotificationContent/GrantSuccessNtfnTemplate.vue";
import {
    Severity,
    type PermissionNotificationType,
} from "@/types/NotificationTypes";
import {
    AddAppUserPermissionErrorQuerykey,
    AddAppUserPermissionSuccessQuerykey,
    type AppPermissionGrantSummary,
    type UserGrantOutcome,
} from "@/views/AddAppPermission/utils";
import type { QueryClient } from "@tanstack/vue-query";
import { EmailSendingStatus } from "fam-api/model";
import { h, type Ref } from "vue";

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

/**
 * The banners for one grant.
 *
 * Up to two: what succeeded and what did not. Both can appear at once, because
 * a grant to several users is several calls and they do not share a fate.
 */
export const toGrantNotifications = (
    summary: AppPermissionGrantSummary | null
): PermissionNotificationType[] => {
    if (!summary || summary.outcomes.length === 0) {
        return [];
    }

    const granted = summary.outcomes.filter(wasGranted);
    const failed = summary.outcomes.filter((outcome) => !wasGranted(outcome));
    const emailFailures = granted.filter(emailFailed);

    const notifications: PermissionNotificationType[] = [];

    if (granted.length > 0) {
        notifications.push({
            severity: Severity.Success,
            message: h(GrantSuccessNtfnTemplate, {
                outcomes: granted,
                roleName: summary.roleName,
                applicationName: summary.applicationName,
            }),
            hasFullMsg: false,
        });
    }

    // Separate from the failure banner: these people do have access, so listing
    // them as failures would be wrong, but somebody still has to tell them.
    if (emailFailures.length > 0) {
        notifications.push({
            severity: Severity.Warn,
            message: `Access was granted, but no email could be sent to ${emailFailures
                .map(describeUser)
                .join(", ")}. Contact them to say their permission is ready.`,
            hasFullMsg: false,
        });
    }

    if (failed.length > 0) {
        notifications.push({
            severity: Severity.Error,
            message: h(GrantFailureNtfnTemplate, {
                outcomes: failed,
                roleName: summary.roleName,
                applicationName: summary.applicationName,
            }),
            hasFullMsg: false,
        });
    }

    return notifications;
};

/** A grant that failed before any user was attempted. */
export const toGrantRequestErrorNotification = (
    message: string | null
): PermissionNotificationType[] =>
    message
        ? [
              {
                  severity: Severity.Error,
                  message: `The permission could not be granted. ${message}`,
                  hasFullMsg: false,
              },
          ]
        : [];

/**
 * Drop the banners and the data behind them.
 *
 * Both halves matter: without removing the cached outcome the same banner
 * reappears on the next visit to this screen, having already been dismissed.
 */
export const clearNotifications = (
    queryClient: QueryClient,
    notifications: Ref<PermissionNotificationType[]>
): void => {
    queryClient.removeQueries({ queryKey: [AddAppUserPermissionSuccessQuerykey] });
    queryClient.removeQueries({ queryKey: [AddAppUserPermissionErrorQuerykey] });
    notifications.value = [];
};
