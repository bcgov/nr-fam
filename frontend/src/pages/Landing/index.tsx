import { Login } from "@carbon/icons-react";
import { Button, Column, Grid } from "@carbon/react";
import type { FC } from "react";
import logo from "@/assets/images/bc-gov-logo.png";
import treeLogs from "@/assets/images/tree-logs.jpg";
import { useAuth } from "@/context/auth/useAuth";
import { IdpProvider } from "@/enum/IdpEnum";
import "./Landing.css";

/**
 * The signed-out page, and the only screen reachable without a session.
 *
 * Laid out like nr-fsp-new's landing page - content on the left, a full-height
 * photograph on the right - so the two applications introduce themselves the
 * same way. The assets are FAM's own: its BC Gov logo and the tree-logs
 * photograph the Vue landing used.
 *
 * Two providers and no third: FAM admits IDIR and Business BCeID. BC Services
 * Card is deliberately absent - the backend's IdentityProvider allowlist refuses
 * it, so offering the button would only produce a rejected sign-in.
 */
export const Landing: FC = () => {
    const { login } = useAuth();

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

                        <h1 id="landing-title" className="landing-title">
                            FAM
                        </h1>

                        <h2 id="landing-subtitle" className="landing-subtitle">
                            Forests Access Management
                        </h2>

                        <div className="landing-actions">
                            <div className="buttons-container single-row">
                                <Button
                                    id="login-idir-button"
                                    type="button"
                                    size="md"
                                    renderIcon={Login}
                                    className="login-btn"
                                    onClick={() => void login(IdpProvider.IDIR)}
                                >
                                    {`Login with ${IdpProvider.IDIR}`}
                                </Button>

                                <Button
                                    id="login-business-bceid-button"
                                    type="button"
                                    kind="tertiary"
                                    size="md"
                                    renderIcon={Login}
                                    className="login-btn"
                                    onClick={() =>
                                        void login(IdpProvider.BCEIDBUSINESS)
                                    }
                                >
                                    {`Login with ${IdpProvider.BCEIDBUSINESS}`}
                                </Button>
                            </div>

                            <p id="landing-desc" className="landing-note">
                                An active IDIR or Business BCeID account is
                                required
                            </p>
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

export default Landing;
