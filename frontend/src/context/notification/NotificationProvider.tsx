import { ToastNotification } from "@carbon/react";
import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { NotificationContext, type NotificationContent } from "./NotificationContext";

/**
 * Everything the app has to say about how something went.
 *
 * Ported from nr-fsp-new, and it replaces PrimeVue's toast service. It began as
 * the confirmation for a grant or a revocation, with anything that had gone
 * wrong staying behind as a banner on the page. Both arrive here now: failures
 * were pushing the screen's own content down the page, and a load failure, a
 * refused revoke and a partly-failed grant could stack three boxes above a table
 * before anybody read one of them.
 *
 * What makes that safe is the timeout rule below - a problem does not expire.
 * The banner's one real advantage was that it waited to be dismissed, and an
 * error toast waits too.
 *
 * Unlike nr-fsp-new's version there is no `safeErrorMessage` scrubbing here.
 * FAM's failures are surfaced from the backend's own `description`, which is
 * written for an administrator to read.
 */

/**
 * Long enough to read two lines, short enough not to sit over the table.
 *
 * Carried over from the PrimeVue implementation: the default three seconds was
 * too quick for wording that names a role, a scope and a person.
 */
export const PERMISSION_TOAST_LIFE_MS = 6000;

/** Kept in step with the slide-out animation in styles/index.scss. */
const EXIT_ANIMATION_MS = 300;

/**
 * Past this the stack is taller than the screen and the oldest is off the top.
 *
 * The oldest goes rather than the newest: the newest is the one whose cause the
 * person is looking at. Reaching this at all means several things failed at once,
 * which is one story rather than five.
 */
const MAX_VISIBLE = 4;

type ActiveToast = NotificationContent & { id: number };

/** Two toasts saying the same thing are one toast repeated. */
const sameMessage = (one: NotificationContent, two: NotificationContent) =>
    one.kind === two.kind &&
    one.title === two.title &&
    one.subtitle === two.subtitle;

/**
 * One toast, owning the timing of its own exit.
 *
 * Per toast rather than one timer in the provider: they arrive at different
 * moments and a success sitting under an error should still expire on its own
 * schedule.
 */
const StackedToast = ({
    toast,
    onDismiss,
}: {
    toast: ActiveToast;
    onDismiss: (id: number) => void;
}) => {
    const [animation, setAnimation] = useState("slide-in");

    // Starts the slide-out slightly before Carbon's own timeout fires, so the
    // toast animates away rather than disappearing mid-frame.
    useEffect(() => {
        if (toast.timeout <= 0) {
            return;
        }
        const timer = setTimeout(
            () => setAnimation("slide-out"),
            Math.max(0, toast.timeout - EXIT_ANIMATION_MS)
        );
        return () => clearTimeout(timer);
    }, [toast.timeout]);

    const close = () => {
        setAnimation("slide-out");
        toast.onClose?.();
        // Removed after the animation rather than on the click, so it leaves the
        // way it arrived instead of blinking out.
        setTimeout(() => onDismiss(toast.id), EXIT_ANIMATION_MS);
    };

    return (
        <ToastNotification
            className={animation}
            lowContrast
            aria-label="closes notification"
            caption={toast.caption}
            kind={toast.kind}
            onClose={close}
            onCloseButtonClick={toast.onCloseButtonClick}
            role="status"
            statusIconDescription="notification"
            subtitle={toast.subtitle}
            timeout={toast.timeout}
            title={toast.title}
        >
            {toast.children}
        </ToastNotification>
    );
};

export const NotificationProvider = ({ children }: { children: ReactNode }) => {
    const [toasts, setToasts] = useState<ActiveToast[]>([]);
    const nextId = useRef(0);

    const display = useCallback((next: NotificationContent) => {
        // Errors and warnings stay until they are dismissed. A caller's timeout
        // is ignored for those so a problem does not vanish before it is read -
        // this is what lets a failure be a toast at all rather than a banner.
        const isProblem =
            next.kind === "error" ||
            next.kind === "warning" ||
            next.kind === "warning-alt";

        setToasts((current) => {
            if (current.some((one) => sameMessage(one, next))) {
                return current;
            }
            const added = [
                ...current,
                {
                    ...next,
                    timeout: isProblem ? 0 : next.timeout,
                    id: nextId.current++,
                },
            ];
            return added.slice(-MAX_VISIBLE);
        });
    }, []);

    const dismiss = useCallback(
        (id: number) =>
            setToasts((current) => current.filter((one) => one.id !== id)),
        []
    );

    return (
        <NotificationContext.Provider value={{ display }}>
            {children}
            {toasts.length > 0 && (
                // A column of its own, because each toast was positioned at the
                // same fixed corner and a second one landed exactly on top of
                // the first.
                <div className="toast-stack">
                    {toasts.map((toast) => (
                        <StackedToast
                            key={toast.id}
                            toast={toast}
                            onDismiss={dismiss}
                        />
                    ))}
                </div>
            )}
        </NotificationContext.Provider>
    );
};

export default NotificationProvider;
