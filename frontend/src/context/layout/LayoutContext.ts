import { createContext } from "react";

export type LayoutContextValue = {
    isSideNavExpanded: boolean;
    toggleSideNav: () => void;
    closeSideNav: () => void;
    isHeaderPanelOpen: boolean;
    toggleHeaderPanel: () => void;
    closeHeaderPanel: () => void;
};

/**
 * Whether the side-nav drawer is open.
 *
 * Context rather than local state because two components need it and neither
 * owns the other: the header draws the toggle, the drawer draws itself, and the
 * layout shifts the page content out from under it.
 */
export const LayoutContext = createContext<LayoutContextValue | undefined>(
    undefined
);
