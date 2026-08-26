import { render, screen } from "@testing-library/react";
import type { FC } from "react";
import { describe, expect, it, vi } from "vitest";
import {
    NotificationContext,
    type NotificationContent,
} from "./NotificationContext";
import { useErrorToast } from "./useErrorToast";

/**
 * The bridge from "this is broken" to "say so once".
 *
 * Failures used to be banners, which are a fact about the current render. A
 * toast is an event, and re-rendering is not one - so the interesting behaviour
 * here is all about what does <em>not</em> fire.
 */

const Subject: FC<{
    when: boolean;
    occurrence?: string | number;
    title?: string;
}> = ({ when, occurrence, title = "It failed" }) => {
    useErrorToast({ when, title, occurrence });
    return null;
};

type DisplayMock = ReturnType<typeof vi.fn<(content: NotificationContent) => void>>;

const renderWith = (display: DisplayMock) => {
    const ui = (props: {
        when: boolean;
        occurrence?: string | number;
        title?: string;
    }) => (
        <NotificationContext.Provider value={{ display }}>
            <Subject {...props} />
        </NotificationContext.Provider>
    );
    return { ui, ...render(ui({ when: false })) };
};

describe("useErrorToast", () => {
    it("says nothing while nothing is wrong", () => {
        const display: DisplayMock = vi.fn();
        renderWith(display);
        expect(display).not.toHaveBeenCalled();
    });

    it("reports the failure once, however often it re-renders", () => {
        const display: DisplayMock = vi.fn();
        const { ui, rerender } = renderWith(display);

        rerender(ui({ when: true, occurrence: 1 }));
        rerender(ui({ when: true, occurrence: 1 }));
        rerender(ui({ when: true, occurrence: 1 }));

        // A screen with a search box re-renders on every keystroke, and the
        // query is still in its failed state throughout.
        expect(display).toHaveBeenCalledTimes(1);
    });

    it("reports a retry that fails again as its own failure", () => {
        const display: DisplayMock = vi.fn();
        const { ui, rerender } = renderWith(display);

        rerender(ui({ when: true, occurrence: 1 }));
        // errorUpdatedAt moves on each failed refetch: pressing retry and being
        // refused again is news, not the first refusal still being true.
        rerender(ui({ when: true, occurrence: 2 }));

        expect(display).toHaveBeenCalledTimes(2);
    });

    it("reports the same failure again after a recovery", () => {
        const display: DisplayMock = vi.fn();
        const { ui, rerender } = renderWith(display);

        rerender(ui({ when: true, occurrence: 1 }));
        rerender(ui({ when: false, occurrence: 1 }));
        rerender(ui({ when: true, occurrence: 1 }));

        expect(display).toHaveBeenCalledTimes(2);
    });

    it("reports a different message as a different failure", () => {
        const display: DisplayMock = vi.fn();
        const { ui, rerender } = renderWith(display);

        rerender(ui({ when: true, title: "First reason" }));
        rerender(ui({ when: true, title: "Second reason" }));

        expect(display).toHaveBeenCalledTimes(2);
        expect(display).toHaveBeenLastCalledWith(
            expect.objectContaining({ title: "Second reason", kind: "error" })
        );
    });

    it("does not let the caller give a failure an expiry", () => {
        const display: DisplayMock = vi.fn();
        const { ui, rerender } = renderWith(display);

        rerender(ui({ when: true }));

        // Belt and braces with the provider, which forces this too - a failure
        // that vanishes on its own is the one thing the banner did better.
        expect(display).toHaveBeenCalledWith(
            expect.objectContaining({ timeout: 0 })
        );
    });
});

/**
 * Rendering into the real screen so a missing `role`/`aria` would show up here,
 * separate from the call-counting above.
 */
describe("useErrorToast wording", () => {
    it("passes the subtitle through", () => {
        const display: DisplayMock = vi.fn();
        const WithSubtitle = () => {
            useErrorToast({
                when: true,
                title: "The list could not be loaded",
                subtitle: "Please try again.",
            });
            return <span>rendered</span>;
        };

        render(
            <NotificationContext.Provider value={{ display }}>
                <WithSubtitle />
            </NotificationContext.Provider>
        );

        expect(screen.getByText("rendered")).toBeInTheDocument();
        expect(display).toHaveBeenCalledWith(
            expect.objectContaining({
                title: "The list could not be loaded",
                subtitle: "Please try again.",
            })
        );
    });
});
