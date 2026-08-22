import { useToast } from "primevue/usetoast";

/**
 * The transient confirmations for permission changes.
 *
 * Grants and revocations used to report themselves differently: a grant left a
 * banner on Manage permissions, a revocation said nothing at all. Both are the
 * same kind of event - it worked, the table below already shows it - so both are
 * a toast now, and the wording is built here so the two cannot drift.
 *
 * <b>Only for outcomes that need no action.</b> Anything a person has to do
 * something about - a user the grant was refused for, an email that never sent -
 * stays a banner: a toast that disappears after six seconds is the wrong place
 * for a list somebody has to read and act on.
 */

/**
 * Long enough to read two lines, short enough not to sit over the table.
 *
 * PrimeVue's default is three seconds, which is too quick for wording that names
 * a role, a scope and a person.
 */
export const PERMISSION_TOAST_LIFE_MS = 6000;

export const usePermissionToast = () => {
    const toast = useToast();

    return {
        /** It worked, in full. */
        succeeded: (summary: string, detail: string) =>
            toast.add({
                severity: "success",
                summary,
                detail,
                life: PERMISSION_TOAST_LIFE_MS,
            }),

        /**
         * It worked for some and not for others.
         *
         * The toast says only how many; the banner underneath names them. A
         * grant to several users is several calls and they do not share a fate.
         */
        partiallySucceeded: (summary: string, detail: string) =>
            toast.add({
                severity: "warn",
                summary,
                detail,
                life: PERMISSION_TOAST_LIFE_MS,
            }),
    };
};
