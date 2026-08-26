import { useEffect, useState, type ReactNode } from "react";
import { LayoutContext } from "./LayoutContext";

/**
 * Remembers whether the drawer was left open, as nr-fsp-new does.
 *
 * Namespaced to FAM so the two applications do not read each other's
 * preference when served from the same origin in a review environment.
 */
const SIDE_NAV_STORAGE_KEY = "fam.layout.sideNavOpen";

const loadSideNavInitial = (): boolean => {
    if (typeof window === "undefined") {
        return true;
    }
    try {
        const raw = window.localStorage.getItem(SIDE_NAV_STORAGE_KEY);
        return raw === null ? true : raw === "true";
    } catch {
        // Private mode or a full quota. Defaulting to open is the same answer
        // a first-time visitor gets, so there is nothing to report.
        return true;
    }
};

export const LayoutProvider = ({ children }: { children: ReactNode }) => {
    const [isSideNavExpanded, setSideNavExpanded] =
        useState<boolean>(loadSideNavInitial);
    // Deliberately not remembered across visits: the profile panel is something
    // you open to read and then dismiss, and restoring it open would cover the
    // page on every arrival.
    const [isHeaderPanelOpen, setHeaderPanelOpen] = useState(false);

    useEffect(() => {
        try {
            window.localStorage.setItem(
                SIDE_NAV_STORAGE_KEY,
                String(isSideNavExpanded)
            );
        } catch {
            /* quota / private mode - non-fatal */
        }
    }, [isSideNavExpanded]);

    return (
        <LayoutContext.Provider
            value={{
                isSideNavExpanded,
                toggleSideNav: () => setSideNavExpanded((open) => !open),
                closeSideNav: () => setSideNavExpanded(false),
                isHeaderPanelOpen,
                toggleHeaderPanel: () => setHeaderPanelOpen((open) => !open),
                closeHeaderPanel: () => setHeaderPanelOpen(false),
            }}
        >
            {children}
        </LayoutContext.Provider>
    );
};
