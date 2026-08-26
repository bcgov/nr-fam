import { Content } from "@carbon/react";
import type { FC, ReactNode } from "react";
import { LayoutProvider } from "@/context/layout/LayoutProvider";
import { useLayout } from "@/context/layout/useLayout";
import { LayoutHeader } from "./LayoutHeader";
import "./Layout.css";

type Props = {
    children: ReactNode;
    accessRoles?: readonly string[];
};

/**
 * The signed-in shell.
 *
 * Wraps Carbon's so the page content slides right when the drawer opens rather
 * than being covered by it - the CSS keys off `bc-layout--nav-open`, which is
 * why the class is applied here rather than inside the drawer.
 */
const LayoutShell: FC<Props> = ({ children, accessRoles = [] }) => {
    const { isSideNavExpanded } = useLayout();
    return (
        <div className={`bc-layout${isSideNavExpanded ? " bc-layout--nav-open" : ""}`}>
            {/*
                Rendered directly rather than through Carbon's HeaderContainer.

                HeaderContainer takes a `render` prop and uses it as a component
                type, so the inline arrow this used to pass was a brand-new type
                on every render - React tore the whole header down and rebuilt it
                each time the layout state changed. The profile panel slides on a
                CSS transition, and a freshly-created node has no previous
                transform to animate from, so it snapped open instead of sliding.

                nr-fsp-new avoids this by passing a stable component reference.
                FAM's header takes a prop, so it sidesteps the question entirely:
                HeaderContainer only supplies `isSideNavExpanded` and
                `onClickSideNavExpand`, and LayoutContext already owns both.
            */}
            <LayoutHeader accessRoles={accessRoles} />
            <Content>{children}</Content>
        </div>
    );
};

export const Layout: FC<Props> = ({ children, accessRoles }) => (
    <LayoutProvider>
        <LayoutShell accessRoles={accessRoles}>{children}</LayoutShell>
    </LayoutProvider>
);

export default Layout;
