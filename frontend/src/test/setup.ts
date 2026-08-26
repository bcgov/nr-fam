import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

// Vitest's `globals: true` gives us the matchers, but not the unmount: without
// this, a component from one test is still in the document during the next, and
// `getByText` fails on "found multiple elements" for reasons that have nothing
// to do with the test that failed.
afterEach(cleanup);

// jsdom implements no media queries at all, and Carbon's useMatchMedia calls
// this unconditionally on mount - so without it every component carrying a
// responsive breakpoint throws before it renders. Reports "does not match",
// which is what a desktop-width viewport would say for Carbon's max-width
// queries.
// Carbon's Tabs measures its own strip to position the ink bar, and jsdom has
// no ResizeObserver at all. Without this the tab list throws on mount and every
// assertion in a spec that renders one fails for the wrong reason.
globalThis.ResizeObserver =
    globalThis.ResizeObserver ??
    class {
        observe() {}
        unobserve() {}
        disconnect() {}
    };

if (typeof window !== "undefined" && !window.matchMedia) {
    window.matchMedia = (query: string) =>
        ({
            matches: false,
            media: query,
            onchange: null,
            addListener: () => {},
            removeListener: () => {},
            addEventListener: () => {},
            removeEventListener: () => {},
            dispatchEvent: () => false,
        }) as MediaQueryList;
}
