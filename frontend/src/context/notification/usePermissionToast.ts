import { useMemo } from "react";
import { PERMISSION_TOAST_LIFE_MS } from "./NotificationProvider";
import { useNotification } from "./useNotification";

/**
 * The wording for permission changes, in one place.
 *
 * Grants and revocations used to report themselves differently: a grant left a
 * banner on Manage permissions, a revocation said nothing at all. Both are the
 * same kind of event, so both are a toast now, and building the wording here is
 * what stops the two drifting apart again.
 */
export const usePermissionToast = () => {
    const { display } = useNotification();

    return useMemo(
        () => ({
            /** It worked, in full. */
            succeeded: (title: string, subtitle: string) =>
                display({
                    kind: "success",
                    title,
                    subtitle,
                    timeout: PERMISSION_TOAST_LIFE_MS,
                }),

            /**
             * It worked for some and not for others.
             *
             * The toast says only how many; the banner underneath names them. A
             * grant to several users is several calls and they do not share a
             * fate. Warnings ignore the timeout and wait to be dismissed - see
             * NotificationProvider.
             */
            partiallySucceeded: (title: string, subtitle: string) =>
                display({
                    kind: "warning",
                    title,
                    subtitle,
                    timeout: PERMISSION_TOAST_LIFE_MS,
                }),
        }),
        [display]
    );
};
