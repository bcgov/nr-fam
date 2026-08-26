import { Logout } from "@carbon/icons-react";
import { Button } from "@carbon/react";
import type { FC } from "react";
import { useAuth } from "@/context/auth/useAuth";
import "./NoAccess.css";

/**
 * Signed in to BC Gov SSO, but holding no FAM administrative role.
 *
 * Deliberately a page rather than a redirect back to the landing screen: the
 * session is valid, so the landing screen would send them straight back here
 * and the pair would flicker between themselves. Sign out is the only action
 * that changes anything, so it is the only one offered.
 *
 * The one message in the app that is not a toast. It is not a report about a
 * request that failed - it is the whole of what this page has to say, and moving
 * it to the corner would leave a blank screen with a button on it that a
 * dismissed toast could no longer explain.
 */
export const NoAccess: FC = () => {
    const { logout } = useAuth();

    return (
        <div className="no-access-container">
            <div className="no-access-content">
                <div className="no-access-message">
                    <h1 className="no-access-title">
                        You do not have access in FAM
                    </h1>
                    <p className="no-access-body">
                        Ask a FAM administrator to grant you access.
                    </p>
                </div>
                <Button
                    id="no-access-sign-out"
                    kind="tertiary"
                    renderIcon={Logout}
                    onClick={() => void logout()}
                >
                    Sign out
                </Button>
            </div>
        </div>
    );
};

export default NoAccess;
