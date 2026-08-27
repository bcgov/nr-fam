import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AuthContext, type AuthContextValue } from "@/context/auth/AuthContext";
import { NotificationProvider } from "@/context/notification/NotificationProvider";
import {
    formatRemaining,
    IDLE_TIMEOUT_MS,
    SessionTimeout,
    WARNING_BEFORE_MS,
} from "./index";

/**
 * The inactivity guard.
 *
 * What is worth holding onto here is the timing contract, because it is the part
 * that is invisible until it is wrong: the warning has to arrive before the
 * deadline, the deadline has to hold even when timers do not fire on schedule,
 * and an idle logout has to be distinguishable from a deliberate one.
 */

const logout = vi.fn(async () => {});
const forceRefreshSession = vi.fn(async () => {});
const ensureFreshToken = vi.fn(async () => {});

const authValue = (): AuthContextValue => ({
    authState: {
        isAuthenticated: true,
        famLoginUser: { username: "JSMITH" },
        isAuthRestored: true,
        accessRoles: ["FAM_ADMIN"],
    },
    login: async () => {},
    logout,
    ensureFreshToken,
    forceRefreshSession,
});

const renderGuard = () =>
    render(
        <AuthContext.Provider value={authValue()}>
            <NotificationProvider>
                <SessionTimeout />
            </NotificationProvider>
        </AuthContext.Provider>
    );

/** Moves both the clock the guard reads and the timers it runs on. */
const idleFor = (ms: number) =>
    act(() => {
        vi.advanceTimersByTime(ms);
    });

const dialog = () => screen.queryByRole("alertdialog");

describe("SessionTimeout", () => {
    beforeEach(() => {
        vi.useFakeTimers({ shouldAdvanceTime: true });
        logout.mockClear();
        forceRefreshSession.mockClear().mockResolvedValue(undefined);
        ensureFreshToken.mockClear();
    });
    afterEach(() => {
        vi.useRealTimers();
    });

    it("says nothing while the session is young", () => {
        renderGuard();
        idleFor(60_000);
        expect(dialog()).not.toBeInTheDocument();
    });

    it("warns before the deadline rather than after it", () => {
        renderGuard();

        // One second inside the warning window.
        idleFor(IDLE_TIMEOUT_MS - WARNING_BEFORE_MS + 1_000);

        expect(dialog()).toBeInTheDocument();
        // The whole point of warning early: nothing has been signed out yet.
        expect(logout).not.toHaveBeenCalled();
    });

    it("counts down while it waits", () => {
        renderGuard();
        idleFor(IDLE_TIMEOUT_MS - WARNING_BEFORE_MS + 1_000);

        const first = screen.getByRole("alertdialog").textContent;
        idleFor(5_000);
        const later = screen.getByRole("alertdialog").textContent;

        expect(first).not.toEqual(later);
    });

    it("logs out at the deadline, and says it was an expiry", () => {
        renderGuard();
        idleFor(IDLE_TIMEOUT_MS + 1_000);

        // `expired` is what leaves the explanation on the sign-in screen. A
        // bare logout() here would sign the person out with no account of why.
        expect(logout).toHaveBeenCalledWith({ expired: true });
    });

    it("holds the deadline across a sleeping laptop", () => {
        renderGuard();

        // One tick, arriving far later than it was scheduled for - which is what
        // a closed lid or a background tab does to timers. The old
        // implementation trusted a single long setTimeout and would have handed
        // back all the time nobody was there for.
        act(() => {
            vi.setSystemTime(Date.now() + IDLE_TIMEOUT_MS + 60_000);
            vi.advanceTimersByTime(1_000);
        });

        expect(logout).toHaveBeenCalledWith({ expired: true });
    });

    describe("staying logged in", () => {
        it("rotates the refresh token and closes", async () => {
            renderGuard();
            idleFor(IDLE_TIMEOUT_MS - WARNING_BEFORE_MS + 1_000);

            await userEvent.click(
                screen.getByRole("button", { name: "Stay logged in" })
            );

            expect(forceRefreshSession).toHaveBeenCalled();
            expect(dialog()).not.toBeInTheDocument();
            expect(logout).not.toHaveBeenCalled();
        });

        it("restarts the clock rather than only hiding the dialog", async () => {
            renderGuard();
            idleFor(IDLE_TIMEOUT_MS - WARNING_BEFORE_MS + 1_000);
            await userEvent.click(
                screen.getByRole("button", { name: "Stay logged in" })
            );

            // Past the original deadline. Closing the dialog without moving the
            // origin would log the person out seconds after they asked to stay.
            idleFor(WARNING_BEFORE_MS + 5_000);

            expect(logout).not.toHaveBeenCalled();
        });

        it("treats a dead refresh token as the expiry it is", async () => {
            forceRefreshSession.mockRejectedValue(new Error("invalid_grant"));
            renderGuard();
            idleFor(IDLE_TIMEOUT_MS - WARNING_BEFORE_MS + 1_000);

            await userEvent.click(
                screen.getByRole("button", { name: "Stay logged in" })
            );

            // Not a closed dialog over a session that is already gone.
            expect(logout).toHaveBeenCalledWith({ expired: true });
        });
    });

    it("signs out without an expiry note when the person chooses to", async () => {
        renderGuard();
        idleFor(IDLE_TIMEOUT_MS - WARNING_BEFORE_MS + 1_000);

        await userEvent.click(screen.getByRole("button", { name: "Log out" }));

        // Deliberate: the sign-in screen should not claim the session expired.
        expect(logout).toHaveBeenCalledWith();
    });

    it("offers no way out but the two choices", () => {
        renderGuard();
        idleFor(IDLE_TIMEOUT_MS - WARNING_BEFORE_MS + 1_000);

        const panel = screen.getByRole("alertdialog");
        expect(panel).toHaveAttribute("aria-modal", "true");
        // A close button would look like choosing and would not be.
        expect(
            screen.queryByRole("button", { name: /close/i })
        ).not.toBeInTheDocument();
        expect(
            screen.getAllByRole("button").map((b) => b.textContent)
        ).toEqual(["Log out", "Stay logged in"]);
    });

    it("keeps the token alive while somebody is reading", () => {
        renderGuard();

        act(() => {
            window.dispatchEvent(new Event("mousemove"));
        });

        // The app makes no request while a person reads a screen, so without
        // this their token dies under them despite them plainly being there.
        expect(ensureFreshToken).toHaveBeenCalled();
    });

    it("stops resetting the clock once the warning is up", () => {
        renderGuard();
        idleFor(IDLE_TIMEOUT_MS - WARNING_BEFORE_MS + 1_000);

        act(() => {
            window.dispatchEvent(new Event("mousemove"));
        });
        idleFor(WARNING_BEFORE_MS);

        // Reaching for the button moves the mouse. If that reset the clock the
        // dialog would dismiss itself and the choice would never be made.
        expect(logout).toHaveBeenCalledWith({ expired: true });
    });
});

describe("formatRemaining", () => {
    it("pads the seconds so the width does not jump", () => {
        expect(formatRemaining(289_000)).toBe("4:49");
        expect(formatRemaining(61_000)).toBe("1:01");
        expect(formatRemaining(9_000)).toBe("0:09");
    });

    it("never shows a negative time", () => {
        expect(formatRemaining(-5_000)).toBe("0:00");
    });
});
