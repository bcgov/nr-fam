import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { type FC } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NotificationProvider } from "./NotificationProvider";
import type { NotificationContent } from "./NotificationContext";
import { useNotification } from "./useNotification";

/**
 * The toast stack.
 *
 * It held one toast when it only carried confirmations, because only one thing
 * had just been done. Failures arrive here now as well - they used to be banners
 * on the page - and several can be true at once: a list that failed to load, a
 * revoke that was refused, a grant that partly failed. A single slot silently
 * dropped all but the last of those.
 */

/** Fires whatever it is handed, once, on mount. */
const Raise: FC<{ toasts: NotificationContent[] }> = ({ toasts }) => {
    const { display } = useNotification();
    return (
        <button type="button" onClick={() => toasts.forEach(display)}>
            raise
        </button>
    );
};

const problem = (title: string): NotificationContent => ({
    kind: "error",
    title,
    timeout: 0,
});

const renderWith = (toasts: NotificationContent[]) =>
    render(
        <NotificationProvider>
            <Raise toasts={toasts} />
        </NotificationProvider>
    );

describe("NotificationProvider", () => {
    it("shows every toast rather than only the newest", async () => {
        const user = userEvent.setup();
        renderWith([problem("The list could not be loaded"), problem("The revoke was refused")]);

        await user.click(screen.getByRole("button", { name: "raise" }));

        expect(screen.getByText("The list could not be loaded")).toBeInTheDocument();
        expect(screen.getByText("The revoke was refused")).toBeInTheDocument();
    });

    it("says the same thing once when two callers say it", async () => {
        const user = userEvent.setup();
        renderWith([problem("Please try again"), problem("Please try again")]);

        await user.click(screen.getByRole("button", { name: "raise" }));

        // Two tables failing for the same reason is one problem reported twice,
        // and a column of identical toasts reads as a bug in the reporting.
        expect(screen.getAllByText("Please try again")).toHaveLength(1);
    });

    it("keeps the newest when more arrive than fit", async () => {
        const user = userEvent.setup();
        renderWith(
            ["first", "second", "third", "fourth", "fifth"].map(problem)
        );

        await user.click(screen.getByRole("button", { name: "raise" }));

        // The oldest goes: the newest is the one whose cause is on screen.
        expect(screen.queryByText("first")).not.toBeInTheDocument();
        expect(screen.getByText("fifth")).toBeInTheDocument();
    });

    describe("expiry", () => {
        beforeEach(() => vi.useFakeTimers());
        afterEach(() => vi.useRealTimers());

        it("leaves a failure up until it is dismissed", async () => {
            render(
                <NotificationProvider>
                    <Raise
                        toasts={[
                            // A caller asking for a short life gets ignored: a
                            // failure that expires is the thing banners were
                            // right about, and it is why they can be toasts now.
                            { kind: "error", title: "It failed", timeout: 1000 },
                        ]}
                    />
                </NotificationProvider>
            );

            act(() => {
                screen.getByRole("button", { name: "raise" }).click();
            });
            act(() => {
                vi.advanceTimersByTime(30_000);
            });

            expect(screen.getByText("It failed")).toBeInTheDocument();
        });

        it("lets a confirmation go on its own", async () => {
            render(
                <NotificationProvider>
                    <Raise
                        toasts={[
                            { kind: "success", title: "It worked", timeout: 1000 },
                        ]}
                    />
                </NotificationProvider>
            );

            act(() => {
                screen.getByRole("button", { name: "raise" }).click();
            });
            expect(screen.getByText("It worked")).toBeInTheDocument();

            // Advanced rather than awaited: waitFor polls on the very timers
            // that are frozen here, so it would sit there until the test times
            // out however long the toast had already been gone.
            act(() => {
                vi.advanceTimersByTime(5_000);
            });

            expect(screen.queryByText("It worked")).not.toBeInTheDocument();
        });
    });
});
