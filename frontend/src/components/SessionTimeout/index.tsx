import { WarningFilled } from "@carbon/icons-react";
import { Button } from "@carbon/react";
import {
    useCallback,
    useEffect,
    useLayoutEffect,
    useRef,
    useState,
    type KeyboardEvent,
} from "react";
import { createPortal } from "react-dom";
import { useAuth } from "@/context/auth/useAuth";
import { useNotification } from "@/context/notification/useNotification";
import "./SessionTimeout.css";

/*
    Timings, and why these numbers.

    Keycloak gives this realm a five-minute access token and a thirty-minute
    refresh token. The refresh token is the real ceiling: once it has gone, no
    amount of clicking brings the session back, and "Stay logged in" would be a
    button that cannot keep its promise.

    So the idle timeout sits *under* that ceiling rather than on it. Logging out
    at exactly thirty minutes - which is what this app did before - meant the
    moment the timer fired was the moment the refresh token died, and the
    sign-out raced its own credentials. Twenty-five minutes leaves five minutes
    of headroom, so the dialog is always backed by a refresh token that still
    works.

    nr-fsp-new warns five minutes out and that reads well, so the shape is the
    same here: warn at 20:00 idle, log out at 25:00.
*/
const ONE_SECOND = 1000;

/** No mouse, keyboard, scroll or touch for this long and the session ends. */
export const IDLE_TIMEOUT_MS = 25 * 60 * ONE_SECOND;

/** How long before the deadline the warning appears, with its countdown. */
export const WARNING_BEFORE_MS = 5 * 60 * ONE_SECOND;

/** Below this the countdown turns red and the warning icon appears. */
const DANGER_AT_MS = 30 * ONE_SECOND;

/** Resetting the clock is cheap; once a second is plenty. */
const ACTIVITY_RESET_THROTTLE_MS = ONE_SECOND;

/** The token keepalive is a no-op unless the token is near expiry. */
const KEEPALIVE_THROTTLE_MS = 60 * ONE_SECOND;

/** What counts as "still here". */
const ACTIVITY_EVENTS: (keyof WindowEventMap)[] = [
    "mousemove",
    "mousedown",
    "keydown",
    "scroll",
    "touchstart",
    "wheel",
];

/** ms to "M:SS", never negative. */
export const formatRemaining = (ms: number): string => {
    const total = Math.max(0, Math.ceil(ms / ONE_SECOND));
    const minutes = Math.floor(total / 60);
    const seconds = total % 60;
    return `${minutes}:${String(seconds).padStart(2, "0")}`;
};

/**
 * The inactivity guard, and the warning that precedes it.
 *
 * Mounted once, high in the tree, only while somebody is signed in.
 *
 * Two things it does that the timer it replaces did not. It counts down from an
 * absolute timestamp rather than trusting one long `setTimeout`, so closing the
 * lid or leaving the tab in the background - both of which throttle timers
 * heavily - cannot quietly extend a session past its deadline. And it warns
 * before acting, so an idle logout is something a person can decline rather than
 * something they discover afterwards by finding their work gone.
 *
 * Once the warning is open, activity stops resetting the clock. Otherwise the
 * mouse movement of reaching for the button would dismiss the very dialog being
 * reached for, and the choice it asks for would never actually be made.
 *
 * The countdown is written straight to a text node through a ref. The dialog
 * does not re-render while it ticks, so a parent re-rendering mid-second cannot
 * blank the number.
 */
