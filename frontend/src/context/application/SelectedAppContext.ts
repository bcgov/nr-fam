import { createContext } from "react";
import type { CssApplicationOptionDto } from "fam-api";

export type SelectedAppContextValue = {
    /**
     * The application being administered, sourced from CSS.
     *
     * Identified by the pair (integration_id, environment): a CSS integration
     * spans environments, where what FAM calls an application does not.
     */
    selectedApp: CssApplicationOptionDto | undefined;
    setSelectedApp: (app: CssApplicationOptionDto | undefined) => void;
};

/**
 * Context rather than the module-level Vue ref this replaces.
 *
 * The ref outlived the app itself: a test that selected an application left it
 * selected for every test after it, and clearing it was something each spec had
 * to remember. A provider is torn down with the tree that holds it.
 *
 * It still has to be shared, though - the selection survives navigating to the
 * grant screens and back, which is the whole reason it is not page state.
 */
export const SelectedAppContext = createContext<
    SelectedAppContextValue | undefined
>(undefined);
