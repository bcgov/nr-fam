import { Logout } from "@carbon/icons-react";
import { SideNavLink } from "@carbon/react";
import type { FC } from "react";
import AvatarImage from "@/components/Layout/AvatarImage";
import { useAuth } from "@/context/auth/useAuth";
import "./HeaderPanelProfile.css";

/**
 * Who is signed in, and the way out.
 *
 * No theme toggle: nr-fsp-new offers one, FAM has only ever had the white
 * theme, and a control with one position is machinery with no purpose.
 */

/**
 * `identity_provider` arrives as Keycloak's internal alias. These are the two
 * FAM admits; anything else falls through to the raw claim rather than being
 * relabelled into a lie about which provider authenticated the session.
 */
const PROVIDER_LABEL: Record<string, string> = {
    idir: "IDIR",
    bceidbusiness: "Business BCeID",
};

const ROLE_LABEL: Record<string, string> = {
    FAM_ADMIN: "FAM administrator",
    APP_ADMIN: "Application administrator",
    DELEGATED_ADMIN: "Delegated administrator",
};

/**
 * Most privileged first: a FAM administrator is usually an application
 * administrator too, and naming the lesser of the two would misdescribe them.
 */
const ROLE_PRIORITY = ["FAM_ADMIN", "APP_ADMIN", "DELEGATED_ADMIN"];

export const HeaderPanelProfile: FC = () => {
    const { authState, logout } = useAuth();
    const user = authState.famLoginUser;

    const fullName = user?.displayName?.trim() || user?.username || "";
    const providerLabel = user?.idpProvider
        ? (PROVIDER_LABEL[user.idpProvider] ?? user.idpProvider)
        : "IDIR";

    const primaryRole = ROLE_PRIORITY.find((role) =>
        authState.accessRoles.includes(role)
    );
    const roleLabel = primaryRole ? ROLE_LABEL[primaryRole] : null;
    const nameWithRole = roleLabel
        ? `${fullName || "User"} (${roleLabel})`
        : fullName || "User";

    return (
        <div className="my-profile-container">
            <div className="user-info-section">
                <div className="user-image">
                    <AvatarImage userName={fullName} size="large" />
                </div>
                <div className="user-data">
                    <p className="user-name">{nameWithRole}</p>
                    {user?.username ? (
                        <p>{`${providerLabel}: ${user.username}`}</p>
                    ) : null}
                    {/* Business BCeID only - an IDIR session has no business name. */}
                    {user?.organization ? (
                        <p>{`Organization: ${user.organization}`}</p>
                    ) : null}
                    {user?.email ? <p>{`Email: ${user.email}`}</p> : null}
                </div>
            </div>
            <hr className="divisory" />
            <nav className="account-nav">
                <ul>
                    <SideNavLink
                        id="sign-out-link"
                        className="cursor-pointer"
                        renderIcon={Logout}
                        onClick={() => void logout()}
                    >
                        Log out
                    </SideNavLink>
                </ul>
            </nav>
        </div>
    );
};

export default HeaderPanelProfile;
