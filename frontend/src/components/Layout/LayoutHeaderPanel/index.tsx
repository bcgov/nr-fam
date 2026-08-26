import { Close } from "@carbon/icons-react";
import { HeaderPanel, IconButton } from "@carbon/react";
import { useEffect, type FC } from "react";
import HeaderPanelProfile from "@/components/Layout/HeaderPanelProfile";
import { useLayout } from "@/context/layout/useLayout";
import "./LayoutHeaderPanel.css";

export const LayoutHeaderPanel: FC = () => {
    const { isHeaderPanelOpen, closeHeaderPanel } = useLayout();

    // Close on a click anywhere outside. The toggle button is excluded because
    // its own onClick already toggles: closing here as well would let the same
    // click reopen the panel it just shut. mousedown fires before click, and the
    // listener only exists while the panel is open.
    useEffect(() => {
        if (!isHeaderPanelOpen) {
            return;
        }
        const handlePointerDown = (event: MouseEvent) => {
            const target = event.target as HTMLElement | null;
            if (
                !target ||
                target.closest(".profile-panel") ||
                target.closest(".profile-action-button")
            ) {
                return;
            }
            closeHeaderPanel();
        };
        document.addEventListener("mousedown", handlePointerDown);
        return () => document.removeEventListener("mousedown", handlePointerDown);
    }, [isHeaderPanelOpen, closeHeaderPanel]);

    return (
        <HeaderPanel
            data-testid="header-panel"
            aria-label="User profile"
            className={`profile-panel${isHeaderPanelOpen ? " profile-panel--open" : ""}`}
            expanded
        >
            <div className="right-title-section">
                <h4>My profile</h4>
                <div className="right-title-buttons">
                    <IconButton
                        kind="ghost"
                        label="Close"
                        onClick={closeHeaderPanel}
                        align="bottom"
                    >
                        <Close />
                    </IconButton>
                </div>
            </div>
            <HeaderPanelProfile />
        </HeaderPanel>
    );
};

export default LayoutHeaderPanel;