export const SessionTimeout = () => {
    const { logout, forceRefreshSession, ensureFreshToken } = useAuth();
    const { display } = useNotification();

    // `open` is the only state - flipping it mounts the dialog. Everything the
    // countdown touches is a ref, so ticking causes no render.
    const [open, setOpen] = useState(false);
    const openRef = useRef(false);

    const tickRef = useRef<number | null>(null);
    // The deadline is this plus IDLE_TIMEOUT_MS. Frozen once the warning opens.
    const lastActivityRef = useRef(Date.now());
    const busyRef = useRef(false);

    const countdownRef = useRef<HTMLSpanElement | null>(null);
    const iconRef = useRef<HTMLSpanElement | null>(null);
    const dialogRef = useRef<HTMLDivElement | null>(null);
    const logoutBtnRef = useRef<HTMLButtonElement | null>(null);
    const stayBtnRef = useRef<HTMLButtonElement | null>(null);

    const clearTick = () => {
        if (tickRef.current !== null) {
            window.clearInterval(tickRef.current);
            tickRef.current = null;
        }
    };

    const paintCountdown = (remaining: number) => {
        if (!countdownRef.current) {
            return;
        }
        countdownRef.current.textContent = formatRemaining(remaining);
        if (remaining <= DANGER_AT_MS) {
            countdownRef.current.classList.add("session-timeout__count--danger");
            if (iconRef.current) {
                iconRef.current.style.display = "inline-flex";
            }
        }
    };

    /** The deadline arrived. Sign out, and say why on the way. */
    const handleExpire = useCallback(() => {
        clearTick();
        openRef.current = false;
        setOpen(false);
        void logout({ expired: true });
    }, [logout]);

    /** "Stay logged in": rotate the refresh token and restart the clock. */
    const handleStay = useCallback(async () => {
        if (busyRef.current) {
            return;
        }
        busyRef.current = true;
        try {
            await forceRefreshSession();
            lastActivityRef.current = Date.now();
            openRef.current = false;
            setOpen(false);
            display({
                kind: "success",
                title: "You're still logged in",
                subtitle: "Your session has been extended.",
                timeout: 6000,
            });
        } catch {
            // The refresh token has already gone, so there is nothing to
            // extend. Treat it as the expiry it is rather than closing the
            // dialog on a session that is not there.
            handleExpire();
        } finally {
            busyRef.current = false;
        }
    }, [forceRefreshSession, display, handleExpire]);

    /** Chosen deliberately, so no "your session expired" note afterwards. */
    const handleLogout = useCallback(() => {
        clearTick();
        openRef.current = false;
        void logout();
    }, [logout]);

    useEffect(() => {
        let lastReset = 0;
        let lastKeepalive = 0;

        const onActivity = () => {
            if (openRef.current) {
                return;
            }
            const now = Date.now();
            if (now - lastReset >= ACTIVITY_RESET_THROTTLE_MS) {
                lastReset = now;
                lastActivityRef.current = now;
            }
            if (now - lastKeepalive >= KEEPALIVE_THROTTLE_MS) {
                lastKeepalive = now;
                void ensureFreshToken();
            }
        };

        ACTIVITY_EVENTS.forEach((event) =>
            window.addEventListener(event, onActivity, { passive: true })
        );

        const tick = () => {
            // Recomputed from an absolute origin every second, which is what
            // makes this survive a sleeping laptop: a timer set for twenty-five
            // minutes wakes up late and hands back time nobody was there for.
            const remaining =
                lastActivityRef.current + IDLE_TIMEOUT_MS - Date.now();

            if (remaining <= 0) {
                handleExpire();
                return;
            }
            if (remaining <= WARNING_BEFORE_MS && !openRef.current) {
                openRef.current = true;
                setOpen(true);
            }
            if (openRef.current) {
                paintCountdown(remaining);
            }
        };

        lastActivityRef.current = Date.now();
        clearTick();
        tickRef.current = window.setInterval(tick, ONE_SECOND);

        return () => {
            ACTIVITY_EVENTS.forEach((event) =>
                window.removeEventListener(event, onActivity)
            );
            clearTick();
        };
    }, [handleExpire, ensureFreshToken]);

    // Painted before the browser shows the dialog, so it never flashes empty.
    useLayoutEffect(() => {
        if (!open) {
            return;
        }
        paintCountdown(lastActivityRef.current + IDLE_TIMEOUT_MS - Date.now());
        dialogRef.current?.focus();
    }, [open]);

    /**
     * Focus stays inside, and Escape does nothing.
     *
     * There is no dismiss affordance at all: no close button, no backdrop click.
     * A dialog whose whole purpose is to make somebody choose should not have a
     * fourth option that looks like choosing and is not.
     */
    const onDialogKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
        if (event.key === "Escape") {
            event.preventDefault();
            event.stopPropagation();
            return;
        }
        if (event.key !== "Tab") {
            return;
        }
        const first = logoutBtnRef.current;
        const last = stayBtnRef.current;
        if (!first || !last) {
            return;
        }
        const active = document.activeElement;
        if (event.shiftKey) {
            if (active === first || active === dialogRef.current) {
                event.preventDefault();
                last.focus();
            }
        } else if (active === last) {
            event.preventDefault();
            first.focus();
        }
    };

    if (!open) {
        return null;
    }

    return createPortal(
        <div className="session-timeout__overlay">
            <div
                ref={dialogRef}
                className="session-timeout__dialog"
                role="alertdialog"
                aria-modal="true"
                aria-labelledby="session-timeout-title"
                aria-describedby="session-timeout-desc"
                tabIndex={-1}
                onKeyDown={onDialogKeyDown}
            >
                <h2 id="session-timeout-title" className="session-timeout__title">
                    You&rsquo;re about to be logged out
                </h2>
                <div id="session-timeout-desc" className="session-timeout__body">
                    <p>
                        For your security, you&rsquo;ll be logged out in{" "}
                        <span
                            className="session-timeout__count-wrap"
                            aria-live="polite"
                        >
                            {/*
                                Empty on purpose. The value is written
                                imperatively so a stray parent re-render cannot
                                clobber a countdown mid-tick.
                            */}
                            <span
                                ref={countdownRef}
                                className="session-timeout__count"
                            />
                            <span
                                ref={iconRef}
                                className="session-timeout__warn-icon"
                                style={{ display: "none" }}
                                aria-hidden="true"
                            >
                                <WarningFilled />
                            </span>
                        </span>{" "}
                        unless you choose to stay logged in.
                    </p>
                    <p>Any unsaved changes may be lost.</p>
                </div>
                <div className="session-timeout__actions">
                    <Button
                        ref={logoutBtnRef}
                        kind="tertiary"
                        size="md"
                        onClick={handleLogout}
                    >
                        Log out
                    </Button>
                    <Button
                        ref={stayBtnRef}
                        kind="primary"
                        size="md"
                        onClick={() => void handleStay()}
                    >
                        Stay logged in
                    </Button>
                </div>
            </div>
        </div>,
        document.body
    );
};

export default SessionTimeout;
