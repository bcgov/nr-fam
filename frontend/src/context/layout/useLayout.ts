import { useContext } from "react";
import { LayoutContext } from "./LayoutContext";

/**
 * Reads the layout state, refusing to guess when there is none.
 *
 * A component rendered outside the provider would otherwise silently get
 * "closed" and never open, which looks like a broken toggle rather than a
 * missing provider.
 */
export const useLayout = () => {
    const context = useContext(LayoutContext);
    if (!context) {
        throw new Error("useLayout must be used inside a LayoutProvider.");
    }
    return context;
};
