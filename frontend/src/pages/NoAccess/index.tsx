import { Logout } from "@carbon/icons-react";
import { Button, Column, Grid } from "@carbon/react";
import type { FC } from "react";
import logo from "@/assets/images/bc-gov-logo.png";
import treeLogs from "@/assets/images/tree-logs.jpg";
import { useAuth } from "@/context/auth/useAuth";
import "@/pages/Landing/Landing.css";
import "./NoAccess.css";

/**
 * Signed in to BC Gov SSO, but holding no FAM administrative role.
 *
 * <p>Deliberately a page rather than a redirect back to the landing screen: the
 * session is valid, so the landing screen would send them straight back here and
 * the pair would flicker between themselves. Sign out is the only action that
 * changes anything, so it is the only one offered - a role has to be granted by
 * somebody else, and there is no self-service path to ask for one from in here.
 *
 * <p>Laid out as the landing page is, and reusing its stylesheet: this is the
 * same moment in the same journey - a person who has just arrived and cannot get
 * in - and it reads as a continuation of the screen they came from rather than
 * as an error page from somewhere else. nr-fsp-new pairs its unauthorized page
 * with its landing page the same way.
 *
 * <p>It names them. "You do not have access" alone invites the reading that FAM
 * is broken; naming the account says which identity was judged, which is the
 * useful thing when somebody has two - an IDIR and a BCeID - and has signed in
 * with the one that was never granted anything.
 */
export const NoAccess: FC = () => {
    const { authState, logout } = useAuth();

    const signedInAs =
        authState.famLoginUser?.displayName ||
        authState.famLoginUser?.username;

    return (
        <div className="landing-grid-container">
            <Grid fullWidth className="landing-grid">
                <Column className="landing-content-col" sm={4} md={8} lg={8}>
                    <div className="landing-content-wrapper">
                        <div>
                            <img
                                src={logo}
                                alt="BC Government"
                                width={160}
                                className="logo"
                            />
                        </div>

                        <h1 id="no-access-title" className="landing-title">
                            You do not have access in FAM
                        </h1>

                        <h2 id="no-access-subtitle" className="landing-subtitle">
                            {signedInAs
                                ? `You're signed in as ${signedInAs}, but this account has not been granted any FAM administrative role.`
                                : "This account has not been granted any FAM administrative role."}
                        </h2>

                        <p id="no-access-desc" className="landing-note">
                            Ask a FAM administrator to grant you access.
                        </p>

                        <div className="landing-actions">
                            <div className="buttons-container single-row">
                                <Button
                                    id="no-access-sign-out"
                                    type="button"
                                    kind="tertiary"
                                    size="md"
                                    renderIcon={Logout}
                                    className="login-btn"
                                    onClick={() => void logout()}
                                >
                                    Sign out
                                </Button>
                            </div>
                        </div>
                    </div>
                </Column>

                <Column className="landing-img-col" sm={4} md={8} lg={8}>
                    <img
                        src={treeLogs}
                        alt="Stacked logs at a British Columbia mill"
                        className="landing-img"
                    />
                </Column>
            </Grid>
        </div>
    );
};

export default NoAccess;
