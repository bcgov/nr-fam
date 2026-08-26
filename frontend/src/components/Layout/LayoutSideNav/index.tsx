import { Email } from "@carbon/icons-react";
import { SideNav, SideNavItems, SideNavLink } from "@carbon/react";
import type { FC } from "react";
import { Link, useLocation } from "react-router-dom";
import { useLayout } from "@/context/layout/useLayout";
import { getMenuEntries, isMenuItemActive } from "@/routes/routePaths";
import "./LayoutSideNav.css";

/** Shared mailbox behind the bottom-pinned Support link, as nr-fsp-new has. */
const SUPPORT_EMAIL = "heartwood@gov.bc.ca";

type Props = {
    /**
     * The caller's FAM roles, deciding which entries appear.
     *
     * Passed in rather than read from a hook so the nav can be rendered in a
     * test without an auth provider - and so it re-renders when the roles land,
     * which they do after the first paint.
     */
    accessRoles: readonly string[];
};

export const LayoutSideNav: FC<Props> = ({ accessRoles }) => {
    const { isSideNavExpanded } = useLayout();
    const location = useLocation();

    return (
        <SideNav
            expanded
            isPersistent={false}
            isChildOfHeader
            className={`side-nav-drawer${
                isSideNavExpanded ? " side-nav-drawer--open" : ""
            }`}
            aria-label="Main navigation"
        >
            <SideNavItems>
                {getMenuEntries(accessRoles).map((item) => (
                    <SideNavLink
                        data-testid={`side-nav-link-${item.id}`}
                        key={item.id}
                        as={Link}
                        to={item.path}
                        // Lights up for the pages beneath it too. Granting is
                        // reached from Manage permissions and belongs to it;
                        // matching the path exactly made the nav go blank the
                        // moment somebody started.
                        isActive={isMenuItemActive(item, location.pathname)}
                        renderIcon={item.icon}
                    >
                        {item.label}
                    </SideNavLink>
                ))}

                <li className="side-nav-support-heading" aria-hidden="true">
                    Support
                </li>
                <SideNavLink
                    data-testid="side-nav-link-email-support"
                    href={`mailto:${SUPPORT_EMAIL}`}
                    renderIcon={Email}
                >
                    Report an issue
                </SideNavLink>
            </SideNavItems>
        </SideNav>
    );
};

export default LayoutSideNav;
